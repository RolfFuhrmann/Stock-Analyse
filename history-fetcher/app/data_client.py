"""
history-fetcher/app/data_client.py

Ruft historische Kursdaten vom yahoo-service und twelvedata-service ab.

Wichtige Unterschiede zwischen den Services:
  Yahoo:      interval nicht nötig, outputsize = Anzahl Tageskerzen
  TwelveData: interval="1day" für Tagesdaten, interval="1h" für Stunden
              outputsize = Anzahl Kerzen (max 5000 pro Request)
"""
import json
import logging
from datetime import date, datetime

import asyncio
import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# TwelveData liefert max. 5000 Kerzen pro Request
TWELVEDATA_MAX_OUTPUTSIZE = 5000


def _normalize_ticker(raw_symbol: str, ticker_format: str, custom_suffix: str | None) -> str:
    """Wendet dasselbe Ticker-Format wie der Angular-Client an."""
    if ticker_format == "XETRA":
        return f"{raw_symbol}.DE"
    if ticker_format == "CUSTOM" and custom_suffix:
        return f"{raw_symbol}{custom_suffix}"
    return raw_symbol  # RAW


async def _stream_quotes(
    service_url: str,
    ticker: str,
    outputsize: int,
    interval: str | None = None,
) -> dict | None:
    """
    Gemeinsame SSE-Stream-Logik für Yahoo und TwelveData.
    Gibt das Quote-Dict zurück oder None bei Fehler.

    Bei Yahoo wird nach JEDEM Request eine Pause eingelegt
    (yahoo_delay_sec) – wichtig, da pro Ticker bis zu 3 Yahoo-Calls
    in Folge erfolgen (daily, hourly, 4h-Aggregation) und Yahoo
    aggressive Anti-Scraping-Mechanismen einsetzt (siehe VPN-Gateway).
    """
    payload = {"tickers": [ticker], "outputsize": outputsize}
    if interval:
        payload["interval"] = interval

    is_yahoo = service_url.rstrip("/") == settings.yahoo_service_url.rstrip("/")

    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(settings.stream_timeout_sec)
        ) as client:
            async with client.stream(
                "POST",
                f"{service_url}/quotes/stream",
                json=payload,
                headers={"Accept": "text/event-stream"},
            ) as response:

                if response.status_code != 200:
                    logger.error(
                        f"[{ticker}] Stream HTTP {response.status_code} "
                        f"von {service_url}"
                    )
                    return None

                event_type = "message"
                buffer     = ""

                async for line in response.aiter_lines():
                    line = line.strip()

                    if line.startswith("event:"):
                        event_type = line[len("event:"):].strip()
                    elif line.startswith("data:"):
                        buffer = line[len("data:"):].strip()
                    elif line == "":
                        if event_type == "quote" and buffer:
                            return json.loads(buffer)
                        elif event_type == "done":
                            return None
                        event_type = "message"
                        buffer     = ""

    except Exception as e:
        logger.error(f"_stream_quotes [{ticker}] {service_url}: {e}")

    finally:
        if is_yahoo:
            await asyncio.sleep(settings.yahoo_delay_sec)

    return None


async def fetch_daily_bars(
    ticker: str,
    source: str,
    days: int,
) -> list[dict] | None:
    """
    Ruft Tageskerzen für einen Ticker vom passenden Service ab.
    Gibt eine Liste von OHLCV-Dicts zurück, oder None bei Fehler.

    Yahoo:      kein interval-Parameter nötig
    TwelveData: interval="1day" ist zwingend für Tagesdaten
    """
    outputsize = min(days + 10, TWELVEDATA_MAX_OUTPUTSIZE)

    if source == "yahoo":
        quote = await _stream_quotes(
            settings.yahoo_service_url,
            ticker,
            outputsize,
            interval=None,          # Yahoo braucht kein interval für Tagesdaten
        )
    else:
        # TwelveData: ohne interval="1day" liefert der Service
        # Intraday-Daten statt Tagesdaten → leeres bars-Array
        quote = await _stream_quotes(
            settings.twelvedata_service_url,
            ticker,
            outputsize,
            interval="1day",
        )
        # Rate-Limit einhalten: Free Plan = 8 req/min → 1 req alle 8s
        await asyncio.sleep(settings.twelvedata_delay_sec)

    if quote is None:
        logger.warning(f"[{ticker}] Kein Quote erhalten")
        return None

    if quote.get("error"):
        logger.warning(f"[{ticker}] Service-Fehler: {quote['error']}")
        return None

    bars = quote.get("bars", [])
    if not bars:
        logger.warning(f"[{ticker}] Leeres bars-Array von {source}")
        return None

    result = _parse_bars_daily(bars)
    logger.info(f"[{ticker}] {len(result)} Tageskerzen von {source} empfangen")
    return result


def _parse_bars_daily(raw_bars: list[dict]) -> list[dict]:
    """
    Konvertiert Rohbalken in das DB-Format für ohlcv_daily.
    Unterstützt beide Datumsformate: Yahoo (date) und TwelveData (datetime).
    """
    result = []
    for bar in raw_bars:
        try:
            # Yahoo: "date" | TwelveData: "datetime" | Fallback: "timestamp"
            raw_date = (
                bar.get("date")
                or bar.get("datetime")
                or bar.get("timestamp")
            )

            if isinstance(raw_date, (int, float)):
                trade_date = date.fromtimestamp(raw_date)
            elif isinstance(raw_date, str):
                trade_date = date.fromisoformat(raw_date[:10])
            else:
                logger.debug(f"Unbekanntes Datumsformat: {raw_date}")
                continue

            result.append({
                "tradeDate": trade_date.isoformat(),
                "open":      str(bar.get("open",  0)),
                "high":      str(bar.get("high",  0)),
                "low":       str(bar.get("low",   0)),
                "close":     str(bar.get("close", 0)),
                "volume":    bar.get("volume"),
            })
        except Exception as e:
            logger.debug(f"Bar-Parse-Fehler: {e} – {bar}")
            continue

    result.sort(key=lambda b: b["tradeDate"])
    return result


async def fetch_hourly_bars(
    ticker: str,
    source: str,
    hours: int,
) -> list[dict] | None:
    """
    Ruft Stundenkerzen für einen Ticker ab.

    Yahoo:      unterstützt Stundendaten mit interval="1h",
                aber nur für die letzten ~730 Tage (60 Tage zuverlässig)
    TwelveData: unterstützt bis zu 5000 Stundenkerzen (~208 Handelstage)
    """
    outputsize = min(hours, TWELVEDATA_MAX_OUTPUTSIZE)

    if source == "yahoo":
        # Yahoo liefert Stundendaten nur zuverlässig für kurze Zeiträume
        # Wir begrenzen auf 1440 Kerzen (≈ 180 Handelstage à 8 Stunden)
        outputsize = min(outputsize, 1440)
        quote = await _stream_quotes(
            settings.yahoo_service_url,
            ticker,
            outputsize,
            interval="1h",
        )
    else:
        quote = await _stream_quotes(
            settings.twelvedata_service_url,
            ticker,
            outputsize,
            interval="1h",
        )
        # Rate-Limit einhalten
        await asyncio.sleep(settings.twelvedata_delay_sec)

    if quote is None or quote.get("error"):
        if quote and quote.get("error"):
            logger.warning(f"[{ticker}] Hourly Service-Fehler: {quote['error']}")
        return None

    bars = quote.get("bars", [])
    if not bars:
        logger.info(f"[{ticker}] Keine Stundendaten verfügbar (leeres bars-Array)")
        return []     # [] statt None = kein Fehler, nur keine Daten

    result = _parse_bars_hourly(bars)
    logger.info(f"[{ticker}] {len(result)} Stundenkerzen von {source} empfangen")
    return result


def _parse_bars_hourly(raw_bars: list[dict]) -> list[dict]:
    """Konvertiert Rohbalken in das DB-Format für ohlcv_hourly."""
    result = []
    for bar in raw_bars:
        try:
            raw_time = (
                bar.get("datetime")
                or bar.get("date")
                or bar.get("timestamp")
            )

            if isinstance(raw_time, (int, float)):
                trade_time = datetime.fromtimestamp(raw_time)
            elif isinstance(raw_time, str):
                trade_time = datetime.fromisoformat(raw_time[:19])
            else:
                continue

            result.append({
                "tradeTime": trade_time.isoformat(),
                "open":      str(bar.get("open",  0)),
                "high":      str(bar.get("high",  0)),
                "low":       str(bar.get("low",   0)),
                "close":     str(bar.get("close", 0)),
                "volume":    bar.get("volume"),
            })
        except Exception as e:
            logger.debug(f"Hourly-Bar-Parse-Fehler: {e} – {bar}")
            continue

    result.sort(key=lambda b: b["tradeTime"])
    return result


async def fetch_4h_bars(
    ticker: str,
    source: str,
    candles: int,
) -> list[dict] | None:
    """
    Ruft 4h-Kerzen für einen Ticker ab.

    Yahoo:      unterstützt kein natives 4h-Interval →
                1h-Kerzen abrufen und zu 4h aggregieren.
                Xetra-Blöcke: 08:00–11:59 / 12:00–15:59 / 16:00–19:59 UTC+2
                Entspricht UTC: 06:00 / 10:00 / 14:00
    TwelveData: interval="4h" nativ verfügbar, direkt abrufen.
    """
    if source == "yahoo":
        # Yahoo/yfinance liefert 1h-Daten nur für ~730 Tage; bei größerem
        # outputsize fällt der yahoo-service offenbar stillschweigend auf
        # interval="1d" zurück (beobachtet: outputsize=1440 → 1440
        # Tageskerzen statt Stundenkerzen, outputsize=82 → korrekt 1h).
        # Daher deutlich unter dieser Schwelle bleiben. Die vollen
        # initial_4h_candles werden so nicht in einem Schritt erreicht,
        # füllen sich aber über die inkrementellen Updates kontinuierlich auf.
        outputsize = min(candles * 4 + 40, 600)
        quote = await _stream_quotes(
            settings.yahoo_service_url,
            ticker,
            outputsize,
            interval="1h",
        )
        if quote is None or quote.get("error"):
            if quote and quote.get("error"):
                logger.warning(f"[{ticker}] 4h (Yahoo) Service-Fehler: {quote['error']}")
            return None

        bars_1h = quote.get("bars", [])
        if not bars_1h:
            logger.info(f"[{ticker}] Keine 1h-Daten für 4h-Aggregation verfügbar")
            return []

        parsed_1h = _parse_bars_hourly(bars_1h)

        # ── Diagnose ─────────────────────────────────────────
        # Zeigt die tatsächliche Granularität der von Yahoo
        # zurückgegebenen "1h"-Daten bei großem outputsize.
        if parsed_1h:
            t0 = parsed_1h[0]["tradeTime"]
            t1 = parsed_1h[1]["tradeTime"] if len(parsed_1h) > 1 else None
            t_last = parsed_1h[-1]["tradeTime"]
            if t1:
                from datetime import datetime as _dt
                delta = _dt.fromisoformat(t1) - _dt.fromisoformat(t0)
                logger.info(
                    f"[{ticker}] 4h-Diagnose: outputsize={outputsize}, "
                    f"erste Kerzen: {t0} → {t1} (Δ={delta}), "
                    f"letzte Kerze: {t_last}"
                )

        result    = _aggregate_1h_to_4h(parsed_1h)
        logger.info(
            f"[{ticker}] {len(parsed_1h)} 1h-Kerzen → {len(result)} 4h-Kerzen aggregiert"
        )
        return result

    else:
        # TwelveData liefert 4h nativ
        outputsize = min(candles, TWELVEDATA_MAX_OUTPUTSIZE)
        quote = await _stream_quotes(
            settings.twelvedata_service_url,
            ticker,
            outputsize,
            interval="4h",
        )
        # Rate-Limit einhalten
        await asyncio.sleep(settings.twelvedata_delay_sec)

        if quote is None or quote.get("error"):
            if quote and quote.get("error"):
                logger.warning(f"[{ticker}] 4h (TwelveData) Service-Fehler: {quote['error']}")
            return None

        bars = quote.get("bars", [])
        if not bars:
            logger.info(f"[{ticker}] Keine 4h-Daten von TwelveData")
            return []

        result = _parse_bars_hourly(bars)   # Format identisch mit 1h
        logger.info(f"[{ticker}] {len(result)} 4h-Kerzen von TwelveData empfangen")
        return result


def _aggregate_1h_to_4h(bars_1h: list[dict]) -> list[dict]:
    """
    Aggregiert 1h-Kerzen zu 4h-Blöcken.

    Blockgrenzen (UTC) für Xetra / NYSE:
      Xetra (UTC+2):  08:00–11:59 → UTC 06:00
                      12:00–15:59 → UTC 10:00
                      16:00–19:59 → UTC 14:00
      NYSE  (UTC-5):  09:30–13:29 → UTC 14:30 (Block-Start 14:00)
                      13:30–17:29 → UTC 18:30 (Block-Start 18:00)

    Implementierung: Jede Stunde wird dem nächst-niedrigeren
    4h-Block-Start (0, 4, 8, 12, 16, 20 Uhr UTC) zugeordnet.
    Blöcke mit weniger als 2 Kerzen werden verworfen
    (unvollständige Handelsblöcke am Rand).
    """
    from collections import defaultdict
    from datetime import datetime

    # Kerzen nach 4h-Block gruppieren
    blocks: dict[str, list[dict]] = defaultdict(list)

    for bar in bars_1h:
        dt = datetime.fromisoformat(bar["tradeTime"])
        # Block-Start = abrunden auf nächste 4h-Grenze (0, 4, 8, 12, 16, 20)
        block_hour  = (dt.hour // 4) * 4
        block_start = dt.replace(hour=block_hour, minute=0, second=0, microsecond=0)
        blocks[block_start.isoformat()].append(bar)

    result = []
    for block_key, group in sorted(blocks.items()):
        # Unvollständige Randblöcke verwerfen (weniger als 2 Kerzen)
        if len(group) < 2:
            continue

        opens   = [float(b["open"])  for b in group]
        highs   = [float(b["high"])  for b in group]
        lows    = [float(b["low"])   for b in group]
        closes  = [float(b["close"]) for b in group]
        volumes = [b["volume"] for b in group if b["volume"] is not None]

        result.append({
            "tradeTime": block_key,
            "open":      str(opens[0]),           # erste 1h-Kerze
            "high":      str(max(highs)),
            "low":       str(min(lows)),
            "close":     str(closes[-1]),          # letzte 1h-Kerze
            "volume":    sum(volumes) if volumes else None,
        })

    return result

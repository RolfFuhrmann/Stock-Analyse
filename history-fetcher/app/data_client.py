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
    """
    payload = {"tickers": [ticker], "outputsize": outputsize}
    if interval:
        payload["interval"] = interval

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

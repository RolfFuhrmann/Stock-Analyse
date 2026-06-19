"""
agent-service/main.py
KI-Agent: Empfängt Ticker-Liste + Datenquelle (yahoo|twelvedata),
abonniert SSE vom gewählten Data Service,
analysiert jeden Ticker auf bullische UND bearische Umkehrsignale,
fragt den ML-Service für die Umkehrwahrscheinlichkeit ab
und pusht das Ergebnis sofort per SSE an den Angular Client.

Stop-Mechanismus:
  POST /analyze/stop  →  setzt ein asyncio.Event für die session_id.
  Der laufende Stream prüft das Event nach jedem Ticker und bricht ab.
"""

import asyncio
import json
import logging
import os
import uuid
from typing import AsyncGenerator, Literal

import httpx
import pandas as pd
import yfinance as yf
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from bullish_reversal_indicator import evaluate_stock
from bearish_reversal_indicator import evaluate_bearish_stock

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Stock Analysis Agent",
    description="Technische Analyse via SSE: Elliott Wave · MACD · Slow Stochastik · ML",
    version="5.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

YAHOO_SERVICE_URL      = os.getenv("YAHOO_SERVICE_URL",      "http://yahoo-service:8011")
TWELVEDATA_SERVICE_URL = os.getenv("TWELVEDATA_SERVICE_URL", "http://twelvedata-service:8012")
ML_SERVICE_URL         = os.getenv("ML_SERVICE_URL",         "http://ml-service:8015")
DB_SERVICE_URL         = os.getenv("DB_SERVICE_URL",         "http://db-service:8013")
LOOKBACK_DAYS          = int(os.getenv("LOOKBACK_DAYS", 90))

# Anzahl Kerzen je Interval für die Analyse-Anzeige (Indikatoren-Fenster)
LOOKBACK_BY_INTERVAL: dict[str, int] = {
    "1d": 90,    # ~3 Monate Tageskerzen
    "4h": 180,   # ~1 Monat 4h-Kerzen (30 Tage × 6 Kerzen)
    "1h": 200,   # ~2 Wochen 1h-Kerzen
}

# Anzahl Kerzen für den ML-Service: unabhängig vom Analyse-Lookback,
# da predictor.predict() mindestens 210 Zeilen benötigt
# (lange Indikatoren wie sma200, dist_52w_high brauchen Historie).
ML_LOOKBACK_BY_INTERVAL: dict[str, int] = {
    "1d": 300,
    "4h": 300,
    "1h": 300,
}

# ── Session-Registry ─────────────────────────────────────────
_stop_events: dict[str, asyncio.Event] = {}

# ── Name-Cache ────────────────────────────────────────────────
_name_cache: dict[str, str] = {}


def _get_ticker_name(ticker: str) -> str | None:
    if ticker in _name_cache:
        return _name_cache[ticker]
    try:
        info = yf.Ticker(ticker).fast_info
        name = getattr(info, "long_name", None) or getattr(info, "short_name", None)
        if not name:
            full = yf.Ticker(ticker).info
            name = full.get("longName") or full.get("shortName")
        if name:
            _name_cache[ticker] = name
            return name
    except Exception as e:
        logger.debug(f"Name-Lookup fehlgeschlagen für {ticker}: {e}")
    return None


# ── ML-Service Client ─────────────────────────────────────────

async def _fetch_ml_signal(ticker: str, interval: str = "1d") -> dict:
    """
    Ruft die Umkehrwahrscheinlichkeit vom ML-Service ab.
    Gibt immer ein Dict zurück – bei Fehler mit Defaults.
    Timeout 5s: ML darf den SSE-Stream nicht blockieren.
    interval wird im Request-Body mitgeschickt damit das Modell
    den richtigen interval_code (Feature #39) verwenden kann.
    """
    defaults = {
        "reversal_prob":   None,
        "reversal_pct":    None,
        "signal":          "none",
        "confidence":      "low",
        "model_available": False,
    }
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                f"{ML_SERVICE_URL}/predict/{ticker}",
                json={"interval": interval, "lookback_days": ML_LOOKBACK_BY_INTERVAL.get(interval, 300)},
            )
            if resp.status_code == 200:
                data = resp.json()
                return {
                    "reversal_prob":   data.get("reversal_prob"),
                    "reversal_pct":    data.get("reversal_pct"),
                    "signal":          data.get("signal", "none"),
                    "confidence":      data.get("confidence", "low"),
                    "model_available": data.get("model_available", True),
                }
            logger.debug(f"ML [{ticker}]: HTTP {resp.status_code}")
    except Exception as e:
        logger.debug(f"ML [{ticker}]: {e}")
    return defaults


# ── DB-Daten für 4h/1h ───────────────────────────────────────

async def _fetch_db_quote(ticker: str, interval: str) -> dict | None:
    """
    Lädt 4h- oder 1h-Kerzen direkt aus dem DB-Service.
    Gibt ein Quote-Dict zurück das kompatibel zu SSE-Quotes ist:
    { "ticker": ..., "bars": [...], "longName": ... }
    Wird verwendet wenn interval != "1d", da Yahoo/TwelveData-SSE
    nur für Daily/Realtime genutzt werden.
    """
    n = LOOKBACK_BY_INTERVAL.get(interval, 200)
    if interval == "4h":
        url = f"{DB_SERVICE_URL}/api/ohlcv/4h/{ticker}/latest"
    else:  # 1h
        url = f"{DB_SERVICE_URL}/api/ohlcv/hourly/{ticker}/latest"

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params={"n": n})
            if resp.status_code == 404:
                return {"ticker": ticker, "error": f"Keine {interval}-Daten in DB", "bars": []}
            resp.raise_for_status()
            raw_bars = resp.json()

        # DB liefert tradeTime, SSE-Analyse erwartet open/high/low/close
        bars = [
            {
                "open":  b["open"],
                "high":  b["high"],
                "low":   b["low"],
                "close": b["close"],
                "volume": b.get("volume"),
                "date":   b["tradeTime"],
            }
            for b in raw_bars
        ]
        return {"ticker": ticker, "bars": bars}

    except Exception as e:
        logger.error(f"_fetch_db_quote [{ticker}/{interval}]: {e}")
        return {"ticker": ticker, "error": str(e), "bars": []}


# ── Models ───────────────────────────────────────────────────

class AnalyzeRequest(BaseModel):
    tickers: list[str]
    source: Literal["yahoo", "twelvedata"] = "yahoo"
    interval: Literal["1d", "4h", "1h"] = "1d"  # Analyse-Zeitrahmen
    lookback_days: int | None = None  # None → Automatisch je Interval
    session_id: str = ""
    # ML-Signal kann pro Analyse-Run deaktiviert werden (z.B. wenn Service down)
    include_ml: bool = True


class StopRequest(BaseModel):
    session_id: str


class StockResult(BaseModel):
    ticker: str
    name: str | None = None
    interval: str = "1d"          # "1d" | "4h" | "1h"
    current_price: float | None = None
    trend_pct: float | None = None
    trend_direction: str | None = None
    elliott_wave: bool
    stochastic: bool
    macd_histogram: bool
    criteria_met: int
    source: str
    candle_pattern: str | None = None
    candle_strength: int = 0
    # ── Neu: ML-Signal ────────────────────────────────────────
    reversal_prob:   float | None = None   # 0.0–1.0
    reversal_pct:    float | None = None   # 0–100 (gerundet)
    ml_signal:       str   = "none"        # none | weak | moderate | strong
    ml_confidence:   str   = "low"         # low | medium | high
    ml_available:    bool  = False         # False wenn ML-Service nicht erreichbar
    # ─────────────────────────────────────────────────────────
    error: str | None = None


# ── Analyse ───────────────────────────────────────────────────

def analyse_quote(quote: dict, lookback: int, source: str, interval: str = "1d") -> StockResult:
    """
    Regelbasierte Analyse (Elliott Wave + MACD + Stochastik + Candle).
    ML-Signal wird separat in analysis_stream ergänzt.

    interval: "1d" | "4h" | "1h" – wird im StockResult mitgegeben
    damit Angular den richtigen Zeitrahmen anzeigen kann.

    Semantik der Indikatoren:
      bull-Indikator erkennt: Abwärtswelle + MACD<0 + Stoch<20 → Trend BEARISH
      bear-Indikator erkennt: Aufwärtswelle + MACD>0 + Stoch>80 → Trend BULLISH
    """
    ticker = quote.get("ticker", "?")

    name = (
        quote.get("longName")
        or quote.get("shortName")
        or quote.get("name")
        or _get_ticker_name(ticker)
    )

    if quote.get("error") or not quote.get("bars"):
        return StockResult(
            ticker=ticker, name=name, interval=interval,
            elliott_wave=False, stochastic=False, macd_histogram=False,
            criteria_met=0, source=source,
            error=quote.get("error", "Keine Kursdaten"),
        )

    try:
        bars = quote["bars"]
        df = pd.DataFrame(bars)
        for col in ["open", "high", "low", "close"]:
            df[col] = pd.to_numeric(df[col], errors="coerce")
        if "volume" in df.columns:
            df["volume"] = pd.to_numeric(df["volume"], errors="coerce")
        df = df.dropna(subset=["close", "high", "low"])

        if len(df) < 30:
            return StockResult(
                ticker=ticker,
                elliott_wave=False, stochastic=False, macd_histogram=False,
                criteria_met=0, source=source, error="Zu wenig Datenpunkte",
            )

        bull = evaluate_stock(df, lookback=lookback)
        bear = evaluate_bearish_stock(df, lookback=lookback)

        if bear["criteria_met"] > bull["criteria_met"]:
            result          = bear
            trend_direction = "bullish"
        elif bull["criteria_met"] > 0:
            result          = bull
            trend_direction = "bearish"
        else:
            result          = bull
            trend_direction = None

        price       = round(df["close"].iloc[-1], 2)
        start_price = df["close"].iloc[-min(lookback, len(df))]
        trend_pct   = round((price - start_price) / start_price * 100, 2)

        logger.info(
            f"  {ticker}: Elliott={result['elliott_ok']} "
            f"Stoch={result['stoch_ok']} MACD={result['macd_ok']} "
            f"[{result['criteria_met']}/3] dir={trend_direction}"
        )

        return StockResult(
            ticker=ticker,
            name=name,
            interval=interval,
            current_price=price,
            trend_pct=trend_pct,
            trend_direction=trend_direction,
            elliott_wave=result["elliott_ok"],
            stochastic=result["stoch_ok"],
            macd_histogram=result["macd_ok"],
            criteria_met=result["criteria_met"],
            source=source,
            candle_pattern=result["candle"].get("pattern"),
            candle_strength=result["candle"].get("strength", 0),
        )

    except Exception as e:
        logger.error(f"{ticker}: Analyse-Fehler – {e}")
        return StockResult(
            ticker=ticker,
            name=name if "name" in dir() else None,
            interval=interval,
            elliott_wave=False, stochastic=False, macd_histogram=False,
            criteria_met=0, source=source, error=str(e),
        )


# ── DB-Stream für 4h/1h ──────────────────────────────────────

async def _db_analysis_stream(
    tickers: list[str],
    source: str,
    interval: str,
    lookback: int,
    session_id: str,
    include_ml: bool,
    stop_event,
) -> AsyncGenerator:
    """
    Analyse-Stream für 4h/1h: Kerzen kommen aus der DB (kein SSE).
    Jeder Ticker wird sequenziell verarbeitet und sofort gepusht.
    """
    for ticker in tickers:
        if stop_event and stop_event.is_set():
            yield {"event": "done", "data": json.dumps({"message": "Stream gestoppt"})}
            return

        quote  = await _fetch_db_quote(ticker, interval)
        result = analyse_quote(quote, lookback, source, interval)

        if include_ml and not result.error:
            ml = await _fetch_ml_signal(result.ticker, interval=interval)
            result.reversal_prob  = ml["reversal_prob"]
            result.reversal_pct   = ml["reversal_pct"]
            result.ml_signal      = ml["signal"]
            result.ml_confidence  = ml["confidence"]
            result.ml_available   = ml["model_available"]
            logger.info(
                f"  {result.ticker} [{interval}]: ML signal={ml['signal']} "
                f"prob={ml['reversal_pct']}%"
            )

        yield {"event": "result", "data": result.model_dump_json()}

    yield {"event": "done", "data": json.dumps({"message": "Analyse abgeschlossen"})}
    _stop_events.pop(session_id, None)


# ── SSE Proxy Generator ───────────────────────────────────────

async def analysis_stream(
    tickers: list[str],
    source: str,
    interval: str,
    lookback: int,
    session_id: str,
    include_ml: bool,
) -> AsyncGenerator:
    stop_event = _stop_events.get(session_id)

    logger.info(
        f"Analyse via '{source}' [{interval}] für {len(tickers)} Ticker "
        f"(session={session_id}, ml={include_ml})"
    )

    # ── 4h / 1h: Kerzen aus DB, kein SSE ────────────────────
    if interval in ("4h", "1h"):
        async for event in _db_analysis_stream(
            tickers, source, interval, lookback, session_id, include_ml, stop_event
        ):
            yield event
        return

    # ── 1d: bisheriger SSE-Pfad bleibt unverändert ───────────
    service_url = YAHOO_SERVICE_URL if source == "yahoo" else TWELVEDATA_SERVICE_URL
    outputsize  = lookback + 40

    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream(
            "POST",
            f"{service_url}/quotes/stream",
            json={"tickers": tickers, "outputsize": outputsize},
            headers={"Accept": "text/event-stream"},
        ) as response:

            event_type = "message"
            buffer     = ""

            async for line in response.aiter_lines():

                if stop_event and stop_event.is_set():
                    logger.info(f"Stream gestoppt (session={session_id})")
                    yield {
                        "event": "done",
                        "data": json.dumps({"message": "Stream gestoppt"}),
                    }
                    return

                line = line.strip()

                if line.startswith("event:"):
                    event_type = line[len("event:"):].strip()
                elif line.startswith("data:"):
                    buffer = line[len("data:"):].strip()
                elif line == "":
                    if event_type == "quote" and buffer:
                        try:
                            quote  = json.loads(buffer)
                            result = analyse_quote(quote, lookback, source)

                            # ── ML-Signal anhängen ────────────────────────
                            # Nur wenn kein Fehler und ML aktiviert.
                            # Läuft parallel zur nächsten Iteration (non-blocking).
                            if include_ml and not result.error:
                                ml = await _fetch_ml_signal(result.ticker, interval="1d")
                                result.reversal_prob  = ml["reversal_prob"]
                                result.reversal_pct   = ml["reversal_pct"]
                                result.ml_signal      = ml["signal"]
                                result.ml_confidence  = ml["confidence"]
                                result.ml_available   = ml["model_available"]

                                logger.info(
                                    f"  {result.ticker}: ML signal={ml['signal']} "
                                    f"prob={ml['reversal_pct']}%"
                                )

                            yield {
                                "event": "result",
                                "data": result.model_dump_json(),
                            }
                        except Exception as e:
                            logger.error(f"Parse-Fehler: {e}")

                    elif event_type == "done":
                        yield {
                            "event": "done",
                            "data": json.dumps({"message": "Analyse abgeschlossen"}),
                        }
                        return

                    event_type = "message"
                    buffer     = ""

    _stop_events.pop(session_id, None)


# ── Endpoints ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status":          "ok",
        "service":         "agent-service",
        "yahoo_url":       YAHOO_SERVICE_URL,
        "twelvedata_url":  TWELVEDATA_SERVICE_URL,
        "ml_url":          ML_SERVICE_URL,
    }


@app.post("/analyze/stream")
async def stream_analysis(request: AnalyzeRequest):
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    session_id = request.session_id or str(uuid.uuid4())
    _stop_events[session_id] = asyncio.Event()

    # Lookback: explizit gesetzt oder Automatik je Interval
    lookback = request.lookback_days or LOOKBACK_BY_INTERVAL.get(request.interval, 90)

    return EventSourceResponse(
        analysis_stream(
            request.tickers,
            request.source,
            request.interval,
            lookback,
            session_id,
            request.include_ml,
        )
    )


@app.post("/analyze/stop")
async def stop_analysis(request: StopRequest):
    event = _stop_events.get(request.session_id)
    if event:
        event.set()
        logger.info(f"Stop-Signal gesetzt für session={request.session_id}")
        return {"status": "stopped", "session_id": request.session_id}
    return {"status": "not_found", "session_id": request.session_id}

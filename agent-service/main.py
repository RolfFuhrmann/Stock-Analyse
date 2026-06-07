"""
agent-service/main.py
KI-Agent: Empfängt Ticker-Liste + Datenquelle (yahoo|twelvedata),
abonniert SSE vom gewählten Data Service,
analysiert jeden Ticker auf bullische UND bearische Umkehrsignale
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
    description="Technische Analyse via SSE: Elliott Wave · MACD · Slow Stochastik",
    version="3.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

YAHOO_SERVICE_URL      = os.getenv("YAHOO_SERVICE_URL",      "http://yahoo-service:8011")
TWELVEDATA_SERVICE_URL = os.getenv("TWELVEDATA_SERVICE_URL", "http://twelvedata-service:8012")
LOOKBACK_DAYS          = int(os.getenv("LOOKBACK_DAYS", 90))

# ── Session-Registry ─────────────────────────────────────────
_stop_events: dict[str, asyncio.Event] = {}

# ── Name-Cache ────────────────────────────────────────────────
# Ticker → Unternehmensname, einmalig geholt und gecacht.
_name_cache: dict[str, str] = {}


def _get_ticker_name(ticker: str) -> str | None:
    """
    Holt den Unternehmensnamen via yfinance.fast_info (kein vollständiger API-Call).
    Ergebnis wird gecacht – pro Analyse-Run wird jeder Ticker nur einmal abgefragt.
    """
    if ticker in _name_cache:
        return _name_cache[ticker]
    try:
        info = yf.Ticker(ticker).fast_info
        # fast_info liefert kein longName – Fallback auf info nur wenn nötig
        name = getattr(info, "long_name", None) or getattr(info, "short_name", None)
        if not name:
            # Vollständiger info-Call als Fallback (langsamer)
            full = yf.Ticker(ticker).info
            name = full.get("longName") or full.get("shortName")
        if name:
            _name_cache[ticker] = name
            return name
    except Exception as e:
        logger.debug(f"Name-Lookup fehlgeschlagen für {ticker}: {e}")
    return None


# ── Models ───────────────────────────────────────────────────

class AnalyzeRequest(BaseModel):
    tickers: list[str]
    source: Literal["yahoo", "twelvedata"] = "yahoo"
    lookback_days: int = LOOKBACK_DAYS
    session_id: str = ""


class StopRequest(BaseModel):
    session_id: str


class StockResult(BaseModel):
    ticker: str
    name: str | None = None          # Unternehmensname aus Yahoo/TwelveData
    current_price: float | None = None
    trend_pct: float | None = None
    # Richtung des aktuellen Trends: "bullish", "bearish" oder None
    trend_direction: str | None = None
    elliott_wave: bool
    stochastic: bool
    macd_histogram: bool
    criteria_met: int
    source: str
    candle_pattern: str | None = None
    candle_strength: int = 0
    error: str | None = None


# ── Analyse ───────────────────────────────────────────────────

def analyse_quote(quote: dict, lookback: int, source: str) -> StockResult:
    """
    Analysiert einen einzelnen Ticker-Quote.
    Führt bullische UND bearische Erkennung durch.

    Semantik der Indikatoren:
      bull-Indikator erkennt: Abwärtswelle + MACD<0 + Stoch<20
        → der aktuelle Markttrend ist BEARISH
      bear-Indikator erkennt: Aufwärtswelle + MACD>0 + Stoch>80
        → der aktuelle Markttrend ist BULLISH

    trend_direction zeigt den aktuellen Trend (nicht die erwartete Umkehrrichtung).
    Die Seite mit mehr erfüllten Kriterien gewinnt.
    Bei Gleichstand: bearish (konservativere Einschätzung).
    """
    ticker = quote.get("ticker", "?")

    # Name aus Quote – falls nicht vorhanden via yfinance nachladen
    name = (
        quote.get("longName")
        or quote.get("shortName")
        or quote.get("name")
        or _get_ticker_name(ticker)
    )

    if quote.get("error") or not quote.get("bars"):
        return StockResult(
            ticker=ticker,
            name=name,
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

        # ── Bullische Auswertung (erkennt bearishen Markt) ───────
        bull = evaluate_stock(df, lookback=lookback)

        # ── Bearische Auswertung (erkennt bullishen Markt) ───────
        bear = evaluate_bearish_stock(df, lookback=lookback)

        # ── Richtung bestimmen ────────────────────────────────
        # bull-Indikator angeschlagen → Abwärtswelle/MACD<0/Stoch<20 → Trend ist BEARISH
        # bear-Indikator angeschlagen → Aufwärtswelle/MACD>0/Stoch>80 → Trend ist BULLISH
        # Die Seite mit mehr erfüllten Kriterien gewinnt.
        # Bei Gleichstand: bearish (konservativere Einschätzung).
        if bear["criteria_met"] > bull["criteria_met"]:
            result          = bear
            trend_direction = "bullish"   # bear-Signal = aktuell bullisher Markt
        elif bull["criteria_met"] > 0:
            result          = bull
            trend_direction = "bearish"   # bull-Signal = aktuell bearisher Markt
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
            name=name if 'name' in dir() else None,
            elliott_wave=False, stochastic=False, macd_histogram=False,
            criteria_met=0, source=source, error=str(e),
        )


# ── SSE Proxy Generator ───────────────────────────────────────

async def analysis_stream(
    tickers: list[str],
    source: str,
    lookback: int,
    session_id: str,
) -> AsyncGenerator:
    service_url = (
        YAHOO_SERVICE_URL if source == "yahoo" else TWELVEDATA_SERVICE_URL
    )
    outputsize = lookback + 40
    stop_event = _stop_events.get(session_id)

    logger.info(f"Analyse via '{source}' für {len(tickers)} Ticker (session={session_id})")

    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream(
            "POST",
            f"{service_url}/quotes/stream",
            json={"tickers": tickers, "outputsize": outputsize},
            headers={"Accept": "text/event-stream"},
        ) as response:

            event_type = "message"
            buffer = ""

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
                    buffer = ""

    _stop_events.pop(session_id, None)


# ── Endpoints ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "agent-service",
        "yahoo_url": YAHOO_SERVICE_URL,
        "twelvedata_url": TWELVEDATA_SERVICE_URL,
    }


@app.post("/analyze/stream")
async def stream_analysis(request: AnalyzeRequest):
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    session_id = request.session_id or str(uuid.uuid4())
    _stop_events[session_id] = asyncio.Event()

    return EventSourceResponse(
        analysis_stream(request.tickers, request.source, request.lookback_days, session_id)
    )


@app.post("/analyze/stop")
async def stop_analysis(request: StopRequest):
    event = _stop_events.get(request.session_id)
    if event:
        event.set()
        logger.info(f"Stop-Signal gesetzt für session={request.session_id}")
        return {"status": "stopped", "session_id": request.session_id}
    return {"status": "not_found", "session_id": request.session_id}

"""
agent-service/main.py
KI-Agent: Empfängt Ticker-Liste + Datenquelle (yahoo|twelvedata),
abonniert SSE vom gewählten Data Service,
analysiert jeden Ticker (Elliott Wave, MACD, Slow Stochastik)
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
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from indicators import evaluate_stock

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Stock Analysis Agent",
    description="Technische Analyse via SSE: Elliott Wave · MACD · Slow Stochastik",
    version="2.1.0",
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
# Speichert pro session_id ein asyncio.Event.
# Wird beim Stop gesetzt → Stream bricht nach dem aktuellen Ticker ab.
_stop_events: dict[str, asyncio.Event] = {}


# ── Models ───────────────────────────────────────────────────

class AnalyzeRequest(BaseModel):
    tickers: list[str]
    source: Literal["yahoo", "twelvedata"] = "yahoo"
    lookback_days: int = LOOKBACK_DAYS
    session_id: str = ""          # Vom Client vergeben; leer = kein Stop möglich


class StopRequest(BaseModel):
    session_id: str


class StockResult(BaseModel):
    ticker: str
    current_price: float | None = None
    trend_pct: float | None = None
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
    """Analysiert einen einzelnen Ticker-Quote aus dem Data Service."""
    ticker = quote.get("ticker", "?")

    if quote.get("error") or not quote.get("bars"):
        return StockResult(
            ticker=ticker,
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

        result      = evaluate_stock(df, lookback=lookback)
        price       = round(df["close"].iloc[-1], 2)
        start_price = df["close"].iloc[-min(lookback, len(df))]
        trend_pct   = round((price - start_price) / start_price * 100, 2)

        logger.info(
            f"  {ticker}: Elliott={result['elliott_ok']} "
            f"Stoch={result['stoch_ok']} MACD={result['macd_ok']} "
            f"[{result['criteria_met']}/3]"
        )

        return StockResult(
            ticker=ticker,
            current_price=price,
            trend_pct=trend_pct,
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
    """
    Abonniert SSE vom gewählten Data Service.
    Pro empfangenem Quote: Analyse durchführen, Ergebnis sofort pushen.
    Prüft nach jedem verarbeiteten Ticker das Stop-Event der Session.
    """
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

                # Stop-Signal vom Client prüfen – nach jeder Zeile reagierbar
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

    # Session aufräumen
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
    """
    SSE-Endpoint für Angular Client.
    Empfängt Ticker + Datenquelle, streamt Analyseergebnisse pro Ticker.

    session_id: Vom Client vergeben – ermöglicht gezielten Stop via /analyze/stop.

    Events:
      result – StockResult JSON pro analysiertem Ticker
      done   – Abschluss-Signal (normal oder durch Stop)
    """
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    # Stop-Event für diese Session registrieren
    session_id = request.session_id or str(uuid.uuid4())
    _stop_events[session_id] = asyncio.Event()

    return EventSourceResponse(
        analysis_stream(request.tickers, request.source, request.lookback_days, session_id)
    )


@app.post("/analyze/stop")
async def stop_analysis(request: StopRequest):
    """
    Setzt das Stop-Event für die angegebene session_id.
    Der laufende Stream bricht nach dem aktuell verarbeiteten Ticker ab.
    Dadurch wird auch der Abruf im Data Service (yahoo/twelvedata) unterbrochen.
    """
    event = _stop_events.get(request.session_id)
    if event:
        event.set()
        logger.info(f"Stop-Signal gesetzt für session={request.session_id}")
        return {"status": "stopped", "session_id": request.session_id}
    return {"status": "not_found", "session_id": request.session_id}

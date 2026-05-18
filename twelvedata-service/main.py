"""
twelvedata-service/main.py
Liefert OHLCV-Kursdaten via Twelve Data API.
Push (SSE): pro verarbeitetem Ticker wird sofort ein Event gesendet.
Delay zwischen Tickern verhindert API-Rate-Limiting.
"""

import asyncio
import json
import logging
import os
from typing import AsyncGenerator

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Stock Twelve Data Service",
    description="OHLCV-Kursdaten via Twelve Data API mit SSE-Push pro Ticker.",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

TWELVE_DATA_URL = "https://api.twelvedata.com/time_series"
API_KEY = os.getenv("TWELVE_DATA_API_KEY", "")

# Twelve Data Free Plan: 8 Requests/Minute → ~7.5s Pause
FETCH_DELAY = 7.5


# ── Models ───────────────────────────────────────────────────

class QuoteRequest(BaseModel):
    tickers: list[str]
    outputsize: int = 180


class OHLCVBar(BaseModel):
    date: str
    open: float
    high: float
    low: float
    close: float
    volume: float | None = None


class TickerQuote(BaseModel):
    ticker: str
    bars: list[OHLCVBar]
    error: str | None = None


# ── Twelve Data Abruf ─────────────────────────────────────────

async def fetch_ticker(client: httpx.AsyncClient, ticker: str, outputsize: int) -> TickerQuote:
    """Ruft OHLCV-Tagesdaten für einen Ticker von Twelve Data ab."""
    if not API_KEY:
        return TickerQuote(ticker=ticker, bars=[], error="TWELVE_DATA_API_KEY nicht gesetzt")

    params = {
        "symbol":     ticker,
        "interval":   "1day",
        "outputsize": outputsize,
        "apikey":     API_KEY,
        "format":     "JSON",
        "order":      "ASC",
    }

    try:
        resp = await client.get(TWELVE_DATA_URL, params=params, timeout=20)
        resp.raise_for_status()
        data = resp.json()

        if "values" not in data:
            msg = data.get("message", "Keine Daten von Twelve Data")
            logger.warning(f"{ticker}: {msg}")
            return TickerQuote(ticker=ticker, bars=[], error=msg)

        bars = []
        for v in data["values"]:
            try:
                bars.append(OHLCVBar(
                    date=v["datetime"],
                    open=float(v["open"]),
                    high=float(v["high"]),
                    low=float(v["low"]),
                    close=float(v["close"]),
                    volume=float(v["volume"]) if v.get("volume") else None,
                ))
            except (KeyError, ValueError):
                continue

        logger.info(f"{ticker}: {len(bars)} Bars geladen")
        return TickerQuote(ticker=ticker, bars=bars)

    except httpx.HTTPError as e:
        logger.error(f"{ticker}: HTTP-Fehler – {e}")
        return TickerQuote(ticker=ticker, bars=[], error=str(e))


# ── SSE Generator ─────────────────────────────────────────────

async def quote_stream(tickers: list[str], outputsize: int) -> AsyncGenerator:
    """Sendet pro Ticker sofort ein SSE-Event nach dem Abruf."""
    async with httpx.AsyncClient() as client:
        for i, ticker in enumerate(tickers):
            quote = await fetch_ticker(client, ticker, outputsize)

            yield {
                "event": "quote",
                "data": quote.model_dump_json(),
            }

            # Twelve Data Free Plan: max 8 req/min → 7.5s Pause
            if i < len(tickers) - 1:
                logger.debug(f"  Pause {FETCH_DELAY}s (Free Plan Rate-Limit)")
                await asyncio.sleep(FETCH_DELAY)

    yield {"event": "done", "data": json.dumps({"message": "Alle Ticker verarbeitet"})}


# ── Endpoints ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "service": "twelvedata-service"}


@app.post("/quotes/stream")
async def stream_quotes(request: QuoteRequest):
    """
    SSE-Endpoint: Sendet pro Ticker sofort ein 'quote'-Event.
    Abschluss wird mit 'done'-Event signalisiert.
    """
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    logger.info(f"SSE-Stream gestartet für {len(request.tickers)} Ticker: {request.tickers}")
    return EventSourceResponse(quote_stream(request.tickers, request.outputsize))

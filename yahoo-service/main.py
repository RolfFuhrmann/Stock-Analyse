"""
yahoo-service/main.py
Liefert OHLCV-Kursdaten via Yahoo Finance.
Push (SSE): pro verarbeitetem Ticker wird sofort ein Event gesendet.
Delay zwischen Tickern verhindert Rate-Limiting.
"""

import asyncio
import json
import logging
import random
import time
from typing import AsyncGenerator

import pandas as pd
import yfinance as yf
from curl_cffi import requests as curl_requests
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

# ── 1. Logging & App-Initialisierung ─────────────────────────
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Stock Yahoo Finance Service",
    description="OHLCV-Kursdaten via Yahoo Finance mit SSE-Push pro Ticker.",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── 2. Globale Konfiguration & Session ────────────────────────
FETCH_DELAY = (2.5, 4.5)
RETRY_DELAYS = [5, 10, 20]

session = curl_requests.Session(impersonate="chrome")


# ── 3. Pydantic Models ───────────────────────────────────────
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


# ── 4. Kern-Logik (Yahoo Abruf) ──────────────────────────────
def fetch_ohlcv(ticker: str, outputsize: int) -> TickerQuote:
    """Lädt OHLCV-Tagesdaten von Yahoo Finance mit Retry bei Rate-Limit."""
    for attempt, wait in enumerate([0] + RETRY_DELAYS, start=1):
        if wait > 0:
            logger.info(f"  {ticker}: Rate-Limit – warte {wait}s (Versuch {attempt}/4)")
            time.sleep(wait)
        try:
            ticker_obj = yf.Ticker(ticker, session=session)
            df = ticker_obj.history(
                period=f"{outputsize}d",
                interval="1d",
                auto_adjust=True
            )
            
            if df.empty or len(df) < 30:
                return TickerQuote(ticker=ticker, bars=[], error="Keine ausreichenden Daten")

            if isinstance(df.columns, pd.MultiIndex):
                df.columns = [c.lower() for c in df.columns]
            else:
                df.columns = [c.lower() for c in df.columns]

            df = df[["open", "high", "low", "close", "volume"]].dropna()
            df.index = pd.to_datetime(df.index)

            bars = [
                OHLCVBar(
                    date=str(idx.date()),
                    open=round(float(row["open"]), 4),
                    high=round(float(row["high"]), 4),
                    low=round(float(row["low"]), 4),
                    close=round(float(row["close"]), 4),
                    volume=round(float(row["volume"]), 0) if pd.notna(row["volume"]) else None,
                )
                for idx, row in df.iterrows()
            ]

            logger.info(f"{ticker}: {len(bars)} Bars geladen")
            return TickerQuote(ticker=ticker, bars=bars)

        except Exception as e:
            err = str(e)
            if "RateLimit" in err or "Too Many Requests" in err or "429" in err:
                if attempt <= len(RETRY_DELAYS):
                    continue
            logger.warning(f"{ticker}: Fehler – {e}")
            return TickerQuote(ticker=ticker, bars=[], error=str(e))

    return TickerQuote(ticker=ticker, bars=[], error="Rate-Limit nach 4 Versuchen")


# ── 5. SSE Generator ──────────────────────────────────────────
async def quote_stream(tickers: list[str], outputsize: int) -> AsyncGenerator:
    """Sendet pro Ticker sofort ein SSE-Event nach dem Abruf."""
    for i, ticker in enumerate(tickers):
        loop = asyncio.get_event_loop()
        quote = await loop.run_in_executor(None, fetch_ohlcv, ticker, outputsize)

        yield {
            "event": "quote",
            "data": quote.model_dump_json(),
        }

        if i < len(tickers) - 1:
            delay = random.uniform(*FETCH_DELAY)
            await asyncio.sleep(delay)

    yield {"event": "done", "data": json.dumps({"message": "Alle Ticker verarbeitet"})}


# ── 6. FastAPI Endpunkte (Zwingend nach App-Initialisierung) ──
@app.get("/health")
def health():
    return {"status": "ok", "service": "yahoo-service"}


@app.post("/quotes/stream")
async def stream_quotes(request: QuoteRequest):
    """
    SSE-Endpoint: Sendet pro Ticker sofort ein 'quote'-Event.
    """
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    logger.info(f"SSE-Stream gestartet für {len(request.tickers)} Ticker.")
    return EventSourceResponse(quote_stream(request.tickers, request.outputsize))

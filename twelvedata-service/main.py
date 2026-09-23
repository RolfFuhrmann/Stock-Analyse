"""
twelvedata-service/main.py
Liefert OHLCV-Kursdaten via Twelve Data API.
Push (SSE): pro verarbeitetem Ticker wird sofort ein Event gesendet.
Delay zwischen Tickern verhindert API-Rate-Limiting.

Änderungen v2:
  - QuoteRequest akzeptiert jetzt optionalen `interval`-Parameter
    ("1day" für Tagesdaten, "1h" für Stundendaten)
  - Default bleibt "1day" – vollständig rückwärtskompatibel
"""

import asyncio
import json
import logging
import os
import re
from typing import AsyncGenerator

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# httpx loggt auf INFO jeden Request mit vollständiger URL - und damit den
# API-Key ("...&apikey=..."). Ab WARNING erscheinen nur noch Probleme.
logging.getLogger("httpx").setLevel(logging.WARNING)

# Fehlermeldungen von httpx (z.B. HTTPStatusError bei 429) enthalten ebenfalls
# die komplette URL inkl. API-Key. Sie werden geloggt UND als "error" im
# SSE-Event bis zum Client durchgereicht - daher vorher schwärzen.
_APIKEY_PATTERN = re.compile(r"(apikey=)[^&\s'\"]+", re.IGNORECASE)


def _redact(text: str) -> str:
    """Ersetzt den Wert von 'apikey=' durch '***'."""
    return _APIKEY_PATTERN.sub(r"\1***", text)

app = FastAPI(
    title="Stock Twelve Data Service",
    description="OHLCV-Kursdaten via Twelve Data API mit SSE-Push pro Ticker.",
    version="2.0.0",
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

# Erlaubte Intervalle – Schutz vor ungültigen Werten
VALID_INTERVALS = {"1min", "5min", "15min", "30min", "1h", "2h", "4h", "1day", "1week", "1month"}

# Twelve Data verlangt bei Rohstoffen und Devisenpaaren zwingend einen
# Schrägstrich im Symbol (z.B. "XAU/USD" - "XAUUSD" kennt die API nicht).
# Unsere eigene DB-Service-API lehnt einen Schrägstrich im URL-Pfad dagegen ab
# (Tomcat: "encoded slash", HTTP 400 - betrifft agent-service-java,
# history-fetcher UND ml-service, die den Ticker alle in Pfade einsetzen).
# Deshalb wird so ein Ticker intern mit Bindestrich geführt (z.B. "XAU-USD" -
# das ist der Wert, den man in der Ticker-Liste einträgt) und hier für den
# Twelve-Data-Abruf auf das Schrägstrich-Symbol übersetzt. Der Ticker in der
# Antwort bleibt die interne Schreibweise, damit DB und Client konsistent
# bleiben. Bei weiteren Rohstoff-/Devisenpaaren hier ergänzen.
SYMBOL_ALIASES = {
    "XAU-USD": "XAU/USD",  # Gold
    "XAG-USD": "XAG/USD",  # Silber
    "XPT-USD": "XPT/USD",  # Platin
    "XPD-USD": "XPD/USD",  # Palladium
}


# ── Models ───────────────────────────────────────────────────

class QuoteRequest(BaseModel):
    tickers: list[str]
    outputsize: int = 180
    # Neu: interval-Parameter – Default "1day" für Rückwärtskompatibilität
    interval: str = "1day"


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
    currency: str | None = None
    error: str | None = None


# ── Twelve Data Abruf ─────────────────────────────────────────

async def fetch_ticker(
    client: httpx.AsyncClient,
    ticker: str,
    outputsize: int,
    interval: str = "1day",
) -> TickerQuote:
    """Ruft OHLCV-Daten für einen Ticker von Twelve Data ab."""
    if not API_KEY:
        return TickerQuote(ticker=ticker, bars=[], error="TWELVE_DATA_API_KEY nicht gesetzt")

    # Ungültiges Interval → Fallback auf 1day
    if interval not in VALID_INTERVALS:
        logger.warning(f"{ticker}: Ungültiges interval '{interval}' – Fallback auf 1day")
        interval = "1day"

    # Für Twelve Data übersetzen (siehe SYMBOL_ALIASES), intern bleibt der
    # Ticker mit Bindestrich - auch in der zurückgegebenen TickerQuote.
    td_symbol = SYMBOL_ALIASES.get(ticker, ticker)

    params = {
        "symbol":     td_symbol,
        "interval":   interval,
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
            logger.warning(f"{ticker} [{interval}] (Twelve-Data-Symbol {td_symbol}): {msg}")
            return TickerQuote(ticker=ticker, bars=[], error=msg)

        # meta stammt aus derselben time_series-Antwort (kein zusätzlicher
        # API-Call, also kein zusätzlicher Verbrauch des knappen Free-Plan-
        # Kontingents) und liefert den ISO-4217-Währungscode des Symbols.
        # Einen Firmennamen liefert dieser Endpunkt NICHT - dafür bräuchte es
        # einen separaten /quote-Aufruf pro Ticker, der das Rate-Limit
        # (Free Plan: 8 Requests/Minute) verdoppeln würde.
        currency = data.get("meta", {}).get("currency")

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

        logger.info(f"{ticker} [{interval}]: {len(bars)} Bars geladen")
        return TickerQuote(ticker=ticker, bars=bars, currency=currency)

    except httpx.HTTPError as e:
        message = _redact(str(e))
        logger.error(f"{ticker}: HTTP-Fehler – {message}")
        return TickerQuote(ticker=ticker, bars=[], error=message)


# ── SSE Generator ─────────────────────────────────────────────

async def quote_stream(
    tickers: list[str],
    outputsize: int,
    interval: str,
) -> AsyncGenerator:
    """Sendet pro Ticker sofort ein SSE-Event nach dem Abruf."""
    async with httpx.AsyncClient() as client:
        for i, ticker in enumerate(tickers):
            quote = await fetch_ticker(client, ticker, outputsize, interval)

            yield {
                "event": "quote",
                "data": quote.model_dump_json(),
            }

            # Delay nur zwischen Tickern im selben Stream-Request.
            # Wenn der history-fetcher einzelne Ticker schickt, wartet er
            # selbst (twelvedata_delay_sec in data_client.py).
            if i < len(tickers) - 1:
                logger.debug(f"  Pause {FETCH_DELAY}s (Free Plan Rate-Limit)")
                await asyncio.sleep(FETCH_DELAY)

    yield {"event": "done", "data": json.dumps({"message": "Alle Ticker verarbeitet"})}


# ── Endpoints ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status":   "ok",
        "service":  "twelvedata-service",
        "version":  "2.0.0",
        "interval": "configurable (default: 1day)",
    }


@app.post("/quotes/stream")
async def stream_quotes(request: QuoteRequest):
    """
    SSE-Endpoint: Sendet pro Ticker sofort ein 'quote'-Event.
    Abschluss wird mit 'done'-Event signalisiert.

    interval: "1day" (Standard) oder "1h" für Stundendaten
    """
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    logger.info(
        f"SSE-Stream: {len(request.tickers)} Ticker, "
        f"interval={request.interval}, outputsize={request.outputsize}"
    )
    return EventSourceResponse(
        quote_stream(request.tickers, request.outputsize, request.interval)
    )

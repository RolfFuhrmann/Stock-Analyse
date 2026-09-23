"""
yahoo-service/main.py
Liefert OHLCV-Kursdaten via Yahoo Finance.
Push (SSE): pro verarbeitetem Ticker wird sofort ein Event gesendet.
Delay zwischen Tickern verhindert Rate-Limiting.
"""

import asyncio
import ipaddress
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
    interval: str = "1d"   # "1d" | "1h" – wird an yfinance weitergegeben


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
    longName: str | None = None
    currency: str | None = None
    error: str | None = None


# ── 4. Kern-Logik (Yahoo Abruf) ──────────────────────────────
def fetch_ohlcv(ticker: str, outputsize: int, interval: str = "1d") -> TickerQuote:
    """
    Lädt OHLCV-Daten von Yahoo Finance mit Retry bei Rate-Limit.

    interval="1d": outputsize = gewünschte Anzahl Tageskerzen,
                    period=f"{outputsize}d".
    interval="1h": outputsize = gewünschte Anzahl Stundenkerzen.
                    yfinance erlaubt für 1h-Daten max. period="730d".
                    Umrechnung: ~7 Handelsstunden/Tag + Puffer für
                    Wochenenden/Feiertage, gekappt auf 729 Tage.
    """
    if interval == "1h":
        days_needed = min(int(outputsize / 7) + 10, 729)
        period      = f"{days_needed}d"
        min_rows    = 2
    else:
        period   = f"{outputsize}d"
        min_rows = 30

    for attempt, wait in enumerate([0] + RETRY_DELAYS, start=1):
        if wait > 0:
            logger.info(f"  {ticker}: Rate-Limit – warte {wait}s (Versuch {attempt}/4)")
            time.sleep(wait)
        try:
            ticker_obj = yf.Ticker(ticker, session=session)
            df = ticker_obj.history(
                period=period,
                interval=interval,
                auto_adjust=True
            )

            if df.empty or len(df) < min_rows:
                return TickerQuote(ticker=ticker, bars=[], error="Keine ausreichenden Daten")

            # history_metadata stammt aus derselben Chart-Antwort wie history()
            # (kein zusätzlicher Yahoo-Request, also kein zusätzliches
            # Rate-Limit-Risiko) und liefert u.a. Firmennamen + ISO-4217-
            # Währungscode. .get() statt Attributzugriff, da einzelne Felder
            # je nach Instrument (z.B. Indizes) fehlen können.
            meta = ticker_obj.history_metadata or {}
            long_name = meta.get("longName") or meta.get("shortName")
            currency = meta.get("currency")

            if isinstance(df.columns, pd.MultiIndex):
                df.columns = [c.lower() for c in df.columns]
            else:
                df.columns = [c.lower() for c in df.columns]

            df = df[["open", "high", "low", "close", "volume"]].dropna()
            df.index = pd.to_datetime(df.index)

            # Für 1d reicht das reine Datum (bisheriges Format,
            # damit bestehende Daily-Verarbeitung unverändert bleibt).
            # Für 1h wird der volle Zeitstempel benötigt – isoformat()
            # liefert die Yahoo-Handelszeit inkl. Stunde (lokale
            # Börsenzeitzone, z.B. Europe/Berlin für Xetra).
            if interval == "1h":
                bars = [
                    OHLCVBar(
                        date=idx.isoformat(),
                        open=round(float(row["open"]), 4),
                        high=round(float(row["high"]), 4),
                        low=round(float(row["low"]), 4),
                        close=round(float(row["close"]), 4),
                        volume=round(float(row["volume"]), 0) if pd.notna(row["volume"]) else None,
                    )
                    for idx, row in df.iterrows()
                ]
            else:
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

            logger.info(f"{ticker}: {len(bars)} Bars geladen (interval={interval}, period={period})")
            return TickerQuote(ticker=ticker, bars=bars, longName=long_name, currency=currency)

        except Exception as e:
            err = str(e)
            if "RateLimit" in err or "Too Many Requests" in err or "429" in err:
                if attempt <= len(RETRY_DELAYS):
                    continue
            logger.warning(f"{ticker}: Fehler – {e}")
            return TickerQuote(ticker=ticker, bars=[], error=str(e))

    return TickerQuote(ticker=ticker, bars=[], error="Rate-Limit nach 4 Versuchen")


# ── 5. SSE Generator ──────────────────────────────────────────
async def quote_stream(tickers: list[str], outputsize: int, interval: str = "1d") -> AsyncGenerator:
    """Sendet pro Ticker sofort ein SSE-Event nach dem Abruf."""
    for i, ticker in enumerate(tickers):
        loop = asyncio.get_event_loop()
        quote = await loop.run_in_executor(None, fetch_ohlcv, ticker, outputsize, interval)

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


# ── Ausgangs-IP (VPN-Info für den Client, 21.09.) ─────────────
# Dieser Service läuft im Netzwerk des VPN-Containers (Gluetun) - eine
# Abfrage von hier zeigt daher die IP, unter der Yahoo uns sieht. Gluetuns
# eigene Public-IP-Route bleibt nach einem VPN-Neustart teilweise leer
# (Fetcher-Fehler direkt nach dem Tunnelaufbau), deshalb fragen wir selbst.
#
# Mehrere Anbieter nacheinander: Die IPs von Gratis-VPN-Servern werden von
# vielen Nutzern geteilt, einzelne IP-Datenbanken lehnen sie dann ab (HTTP 429).
# Der erste Anbieter mit brauchbarer Antwort gewinnt; als letzter Ausweg
# liefert ipify nur die IP ohne Standort.
IP_LOOKUP_TIMEOUT_SECONDS = 3  # 3 Anbieter + ipify = max. 12 s (agent-service-java wartet 15 s)
IP_PLAIN_FALLBACK_URL = "https://api.ipify.org"


def _valid_ip(value: object) -> str | None:
    """Gibt den Wert nur zurück, wenn er wirklich eine IP-Adresse ist (kein HTML/Fehlertext)."""
    if not isinstance(value, str):
        return None
    try:
        return str(ipaddress.ip_address(value.strip()))
    except ValueError:
        return None


def _parse_ipinfo(data: dict) -> dict:
    return {"ip": data.get("ip"), "city": data.get("city"), "region": data.get("region"),
            "country": data.get("country"), "organization": data.get("org")}


def _parse_ipwhois(data: dict) -> dict:
    if data.get("success") is False:
        return {}
    connection = data.get("connection") or {}
    return {"ip": data.get("ip"), "city": data.get("city"), "region": data.get("region"),
            "country": data.get("country_code"),
            "organization": connection.get("org") or connection.get("isp")}


def _parse_ipapi(data: dict) -> dict:
    if data.get("error"):
        return {}
    return {"ip": data.get("ip"), "city": data.get("city"), "region": data.get("region"),
            "country": data.get("country_code") or data.get("country"), "organization": data.get("org")}


# (URL, Parser) - Länder immer als ISO-Code, der Client übersetzt in den Namen
IP_INFO_PROVIDERS = [
    ("https://ipinfo.io/json", _parse_ipinfo),
    ("https://ipwho.is/", _parse_ipwhois),
    ("https://ipapi.co/json/", _parse_ipapi),
]


@app.get("/ip")
def exit_ip():
    """
    Ausgangs-IP dieses Containers (= VPN-IP) plus Standort/Anbieter.
    ip=None, wenn aktuell keine Verbindung besteht (z.B. Tunnel im Aufbau).
    """
    empty = {"ip": None, "city": None, "region": None, "country": None, "organization": None}

    for url, parse in IP_INFO_PROVIDERS:
        try:
            response = curl_requests.get(url, timeout=IP_LOOKUP_TIMEOUT_SECONDS)
            if response.status_code != 200:
                logger.warning(f"IP-Abfrage {url}: HTTP {response.status_code}")
                continue
            info = parse(response.json())
            ip = _valid_ip(info.get("ip"))
            if ip:
                return {**empty, **info, "ip": ip}
        except Exception as e:
            logger.warning(f"IP-Abfrage {url} fehlgeschlagen: {e}")

    # Letzter Ausweg: nur die IP, ohne Standortdaten
    try:
        response = curl_requests.get(IP_PLAIN_FALLBACK_URL, timeout=IP_LOOKUP_TIMEOUT_SECONDS)
        ip = _valid_ip(response.text) if response.status_code == 200 else None
        if ip:
            return {**empty, "ip": ip}
    except Exception as e:
        logger.warning(f"IP-Abfrage {IP_PLAIN_FALLBACK_URL} fehlgeschlagen: {e}")

    return empty


@app.post("/quotes/stream")
async def stream_quotes(request: QuoteRequest):
    """
    SSE-Endpoint: Sendet pro Ticker sofort ein 'quote'-Event.
    """
    if not request.tickers:
        return {"error": "Ticker-Liste ist leer"}

    logger.info(
        f"SSE-Stream gestartet für {len(request.tickers)} Ticker "
        f"(interval={request.interval}, outputsize={request.outputsize})."
    )
    return EventSourceResponse(quote_stream(request.tickers, request.outputsize, request.interval))

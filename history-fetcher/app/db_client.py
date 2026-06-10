"""
history-fetcher/app/db_client.py
Einfacher HTTP-Client für alle Aufrufe an den stock-data-db-access Service.
"""
import logging
from datetime import date, datetime
from decimal import Decimal
from typing import Any

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

BASE = settings.db_service_url
TIMEOUT = httpx.Timeout(30.0)


# ── TickerMeta ────────────────────────────────────────────────

async def upsert_ticker_meta(
    ticker: str,
    raw_symbol: str,
    source: str,
    company_name: str | None = None,
) -> bool:
    """Legt einen Ticker in ticker_meta an oder aktualisiert ihn."""
    payload = {
        "ticker":      ticker,
        "rawSymbol":   raw_symbol,
        "source":      source,
        "companyName": company_name,
    }
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.put(f"{BASE}/api/ohlcv/meta/{ticker}", json=payload)
        if resp.status_code in (200, 201):
            return True
        logger.error(f"upsert_ticker_meta [{ticker}]: {resp.status_code} {resp.text}")
        return False


async def get_all_meta() -> list[dict]:
    """Gibt alle bekannten Ticker-Metadaten zurück."""
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.get(f"{BASE}/api/ohlcv/meta")
        resp.raise_for_status()
        return resp.json()


async def get_meta(ticker: str) -> dict | None:
    """Gibt Metadaten + Abdeckungsinfo für einen Ticker zurück."""
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.get(f"{BASE}/api/ohlcv/meta/{ticker}")
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        return resp.json()


# ── Tageskerzen ───────────────────────────────────────────────

async def bulk_insert_daily(
    ticker: str,
    source: str,
    bars: list[dict],
) -> dict:
    """
    Speichert Tageskerzen in ohlcv_daily.
    bars: Liste von Dicts mit keys: tradeDate, open, high, low, close, volume
    """
    payload = {
        "ticker": ticker,
        "source": source,
        "bars":   bars,
    }
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.post(f"{BASE}/api/ohlcv/daily/bulk", json=payload)
        if resp.status_code in (200, 201):
            return resp.json()
        logger.error(f"bulk_insert_daily [{ticker}]: {resp.status_code} {resp.text}")
        return {"inserted": 0, "skipped": 0, "message": resp.text}


async def get_latest_daily_date(ticker: str) -> date | None:
    """
    Gibt das neueste vorhandene Datum in ohlcv_daily zurück.
    Wird verwendet um zu entscheiden ob Erstbefüllung oder Update nötig ist.
    """
    meta = await get_meta(ticker)
    if not meta or not meta.get("newestDaily"):
        return None
    return date.fromisoformat(meta["newestDaily"])


# ── Stundenkerzen ─────────────────────────────────────────────

async def bulk_insert_hourly(
    ticker: str,
    source: str,
    bars: list[dict],
) -> dict:
    """
    Speichert Stundenkerzen in ohlcv_hourly.
    bars: Liste von Dicts mit keys: tradeTime, open, high, low, close, volume
    """
    payload = {
        "ticker": ticker,
        "source": source,
        "bars":   bars,
    }
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.post(f"{BASE}/api/ohlcv/hourly/bulk", json=payload)
        if resp.status_code in (200, 201):
            return resp.json()
        logger.error(f"bulk_insert_hourly [{ticker}]: {resp.status_code} {resp.text}")
        return {"inserted": 0, "skipped": 0, "message": resp.text}


# ── Fetch-Log ─────────────────────────────────────────────────

async def log_fetch(
    ticker: str,
    interval_type: str,
    source: str,
    status: str,
    bars_fetched: int = 0,
    error_msg: str | None = None,
) -> None:
    """Schreibt einen Eintrag in fetch_log."""
    payload = {
        "ticker":       ticker,
        "intervalType": interval_type,
        "source":       source,
        "status":       status,
        "barsFetched":  bars_fetched,
        "errorMsg":     error_msg,
    }
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.post(f"{BASE}/api/ohlcv/fetch-log", json=payload)
        if resp.status_code not in (200, 201):
            logger.warning(f"log_fetch [{ticker}]: {resp.status_code}")


# ── Ticker-Listen ─────────────────────────────────────────────

async def get_ticker_list(list_code: str) -> dict | None:
    """Holt eine Ticker-Liste mit raw_symbols aus dem DB-Service."""
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.get(f"{BASE}/api/lists/code/{list_code}/raw-symbols")
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        return resp.json()

"""
ml-service/app/api/db_client.py
Lädt OHLCV-Daten aus dem stock-data-db-access Service.
"""
import logging

import httpx
import pandas as pd

from app.config import settings

logger = logging.getLogger(__name__)
BASE    = settings.db_service_url
TIMEOUT = httpx.Timeout(60.0)


async def get_all_tickers() -> list[str]:
    """Gibt alle bekannten Ticker aus ticker_meta zurück."""
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.get(f"{BASE}/api/ohlcv/meta")
        resp.raise_for_status()
        return [m["ticker"] for m in resp.json()]


async def get_daily_bars(ticker: str, limit: int = 0) -> pd.DataFrame | None:
    """
    Lädt Tageskerzen für einen Ticker als DataFrame.
    limit=0 → alle verfügbaren Daten.
    """
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            if limit > 0:
                url  = f"{BASE}/api/ohlcv/daily/{ticker}/latest"
                resp = await client.get(url, params={"n": limit})
            else:
                url  = f"{BASE}/api/ohlcv/daily/{ticker}"
                resp = await client.get(url)

            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            bars = resp.json()

        if not bars:
            return None

        df = pd.DataFrame(bars)
        df["trade_date"] = pd.to_datetime(df["tradeDate"])
        df = df.set_index("trade_date").sort_index()

        for col in ["open", "high", "low", "close"]:
            df[col] = pd.to_numeric(df[col], errors="coerce")
        if "volume" in df.columns:
            df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0)

        df = df[["open", "high", "low", "close", "volume"]].dropna(
            subset=["open", "high", "low", "close"]
        )
        return df

    except Exception as e:
        logger.error(f"get_daily_bars [{ticker}]: {e}")
        return None


async def get_all_daily_bars() -> dict[str, pd.DataFrame]:
    """
    Lädt alle verfügbaren Tageskerzen für alle Ticker.
    Gibt Dict { ticker → DataFrame } zurück.
    Verwendet für das Training.
    """
    tickers = await get_all_tickers()
    result  = {}
    logger.info(f"Lade OHLCV-Daten für {len(tickers)} Ticker ...")

    for ticker in tickers:
        df = await get_daily_bars(ticker)
        if df is not None and len(df) >= 100:
            result[ticker] = df
            logger.info(f"  [{ticker}] {len(df)} Tageskerzen geladen")
        else:
            logger.warning(f"  [{ticker}] Nicht genug Daten – übersprungen")

    logger.info(f"Gesamt: {len(result)} Ticker mit ausreichend Daten")
    return result

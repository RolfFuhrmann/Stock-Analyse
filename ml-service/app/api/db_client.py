"""
ml-service/app/api/db_client.py
Lädt OHLCV-Daten aus dem stock-data-db-access Service.
"""
import logging
from urllib.parse import quote

import httpx
import pandas as pd

from app.config import settings

logger = logging.getLogger(__name__)
BASE    = settings.db_service_url
TIMEOUT = httpx.Timeout(60.0)


async def get_all_tickers() -> list[str]:
    """
    Gibt alle Ticker zurück, für die tatsächlich OHLCV-Daten vorliegen (21.09.,
    vorher: alle Ticker aus ticker_meta). ticker_meta wird nur vom
    history-fetcher gepflegt (beim Abruf für seine konfigurierten Listen) -
    ein Ticker, der ausschließlich über den Live-Write-back des
    agent-service-java Daten bekommen hat (z.B. eine neu erstellte Liste, die
    der Fetcher noch nie gesehen hat), tauchte dort nie auf. Das Training blieb
    dadurch faktisch auf die dem Fetcher bekannten Listen beschränkt. Die neue
    Quelle `/api/ohlcv/tickers` zählt direkt in den OHLCV-Tabellen nach und ist
    unabhängig von Listen/ticker_meta.
    """
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        resp = await client.get(f"{BASE}/api/ohlcv/tickers")
        resp.raise_for_status()
        return [t["ticker"] for t in resp.json()]


async def get_daily_bars(ticker: str, limit: int = 0) -> pd.DataFrame | None:
    """
    Lädt Tageskerzen für einen Ticker als DataFrame.
    limit=0 → alle verfügbaren Daten.
    """
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            if limit > 0:
                url  = f"{BASE}/api/ohlcv/daily/{quote(ticker, safe='')}/latest"
                resp = await client.get(url, params={"n": limit})
            else:
                url  = f"{BASE}/api/ohlcv/daily/{quote(ticker, safe='')}"
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
        if df is not None and len(df) >= settings.min_daily_candles:
            result[ticker] = df
            logger.info(f"  [{ticker}] {len(df)} Tageskerzen geladen")
        else:
            logger.warning(f"  [{ticker}] Nicht genug Daten – übersprungen")

    logger.info(f"Gesamt: {len(result)} Ticker mit ausreichend Daten")
    return result


async def get_4h_bars(ticker: str, limit: int = 0) -> pd.DataFrame | None:
    """
    Lädt 4h-Kerzen für einen Ticker als DataFrame.
    limit=0 → alle verfügbaren Daten.
    """
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            if limit > 0:
                url  = f"{BASE}/api/ohlcv/4h/{quote(ticker, safe='')}/latest"
                resp = await client.get(url, params={"n": limit})
            else:
                url  = f"{BASE}/api/ohlcv/4h/{quote(ticker, safe='')}"
                resp = await client.get(url)

            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            bars = resp.json()

        if not bars:
            return None

        df = pd.DataFrame(bars)
        df["trade_date"] = pd.to_datetime(df["tradeTime"])
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
        logger.error(f"get_4h_bars [{ticker}]: {e}")
        return None


async def get_hourly_bars(ticker: str, limit: int = 0) -> pd.DataFrame | None:
    """
    Lädt 1h-Kerzen für einen Ticker als DataFrame.
    limit=0 → alle verfügbaren Daten.
    """
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            if limit > 0:
                url  = f"{BASE}/api/ohlcv/hourly/{quote(ticker, safe='')}/latest"
                resp = await client.get(url, params={"n": limit})
            else:
                url  = f"{BASE}/api/ohlcv/hourly/{quote(ticker, safe='')}"
                resp = await client.get(url)

            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            bars = resp.json()

        if not bars:
            return None

        df = pd.DataFrame(bars)
        df["trade_date"] = pd.to_datetime(df["tradeTime"])
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
        logger.error(f"get_hourly_bars [{ticker}]: {e}")
        return None


async def get_all_bars_by_interval() -> dict[str, dict[str, pd.DataFrame]]:
    """
    Lädt OHLCV-Daten für alle Ticker und alle Intervals.
    Gibt Dict { interval → { ticker → DataFrame } } zurück.
    Wird für das Multi-Interval-Training genutzt.
    """
    tickers = await get_all_tickers()
    result: dict[str, dict[str, pd.DataFrame]] = {
        "1d": {},
        "4h": {},
        "1h": {},
    }
    logger.info(f"Lade OHLCV-Daten (3 Intervals) für {len(tickers)} Ticker ...")

    for ticker in tickers:
        # Daily
        df = await get_daily_bars(ticker)
        if df is not None and len(df) >= settings.min_daily_candles:
            result["1d"][ticker] = df

        # 4h
        df = await get_4h_bars(ticker)
        if df is not None and len(df) >= settings.min_4h_candles:
            result["4h"][ticker] = df

        # 1h
        df = await get_hourly_bars(ticker)
        if df is not None and len(df) >= settings.min_1h_candles:
            result["1h"][ticker] = df

    logger.info(
        f"Geladen: daily={len(result['1d'])} "
        f"4h={len(result['4h'])} "
        f"1h={len(result['1h'])} Ticker"
    )
    return result

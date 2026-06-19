"""
history-fetcher/app/fetcher.py
Kernlogik des History-Fetchers.

Korrekturen gegenüber v1:
  - TwelveData Tagesdaten: interval="1day" wird jetzt korrekt übergeben
  - Stundendaten: werden jetzt für ALLE Quellen abgerufen (yahoo + twelvedata)
  - Besseres Logging bei 0 Kerzen zur Fehlerdiagnose
"""
import asyncio
import logging
from datetime import date, datetime

from app.config import settings
from app.data_client import (
    _normalize_ticker,
    fetch_daily_bars,
    fetch_hourly_bars,
    fetch_4h_bars,
)
from app.db_client import (
    bulk_insert_daily,
    bulk_insert_hourly,
    bulk_insert_4h,
    get_latest_daily_date,
    get_latest_hourly_time,
    get_latest_4h_time,
    get_ticker_list,
    log_fetch,
    upsert_ticker_meta,
)

logger = logging.getLogger(__name__)

LIST_CODES = ["DAX40", "DOW30", "INDIZES", "INTERNATIONALE RTF'S"]


async def _get_all_tickers() -> list[dict]:
    """
    Holt alle Ticker aus allen konfigurierten Listen.
    Unbekannte Listen-Codes werden gewarnt aber nicht abgebrochen.
    """
    all_tickers = []
    seen        = set()

    for code in LIST_CODES:
        data = await get_ticker_list(code)
        if not data:
            logger.warning(f"Liste '{code}' nicht gefunden – übersprungen")
            continue

        source        = data.get("source", "yahoo")
        ticker_format = data.get("tickerFormat", "RAW")
        custom_suffix = data.get("customSuffix")

        symbols = data.get("rawSymbols", [])
        logger.info(f"Liste '{code}': {len(symbols)} Ticker, "
                    f"source={source}, format={ticker_format}")

        for raw in symbols:
            ticker = _normalize_ticker(raw, ticker_format, custom_suffix)
            if ticker in seen:
                continue
            seen.add(ticker)
            all_tickers.append({
                "ticker":        ticker,
                "raw_symbol":    raw,
                "source":        source,
                "ticker_format": ticker_format,
                "custom_suffix": custom_suffix,
                "list_code":     code,
            })

    logger.info(f"Gesamt {len(all_tickers)} eindeutige Ticker aus "
                f"{len(LIST_CODES)} Listen")
    return all_tickers


async def _fetch_and_store_daily(
    ticker: str,
    raw_symbol: str,
    source: str,
    days: int,
    label: str = "initial",
) -> int:
    """Ruft Tagesdaten ab und speichert sie. Gibt Anzahl eingefügter Kerzen zurück."""
    logger.info(f"  [{ticker}] Tagesdaten ({label}, {days} Tage, source={source}) ...")
    bars = await fetch_daily_bars(ticker, source, days)

    if bars is None:
        await log_fetch(ticker, "daily", source, "ERROR", 0,
                        "Kein Ergebnis vom Data-Service")
        return 0

    if not bars:
        await log_fetch(ticker, "daily", source, "PARTIAL", 0,
                        "Leere Balken-Liste – möglicherweise falsches interval")
        return 0

    result   = await bulk_insert_daily(ticker, source, bars)
    inserted = result.get("inserted", 0)
    skipped  = result.get("skipped",  0)

    status = "SUCCESS" if (inserted + skipped) > 0 else "PARTIAL"
    await log_fetch(ticker, "daily", source, status, inserted)
    logger.info(f"  [{ticker}] Daily: {inserted} neu, {skipped} vorhanden")
    return inserted


async def _fetch_and_store_hourly(
    ticker: str,
    source: str,
    hours: int,
) -> int:
    """
    Ruft Stundendaten ab und speichert sie.
    Funktioniert jetzt für yahoo UND twelvedata.
    """
    logger.info(f"  [{ticker}] Stundendaten ({hours} Stunden, source={source}) ...")
    bars = await fetch_hourly_bars(ticker, source, hours)

    if bars is None:
        await log_fetch(ticker, "hourly", source, "ERROR", 0,
                        "Kein Ergebnis vom Data-Service")
        return 0

    if not bars:
        # Kein Fehler – manche Ticker liefern einfach keine Stundendaten
        logger.info(f"  [{ticker}] Keine Stundendaten verfügbar")
        return 0

    result   = await bulk_insert_hourly(ticker, source, bars)
    inserted = result.get("inserted", 0)

    await log_fetch(ticker, "hourly", source, "SUCCESS", inserted)
    logger.info(f"  [{ticker}] Hourly: {inserted} neu gespeichert")
    return inserted


async def _fetch_and_store_4h(
    ticker: str,
    source: str,
    candles: int,
) -> int:
    """
    Ruft 4h-Kerzen ab und speichert sie.
    Yahoo: aggregiert aus 1h-Kerzen.
    TwelveData: nativ via interval="4h".
    """
    logger.info(f"  [{ticker}] 4h-Daten ({candles} Kerzen, source={source}) ...")
    bars = await fetch_4h_bars(ticker, source, candles)

    if bars is None:
        await log_fetch(ticker, "4h", source, "ERROR", 0,
                        "Kein Ergebnis vom Data-Service")
        return 0

    if not bars:
        logger.info(f"  [{ticker}] Keine 4h-Daten verfügbar")
        return 0

    result   = await bulk_insert_4h(ticker, source, bars)
    inserted = result.get("inserted", 0)
    skipped  = result.get("skipped",  0)

    status = "SUCCESS" if (inserted + skipped) > 0 else "PARTIAL"
    await log_fetch(ticker, "4h", source, status, inserted)
    logger.info(f"  [{ticker}] 4h: {inserted} neu, {skipped} vorhanden")
    return inserted


# ── Öffentliche Lauf-Funktionen ───────────────────────────────

async def initial_run() -> dict:
    """
    Erstbefüllung für alle Ticker:
    - 5 Jahre Tagesdaten
    - Stundendaten (Yahoo: ~180 Tage, TwelveData: ~12 Monate)
    """
    logger.info("═" * 60)
    logger.info("INITIAL RUN gestartet (v3 – 4h-Erweiterung)")
    logger.info(f"  Tagesdaten:   {settings.initial_daily_days} Tage")
    logger.info(f"  Stundendaten: {settings.initial_hourly_hours} Stunden")
    logger.info(f"  4h-Daten:     {settings.initial_4h_candles} Kerzen")
    logger.info("═" * 60)

    tickers = await _get_all_tickers()
    if not tickers:
        logger.error("Keine Ticker gefunden – Abbruch")
        return {"status": "error", "message": "Keine Ticker in den Listen"}

    total_daily   = 0
    total_hourly  = 0
    total_4h      = 0
    errors        = []

    for i, t in enumerate(tickers, 1):
        ticker = t["ticker"]
        logger.info(f"[{i}/{len(tickers)}] {ticker} ({t['list_code']})")

        await upsert_ticker_meta(ticker, t["raw_symbol"], t["source"])

        try:
            n = await _fetch_and_store_daily(
                ticker, t["raw_symbol"], t["source"],
                settings.initial_daily_days, "initial"
            )
            total_daily += n
        except Exception as e:
            logger.error(f"[{ticker}] Daily-Fehler: {e}")
            errors.append(f"{ticker} (daily): {e}")

        try:
            n = await _fetch_and_store_hourly(
                ticker, t["source"],
                settings.initial_hourly_hours
            )
            total_hourly += n
        except Exception as e:
            logger.error(f"[{ticker}] Hourly-Fehler: {e}")

        try:
            n = await _fetch_and_store_4h(
                ticker, t["source"],
                settings.initial_4h_candles
            )
            total_4h += n
        except Exception as e:
            logger.error(f"[{ticker}] 4h-Fehler: {e}")

        await asyncio.sleep(settings.ticker_delay_sec)

    logger.info("═" * 60)
    logger.info(f"INITIAL RUN abgeschlossen: {total_daily} Tageskerzen, "
                f"{total_hourly} Stundenkerzen, {total_4h} 4h-Kerzen, "
                f"{len(errors)} Fehler")
    logger.info("═" * 60)

    return {
        "status":      "done",
        "tickers":     len(tickers),
        "daily_bars":  total_daily,
        "hourly_bars": total_hourly,
        "4h_bars":     total_4h,
        "errors":      errors,
    }


async def update_run() -> dict:
    """
    Tägliches Update: Nur neue Kerzen seit letztem Abruf.
    """
    logger.info("─" * 60)
    logger.info("UPDATE RUN gestartet")
    logger.info("─" * 60)

    tickers   = await _get_all_tickers()
    total_new = 0
    errors    = []
    today     = date.today()

    for i, t in enumerate(tickers, 1):
        ticker      = t["ticker"]
        latest      = await get_latest_daily_date(ticker)

        if latest is None:
            logger.info(f"[{i}/{len(tickers)}] {ticker} – keine Daten, Erstbefüllung ...")
            await upsert_ticker_meta(ticker, t["raw_symbol"], t["source"])
            n = await _fetch_and_store_daily(
                ticker, t["raw_symbol"], t["source"],
                settings.initial_daily_days, "initial (via update)"
            )
            total_new += n
        else:
            gap_days = (today - latest).days
            if gap_days <= 0:
                logger.info(f"[{i}/{len(tickers)}] {ticker} – aktuell ({latest})")
                continue

            logger.info(f"[{i}/{len(tickers)}] {ticker} – {gap_days} Tage fehlen")
            try:
                fetch_days = min(gap_days + settings.update_daily_days, 30)
                n = await _fetch_and_store_daily(
                    ticker, t["raw_symbol"], t["source"],
                    fetch_days, "update"
                )
                total_new += n
            except Exception as e:
                logger.error(f"[{ticker}] Update-Fehler: {e}")
                errors.append(f"{ticker}: {e}")

        # ── 1h-Update: nur neue Kerzen seit dem letzten Zeitstempel ──
        # (war bisher komplett ausgelassen → kein TwelveData-Request,
        #  kein Delay für 1h im Update-Lauf)
        try:
            latest_1h = await get_latest_hourly_time(ticker)
            if latest_1h is None:
                # Noch keine 1h-Daten vorhanden → Erstbefüllung
                await _fetch_and_store_hourly(
                    ticker, t["source"], settings.initial_hourly_hours
                )
            else:
                elapsed_hours = int(
                    (datetime.utcnow() - latest_1h).total_seconds() / 3600
                )
                # Nur abrufen wenn seit der letzten Kerze mind. 1h vergangen ist
                if elapsed_hours >= 1:
                    fetch_hours = min(
                        elapsed_hours + settings.update_hourly_buffer_hours,
                        settings.initial_hourly_hours,
                    )
                    await _fetch_and_store_hourly(ticker, t["source"], fetch_hours)
        except Exception as e:
            logger.error(f"[{ticker}] 1h-Update-Fehler: {e}")

        # ── 4h-Update: nur neue Kerzen seit dem letzten Zeitstempel ──
        try:
            latest_4h = await get_latest_4h_time(ticker)
            if latest_4h is None:
                # Noch keine 4h-Daten vorhanden → Erstbefüllung
                await _fetch_and_store_4h(
                    ticker, t["source"], settings.initial_4h_candles
                )
            else:
                elapsed_4h_periods = int(
                    (datetime.utcnow() - latest_4h).total_seconds() / (4 * 3600)
                )
                # Nur abrufen wenn seit der letzten Kerze mind. ein 4h-Block
                # vergangen ist – verhindert unnötige TwelveData-Requests
                # bei jedem täglichen Lauf
                if elapsed_4h_periods >= 1:
                    fetch_candles = min(
                        elapsed_4h_periods + settings.update_4h_candles,
                        settings.initial_4h_candles,
                    )
                    await _fetch_and_store_4h(ticker, t["source"], fetch_candles)
        except Exception as e:
            logger.error(f"[{ticker}] 4h-Update-Fehler: {e}")

        await asyncio.sleep(settings.ticker_delay_sec)

    logger.info("─" * 60)
    logger.info(f"UPDATE RUN: {total_new} neue Kerzen, {len(errors)} Fehler")
    logger.info("─" * 60)

    return {
        "status":   "done",
        "tickers":  len(tickers),
        "new_bars": total_new,
        "errors":   errors,
    }

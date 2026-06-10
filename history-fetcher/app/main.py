"""
history-fetcher/app/main.py
FastAPI-App mit APScheduler für tägliche Updates.

Endpunkte:
  GET  /health              – Liveness-Check
  GET  /status              – Letzter Lauf-Status + Scheduler-Info
  POST /fetch/initial       – Erstbefüllung manuell starten
  POST /fetch/update        – Tägliches Update manuell starten
  GET  /coverage            – Datenbestand-Übersicht (Proxy zum DB-Service)
"""
import asyncio
import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime

import httpx
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import BackgroundTasks, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.fetcher import initial_run, update_run

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s – %(message)s",
)
logger = logging.getLogger(__name__)

# ── Zustand ───────────────────────────────────────────────────
_last_run: dict = {}
_running: bool  = False
scheduler = AsyncIOScheduler()


# ── Startup / Shutdown ────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Beim Start:
      1. Scheduler für tägliche Updates einrichten
      2. Prüfen ob Erstbefüllung nötig ist (keine Daten vorhanden)
         → wenn ja: Erstbefüllung im Hintergrund starten
    """
    # Täglicher Update-Lauf (Standard: 20:00 Uhr)
    scheduler.add_job(
        _scheduled_update,
        CronTrigger(
            hour=settings.daily_update_hour,
            minute=settings.daily_update_minute,
        ),
        id="daily_update",
        name="Täglicher Kurs-Update",
        replace_existing=True,
    )
    scheduler.start()
    logger.info(
        f"Scheduler gestartet – täglicher Update um "
        f"{settings.daily_update_hour:02d}:{settings.daily_update_minute:02d} Uhr"
    )

    # Erstbefüllung prüfen
    auto_initial = os.getenv("AUTO_INITIAL_RUN", "true").lower() == "true"
    if auto_initial:
        logger.info("AUTO_INITIAL_RUN=true – prüfe ob Erstbefüllung nötig ...")
        asyncio.create_task(_auto_initial_if_needed())

    yield

    scheduler.shutdown()
    logger.info("Scheduler gestoppt")


async def _auto_initial_if_needed():
    """
    Startet die Erstbefüllung automatisch wenn noch keine Daten vorhanden sind.
    Wartet kurz bis alle anderen Services bereit sind.
    """
    await asyncio.sleep(10)  # Services hochfahren lassen

    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(f"{settings.db_service_url}/api/ohlcv/coverage")
            if resp.status_code == 200:
                data = resp.json()
                if data.get("totalDailyBars", 0) == 0:
                    logger.info("Keine Tagesdaten gefunden → Erstbefüllung wird gestartet")
                    await _run_fetch(initial_run, "initial (auto)")
                else:
                    logger.info(
                        f"Daten bereits vorhanden ({data['totalDailyBars']} Tageskerzen) "
                        f"→ kein Initial-Run nötig"
                    )
    except Exception as e:
        logger.warning(f"Auto-Initial-Check fehlgeschlagen: {e} – manuell per POST /fetch/initial starten")


async def _scheduled_update():
    await _run_fetch(update_run, "update (scheduled)")


async def _run_fetch(fn, label: str):
    global _running, _last_run
    if _running:
        logger.warning(f"Fetch bereits aktiv – {label} übersprungen")
        return
    _running = True
    try:
        logger.info(f"Starte: {label}")
        result = await fn()
        _last_run = {
            "label":      label,
            "finished_at": datetime.now().isoformat(),
            "result":      result,
        }
    except Exception as e:
        logger.error(f"Fetch-Fehler [{label}]: {e}")
        _last_run = {
            "label":       label,
            "finished_at": datetime.now().isoformat(),
            "result":      {"status": "error", "message": str(e)},
        }
    finally:
        _running = False


# ── App ───────────────────────────────────────────────────────

app = FastAPI(
    title="History Fetcher",
    description="Historische Kursdaten (OHLCV) befüllen und täglich aktualisieren",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Endpunkte ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status":    "ok",
        "service":   "history-fetcher",
        "running":   _running,
        "scheduler": "active" if scheduler.running else "stopped",
    }


@app.get("/status")
def status():
    next_run = None
    job = scheduler.get_job("daily_update")
    if job and job.next_run_time:
        next_run = job.next_run_time.isoformat()

    return {
        "running":      _running,
        "next_update":  next_run,
        "last_run":     _last_run or None,
        "config": {
            "initial_daily_days":   settings.initial_daily_days,
            "initial_hourly_hours": settings.initial_hourly_hours,
            "update_hour":          settings.daily_update_hour,
            "update_minute":        settings.daily_update_minute,
        },
    }


@app.post("/fetch/initial")
async def trigger_initial(background_tasks: BackgroundTasks):
    """Erstbefüllung manuell starten (läuft im Hintergrund)."""
    if _running:
        return {"status": "busy", "message": "Fetch läuft bereits"}
    background_tasks.add_task(_run_fetch, initial_run, "initial (manual)")
    return {"status": "started", "message": "Erstbefüllung gestartet – siehe /status"}


@app.post("/fetch/update")
async def trigger_update(background_tasks: BackgroundTasks):
    """Tägliches Update manuell starten (läuft im Hintergrund)."""
    if _running:
        return {"status": "busy", "message": "Fetch läuft bereits"}
    background_tasks.add_task(_run_fetch, update_run, "update (manual)")
    return {"status": "started", "message": "Update gestartet – siehe /status"}


@app.get("/coverage")
async def coverage():
    """Proxy: Datenbestand-Übersicht vom DB-Service."""
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(f"{settings.db_service_url}/api/ohlcv/coverage")
        return resp.json()

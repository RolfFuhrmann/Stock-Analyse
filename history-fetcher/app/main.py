"""
history-fetcher/app/main.py
FastAPI-App zum Befüllen und Reparieren der Kursdaten in der DB.

Standardmäßig läuft der Fetcher NUR MANUELL (auto_run_enabled=false, siehe
config.py). Mit AUTO_RUN_ENABLED=true kommen ein täglicher APScheduler-Lauf
und ein Catch-up bei jedem Container-Start dazu.

Endpunkte:
  GET  /health              – Liveness-Check
  GET  /status              – Letzter Lauf-Status + Scheduler-Info
  POST /fetch/initial       – Alles neu abrufen (überschreibt vorhandene Kerzen)
  POST /fetch/update        – Fehlende Kerzen nachholen (Reparatur)
  GET  /coverage            – Datenbestand-Übersicht (Proxy zum DB-Service)
"""
import asyncio
import logging
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
    Automatischer Betrieb ist standardmäßig AUS (settings.auto_run_enabled):
    Dann wird weder ein Scheduler gestartet noch beim Start etwas abgerufen -
    Läufe passieren ausschließlich über POST /fetch/update bzw. /fetch/initial.

    Bei AUTO_RUN_ENABLED=true:
      1. Scheduler für tägliche Updates einrichten
      2. Bei jedem Start einen Catch-up starten (leere DB → Erstbefüllung,
         sonst Update-Lauf für fehlende Kerzen)
    """
    if settings.auto_run_enabled:
        # Täglicher Update-Lauf (Standard: 20:00 Uhr)
        #
        # misfire_grace_time: APScheduler überspringt einen verpassten Lauf
        # standardmäßig komplett, wenn er mehr als 1 Sekunde zu spät dran ist
        # (Default-Wert der Bibliothek). Läuft der Host (z.B. lokaler Mac) zur
        # geplanten Zeit im Schlafzustand, wacht der Container-Prozess erst
        # Stunden später wieder auf ("missed by 17:48:34" im Log) - ohne
        # großzügige Grace-Time wird der Lauf dann NIE nachgeholt, wodurch die
        # DB dauerhaft veraltet bleibt. coalesce=True (APScheduler-Default)
        # sorgt dafür, dass bei mehreren verpassten Läufen trotzdem nur einer
        # nachgeholt wird.
        scheduler.add_job(
            _scheduled_update,
            CronTrigger(
                hour=settings.daily_update_hour,
                minute=settings.daily_update_minute,
                timezone=settings.scheduler_timezone,
            ),
            id="daily_update",
            name="Täglicher Kurs-Update",
            replace_existing=True,
            misfire_grace_time=settings.misfire_grace_hours * 3600,
            coalesce=True,
        )
        scheduler.start()
        logger.info(
            f"Scheduler gestartet – täglicher Update um "
            f"{settings.daily_update_hour:02d}:{settings.daily_update_minute:02d} Uhr "
            f"(misfire_grace_time={settings.misfire_grace_hours}h)"
        )

        # Start-Catchup: läuft bei JEDEM Container-Start, nicht nur bei leerer
        # DB (pro Ticker wird nur bei tatsächlicher Lücke etwas nachgeladen,
        # siehe update_run()).
        logger.info("AUTO_RUN_ENABLED=true – prüfe Datenbestand beim Start ...")
        asyncio.create_task(_startup_catchup())
    else:
        logger.info(
            "Automatischer Betrieb AUS – Läufe nur manuell: "
            "POST /fetch/update (fehlende Kerzen) bzw. POST /fetch/initial (alles neu)"
        )

    yield

    if scheduler.running:
        scheduler.shutdown()
        logger.info("Scheduler gestoppt")


async def _startup_catchup():
    """
    Läuft bei jedem Container-Start:
    - Komplett leere DB (totalDailyBars == 0)   → volle Erstbefüllung
    - Bereits vorhandene Daten (auch wenn alt)  → Update-Lauf (holt pro
      Ticker nur die tatsächlich fehlenden Tage nach, siehe update_run())

    Vorher wurde bei vorhandenen Daten GAR NICHTS getan und stattdessen rein
    auf den täglichen 20-Uhr-Cron vertraut - der aber verpasst wird, wenn der
    Host zu dieser Zeit im Schlafzustand ist (siehe misfire_grace_time weiter
    oben). Damit blieb die DB nach einem Neustart beliebig lange veraltet.

    Wartet mit Retry auf db-service, statt nach einem einzigen Versuch
    aufzugeben: docker-compose kennt für db-service keinen Healthcheck,
    "depends_on" wartet daher nur bis der Container-PROZESS gestartet ist,
    nicht bis die Spring-Boot-App tatsächlich auf Port 8013 antwortet. Bei
    einem vollen Stack-Neustart (inkl. MySQL) kann das deutlich länger als
    die vorherigen 10 Sekunden dauern.
    """
    max_wait_sec = 180
    poll_interval_sec = 5
    waited = 0.0

    while waited < max_wait_sec:
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
                            f"Daten vorhanden ({data['totalDailyBars']} Tageskerzen) "
                            f"→ Catch-up-Update wird gestartet (holt nur fehlende Tage nach)"
                        )
                        await _run_fetch(update_run, "update (startup catchup)")
                    return
                else:
                    logger.info(
                        f"db-service antwortet noch nicht wie erwartet "
                        f"(HTTP {resp.status_code}) – warte {poll_interval_sec}s ..."
                    )
        except Exception as e:
            logger.info(
                f"db-service noch nicht erreichbar ({type(e).__name__}: {e!r}) "
                f"– warte {poll_interval_sec}s ... ({waited:.0f}/{max_wait_sec}s)"
            )

        await asyncio.sleep(poll_interval_sec)
        waited += poll_interval_sec

    logger.warning(
        f"Start-Catchup abgebrochen: db-service nach {max_wait_sec}s immer noch "
        f"nicht erreichbar – manuell per POST /fetch/update starten"
    )


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
    description="Historische Kursdaten (OHLCV) befüllen und reparieren (manuell; täglicher Lauf optional)",
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
        "running":          _running,
        "auto_run_enabled": settings.auto_run_enabled,
        "next_update":      next_run,
        "last_run":         _last_run or None,
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

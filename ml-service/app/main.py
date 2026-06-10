"""
ml-service/app/main.py

FastAPI-App mit:
  - Automatischem Training beim ersten Start (wenn kein Modell vorhanden)
  - Wöchentlichem Retraining (Sonntag 02:00 Uhr)
  - REST-Endpunkten für Vorhersagen und Modell-Status

Endpunkte:
  GET  /health                    – Liveness-Check
  GET  /model/status              – Modell-Metadaten + Trainings-Metriken
  POST /model/train               – Training manuell starten
  POST /predict/{ticker}          – Umkehrwahrscheinlichkeit für einen Ticker
  POST /predict/batch             – Vorhersagen für mehrere Ticker
"""
import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import BackgroundTasks, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.api.db_client import get_all_daily_bars, get_daily_bars
from app.config import settings
from app.model import predictor
from app.model.trainer import META_PATH, load_meta, train

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s – %(message)s",
)
logger = logging.getLogger(__name__)

# ── Zustand ───────────────────────────────────────────────────
_training: bool = False
_last_train: dict = {}
scheduler = AsyncIOScheduler()


# ── Training ──────────────────────────────────────────────────

async def _run_training(label: str = "manual"):
    global _training, _last_train
    if _training:
        logger.warning("Training läuft bereits – übersprungen")
        return

    _training = True
    try:
        logger.info(f"Training gestartet ({label})")
        ohlcv = await get_all_daily_bars()

        if not ohlcv:
            raise ValueError("Keine OHLCV-Daten verfügbar")

        meta = train(ohlcv)
        predictor.reload()

        _last_train = {
            "label":      label,
            "finished_at": datetime.now().isoformat(),
            "result":      meta,
        }
        logger.info("Training abgeschlossen")

    except Exception as e:
        logger.error(f"Training-Fehler: {e}")
        _last_train = {
            "label":       label,
            "finished_at": datetime.now().isoformat(),
            "result":      {"status": "error", "message": str(e)},
        }
    finally:
        _training = False


# ── Startup ───────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Wöchentliches Retraining einrichten
    scheduler.add_job(
        lambda: asyncio.create_task(_run_training("weekly retrain")),
        CronTrigger(
            day_of_week=settings.retrain_weekday,
            hour=settings.retrain_hour,
            minute=settings.retrain_minute,
        ),
        id="weekly_retrain",
        replace_existing=True,
    )
    scheduler.start()
    logger.info(
        f"Scheduler aktiv – wöchentliches Retraining "
        f"(weekday={settings.retrain_weekday}, "
        f"{settings.retrain_hour:02d}:{settings.retrain_minute:02d})"
    )

    # Modell beim Start laden (falls vorhanden)
    predictor.reload()

    # Wenn kein Modell vorhanden → automatisches Training
    if not META_PATH.exists():
        logger.info("Kein Modell gefunden → automatisches Training in 15 Sekunden ...")
        await asyncio.sleep(15)
        asyncio.create_task(_run_training("auto (initial)"))
    else:
        meta = load_meta()
        logger.info(
            f"Modell geladen – trainiert am {meta.get('trained_at', '?')}, "
            f"ROC-AUC={meta.get('backtesting', {}).get('roc_auc', '?')}"
        )

    yield
    scheduler.shutdown()


# ── App ───────────────────────────────────────────────────────

app = FastAPI(
    title="ML Reversal Service",
    description="XGBoost-basierte Umkehrwahrscheinlichkeit für Aktienkurse",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request/Response Models ───────────────────────────────────

class BatchPredictRequest(BaseModel):
    tickers: list[str]
    lookback_days: int = 300   # Kerzen für die Vorhersage


class PredictResponse(BaseModel):
    ticker:          str
    reversal_prob:   float | None
    reversal_pct:    float | None
    signal:          str
    confidence:      str
    top_features:    dict
    model_available: bool
    error:           str | None = None


# ── Endpunkte ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status":    "ok",
        "service":   "ml-service",
        "training":  _training,
        "model_ready": META_PATH.exists(),
    }


@app.get("/model/status")
def model_status():
    meta = load_meta()
    next_run = None
    job = scheduler.get_job("weekly_retrain")
    if job and job.next_run_time:
        next_run = job.next_run_time.isoformat()

    return {
        "model_ready":    META_PATH.exists(),
        "training_active": _training,
        "next_retrain":   next_run,
        "last_train":     _last_train or None,
        "model_meta":     meta,
    }


@app.post("/model/train")
async def trigger_training(background_tasks: BackgroundTasks):
    """Training manuell starten (läuft im Hintergrund)."""
    if _training:
        return {"status": "busy", "message": "Training läuft bereits"}
    background_tasks.add_task(_run_training, "manual")
    return {"status": "started", "message": "Training gestartet – siehe /model/status"}


@app.post("/predict/{ticker}", response_model=PredictResponse)
async def predict_single(ticker: str, lookback_days: int = 300):
    """Umkehrwahrscheinlichkeit für einen einzelnen Ticker."""
    t = ticker.upper()
    df = await get_daily_bars(t, limit=lookback_days)

    if df is None or df.empty:
        raise HTTPException(status_code=404, detail=f"Keine Daten für {t}")

    result = predictor.predict(df)
    return PredictResponse(ticker=t, **result)


@app.post("/predict/batch", response_model=list[PredictResponse])
async def predict_batch(request: BatchPredictRequest):
    """
    Umkehrwahrscheinlichkeit für mehrere Ticker in einem Request.
    Wird vom agent-service für den Analyse-Lauf genutzt.
    """
    responses = []
    for ticker in request.tickers:
        t  = ticker.upper()
        df = await get_daily_bars(t, limit=request.lookback_days)

        if df is None or df.empty:
            responses.append(PredictResponse(
                ticker=t,
                reversal_prob=None, reversal_pct=None,
                signal="none", confidence="low",
                top_features={}, model_available=False,
                error="Keine Daten",
            ))
            continue

        result = predictor.predict(df)
        responses.append(PredictResponse(ticker=t, **result))

    return responses

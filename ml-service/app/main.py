"""
ml-service/app/main.py

FastAPI-App mit:
  - Automatischem Training beim ersten Start (wenn kein Modell vorhanden)
  - Wöchentlichem Retraining (Sonntag 02:00 Uhr)
  - REST-Endpunkten für Vorhersagen und Modell-Status

Endpunkte:
  GET  /health                    – Liveness-Check
  GET  /model/status              – Modell-Metadaten + Trainings-Metriken
  GET  /model/info                – aufbereitete Modell-Info für den Client (deutsche Labels)
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

from app.api.db_client import get_all_daily_bars, get_daily_bars, get_4h_bars, get_hourly_bars, get_all_bars_by_interval
from app.config import settings
from app.features.labels import label_of
from app.model import predictor
from app.model.trainer import INTERVALS, any_model_ready, load_all_meta, load_meta, train

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
        ohlcv = await get_all_bars_by_interval()

        if not any(ohlcv.values()):
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

    # Wenn kein einziges Modell vorhanden → automatisches Training
    if not any_model_ready():
        logger.info("Kein Modell gefunden → automatisches Training in 15 Sekunden ...")
        await asyncio.sleep(15)
        asyncio.create_task(_run_training("auto (initial)"))
    else:
        for iv in INTERVALS:
            m = load_meta(iv)
            if m:
                logger.info(
                    f"Modell {iv} geladen – trainiert am {m.get('trained_at', '?')}, "
                    f"ROC-AUC={m.get('backtesting', {}).get('roc_auc', '?')}"
                )
            else:
                logger.info(f"Modell {iv} noch nicht trainiert")

    yield
    scheduler.shutdown()


# ── App ───────────────────────────────────────────────────────

app = FastAPI(
    title="ML Reversal Service",
    description="XGBoost-basierte Umkehrwahrscheinlichkeit für Aktienkurse",
    version="2.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request/Response Models ───────────────────────────────────

class PredictRequest(BaseModel):
    interval:     str = "1d"    # "1d" | "4h" | "1h"
    lookback_days: int = 300    # Kerzen für die Vorhersage


class BatchPredictRequest(BaseModel):
    tickers:      list[str]
    interval:     str = "1d"    # "1d" | "4h" | "1h"
    lookback_days: int = 300    # Kerzen für die Vorhersage


class PredictResponse(BaseModel):
    ticker:          str
    reversal_prob:   float | None
    reversal_pct:    float | None
    signal:          str
    confidence:      str
    top_features:    dict
    explanation:     dict | None = None
    model_available: bool
    error:           str | None = None


# ── Endpunkte ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status":      "ok",
        "service":     "ml-service",
        "training":    _training,
        "model_ready": any_model_ready(),
    }


@app.get("/model/status")
def model_status():
    next_run = None
    job = scheduler.get_job("weekly_retrain")
    if job and job.next_run_time:
        next_run = job.next_run_time.isoformat()

    return {
        "model_ready":     any_model_ready(),
        "training_active": _training,
        "next_retrain":    next_run,
        "last_train":      _last_train or None,
        "model_meta":      load_all_meta(),  # {"1d": {...} | None, "4h": ..., "1h": ...}
    }


def _model_info_for(meta: dict | None) -> dict:
    """Aufbereitete Modell-Info EINES Zeitrahmens (siehe model_info())."""
    if meta is None:
        return {"model_ready": False}

    importance = meta.get("feature_importance_all") or meta.get("feature_importance") or {}
    summary    = meta.get("summary") or {}

    return {
        "model_ready":       True,
        "trained_at":        meta.get("trained_at"),
        "total_samples":     meta.get("total_samples"),
        "train_samples":     meta.get("train_samples"),
        "test_samples":      meta.get("test_samples"),
        "tickers_count":     len(meta.get("tickers", [])),
        "positive_rate_pct": meta.get("positive_rate_pct"),
        "forecast_horizon":  meta.get("forecast_horizon"),
        "reversal_threshold_pct": meta.get("reversal_threshold"),
        "scale_pos_weight":  meta.get("scale_pos_weight"),
        # False bei Modellen, die noch vor dem 21.09. trainiert wurden (kein
        # Kalibrator vorhanden) - der Modellwert ist dann unkalibriert, siehe
        # das Feld "calibrated" in der Anzeige.
        "calibrated":        meta.get("calibrated", False),
        "val_samples":       meta.get("val_samples"),
        "metrics":           meta.get("backtesting", {}),
        "summary":           summary,
        "feature_importance": [
            {"feature": name, "label": label_of(name), "importance": value}
            for name, value in list(importance.items())[:12]
        ],
        "calibration":       meta.get("calibration"),
    }


@app.get("/model/info")
def model_info():
    """
    Aufbereitete Modell-Info für die Anzeige im Client, JE ZEITRAHMEN
    (29.09.: drei eigenständige Modelle statt eines gemeinsamen - siehe
    CLAUDE.md 3.8). Enthält deutsche Feature-Labels, die Signal-Schwellen
    (gemeinsam für alle drei) sowie Diagnosedaten je Modell.

    Antwortform: {"1d": {...}, "4h": {...}, "1h": {...}}, jeweils mit der
    bisherigen flachen Struktur (model_ready, trained_at, metrics, ...).
    """
    next_run = None
    job = scheduler.get_job("weekly_retrain")
    if job and job.next_run_time:
        next_run = job.next_run_time.isoformat()

    metas = load_all_meta()
    result = {iv: _model_info_for(metas.get(iv)) for iv in INTERVALS}
    for iv in INTERVALS:
        result[iv]["training_active"] = _training
        result[iv]["next_retrain"]    = next_run
        result[iv]["thresholds"] = {
            "weak":     round(predictor.THRESHOLD_WEAK * 100),
            "moderate": round(predictor.THRESHOLD_MODERATE * 100),
            "strong":   round(predictor.THRESHOLD_STRONG * 100),
        }
    return result


@app.post("/model/train")
async def trigger_training(background_tasks: BackgroundTasks):
    """Training manuell starten (läuft im Hintergrund)."""
    if _training:
        return {"status": "busy", "message": "Training läuft bereits"}
    background_tasks.add_task(_run_training, "manual")
    return {"status": "started", "message": "Training gestartet – siehe /model/status"}


@app.post("/predict/{ticker}", response_model=PredictResponse)
async def predict_single(ticker: str, request: PredictRequest = PredictRequest()):
    """
    Umkehrwahrscheinlichkeit für einen einzelnen Ticker.
    Body: { "interval": "1d" | "4h" | "1h", "lookback_days": 300 }
    """
    t = ticker.upper()

    if request.interval == "4h":
        df = await get_4h_bars(t, limit=request.lookback_days)
    elif request.interval == "1h":
        df = await get_hourly_bars(t, limit=request.lookback_days)
    else:
        df = await get_daily_bars(t, limit=request.lookback_days)

    if df is None or df.empty:
        raise HTTPException(status_code=404, detail=f"Keine Daten für {t} (interval={request.interval})")

    result = predictor.predict(df, interval=request.interval)
    return PredictResponse(ticker=t, **result)


@app.post("/predict/batch", response_model=list[PredictResponse])
async def predict_batch(request: BatchPredictRequest):
    """
    Umkehrwahrscheinlichkeit für mehrere Ticker in einem Request.
    Wird vom agent-service für den Analyse-Lauf genutzt.
    Body: { "tickers": [...], "interval": "1d" | "4h" | "1h", "lookback_days": 300 }
    """
    responses = []
    for ticker in request.tickers:
        t = ticker.upper()

        if request.interval == "4h":
            df = await get_4h_bars(t, limit=request.lookback_days)
        elif request.interval == "1h":
            df = await get_hourly_bars(t, limit=request.lookback_days)
        else:
            df = await get_daily_bars(t, limit=request.lookback_days)

        if df is None or df.empty:
            responses.append(PredictResponse(
                ticker=t,
                reversal_prob=None, reversal_pct=None,
                signal="none", confidence="low",
                top_features={}, model_available=False,
                error=f"Keine Daten (interval={request.interval})",
            ))
            continue

        result = predictor.predict(df, interval=request.interval)
        responses.append(PredictResponse(ticker=t, **result))

    return responses

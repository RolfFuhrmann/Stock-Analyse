"""
ml-service/app/model/trainer.py

XGBoost-Training für die Umkehrwahrscheinlichkeit.

Ablauf:
  1. OHLCV-Daten aller Ticker aus dem DB-Service laden
  2. Features und Labels berechnen
  3. Zeitreihen-Split (kein zufälliges Shufflen – verhindert Data-Leakage)
  4. XGBoost trainieren
  5. Backtesting auf dem Test-Set
  6. Modell + Metadaten speichern
"""
import json
import logging
import os
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import (
    classification_report,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier

from app.config import settings
from app.features.engineer import FEATURE_NAMES, INTERVAL_CODE_MAP, compute_features, compute_labels

logger = logging.getLogger(__name__)

MODEL_PATH    = Path(settings.model_dir) / "xgb_reversal.joblib"
SCALER_PATH   = Path(settings.model_dir) / "scaler.joblib"
META_PATH     = Path(settings.model_dir) / "model_meta.json"


def _collect_features_for_interval(
    ohlcv_by_ticker: dict[str, pd.DataFrame],
    interval: str,
    min_rows: int = 100,
) -> tuple[list, list]:
    """
    Berechnet Features und Labels für alle Ticker eines Intervals.
    Gibt (all_features, all_labels) zurück.
    """
    all_features = []
    all_labels   = []

    for ticker, df in ohlcv_by_ticker.items():
        if len(df) < min_rows:
            logger.warning(
                f"[{ticker}/{interval}] Zu wenig Daten ({len(df)} Zeilen) – übersprungen"
            )
            continue
        try:
            # interval wird als Feature #39 (interval_code) eingebettet
            feat   = compute_features(df, interval=interval)
            labels = compute_labels(
                df, settings.forecast_horizon, settings.reversal_threshold_pct
            )

            labels = labels.dropna()
            common = feat.index.intersection(labels.index)
            feat   = feat.loc[common]
            labels = labels.loc[common]

            if len(feat) < settings.min_samples_per_class * 2:
                logger.warning(
                    f"[{ticker}/{interval}] Zu wenig Samples – übersprungen"
                )
                continue

            all_features.append(feat)
            all_labels.append(labels)
            logger.info(
                f"[{ticker}/{interval}] {len(feat)} Samples, "
                f"{int(labels.sum())} Umkehrsignale ({labels.mean()*100:.1f}%)"
            )
        except Exception as e:
            logger.error(f"[{ticker}/{interval}] Feature-Fehler: {e}")

    return all_features, all_labels


def train(ohlcv_by_interval: dict[str, dict[str, pd.DataFrame]]) -> dict:
    """
    Multi-Interval-Training.

    ohlcv_by_interval: {
        "1d": { "ADS.DE": DataFrame, ... },
        "4h": { "ADS.DE": DataFrame, ... },
        "1h": { "ADS.DE": DataFrame, ... },
    }

    Alle drei Zeitrahmen werden zu einem gemeinsamen Trainings-Dataset
    zusammengeführt. interval_code (0/1/2) als Feature #39 erlaubt dem
    Modell, je Zeitrahmen unterschiedliche Schwellen zu lernen.

    Gibt ein Dict mit Trainings-Metriken zurück.
    """
    total_tickers = sum(len(v) for v in ohlcv_by_interval.values())
    logger.info("═" * 60)
    logger.info("TRAINING gestartet (Multi-Interval: 1d / 4h / 1h)")
    logger.info(f"  Ticker gesamt:      {total_tickers}")
    logger.info(f"  Forecast-Horizont:  {settings.forecast_horizon} Kerzen")
    logger.info(f"  Umkehr-Schwelle:    {settings.reversal_threshold_pct}%")
    logger.info("═" * 60)

    # ── 1. Features und Labels für alle Intervals berechnen ───
    all_features = []
    all_labels   = []

    for interval, tickers_df in ohlcv_by_interval.items():
        if not tickers_df:
            logger.warning(f"Keine Daten für Interval {interval} – übersprungen")
            continue
        logger.info(f"── Interval {interval}: {len(tickers_df)} Ticker ──")
        feats, labels = _collect_features_for_interval(tickers_df, interval)
        all_features.extend(feats)
        all_labels.extend(labels)

    if not all_features:
        raise ValueError("Kein einziger Ticker/Interval lieferte ausreichend Daten für das Training")

    X = pd.concat(all_features, ignore_index=True)
    y = pd.concat(all_labels,   ignore_index=True).astype(int)

    logger.info(f"Gesamt: {len(X)} Samples, {int(y.sum())} positiv ({y.mean()*100:.1f}%)")

    # ── 2. Zeitreihen-Split (80% Train / 20% Test) ────────────
    # WICHTIG: Kein zufälliges Shufflen – spätere Daten kommen ins Test-Set
    split = int(len(X) * 0.8)
    X_train, X_test = X.iloc[:split], X.iloc[split:]
    y_train, y_test = y.iloc[:split], y.iloc[split:]

    logger.info(f"Train: {len(X_train)} Samples | Test: {len(X_test)} Samples")

    if y_train.sum() < settings.min_samples_per_class:
        raise ValueError(
            f"Zu wenig positive Trainingsbeispiele: {y_train.sum()} "
            f"(Minimum: {settings.min_samples_per_class})"
        )

    # ── 3. Feature-Skalierung ─────────────────────────────────
    # XGBoost braucht keine Skalierung, aber der Scaler hilft beim
    # späteren LSTM-Training und macht Features vergleichbar.
    scaler  = StandardScaler()
    X_train_s = scaler.fit_transform(X_train)
    X_test_s  = scaler.transform(X_test)

    # ── 4. Klassengewichte berechnen ──────────────────────────
    # Umkehrpunkte sind selten → Klasse 1 höher gewichten
    pos_weight = (y_train == 0).sum() / max((y_train == 1).sum(), 1)
    logger.info(f"Klassen-Gewicht (pos): {pos_weight:.2f}")

    # ── 5. XGBoost trainieren ─────────────────────────────────
    model = XGBClassifier(
        n_estimators=400,
        max_depth=5,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        scale_pos_weight=pos_weight,
        use_label_encoder=False,
        eval_metric="logloss",
        early_stopping_rounds=30,
        random_state=42,
        n_jobs=-1,          # alle Kerne nutzen
        tree_method="hist", # schnellster Algorithmus, M3-kompatibel
    )

    model.fit(
        X_train_s, y_train,
        eval_set=[(X_test_s, y_test)],
        verbose=False,
    )

    # ── 6. Backtesting ────────────────────────────────────────
    y_prob  = model.predict_proba(X_test_s)[:, 1]
    y_pred  = (y_prob >= 0.5).astype(int)

    precision = precision_score(y_test, y_pred, zero_division=0)
    recall    = recall_score(y_test, y_pred, zero_division=0)
    auc       = roc_auc_score(y_test, y_prob) if y_test.sum() > 0 else 0.0

    logger.info("── Backtesting-Ergebnis ──────────────────────────")
    logger.info(f"  Precision:  {precision:.3f}")
    logger.info(f"  Recall:     {recall:.3f}")
    logger.info(f"  ROC-AUC:    {auc:.3f}")
    logger.info(classification_report(y_test, y_pred,
                                       target_names=["kein Signal", "Umkehr"],
                                       zero_division=0))

    # ── 7. Feature Importance (Top 10) ────────────────────────
    importances = dict(zip(
        FEATURE_NAMES,
        model.feature_importances_.tolist()
    ))
    top10 = sorted(importances.items(), key=lambda x: x[1], reverse=True)[:10]
    logger.info("── Top-10 Features ───────────────────────────────")
    for name, imp in top10:
        logger.info(f"  {name:<25} {imp:.4f}")

    # ── 8. Modell speichern ───────────────────────────────────
    Path(settings.model_dir).mkdir(parents=True, exist_ok=True)
    joblib.dump(model,  MODEL_PATH)
    joblib.dump(scaler, SCALER_PATH)

    meta = {
        "trained_at":         datetime.now().isoformat(),
        "tickers":            [
            f"{t}/{iv}"
            for iv, tmap in ohlcv_by_interval.items()
            for t in tmap
        ],
        "total_samples":      len(X),
        "train_samples":      len(X_train),
        "test_samples":       len(X_test),
        "positive_rate_pct":  round(float(y.mean() * 100), 2),
        "forecast_horizon":   settings.forecast_horizon,
        "reversal_threshold": settings.reversal_threshold_pct,
        "backtesting": {
            "precision": round(precision, 4),
            "recall":    round(recall, 4),
            "roc_auc":   round(auc, 4),
        },
        "feature_importance": {k: round(v, 4) for k, v in top10},
        "intervals_trained":  list(ohlcv_by_interval.keys()),
        "n_estimators_used":  model.best_iteration + 1,
    }
    with open(META_PATH, "w") as f:
        json.dump(meta, f, indent=2)

    logger.info(f"Modell gespeichert → {MODEL_PATH}")
    logger.info("═" * 60)

    return meta


def load_model():
    """Lädt Modell + Scaler. Gibt (model, scaler) zurück oder (None, None)."""
    if not MODEL_PATH.exists() or not SCALER_PATH.exists():
        return None, None
    try:
        return joblib.load(MODEL_PATH), joblib.load(SCALER_PATH)
    except Exception as e:
        logger.error(f"Modell laden fehlgeschlagen: {e}")
        return None, None


def load_meta() -> dict | None:
    """Lädt die gespeicherten Trainings-Metadaten."""
    if not META_PATH.exists():
        return None
    with open(META_PATH) as f:
        return json.load(f)

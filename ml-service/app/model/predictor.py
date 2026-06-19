"""
ml-service/app/model/predictor.py

Lädt das trainierte Modell und berechnet für einen einzelnen Ticker
die Umkehrwahrscheinlichkeit auf Basis der letzten N Kerzen.
"""
import logging

import numpy as np
import pandas as pd

from app.features.engineer import compute_features
from app.model.trainer import load_meta, load_model

logger = logging.getLogger(__name__)

# Modell einmalig beim Import laden (wird durch retrain() neu geladen)
_model, _scaler = None, None


def _ensure_loaded():
    global _model, _scaler
    if _model is None:
        _model, _scaler = load_model()


def reload():
    """Nach einem Retraining aufrufen um das neue Modell zu laden."""
    global _model, _scaler
    _model, _scaler = load_model()
    logger.info("Modell neu geladen")


def predict(df: pd.DataFrame, interval: str = "1d") -> dict:
    """
    Berechnet die Umkehrwahrscheinlichkeit für die letzte Kerze im DataFrame.

    df:       OHLCV-DataFrame (chronologisch aufsteigend, mind. 220 Zeilen)
    interval: "1d" | "4h" | "1h" – wird als Feature #39 eingebettet

    Gibt zurück:
    {
      "reversal_prob":   0.73,      # 0–1, Wahrscheinlichkeit einer Aufwärtsumkehr
      "reversal_pct":    73,         # gerundeter %-Wert für die Anzeige
      "signal":          "strong",   # none | weak | moderate | strong
      "confidence":      "high",     # low | medium | high
      "top_features":    {...},      # die 3 stärksten Signale
      "model_available": True
    }
    """
    _ensure_loaded()

    if _model is None:
        return {
            "reversal_prob":   None,
            "reversal_pct":    None,
            "signal":          "none",
            "confidence":      "low",
            "top_features":    {},
            "model_available": False,
        }

    if len(df) < 210:
        return {
            "reversal_prob":   None,
            "reversal_pct":    None,
            "signal":          "none",
            "confidence":      "low",
            "top_features":    {},
            "model_available": True,
        }

    try:
        features = compute_features(df, interval=interval)
        if features.empty:
            raise ValueError("Keine Features berechnet")

        # Letzte Zeile = aktuellster Zeitpunkt
        X = features.iloc[[-1]]
        X_scaled = _scaler.transform(X)

        prob = float(_model.predict_proba(X_scaled)[0, 1])

        # ── Signal-Stärke ──────────────────────────────────────
        if prob >= 0.75:
            signal     = "strong"
            confidence = "high"
        elif prob >= 0.55:
            signal     = "moderate"
            confidence = "medium"
        elif prob >= 0.40:
            signal     = "weak"
            confidence = "low"
        else:
            signal     = "none"
            confidence = "low"

        # ── Top-3 Feature-Werte (für die Anzeige) ─────────────
        meta = load_meta()
        top_features = {}
        if meta and meta.get("feature_importance"):
            top3 = list(meta["feature_importance"].keys())[:3]
            for feat_name in top3:
                if feat_name in X.columns:
                    top_features[feat_name] = round(float(X[feat_name].iloc[0]), 4)

        return {
            "reversal_prob":   round(prob, 4),
            "reversal_pct":    round(prob * 100, 1),
            "signal":          signal,
            "confidence":      confidence,
            "top_features":    top_features,
            "model_available": True,
        }

    except Exception as e:
        logger.error(f"Prediction-Fehler: {e}")
        return {
            "reversal_prob":   None,
            "reversal_pct":    None,
            "signal":          "none",
            "confidence":      "low",
            "top_features":    {},
            "model_available": True,
        }

"""
ml-service/app/model/predictor.py

Lädt das trainierte Modell und berechnet für einen einzelnen Ticker
die Umkehrwahrscheinlichkeit auf Basis der letzten N Kerzen.
"""
import logging

import numpy as np
import pandas as pd
import xgboost as xgb

from app.config import settings
from app.features.engineer import FEATURE_NAMES, compute_features
from app.features.labels import format_value, label_of
from app.model.trainer import load_calibrator, load_meta, load_model

logger = logging.getLogger(__name__)

# Modell einmalig beim Import laden (wird durch retrain() neu geladen)
_model, _scaler, _calibrator = None, None, None


def _ensure_loaded():
    global _model, _scaler, _calibrator
    if _model is None:
        _model, _scaler = load_model()
        _calibrator = load_calibrator()


def reload():
    """Nach einem Retraining aufrufen um das neue Modell zu laden."""
    global _model, _scaler, _calibrator
    _model, _scaler = load_model()
    _calibrator = load_calibrator()
    logger.info("Modell neu geladen")


def _calibrate(p: float) -> float:
    """
    Bildet den rohen Modellwert auf die kalibrierte Wahrscheinlichkeit ab
    (siehe trainer.train, Schritt 5). Ohne Kalibrator (Modelle vor dem 21.09.)
    bleibt der Wert unverändert - /model/info meldet das über "calibrated".
    """
    if _calibrator is None:
        return p
    return float(_calibrator.predict([p])[0])


# Schwellen für die Signal-Einstufung (Modellwert 0-1)
THRESHOLD_STRONG   = 0.75
THRESHOLD_MODERATE = 0.55
THRESHOLD_WEAK     = 0.40

# Anzahl der Merkmale, die einzeln in der Erklärung erscheinen (Rest wird zusammengefasst)
EXPLANATION_TOP_N = 8


def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + np.exp(-x))


def _explain(X: pd.DataFrame, X_scaled: np.ndarray, prob: float, interval: str) -> dict | None:
    """
    Erklärt, wie der Modellwert zustande kommt (Beitrag jedes Merkmals).

    XGBoost liefert pro Vorhersage einen Beitrag je Merkmal im Log-Odds-Raum
    (pred_contribs, SHAP-Werte der Bäume), plus einen Basiswert (Bias) - die
    Summe ergibt das Roh-Ergebnis vor der Umrechnung in Prozent. Log-Odds sind
    für Menschen schwer lesbar, deshalb werden die Beiträge proportional in
    Prozentpunkte umgerechnet: Basiswert + alle Beiträge = Modellwert, und
    jeder Beitrag ist positiv (schiebt Richtung "Anstieg") oder negativ.

    Basiswert = Ausgabe des Modells für einen "durchschnittlichen" Fall. Wie der
    Modellwert selbst ist er kein kalibrierter Prozentsatz (siehe /model/info).
    """
    try:
        booster = _model.get_booster()

        # Wie predict_proba nur die Bäume bis zur besten Iteration verwenden: Mit
        # early_stopping_rounds enthält der Booster weitere Bäume dahinter, die
        # predict_proba ignoriert - ohne diese Einschränkung passen Erklärung und
        # angezeigter Modellwert nicht zusammen.
        try:
            iteration_range = (0, int(_model.best_iteration) + 1)
        except AttributeError:
            iteration_range = (0, 0)  # 0 = alle Bäume

        contribs = booster.predict(
            xgb.DMatrix(X_scaled), pred_contribs=True, iteration_range=iteration_range
        )[0]
        # letzte Spalte = Bias, davor ein Beitrag je Feature in FEATURE_NAMES-Reihenfolge
        bias          = float(contribs[-1])
        feature_parts = contribs[:-1].astype(float)

        margin = bias + float(feature_parts.sum())

        # Basiswert und Modellwert getrennt kalibrieren (siehe _calibrate) - so
        # bleibt "Basiswert + Beiträge = Modellwert" auch nach der Kalibrierung
        # exakt erhalten, und der angezeigte Wert stimmt mit predict() überein.
        base_prob   = _calibrate(_sigmoid(bias))
        full_prob   = _calibrate(_sigmoid(margin))
        total_shift = float(feature_parts.sum())
        prob_shift  = full_prob - base_prob   # Gesamtverschiebung in (kalibrierter) Wahrscheinlichkeit

        # Proportionale Umrechnung Log-Odds → Prozentpunkte (Summe bleibt exakt erhalten)
        if abs(total_shift) < 1e-9:
            effects_pp = np.zeros_like(feature_parts)
        else:
            effects_pp = feature_parts / total_shift * prob_shift * 100.0

        order = np.argsort(-np.abs(effects_pp))
        top   = order[:EXPLANATION_TOP_N]
        rest  = order[EXPLANATION_TOP_N:]

        factors = []
        for idx in top:
            name  = FEATURE_NAMES[idx]
            value = float(X[name].iloc[0])
            factors.append({
                "feature":    name,
                "label":      label_of(name),
                "value":      round(value, 6),
                "value_text": format_value(name, value),
                "effect_pp":  round(float(effects_pp[idx]), 2),
            })

        # Einordnung: typischer Modellwert und tatsächliche Trefferquote im Training
        # für diesen Zeitrahmen (Modelle vor dem 21.09. haben diese Diagnose noch nicht)
        meta    = load_meta() or {}
        context = (meta.get("breakdown") or {}).get(interval) or {}

        return {
            "interval":          interval,
            "base_pct":          round(base_prob * 100, 1),
            "prob_pct":          round(full_prob * 100, 1),
            "factors":           factors,
            "other_pp":          round(float(effects_pp[rest].sum()), 2),
            "other_count":       int(len(rest)),
            "typical_score_pct": context.get("mean_score_pct"),
            "actual_rate_pct":   context.get("positive_rate_pct"),
        }
    except Exception as e:
        logger.error(f"Erklärung fehlgeschlagen: {e}")
        return None


def predict(df: pd.DataFrame, interval: str = "1d") -> dict:
    """
    Berechnet die Umkehrwahrscheinlichkeit für die letzte Kerze im DataFrame.

    df:       OHLCV-DataFrame (chronologisch aufsteigend, Mindestlänge siehe
              settings.min_daily_candles / min_4h_candles / min_1h_candles)
    interval: "1d" | "4h" | "1h" – wird als Feature #39 eingebettet

    Gibt zurück:
    {
      "reversal_prob":   0.73,      # 0–1, Wahrscheinlichkeit einer Aufwärtsumkehr
      "reversal_pct":    73,         # gerundeter %-Wert für die Anzeige
      "signal":          "strong",   # none | weak | moderate | strong
      "confidence":      "high",     # low | medium | high
      "top_features":    {...},      # die 3 global wichtigsten Features mit aktuellem Wert
      "explanation":     {...},      # Erklärung dieser Vorhersage (siehe _explain)
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
            "explanation":     None,
            "model_available": False,
        }

    min_rows = {
        "1d": settings.min_daily_candles,
        "4h": settings.min_4h_candles,
        "1h": settings.min_1h_candles,
    }.get(interval, settings.min_daily_candles)
    if len(df) < min_rows:
        return {
            "reversal_prob":   None,
            "reversal_pct":    None,
            "signal":          "none",
            "confidence":      "low",
            "top_features":    {},
            "explanation":     None,
            "model_available": True,
        }

    try:
        features = compute_features(df, interval=interval)
        if features.empty:
            raise ValueError("Keine Features berechnet")

        # Letzte Zeile = aktuellster Zeitpunkt
        X = features.iloc[[-1]]
        X_scaled = _scaler.transform(X)

        prob_raw    = float(_model.predict_proba(X_scaled)[0, 1])
        explanation = _explain(X, X_scaled, prob_raw, interval)
        # Kalibrierter Wert aus der Erklärung übernehmen, damit beide exakt
        # übereinstimmen (siehe _explain) - nur falls die Erklärung
        # fehlschlägt, hier direkt kalibrieren.
        prob = explanation["prob_pct"] / 100 if explanation else _calibrate(prob_raw)

        # ── Signal-Stärke ──────────────────────────────────────
        if prob >= THRESHOLD_STRONG:
            signal     = "strong"
            confidence = "high"
        elif prob >= THRESHOLD_MODERATE:
            signal     = "moderate"
            confidence = "medium"
        elif prob >= THRESHOLD_WEAK:
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
            "explanation":     explanation,
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
            "explanation":     None,
            "model_available": True,
        }

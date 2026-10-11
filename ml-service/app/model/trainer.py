"""
ml-service/app/model/trainer.py

XGBoost-Training für die Umkehrwahrscheinlichkeit.

Ablauf (21.09. überarbeitet, siehe train()):
  1. OHLCV-Daten aller Ticker aus dem DB-Service laden (jeder Ticker mit
     ausreichend Daten, unabhängig von Liste/ticker_meta - siehe api/db_client.py)
  2. Features und Labels berechnen, PRO TICKER chronologisch in
     train/val/test aufgeteilt (kein zufälliges Shufflen, verhindert
     Data-Leakage; pro Ticker statt einmal über den Gesamtbestand, damit jeder
     Zeitrahmen und jeder Ticker anteilig in allen drei Mengen vorkommt)
  3. XGBoost trainieren, Early Stopping auf der Validierungsmenge
  4. Isotonische Kalibrierung auf der Validierungsmenge (macht den
     Modellwert zu einer echten, wenn auch groben Wahrscheinlichkeit)
  5. Backtesting auf dem unberührten Test-Set (kalibrierte Werte)
  6. Modell + Scaler + Kalibrator + Metadaten speichern
"""
import json
import logging
import os
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import (
    classification_report,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier

from app.config import settings
from app.features.engineer import FEATURE_NAMES, compute_features, compute_labels

logger = logging.getLogger(__name__)

INTERVALS = ("1d", "4h", "1h")


def _paths(interval: str) -> dict[str, Path]:
    """
    Dateipfade für EIN Modell (29.09.: ein eigenständiges Modell je
    Zeitrahmen statt eines gemeinsamen Modells mit interval_code-Feature -
    1h/4h haben inzwischen genug Historie dafür, siehe CLAUDE.md 3.8).
    """
    d = Path(settings.model_dir)
    return {
        "model":      d / f"xgb_reversal_{interval}.joblib",
        "scaler":     d / f"scaler_{interval}.joblib",
        "calibrator": d / f"calibrator_{interval}.joblib",
        "meta":       d / f"model_meta_{interval}.json",
    }


def any_model_ready() -> bool:
    """True, sobald mindestens eines der drei Modelle existiert."""
    return any(_paths(iv)["meta"].exists() for iv in INTERVALS)


def all_models_ready() -> bool:
    return all(_paths(iv)["meta"].exists() for iv in INTERVALS)


def _split_chronological(
    feat: pd.DataFrame, labels: pd.Series
) -> tuple[tuple[pd.DataFrame, pd.Series], tuple[pd.DataFrame, pd.Series], tuple[pd.DataFrame, pd.Series]]:
    """
    Teilt EINE (bereits chronologisch aufsteigend sortierte) Ticker/Interval-
    Zeitreihe in train/val/test - älterer Teil zum Trainieren, jüngster Teil
    zum Testen, dazwischen eine Validierungsmenge fürs Early Stopping und die
    Kalibrierung. Reine Positions-Slices, kein Shufflen.
    """
    n = len(feat)
    train_end = int(n * settings.train_frac)
    val_end   = train_end + int(n * settings.val_frac)

    return (
        (feat.iloc[:train_end],       labels.iloc[:train_end]),
        (feat.iloc[train_end:val_end], labels.iloc[train_end:val_end]),
        (feat.iloc[val_end:],          labels.iloc[val_end:]),
    )


def _collect_features_for_interval(
    ohlcv_by_ticker: dict[str, pd.DataFrame],
    interval: str,
    min_rows: int = 100,
) -> tuple[list, list, list]:
    """
    Berechnet Features und Labels für alle Ticker eines Intervals und teilt
    JEDEN Ticker für sich chronologisch in train/val/test (siehe
    _split_chronological).

    Wichtig gegenüber der Vorversion: dort wurden alle Ticker/Intervalle in
    Verarbeitungsreihenfolge aneinandergehängt und die letzten 20% DIESER
    LISTE als Testmenge genommen - je nach Reihenfolge im Dict bestand die
    Testmenge dadurch nur aus einem einzelnen Zeitrahmen oder einer einzelnen
    Ticker-Gruppe, nicht aus "den neuesten Daten". Der Split hier passiert pro
    Ticker VOR dem Zusammenführen, sodass train/val/test aus jedem Ticker und
    jedem Zeitrahmen jeweils den eigenen ältesten/mittleren/jüngsten Teil
    enthalten.

    Gibt (train_parts, val_parts, test_parts) zurück - je eine Liste aus
    (features, labels)-Paaren pro Ticker.
    """
    train_parts, val_parts, test_parts = [], [], []

    for ticker, df in ohlcv_by_ticker.items():
        if len(df) < min_rows:
            logger.warning(
                f"[{ticker}/{interval}] Zu wenig Daten ({len(df)} Zeilen) – übersprungen"
            )
            continue
        try:
            feat   = compute_features(df, interval=interval)
            labels = compute_labels(
                df, settings.forecast_horizon[interval], settings.reversal_threshold_pct[interval],
                downtrend_lookback=settings.downtrend_lookback,
                downtrend_threshold_pct=settings.downtrend_threshold_pct,
                low_break_tolerance_pct=settings.low_break_tolerance_pct,
            )

            labels = labels.dropna()
            common = feat.index.intersection(labels.index).sort_values()
            feat   = feat.loc[common]
            labels = labels.loc[common]

            if len(feat) < settings.min_samples_per_class * 2:
                logger.warning(
                    f"[{ticker}/{interval}] Zu wenig Samples – übersprungen"
                )
                continue

            (tr_f, tr_l), (va_f, va_l), (te_f, te_l) = _split_chronological(feat, labels)
            train_parts.append((tr_f, tr_l))
            if len(va_f) > 0:
                val_parts.append((va_f, va_l))
            if len(te_f) > 0:
                test_parts.append((te_f, te_l))

            logger.info(
                f"[{ticker}/{interval}] {len(feat)} Samples "
                f"(train={len(tr_f)}, val={len(va_f)}, test={len(te_f)}), "
                f"{int(labels.sum())} Umkehrsignale ({labels.mean()*100:.1f}%)"
            )
        except Exception as e:
            logger.error(f"[{ticker}/{interval}] Feature-Fehler: {e}")

    return train_parts, val_parts, test_parts


# Anzahl Klassen-Bins der Kalibrierungstabelle und Mindestanzahl Samples je Bin
CALIBRATION_BINS = 10
CALIBRATION_MIN_SAMPLES = 20


def _diagnostics(
    X_train: pd.DataFrame, y_train: pd.Series,
    X_test: pd.DataFrame, y_test: pd.Series,
    y_prob: np.ndarray,
    y_prob_train: np.ndarray,
) -> dict:
    """
    Diagnose-Daten für die Modell-Info im Client (21.09., 29.09. vereinfacht:
    kein Breakdown mehr nach interval_code nötig, da jedes Modell jetzt schon
    genau einen Zeitrahmen abdeckt - die Werte hier sind direkt die des ganzen
    Modells).

    - summary:     Anzahl Samples in Train/Test, Anteil positiver Labels,
                   typischer Modellwert (Mittel über Train+Test).
    - calibration: je Wahrscheinlichkeits-Bin der mittlere Modellwert und die
                   tatsächlich beobachtete Trefferquote in der Testmenge.
    """
    all_scores = np.concatenate([y_prob_train, y_prob]) if len(y_prob_train) or len(y_prob) else np.array([])
    summary = {
        "train_samples":     int(len(X_train)),
        "test_samples":      int(len(X_test)),
        "positive_rate_pct": round(float(y_train.mean() * 100), 2) if len(y_train) else None,
        "mean_score_pct":    round(float(all_scores.mean() * 100), 1) if len(all_scores) else None,
    }

    edges = np.linspace(0.0, 1.0, CALIBRATION_BINS + 1)
    calibration = []
    y_arr = y_test.to_numpy()
    for lo, hi in zip(edges[:-1], edges[1:]):
        in_bin = (y_prob >= lo) & ((y_prob < hi) if hi < 1.0 else (y_prob <= hi))
        if in_bin.sum() < CALIBRATION_MIN_SAMPLES:
            continue
        calibration.append({
            "bin_from":      round(float(lo) * 100),
            "bin_to":        round(float(hi) * 100),
            "samples":       int(in_bin.sum()),
            "predicted_pct": round(float(y_prob[in_bin].mean() * 100), 1),
            "actual_pct":    round(float(y_arr[in_bin].mean() * 100), 1),
        })

    return {"summary": summary, "calibration": calibration}


def _train_single_interval(interval: str, tickers_df: dict[str, pd.DataFrame]) -> dict:
    """
    Trainiert EIN eigenständiges Modell für genau einen Zeitrahmen
    (29.09., ersetzt das gemeinsame Multi-Interval-Modell mit
    interval_code-Feature - siehe Modulkopf/CLAUDE.md 3.8).
    """
    logger.info(f"── Interval {interval}: {len(tickers_df)} Ticker ──")
    paths = _paths(interval)

    # ── 1. Features/Labels berechnen und PRO TICKER chronologisch splitten ──
    train_parts, val_parts, test_parts = _collect_features_for_interval(tickers_df, interval)

    if not train_parts:
        raise ValueError(f"[{interval}] Kein einziger Ticker lieferte ausreichend Daten für das Training")

    def concat(parts):
        feats  = pd.concat([f for f, _ in parts], ignore_index=True)
        labels = pd.concat([l for _, l in parts], ignore_index=True).astype(int)
        return feats, labels

    X_train, y_train = concat(train_parts)
    X_val,   y_val   = concat(val_parts) if val_parts else (X_train.iloc[:0], y_train.iloc[:0])
    X_test,  y_test  = concat(test_parts) if test_parts else (X_train.iloc[:0], y_train.iloc[:0])

    total_samples = len(X_train) + len(X_val) + len(X_test)
    logger.info(
        f"[{interval}] Gesamt: {total_samples} Samples "
        f"(train={len(X_train)}, val={len(X_val)}, test={len(X_test)})"
    )

    if y_train.sum() < settings.min_samples_per_class:
        raise ValueError(
            f"[{interval}] Zu wenig positive Trainingsbeispiele: {y_train.sum()} "
            f"(Minimum: {settings.min_samples_per_class})"
        )
    if y_val.sum() < 5 or y_test.sum() < 5:
        logger.warning(
            f"[{interval}] Sehr wenige positive Beispiele in Val ({int(y_val.sum())}) oder "
            f"Test ({int(y_test.sum())}) - Metriken und Kalibrierung sind entsprechend unsicher."
        )

    # ── 2. Feature-Skalierung (nur auf Train angepasst) ───────
    scaler    = StandardScaler()
    X_train_s = scaler.fit_transform(X_train)
    X_val_s   = scaler.transform(X_val) if len(X_val) else X_train_s[:0]
    X_test_s  = scaler.transform(X_test) if len(X_test) else X_train_s[:0]

    # ── 3. Klassengewichte berechnen ──────────────────────────
    pos_weight = (y_train == 0).sum() / max((y_train == 1).sum(), 1)
    logger.info(f"[{interval}] Klassen-Gewicht (pos): {pos_weight:.2f}")

    # ── 4. XGBoost trainieren ──────────────────────────────────
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
        n_jobs=-1,
        tree_method="hist",
    )

    eval_set = [(X_val_s, y_val)] if len(X_val) else [(X_train_s, y_train)]
    model.fit(X_train_s, y_train, eval_set=eval_set, verbose=False)

    # ── 5. Kalibrierung (auf der Validierungsmenge, NICHT auf Test) ────
    raw_val_prob = model.predict_proba(X_val_s)[:, 1] if len(X_val) else np.array([])
    if len(raw_val_prob) >= 20 and y_val.nunique() == 2:
        calibrator = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
        calibrator.fit(raw_val_prob, y_val.to_numpy())
    else:
        logger.warning(f"[{interval}] Zu wenig Validierungsdaten für eine Kalibrierung - Modellwert bleibt unkalibriert.")
        calibrator = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
        calibrator.fit([0.0, 1.0], [0.0, 1.0])

    def calibrate(p: np.ndarray) -> np.ndarray:
        return calibrator.predict(p)

    # ── 6. Backtesting auf dem unberührten Test-Set ───────────
    y_prob_raw   = model.predict_proba(X_test_s)[:, 1] if len(X_test) else np.array([])
    y_prob       = calibrate(y_prob_raw)
    y_prob_train = calibrate(model.predict_proba(X_train_s)[:, 1])
    y_pred       = (y_prob >= 0.5).astype(int)

    precision = precision_score(y_test, y_pred, zero_division=0) if len(y_test) else 0.0
    recall    = recall_score(y_test, y_pred, zero_division=0) if len(y_test) else 0.0
    auc       = roc_auc_score(y_test, y_prob) if y_test.sum() > 0 else 0.0

    logger.info(f"── [{interval}] Backtesting-Ergebnis (Testmenge, kalibriert) ──")
    logger.info(f"  Precision:  {precision:.3f}")
    logger.info(f"  Recall:     {recall:.3f}")
    logger.info(f"  ROC-AUC:    {auc:.3f}")
    if len(y_test):
        logger.info(classification_report(y_test, y_pred,
                                           target_names=["kein Signal", "Umkehr"],
                                           zero_division=0))

    # ── 7. Feature Importance (Top 10) ────────────────────────
    importances = dict(zip(FEATURE_NAMES, model.feature_importances_.tolist()))
    top10 = sorted(importances.items(), key=lambda x: x[1], reverse=True)[:10]
    logger.info(f"── [{interval}] Top-10 Features ───────────────────────────")
    for name, imp in top10:
        logger.info(f"  {name:<25} {imp:.4f}")

    # ── 8. Modell speichern ───────────────────────────────────
    Path(settings.model_dir).mkdir(parents=True, exist_ok=True)
    joblib.dump(model,      paths["model"])
    joblib.dump(scaler,     paths["scaler"])
    joblib.dump(calibrator, paths["calibrator"])

    meta = {
        "interval":           interval,
        "trained_at":         datetime.now().isoformat(),
        "tickers":            list(tickers_df.keys()),
        "total_samples":      total_samples,
        "train_samples":      len(X_train),
        "val_samples":        len(X_val),
        "test_samples":       len(X_test),
        "positive_rate_pct":  round(float(pd.concat([y_train, y_val, y_test]).mean() * 100), 2) if total_samples else 0.0,
        "forecast_horizon":   settings.forecast_horizon[interval],
        "reversal_threshold": settings.reversal_threshold_pct[interval],
        "downtrend_lookback":      settings.downtrend_lookback,
        "downtrend_threshold_pct": settings.downtrend_threshold_pct,
        "low_break_tolerance_pct": settings.low_break_tolerance_pct,
        "calibrated":         True,
        "backtesting": {
            "precision": round(precision, 4),
            "recall":    round(recall, 4),
            "roc_auc":   round(auc, 4),
        },
        "feature_importance": {k: round(v, 4) for k, v in top10},
        "feature_importance_all": {k: round(v, 4) for k, v in sorted(importances.items(), key=lambda x: x[1], reverse=True)},
        "n_estimators_used":  model.best_iteration + 1,
        "scale_pos_weight":   round(float(pos_weight), 3),
        **_diagnostics(X_train, y_train, X_test, y_test, y_prob, y_prob_train),
    }
    with open(paths["meta"], "w") as f:
        json.dump(meta, f, indent=2)

    logger.info(f"[{interval}] Modell gespeichert → {paths['model']}")
    return meta


def train(ohlcv_by_interval: dict[str, dict[str, pd.DataFrame]]) -> dict:
    """
    Trainiert DREI eigenständige Modelle, eines je Zeitrahmen (29.09.,
    ersetzt das gemeinsame Multi-Interval-Modell - siehe CLAUDE.md 3.8:
    1h/4h haben inzwischen genug Historie für je ein eigenes Modell, statt
    sich das über ein einzelnes interval_code-Feature "erschließen" zu
    müssen).

    ohlcv_by_interval: {
        "1d": { "ADS.DE": DataFrame, ... },
        "4h": { "ADS.DE": DataFrame, ... },
        "1h": { "ADS.DE": DataFrame, ... },
    }

    Gibt {"1d": {...meta...}, "4h": {...}, "1h": {...}} zurück - fehlgeschlagene
    Zeitrahmen (z.B. zu wenig Daten) tauchen mit {"status": "error", ...} auf,
    ohne die anderen beiden Trainings zu verhindern.
    """
    logger.info("═" * 60)
    logger.info("TRAINING gestartet (3 eigenständige Modelle: 1d / 4h / 1h)")
    for iv in INTERVALS:
        logger.info(
            f"  [{iv}] Forecast-Horizont: {settings.forecast_horizon[iv]} Kerzen, "
            f"Umkehr-Schwelle: {settings.reversal_threshold_pct[iv]}%"
        )
    logger.info(
        f"  Downtrend-Vorbedingung (für alle 3 gleich): >= {settings.downtrend_threshold_pct}% unter "
        f"Hoch der letzten {settings.downtrend_lookback} Kerzen "
        f"(Tief-Toleranz {settings.low_break_tolerance_pct}%)"
    )
    logger.info("═" * 60)

    results: dict[str, dict] = {}
    any_ok = False
    for interval in INTERVALS:
        tickers_df = ohlcv_by_interval.get(interval) or {}
        if not tickers_df:
            logger.warning(f"Keine Daten für Interval {interval} – übersprungen")
            results[interval] = {"status": "error", "message": "Keine OHLCV-Daten verfügbar"}
            continue
        try:
            results[interval] = _train_single_interval(interval, tickers_df)
            any_ok = True
        except Exception as e:
            logger.error(f"[{interval}] Training fehlgeschlagen: {e}")
            results[interval] = {"status": "error", "message": str(e)}

    if not any_ok:
        raise ValueError("Kein einziges der drei Modelle konnte trainiert werden")

    logger.info("═" * 60)
    return results


def load_model(interval: str):
    """Lädt Modell + Scaler für EIN Interval. Gibt (model, scaler) zurück oder (None, None)."""
    paths = _paths(interval)
    if not paths["model"].exists() or not paths["scaler"].exists():
        return None, None
    try:
        return joblib.load(paths["model"]), joblib.load(paths["scaler"])
    except Exception as e:
        logger.error(f"[{interval}] Modell laden fehlgeschlagen: {e}")
        return None, None


def load_calibrator(interval: str):
    """
    Lädt den Kalibrator für EIN Interval (21.09.). None, wenn keiner existiert.
    """
    paths = _paths(interval)
    if not paths["calibrator"].exists():
        return None
    try:
        return joblib.load(paths["calibrator"])
    except Exception as e:
        logger.error(f"[{interval}] Kalibrator laden fehlgeschlagen: {e}")
        return None


def load_meta(interval: str) -> dict | None:
    """Lädt die gespeicherten Trainings-Metadaten für EIN Interval."""
    path = _paths(interval)["meta"]
    if not path.exists():
        return None
    with open(path) as f:
        return json.load(f)


def load_all_meta() -> dict[str, dict | None]:
    """Metadaten aller drei Modelle, {"1d": {...} | None, "4h": ..., "1h": ...}."""
    return {iv: load_meta(iv) for iv in INTERVALS}

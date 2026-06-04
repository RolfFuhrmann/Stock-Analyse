"""
bearish_reversal_indicator.py – Bearische Trendumkehr-Indikatoren
Gekapselter Bereich für aufsteigende Elliott Wave 1-2-3, MACD-Histogramm über 0
und Slow Stochastik über 80 (überkauft).

Jede Funktion ist unabhängig testbar und gibt ein strukturiertes dict zurück.
evaluate_bearish_stock() fasst alle Indikatoren zur Gesamtauswertung zusammen.
"""

import numpy as np
import pandas as pd

from bearish_candle_pattern_reversal import detect_bearish_candle_pattern


# ─────────────────────────────────────────────
# MACD (bearish)
# ─────────────────────────────────────────────

def calc_macd_bearish(
    closes: pd.Series,
    fast: int = 12,
    slow: int = 26,
    signal: int = 9,
) -> dict | None:
    """
    Berechnet MACD-Linie, Signal-Linie und Histogramm.

    Signal gilt als erfüllt wenn:
      - Histogramm positiv (MACD über Nulllinie)

    Parameter:
        closes: Schlusskurse als pd.Series
        fast:   Perioden EMA schnell (Standard 12)
        slow:   Perioden EMA langsam (Standard 26)
        signal: Perioden Signal-EMA (Standard 9)

    Rückgabe:
        dict mit Kennzahlen oder None wenn zu wenig Daten.
    """
    if len(closes) < slow + signal:
        return None

    ema_fast    = closes.ewm(span=fast, adjust=False).mean()
    ema_slow    = closes.ewm(span=slow, adjust=False).mean()
    macd_line   = ema_fast - ema_slow
    signal_line = macd_line.ewm(span=signal, adjust=False).mean()
    histogram   = macd_line - signal_line

    recent = histogram.iloc[-5:].values if len(histogram) >= 5 else histogram.values

    is_positive = bool(histogram.iloc[-1] > 0)

    # Aufeinanderfolgende wachsende Balken von hinten zählen
    grow_count = 0
    for i in range(len(recent) - 1, 0, -1):
        if abs(recent[i]) > abs(recent[i - 1]):
            grow_count += 1
        else:
            break

    return {
        "macd":               float(macd_line.iloc[-1]),
        "signal":             float(signal_line.iloc[-1]),
        "histogram":          float(histogram.iloc[-1]),
        "histogram_prev":     float(histogram.iloc[-2]) if len(histogram) > 1 else None,
        "is_positive":        is_positive,
        "histogram_growing":  grow_count >= 2,
        "grow_count":         grow_count,
    }


# ─────────────────────────────────────────────
# SLOW STOCHASTIK (bearish)
# ─────────────────────────────────────────────

def calc_slow_stochastic_bearish(
    df: pd.DataFrame,
    k_period: int = 14,
    d_period: int = 3,
) -> dict | None:
    """
    Slow Stochastik:
      Raw %K   = (Close - Low_n) / (High_n - Low_n) * 100
      Slow %K  = d_period-SMA von Raw %K
      Slow %D  = d_period-SMA von Slow %K

    Signal gilt als erfüllt wenn:
      - %K über 80 (überkauft)

    Parameter:
        df:       DataFrame mit Spalten high, low, close
        k_period: Perioden für %K (Standard 14)
        d_period: Glättungsperioden (Standard 3)

    Rückgabe:
        dict mit Kennzahlen oder None wenn zu wenig Daten.
    """
    if len(df) < k_period + d_period * 2:
        return None

    low_min  = df["low"].rolling(k_period).min()
    high_max = df["high"].rolling(k_period).max()

    denom  = (high_max - low_min).replace(0, np.nan)
    raw_k  = (df["close"] - low_min) / denom * 100
    slow_k = raw_k.rolling(d_period).mean()
    slow_d = slow_k.rolling(d_period).mean()

    k_val  = slow_k.iloc[-1]
    d_val  = slow_d.iloc[-1]
    k_prev = slow_k.iloc[-2] if len(slow_k) >= 2 else k_val

    if pd.isna(k_val) or pd.isna(d_val):
        return None

    return {
        "k":             round(float(k_val), 2),
        "d":             round(float(d_val), 2),
        "is_overbought": bool(k_val > 80),   # Signal: %K über 80
        "k_falling":     bool(k_val < k_prev),
    }


# ─────────────────────────────────────────────
# ELLIOTT WAVE 1-2-3 (AUFWÄRTS-IMPULS)
# ─────────────────────────────────────────────

def detect_elliott_123_up(closes: pd.Series, lookback: int = 90) -> dict:
    """
    Erkennt einen aufwärtsgerichteten Elliott-Impuls 1-2-3 auf Tagesbasis.

    Struktur:
      Trough → Tiefpunkt in den ersten 60% des Zeitraums
      Welle 1: Impulsiver Anstieg vom Trough (mind. 4%)
      Welle 2: Korrektur abwärts (Fibonacci 20–85%), 2-Tief über Trough
      Welle 3: Weiterer Anstieg nach 2 (mind. 2%), stärker als Welle 1

    Parameter:
        closes:   Schlusskurse als pd.Series
        lookback: Anzahl Handelstage rückwärts (Standard 90)

    Rückgabe:
        dict mit ok (bool), detail (str) und Detailwerten.
    """
    data = closes.iloc[-lookback:].reset_index(drop=True)
    n    = len(data)

    if n < 20:
        return {"ok": False, "detail": "Zu wenig Datenpunkte"}

    # Trough suchen (erste 60%)
    trough_window = int(n * 0.60)
    trough_idx    = int(data.iloc[:trough_window].idxmin())
    trough_val    = data.iloc[trough_idx]

    if trough_idx > n - 8:
        return {"ok": False, "detail": "Kein gültiger Tiefpunkt"}

    # Welle 1: Anstieg vom Trough
    w1_slice = data.iloc[trough_idx + 1: int(n * 0.85)]
    if len(w1_slice) < 3:
        return {"ok": False, "detail": "Welle 1: Zu kurz"}

    w1_high_idx  = int(w1_slice.idxmax())
    w1_high_val  = data.iloc[w1_high_idx]
    wave_1_pct   = (w1_high_val - trough_val) / trough_val * 100

    if wave_1_pct < 4.0:
        return {"ok": False, "detail": f"Welle 1 zu klein ({wave_1_pct:.1f}%)"}

    # Welle 2: Korrektur (20–85% Fibonacci), 2-Tief über Trough
    w2_slice = data.iloc[w1_high_idx + 1:]
    if len(w2_slice) < 3:
        return {"ok": False, "detail": "Welle 2: Zu kurz"}

    w2_low_idx    = int(w2_slice.idxmin())
    w2_low_val    = data.iloc[w2_low_idx]
    w12_range     = w1_high_val - trough_val
    w2_retracement = (w1_high_val - w2_low_val) / w12_range if w12_range != 0 else 0

    if w2_low_val <= trough_val:
        return {"ok": False, "detail": "Welle 2 unterschreitet Trough"}

    if not (0.20 <= w2_retracement <= 0.85):
        return {"ok": False, "detail": f"Welle 2 außerhalb Fibonacci ({w2_retracement * 100:.0f}%)"}

    # Welle 3: Weiterer Anstieg nach Welle 2
    w3_slice = data.iloc[w2_low_idx + 1:]
    if len(w3_slice) < 3:
        return {"ok": False, "detail": "Welle 3: Zu kurz"}

    w3_high_val  = w3_slice.max()
    w3_rise_pct  = (w3_high_val - w2_low_val) / w2_low_val * 100

    if w3_rise_pct < 2.0:
        return {"ok": False, "detail": f"Welle 3 zu flach ({w3_rise_pct:.1f}%)"}

    w3_above_w1  = bool(w3_high_val >= w1_high_val)
    current_price = data.iloc[-1]
    dist_from_w3_high = (current_price - w3_high_val) / w3_high_val * 100

    return {
        "ok": True,
        "detail": (
            f"1:{wave_1_pct:.1f}% "
            f"2:{w2_retracement * 100:.0f}%Fib "
            f"3:{w3_rise_pct:.1f}%"
            + (" 3>1✓" if w3_above_w1 else "")
        ),
        "wave_1_pct":            round(wave_1_pct, 2),
        "w2_retracement_pct":    round(w2_retracement * 100, 1),
        "wave_3_pct":            round(w3_rise_pct, 2),
        "w3_high":               round(float(w3_high_val), 2),
        "w3_above_w1":           w3_above_w1,
        "dist_from_w3_high_pct": round(dist_from_w3_high, 2),
        "trough_val":            round(float(trough_val), 2),
    }


# ─────────────────────────────────────────────
# GESAMT-AUSWERTUNG (BEARISH)
# ─────────────────────────────────────────────

def evaluate_bearish_stock(df: pd.DataFrame, lookback: int = 90) -> dict:
    """
    Führt alle bearischen Indikatoren aus und gibt ein kombiniertes Ergebnis zurück.

    Parameter:
        df:       DataFrame mit Spalten open, high, low, close, volume (lowercase)
        lookback: Anzahl Handelstage für Elliott-Wave-Analyse (Standard 90)

    Rückgabe:
        dict mit Einzelergebnissen, Flags und Gesamtscore (criteria_met 0–3).
    """
    closes = df["close"]

    elliott = detect_elliott_123_up(closes, lookback)
    macd    = calc_macd_bearish(closes)
    stoch   = calc_slow_stochastic_bearish(df, k_period=14, d_period=3)
    candle  = detect_bearish_candle_pattern(df)

    macd_ok    = bool(macd["is_positive"]) if macd else False   # Signal: MACD > 0
    stoch_ok   = bool(stoch["is_overbought"]) if stoch else False  # Signal: %K > 80
    elliott_ok = elliott["ok"]

    criteria_met = sum([elliott_ok, macd_ok, stoch_ok])

    trend_pct = (
        (closes.iloc[-1] - closes.iloc[-min(lookback, len(closes))]) /
        closes.iloc[-min(lookback, len(closes))] * 100
    )

    return {
        "elliott":      elliott,
        "macd":         macd,
        "stoch":        stoch,
        "candle":       candle,
        "elliott_ok":   elliott_ok,
        "macd_ok":      macd_ok,
        "stoch_ok":     stoch_ok,
        "criteria_met": criteria_met,
        "current_price": round(float(closes.iloc[-1]), 2),
        "trend_pct":    round(float(trend_pct), 2),
    }

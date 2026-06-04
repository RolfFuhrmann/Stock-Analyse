"""
bullish_reversal_indicator.py – Bullische Trendumkehr-Indikatoren
Gekapselter Bereich für Elliott Wave A-B-C (abwärts), MACD-Histogramm unter 0 und Slow Stochastik unter 20.

Jede Funktion ist unabhängig testbar und gibt ein strukturiertes dict zurück.
evaluate_stock() fasst alle Indikatoren zur Gesamtauswertung zusammen.
"""

import numpy as np
import pandas as pd

from bullish_candle_pattern_reversal import detect_candle_pattern


# ─────────────────────────────────────────────
# MACD
# ─────────────────────────────────────────────

def calc_macd(
    closes: pd.Series,
    fast: int = 12,
    slow: int = 26,
    signal: int = 9,
) -> dict | None:
    """
    Berechnet MACD-Linie, Signal-Linie und Histogramm.

    Signal gilt als erfüllt wenn:
      - Histogramm negativ (MACD unter Nulllinie)
      histogram_shrinking wird weiterhin berechnet und steht für
      weiterführende Auswertungen zur Verfügung, ist aber kein Pflichtkriterium.

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

    is_negative = bool(histogram.iloc[-1] < 0)

    # Aufeinanderfolgende schrumpfende Balken von hinten zählen
    shrink_count = 0
    for i in range(len(recent) - 1, 0, -1):
        if abs(recent[i]) < abs(recent[i - 1]):
            shrink_count += 1
        else:
            break

    return {
        "macd":                float(macd_line.iloc[-1]),
        "signal":              float(signal_line.iloc[-1]),
        "histogram":           float(histogram.iloc[-1]),
        "histogram_prev":      float(histogram.iloc[-2]) if len(histogram) > 1 else None,
        "is_negative":         is_negative,
        "histogram_shrinking": shrink_count >= 2,
        "shrink_count":        shrink_count,
    }


# ─────────────────────────────────────────────
# SLOW STOCHASTIK
# ─────────────────────────────────────────────

def calc_slow_stochastic(
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
      - %K unter 20 (überverkauft)
      k_rising wird weiterhin berechnet, ist aber kein Pflichtkriterium.

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
        "k":           round(float(k_val), 2),
        "d":           round(float(d_val), 2),
        "is_oversold": bool(k_val < 20),  # Signal: %K unter 20
        "k_rising":    bool(k_val > k_prev),
    }


# ─────────────────────────────────────────────
# ELLIOTT WAVE A-B-C (ABWÄRTS-KORREKTUR)
# ─────────────────────────────────────────────

def detect_elliott_abc(closes: pd.Series, lookback: int = 90) -> dict:
    """
    Erkennt eine abwärtsgerichtete Elliott-Korrektur A-B-C auf Tagesbasis.

    Struktur:
      Peak  → Hochpunkt in den ersten 60% des Zeitraums
      Welle A: Impulsiver Abfall vom Peak (mind. 4%)
      Welle B: Gegenbewegung aufwärts (Fibonacci 20–85%), B-Hoch unter Peak
      Welle C: Weiterer Abfall nach B (mind. 2%)
      C-Tief unter A-Tief = stärkeres Signal

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

    # Peak suchen (erste 60%)
    peak_window = int(n * 0.60)
    peak_idx    = int(data.iloc[:peak_window].idxmax())
    peak_val    = data.iloc[peak_idx]

    if peak_idx > n - 8:
        return {"ok": False, "detail": "Kein gültiger Hochpunkt"}

    # Welle A: Abfall vom Peak
    a_slice = data.iloc[peak_idx + 1: int(n * 0.85)]
    if len(a_slice) < 3:
        return {"ok": False, "detail": "Welle A: Zu kurz"}

    a_low_idx  = int(a_slice.idxmin())
    a_low_val  = data.iloc[a_low_idx]
    wave_a_pct = (a_low_val - peak_val) / peak_val * 100

    if wave_a_pct > -4.0:
        return {"ok": False, "detail": f"Welle A zu klein ({wave_a_pct:.1f}%)"}

    # Welle B: Gegenbewegung (20–85% Fibonacci)
    b_slice = data.iloc[a_low_idx + 1:]
    if len(b_slice) < 3:
        return {"ok": False, "detail": "Welle B: Zu kurz"}

    b_high_idx    = int(b_slice.idxmax())
    b_high_val    = data.iloc[b_high_idx]
    ab_range      = peak_val - a_low_val
    b_retracement = (b_high_val - a_low_val) / ab_range if ab_range != 0 else 0

    if b_high_val >= peak_val:
        return {"ok": False, "detail": "Welle B überschreitet Peak"}

    if not (0.20 <= b_retracement <= 0.85):
        return {"ok": False, "detail": f"Welle B außerhalb Fibonacci ({b_retracement * 100:.0f}%)"}

    # Welle C: Weiterer Abfall nach B
    c_slice = data.iloc[b_high_idx + 1:]
    if len(c_slice) < 3:
        return {"ok": False, "detail": "Welle C: Zu kurz"}

    c_low_val  = c_slice.min()
    c_drop_pct = (c_low_val - b_high_val) / b_high_val * 100

    if c_drop_pct > -2.0:
        return {"ok": False, "detail": f"Welle C zu flach ({c_drop_pct:.1f}%)"}

    c_below_a       = bool(c_low_val <= a_low_val)
    current_price   = data.iloc[-1]
    dist_from_c_low = (current_price - c_low_val) / c_low_val * 100

    return {
        "ok": True,
        "detail": (
            f"A:{wave_a_pct:.1f}% "
            f"B:{b_retracement * 100:.0f}%Fib "
            f"C:{c_drop_pct:.1f}%"
            + (" C<A✓" if c_below_a else "")
        ),
        "wave_a_pct":          round(wave_a_pct, 2),
        "b_retracement_pct":   round(b_retracement * 100, 1),
        "c_drop_pct":          round(c_drop_pct, 2),
        "c_low":               round(float(c_low_val), 2),
        "c_below_a":           c_below_a,
        "dist_from_c_low_pct": round(dist_from_c_low, 2),
        "peak_val":            round(float(peak_val), 2),
    }


# ─────────────────────────────────────────────
# GESAMT-AUSWERTUNG
# ─────────────────────────────────────────────

def evaluate_stock(df: pd.DataFrame, lookback: int = 90) -> dict:
    """
    Führt alle Indikatoren aus und gibt ein kombiniertes Ergebnis zurück.

    Parameter:
        df:       DataFrame mit Spalten open, high, low, close, volume (lowercase)
        lookback: Anzahl Handelstage für Elliott-Wave-Analyse (Standard 90)

    Rückgabe:
        dict mit Einzelergebnissen, Flags und Gesamtscore (criteria_met 0–3).
    """
    closes = df["close"]

    elliott = detect_elliott_abc(closes, lookback)
    macd    = calc_macd(closes)
    stoch   = calc_slow_stochastic(df, k_period=14, d_period=3)
    candle  = detect_candle_pattern(df)

    macd_ok    = bool(macd["is_negative"]) if macd else False  # Signal: MACD-Histogramm unter 0
    stoch_ok   = bool(stoch["is_oversold"]) if stoch else False
    elliott_ok = elliott["ok"]

    criteria_met = sum([elliott_ok, macd_ok, stoch_ok])

    trend_pct = (
        (closes.iloc[-1] - closes.iloc[-min(lookback, len(closes))]) /
        closes.iloc[-min(lookback, len(closes))] * 100
    )

    return {
        "elliott":       elliott,
        "macd":          macd,
        "stoch":         stoch,
        "candle":        candle,
        "elliott_ok":    elliott_ok,
        "macd_ok":       macd_ok,
        "stoch_ok":      stoch_ok,
        "criteria_met":  criteria_met,
        "current_price": round(float(closes.iloc[-1]), 2),
        "trend_pct":     round(float(trend_pct), 2),
    }

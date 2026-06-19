"""
ml-service/app/features/engineer.py

Feature-Engineering für das XGBoost-Modell.
Berechnet technische Indikatoren als numerische Features aus OHLCV-Daten.

Features (38 total):
  Preis-Returns:        1, 3, 5, 10, 20 Tage
  Volatilität:          5, 10, 20 Tage (rolling std der Returns)
  MACD:                 Wert, Signal, Histogramm, Histogramm-Steigung
  Stochastik:           %K, %D, %K-%D
  RSI (14):             Wert, Abstand zu 30/70
  Bollinger Bands:      %B, Bandbreite
  Volumen:              Relatives Volumen (5/20 Tage), Volumen-Trend
  Elliott-Score:        Aus bestehendem regelbasiertem System (0–3)
  Trendstärke:          SMA-20/50/200 Abstand, GD-Kreuzungen
  Kerzen-Eigenschaften: Body-Größe, Docht-Länge, Kerzen-Richtung
"""
import logging

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)

# Namen aller Features – Reihenfolge muss mit dem Training übereinstimmen
FEATURE_NAMES = [
    # Returns
    "ret_1d", "ret_3d", "ret_5d", "ret_10d", "ret_20d",
    # Volatilität
    "vol_5d", "vol_10d", "vol_20d",
    # MACD
    "macd_val", "macd_signal", "macd_hist", "macd_hist_slope",
    # Stochastik
    "stoch_k", "stoch_d", "stoch_kd_diff",
    # RSI
    "rsi_14", "rsi_dist_30", "rsi_dist_70",
    # Bollinger
    "bb_pct_b", "bb_width",
    # Volumen
    "vol_rel_5d", "vol_rel_20d", "vol_trend",
    # Trend / GDs
    "dist_sma20", "dist_sma50", "dist_sma200",
    "sma20_above_50", "sma50_above_200",
    # Kerzen-Eigenschaften
    "body_size", "upper_wick", "lower_wick", "candle_dir",
    # Highs / Lows
    "dist_52w_high", "dist_52w_low",
    # Momentum
    "roc_10d", "roc_20d",
    # Trendkontinuität
    "days_above_sma20", "days_above_sma50",
    # Interval-Kodierung (ordinale Skala: 0=1d, 1=4h, 2=1h)
    "interval_code",
]


# Mapping Interval-String → ordinaler Code für das Modell
INTERVAL_CODE_MAP: dict[str, int] = {"1d": 0, "4h": 1, "1h": 2}


def compute_features(df: pd.DataFrame, interval: str = "1d") -> pd.DataFrame:
    """
    Berechnet alle Features aus einem OHLCV-DataFrame.

    Erwartet Spalten: open, high, low, close, volume
    Index: DatetimeIndex oder RangeIndex (chronologisch aufsteigend)

    interval: "1d" | "4h" | "1h" – wird als ordinales Feature kodiert,
              damit das Modell Signalstärken je Zeitrahmen unterscheiden kann.

    Gibt einen DataFrame mit FEATURE_NAMES als Spalten zurück.
    Zeilen mit NaN (Anfang der Serie) werden entfernt.
    """
    df = df.copy()
    df = df.sort_index()

    close  = df["close"].astype(float)
    high   = df["high"].astype(float)
    low    = df["low"].astype(float)
    open_  = df["open"].astype(float)
    volume = df["volume"].astype(float) if "volume" in df.columns else pd.Series(1.0, index=df.index)

    feat = pd.DataFrame(index=df.index)

    # ── Returns ───────────────────────────────────────────────
    for n in [1, 3, 5, 10, 20]:
        feat[f"ret_{n}d"] = close.pct_change(n)

    # ── Volatilität ───────────────────────────────────────────
    daily_ret = close.pct_change()
    for n in [5, 10, 20]:
        feat[f"vol_{n}d"] = daily_ret.rolling(n).std()

    # ── MACD (12/26/9) ────────────────────────────────────────
    ema12 = close.ewm(span=12, adjust=False).mean()
    ema26 = close.ewm(span=26, adjust=False).mean()
    macd  = ema12 - ema26
    signal = macd.ewm(span=9, adjust=False).mean()
    hist   = macd - signal

    feat["macd_val"]       = macd   / close  # normalisiert auf Preis
    feat["macd_signal"]    = signal / close
    feat["macd_hist"]      = hist   / close
    feat["macd_hist_slope"] = hist.diff()    / close

    # ── Slow Stochastik (14/3) ────────────────────────────────
    low14  = low.rolling(14).min()
    high14 = high.rolling(14).max()
    range14 = (high14 - low14).replace(0, np.nan)
    k = 100 * (close - low14) / range14
    d = k.rolling(3).mean()

    feat["stoch_k"]      = k / 100
    feat["stoch_d"]      = d / 100
    feat["stoch_kd_diff"] = (k - d) / 100

    # ── RSI (14) ──────────────────────────────────────────────
    delta  = close.diff()
    gain   = delta.clip(lower=0).rolling(14).mean()
    loss   = (-delta.clip(upper=0)).rolling(14).mean()
    rs     = gain / loss.replace(0, np.nan)
    rsi    = 100 - (100 / (1 + rs))

    feat["rsi_14"]      = rsi / 100
    feat["rsi_dist_30"] = (rsi - 30) / 100   # positiv = über 30
    feat["rsi_dist_70"] = (70 - rsi) / 100   # positiv = unter 70

    # ── Bollinger Bands (20/2) ────────────────────────────────
    sma20  = close.rolling(20).mean()
    std20  = close.rolling(20).std()
    upper  = sma20 + 2 * std20
    lower  = sma20 - 2 * std20
    band_w = (upper - lower)

    feat["bb_pct_b"] = (close - lower) / band_w.replace(0, np.nan)
    feat["bb_width"] = band_w / sma20.replace(0, np.nan)

    # ── Volumen ───────────────────────────────────────────────
    vol_ma5  = volume.rolling(5).mean()
    vol_ma20 = volume.rolling(20).mean()

    feat["vol_rel_5d"]  = volume / vol_ma5.replace(0, np.nan)
    feat["vol_rel_20d"] = volume / vol_ma20.replace(0, np.nan)
    feat["vol_trend"]   = vol_ma5 / vol_ma20.replace(0, np.nan)

    # ── Gleitende Durchschnitte ───────────────────────────────
    sma50  = close.rolling(50).mean()
    sma200 = close.rolling(200).mean()

    feat["dist_sma20"]  = (close - sma20)  / sma20.replace(0, np.nan)
    feat["dist_sma50"]  = (close - sma50)  / sma50.replace(0, np.nan)
    feat["dist_sma200"] = (close - sma200) / sma200.replace(0, np.nan)

    feat["sma20_above_50"]  = (sma20  > sma50).astype(float)
    feat["sma50_above_200"] = (sma50  > sma200).astype(float)

    # ── Kerzen-Eigenschaften ──────────────────────────────────
    body        = (close - open_).abs() / close
    upper_wick  = (high - pd.concat([close, open_], axis=1).max(axis=1)) / close
    lower_wick  = (pd.concat([close, open_], axis=1).min(axis=1) - low) / close

    feat["body_size"]  = body
    feat["upper_wick"] = upper_wick
    feat["lower_wick"] = lower_wick
    feat["candle_dir"] = np.sign(close - open_)  # +1 grün, -1 rot

    # ── 52-Wochen Hochs/Tiefs ─────────────────────────────────
    high_52w = high.rolling(252).max()
    low_52w  = low.rolling(252).min()

    feat["dist_52w_high"] = (close - high_52w) / high_52w.replace(0, np.nan)
    feat["dist_52w_low"]  = (close - low_52w)  / low_52w.replace(0, np.nan)

    # ── Rate of Change ────────────────────────────────────────
    feat["roc_10d"] = close.pct_change(10)
    feat["roc_20d"] = close.pct_change(20)

    # ── Trendkontinuität (wie viele der letzten 20 Tage über GD) ──
    above_sma20 = (close > sma20).astype(float)
    above_sma50 = (close > sma50).astype(float)

    feat["days_above_sma20"] = above_sma20.rolling(20).mean()
    feat["days_above_sma50"] = above_sma50.rolling(20).mean()

    # ── Interval-Kodierung ────────────────────────────────────
    # Ordinales Feature damit das Modell je Zeitrahmen unterschiedliche
    # Schwellen und Signalstärken lernen kann (0=daily, 1=4h, 2=1h)
    feat["interval_code"] = float(INTERVAL_CODE_MAP.get(interval, 0))

    # ── Sicherstellung der Spaltenreihenfolge ─────────────────
    feat = feat[FEATURE_NAMES]

    # NaN-Zeilen entfernen (Aufwärmphase der rollenden Indikatoren)
    feat = feat.dropna()

    return feat


def compute_labels(df: pd.DataFrame, horizon: int, threshold_pct: float) -> pd.Series:
    """
    Berechnet binäre Labels für das Training:
      1 = Umkehr nach oben in den nächsten `horizon` Tagen
          (max. Preis steigt um mehr als `threshold_pct`%)
      0 = keine signifikante Aufwärtsumkehr

    Das Label wird rückwärts berechnet: für jeden Zeitpunkt t schauen wir,
    ob der Preis in t+1 bis t+horizon um mehr als threshold_pct% steigt.
    """
    close  = df["close"].astype(float)
    labels = pd.Series(0, index=df.index, name="label")

    for i in range(len(close) - horizon):
        current = close.iloc[i]
        future_max = close.iloc[i + 1 : i + horizon + 1].max()
        if current > 0 and (future_max - current) / current * 100 >= threshold_pct:
            labels.iloc[i] = 1

    # Die letzten `horizon` Zeilen können kein Label haben
    labels.iloc[-horizon:] = np.nan

    return labels

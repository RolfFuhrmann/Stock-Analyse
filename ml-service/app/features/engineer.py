"""
ml-service/app/features/engineer.py

Feature-Engineering für das XGBoost-Modell.
Berechnet technische Indikatoren als numerische Features aus OHLCV-Daten.

Seit 29.09. mit pandas-ta (pandas-ta-openbb, NumPy-2-kompatibler Fork) für die
etablierten "Lehrbuch"-Indikatoren (MACD, RSI, Stochastik, Bollinger, SMA) -
selbst gebaut bleiben nur die Merkmale, für die pandas-ta keinen direkten
Mehrwert bietet (Returns, Volatilität, Volumen-Verhältnisse, Kerzen-Geometrie,
52-Wochen-Hoch/Tief, Trendkontinuität). Wichtig: der RSI wechselt dabei von
einer simplen Rolling-Mean-Glättung auf die korrekte Wilder-Glättung - die
Werte verschieben sich dadurch leicht gegenüber der alten Berechnung, das ist
beabsichtigt (Standard-Definition statt Eigenbau).

Seit 29.09. außerdem EIN Modell PRO Zeitrahmen (1d/4h/1h) statt eines
gemeinsamen Modells - "interval_code" ist daher kein Feature mehr (wäre pro
Modell ohnehin konstant).

Features (38 total):
  Preis-Returns:        1, 3, 5, 10, 20 Kerzen
  Volatilität:          5, 10, 20 Kerzen (rolling std der Returns)
  MACD:                 Wert, Signal, Histogramm, Histogramm-Steigung (pandas-ta)
  Stochastik:           %K, %D, %K-%D (pandas-ta, slow/geglättet)
  RSI (14):             Wert, Abstand zu 30/70 (pandas-ta, Wilder-Glättung)
  Bollinger Bands:      %B, Bandbreite (pandas-ta)
  Volumen:              Relatives Volumen (5/20 Kerzen), Volumen-Trend
  Trendstärke:          SMA-20/50/200 Abstand (SMA via pandas-ta), GD-Kreuzungen
  Kerzen-Eigenschaften: Body-Größe, Docht-Länge, Kerzen-Richtung
"""
import logging

import numpy as np
import pandas as pd
import pandas_ta as ta

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
]


# Mapping Interval-String → ordinaler Code. Wird nicht mehr als Feature
# genutzt (siehe oben), aber noch für Pfad-/Log-Zwecke referenziert.
INTERVAL_CODE_MAP: dict[str, int] = {"1d": 0, "4h": 1, "1h": 2}


def compute_features(df: pd.DataFrame, interval: str = "1d") -> pd.DataFrame:
    """
    Berechnet alle Features aus einem OHLCV-DataFrame.

    Erwartet Spalten: open, high, low, close, volume
    Index: DatetimeIndex oder RangeIndex (chronologisch aufsteigend)

    interval: "1d" | "4h" | "1h" – wird NICHT mehr als Feature kodiert (siehe
              Modulkopf), nur noch für eventuelles künftiges intervall-
              spezifisches Verhalten mitgegeben.

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

    # ── MACD (12/26/9) – pandas-ta ─────────────────────────────
    # Per Position statt exaktem Spaltennamen zugegriffen (robust gegen
    # Formatierungsunterschiede wie "12_26_9" vs. "12.0_26.0_9.0" zwischen
    # pandas-ta-Versionen). Reihenfolge laut Doku fix: MACD, Histogramm, Signal.
    macd_df = ta.macd(close, fast=12, slow=26, signal=9)
    macd    = macd_df.iloc[:, 0]
    hist    = macd_df.iloc[:, 1]
    signal  = macd_df.iloc[:, 2]

    feat["macd_val"]        = macd   / close  # normalisiert auf Preis
    feat["macd_signal"]     = signal / close
    feat["macd_hist"]       = hist   / close
    feat["macd_hist_slope"] = hist.diff() / close

    # ── Slow Stochastik (10/3/3) – pandas-ta ───────────────────
    # 01.10. von 14 auf 10 umgestellt (Rolfs Wunsch, einheitlich mit den
    # Stochastik-Konstanten in agent-service-java/Bullish+BearishIndicator).
    stoch_df = ta.stoch(high, low, close, k=10, d=3, smooth_k=3)
    k = stoch_df.iloc[:, 0]  # STOCHk
    d = stoch_df.iloc[:, 1]  # STOCHd

    feat["stoch_k"]       = k / 100
    feat["stoch_d"]       = d / 100
    feat["stoch_kd_diff"] = (k - d) / 100

    # ── RSI (14) – pandas-ta, Wilder-Glättung ──────────────────
    rsi = ta.rsi(close, length=14)

    feat["rsi_14"]      = rsi / 100
    feat["rsi_dist_30"] = (rsi - 30) / 100   # positiv = über 30
    feat["rsi_dist_70"] = (70 - rsi) / 100   # positiv = unter 70

    # ── Bollinger Bands (20/2) – pandas-ta ─────────────────────
    # Spaltenreihenfolge laut Doku fix: Lower, Mid(=SMA20), Upper, Bandwidth, %B
    bb_df  = ta.bbands(close, length=20, std=2)
    sma20  = bb_df.iloc[:, 1]

    feat["bb_pct_b"] = bb_df.iloc[:, 4]
    feat["bb_width"] = bb_df.iloc[:, 3] / 100  # pandas-ta gibt %, wir normieren auf 0-1

    # ── Volumen ───────────────────────────────────────────────
    vol_ma5  = volume.rolling(5).mean()
    vol_ma20 = volume.rolling(20).mean()

    feat["vol_rel_5d"]  = volume / vol_ma5.replace(0, np.nan)
    feat["vol_rel_20d"] = volume / vol_ma20.replace(0, np.nan)
    feat["vol_trend"]   = vol_ma5 / vol_ma20.replace(0, np.nan)

    # ── Gleitende Durchschnitte – pandas-ta ─────────────────────
    sma50  = ta.sma(close, length=50)
    sma200 = ta.sma(close, length=200)

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

    # ── Trendkontinuität (wie viele der letzten 20 Kerzen über GD) ──
    above_sma20 = (close > sma20).astype(float)
    above_sma50 = (close > sma50).astype(float)

    feat["days_above_sma20"] = above_sma20.rolling(20).mean()
    feat["days_above_sma50"] = above_sma50.rolling(20).mean()

    # ── Sicherstellung der Spaltenreihenfolge ─────────────────
    feat = feat[FEATURE_NAMES]

    # NaN-Zeilen entfernen (Aufwärmphase der rollenden Indikatoren)
    feat = feat.dropna()

    return feat


def compute_labels(
    df: pd.DataFrame,
    horizon: int,
    threshold_pct: float,
    downtrend_lookback: int = 20,
    downtrend_threshold_pct: float = 8.0,
    low_break_tolerance_pct: float = 0.3,
) -> pd.Series:
    """
    Reversal-Label (29.09. neu definiert - ersetzt das reine, trend-
    unabhängige Anstiegs-Label vom 21.09.). Beantwortet: "Ist der aktuelle
    Abwärtstrend erschöpft und beginnt eine echte Aufwärtsbewegung?"

    Zwei Schritte:

    1. Downtrend-Vorbedingung (Filter): Nur Zeitpunkte, an denen der
       Schlusskurs mindestens `downtrend_threshold_pct`% unter dem höchsten
       Schlusskurs der letzten `downtrend_lookback` Kerzen liegt, werden
       überhaupt als Trainingsbeispiel gewertet. Alle anderen -> NaN (werden
       von der aufrufenden Stelle über labels.dropna() aussortiert, nicht
       als Negativ-Label gezählt - sonst lernt das Modell an Fällen, die mit
       der Fragestellung nichts zu tun haben).

    2. Von den verbleibenden (Downtrend-)Zeitpunkten:
         1 = "echtes Reversal": Kurs steigt innerhalb der nächsten `horizon`
             Kerzen um mindestens `threshold_pct`%, OHNE vorher ein neues,
             signifikant tieferes Tief zu erzeugen (Toleranz
             `low_break_tolerance_pct`%, filtert einzelne Docht-Ausreißer).
         0 = kein Reversal: entweder kein ausreichender Anstieg, oder vorher
             entsteht ein neues Tief (Abwärtstrend setzt sich fort).
    """
    close = df["close"].astype(float)
    low   = df["low"].astype(float)

    rolling_high  = close.rolling(downtrend_lookback).max()
    in_downtrend  = (close - rolling_high) / rolling_high.replace(0, np.nan) * 100 <= -downtrend_threshold_pct

    labels = pd.Series(np.nan, index=df.index, name="label")

    for i in range(len(close) - horizon):
        if not bool(in_downtrend.iloc[i]):
            continue  # keine Downtrend-Vorbedingung -> kein Trainingsbeispiel

        current_close = close.iloc[i]
        current_low   = low.iloc[i]
        if current_close <= 0 or pd.isna(current_low):
            continue

        low_break_level = current_low * (1 - low_break_tolerance_pct / 100)

        label = 0
        for j in range(i + 1, i + horizon + 1):
            if low.iloc[j] < low_break_level:
                label = 0  # neues, signifikantes Tief vor dem Ziel-Anstieg
                break
            rise_pct = (close.iloc[j] - current_close) / current_close * 100
            if rise_pct >= threshold_pct:
                label = 1  # Ziel-Anstieg erreicht, ohne vorheriges neues Tief
                break
        labels.iloc[i] = label

    return labels

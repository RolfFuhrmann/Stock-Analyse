"""
ml-service/app/features/labels.py

Deutsche Bezeichnungen und Anzeigeformate der Features (21.09.).
Dient ausschließlich der Erklärung im Client ("Warum dieser Wert?") - die
Berechnung der Features bleibt in engineer.py.

Hinweis: Alle Zeitangaben der Features zählen in KERZEN des gewählten
Zeitrahmens (1d/4h/1h), nicht in Tagen - bei 4h- und 1h-Kerzen ist "20 Kerzen"
also 80 bzw. 20 Handelsstunden. Deshalb sprechen die Labels von "Kerzen".
"""
from app.features.engineer import FEATURE_NAMES, INTERVAL_CODE_MAP

# Anzeigeformate
PCT = "pct"          # Bruchteil → Prozent, z.B. 0.041 → "4,1 %"
PCT2 = "pct2"        # wie pct, aber 2 Nachkommastellen (kleine Werte, z.B. MACD)
SCALE100 = "scale100"  # 0..1 → 0..100 ohne Einheit, z.B. RSI 0.35 → "35"
RATIO = "ratio"      # Verhältnis, z.B. Volumen 1.4 → "1,4×"
FLAG = "flag"        # 0/1 → "ja"/"nein"
COLOR = "color"      # -1/0/+1 → rot/neutral/grün
SHARE20 = "share20"  # Anteil der letzten 20 Kerzen → "12 von 20"
INTERVAL = "interval"  # 0/1/2 → 1d/4h/1h

# feature → (deutsches Label, Format)
FEATURE_INFO: dict[str, tuple[str, str]] = {
    "ret_1d":  ("Rendite letzte Kerze", PCT),
    "ret_3d":  ("Rendite 3 Kerzen", PCT),
    "ret_5d":  ("Rendite 5 Kerzen", PCT),
    "ret_10d": ("Rendite 10 Kerzen", PCT),
    "ret_20d": ("Rendite 20 Kerzen", PCT),
    "vol_5d":  ("Schwankung 5 Kerzen", PCT),
    "vol_10d": ("Schwankung 10 Kerzen", PCT),
    "vol_20d": ("Schwankung 20 Kerzen", PCT),
    "macd_val":        ("MACD-Linie (in % vom Kurs)", PCT2),
    "macd_signal":     ("MACD-Signallinie (in % vom Kurs)", PCT2),
    "macd_hist":       ("MACD-Histogramm (in % vom Kurs)", PCT2),
    "macd_hist_slope": ("MACD-Histogramm-Steigung", PCT2),
    "stoch_k":       ("Stochastik %K", SCALE100),
    "stoch_d":       ("Stochastik %D", SCALE100),
    "stoch_kd_diff": ("Stochastik K minus D", SCALE100),
    "rsi_14":      ("RSI (14)", SCALE100),
    "rsi_dist_30": ("RSI-Abstand zu 30 (überverkauft)", SCALE100),
    "rsi_dist_70": ("RSI-Abstand zu 70 (überkauft)", SCALE100),
    "bb_pct_b":  ("Lage im Bollinger-Band (0 = unten, 1 = oben)", RATIO),
    "bb_width":  ("Bollinger-Bandbreite", PCT),
    "vol_rel_5d":  ("Handelsvolumen im Vergleich zu 5 Kerzen", RATIO),
    "vol_rel_20d": ("Handelsvolumen im Vergleich zu 20 Kerzen", RATIO),
    "vol_trend":   ("Volumen-Trend (5 vs. 20 Kerzen)", RATIO),
    "dist_sma20":  ("Abstand zum GD 20", PCT),
    "dist_sma50":  ("Abstand zum GD 50", PCT),
    "dist_sma200": ("Abstand zum GD 200", PCT),
    "sma20_above_50":  ("GD 20 über GD 50", FLAG),
    "sma50_above_200": ("GD 50 über GD 200", FLAG),
    "body_size":  ("Kerzenkörper (in % vom Kurs)", PCT2),
    "upper_wick": ("Oberer Kerzendocht (in % vom Kurs)", PCT2),
    "lower_wick": ("Unterer Kerzendocht (in % vom Kurs)", PCT2),
    "candle_dir": ("Farbe der letzten Kerze", COLOR),
    "dist_52w_high": ("Abstand zum 252-Kerzen-Hoch", PCT),
    "dist_52w_low":  ("Abstand zum 252-Kerzen-Tief", PCT),
    "roc_10d": ("Kursänderung 10 Kerzen (ROC)", PCT),
    "roc_20d": ("Kursänderung 20 Kerzen (ROC)", PCT),
    "days_above_sma20": ("Kerzen über GD 20 (von den letzten 20)", SHARE20),
    "days_above_sma50": ("Kerzen über GD 50 (von den letzten 20)", SHARE20),
    "interval_code": ("Zeitrahmen", INTERVAL),
}

# Sicherheitsnetz: jedes Feature des Modells braucht ein Label
_missing = [name for name in FEATURE_NAMES if name not in FEATURE_INFO]
if _missing:
    raise RuntimeError(f"Features ohne Label in labels.py: {_missing}")


def label_of(feature: str) -> str:
    """Deutsches Label eines Features (Fallback: technischer Name)."""
    return FEATURE_INFO.get(feature, (feature, PCT))[0]


def format_value(feature: str, value: float) -> str:
    """Formatiert den Rohwert eines Features für die Anzeige (deutsche Schreibweise)."""
    fmt = FEATURE_INFO.get(feature, (feature, PCT))[1]

    def num(x: float, digits: int) -> str:
        return f"{x:.{digits}f}".replace(".", ",")

    if fmt == PCT:
        return f"{num(value * 100, 1)} %"
    if fmt == PCT2:
        return f"{num(value * 100, 2)} %"
    if fmt == SCALE100:
        return num(value * 100, 0)
    if fmt == RATIO:
        return f"{num(value, 2)}"
    if fmt == FLAG:
        return "ja" if value >= 0.5 else "nein"
    if fmt == COLOR:
        return "grün" if value > 0 else "rot" if value < 0 else "neutral"
    if fmt == SHARE20:
        return f"{round(value * 20)} von 20"
    if fmt == INTERVAL:
        names = {code: name for name, code in INTERVAL_CODE_MAP.items()}
        return names.get(int(round(value)), "?")
    return num(value, 3)

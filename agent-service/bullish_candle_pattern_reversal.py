"""
bullish_candle_pattern_reversal.py – Bullish Reversal Pattern Recognition

Diese Datei enthält Erkennungsalgorithmen für bullische Kerzen-Formationen (Reversals)
am Ende eines Abwärtstrends. Um Seitwärtsphasen (Rauschen) verlässlich to filtern,
wird eine historische Periode von 5 aufeinanderfolgenden Trendkerzen vorausgesetzt.
"""

import pandas as pd

# ══════════════════════════════════════════════════════════════════════════════
# TREND AND PATTERN RULES (IMPLEMENTED VIA GLOBAL CONSTANTS):
#
# 1. Trend Definition:
#    - For ALL patterns, exactly 5 completed candles are used to establish 
#      the initial trend context before the pattern starts.
#    - The trend is validated by checking if the trend direction moves downward
#      overall, preventing false signals inside flat sideways channels.
#    - A threshold (TREND_TOLERANCE_PCT) is applied to handle minor market noise.
#
# 2. Pattern Windows and Alignments:
#    - Hammer: Requires 6 candles in total.
#      First 5 candles = Downtrend context | Last 1 candle = Hammer pattern.
#    - Bullish Engulfing & Piercing Line: Requires 7 candles in total.
#      First 5 candles = Downtrend context | Last 2 candles = Reversal pattern.
#    - Bullish Abandoned Baby & Morning Star: Requires 8 candles in total.
#      First 5 candles = Downtrend context | Last 3 candles = Reversal pattern.
# ══════════════════════════════════════════════════════════════════════════════

# ══════════════════════════════════════════════════════════════════════════════
# Globale Konfiguration / Toleranzen
TREND_TOLERANCE_PCT = 0.005  # Erlaubter "Fehler" (Puffer) bei der Trendprüfung (0.5%)
GAP_TOLERANCE_PCT = 0.02     # Toleranz für Gap-Überschneidungen (2%)
ENGULFING_THRESHOLD = 0.95   # Prozentsatz, den der Körper umschlossen sein muss (95%)
PIERCING_MIDPOINT_TOLERANCE = 0.02 # Puffer für das Erreichen der 50%-Linie (2%)


class _CandleUtils:
    """Hilfsfunktionen für Kerzenberechnungen basierend auf relativen Indizes von hinten."""

    def __init__(self, df: pd.DataFrame):
        self.o = df["open"].values
        self.h = df["high"].values
        self.l = df["low"].values
        self.c = df["close"].values
        self.length = len(df)

    def body(self, idx: int) -> float:
        """Berechnet die absolute Größe des Kerzenkörpers."""
        return abs(self.c[idx] - self.o[idx])

    def is_bearish(self, idx: int) -> bool:
        return self.c[idx] < self.o[idx]

    def is_bullish(self, idx: int) -> bool:
        return self.c[idx] > self.o[idx]

    def lower_shadow(self, idx: int) -> float:
        return min(self.o[idx], self.c[idx]) - self.l[idx]

    def upper_shadow(self, idx: int) -> float:
        return self.h[idx] - max(self.o[idx], self.c[idx])

    def is_doji(self, idx: int, tolerance: float = 0.1) -> bool:
        span = self.h[idx] - self.l[idx]
        if span == 0: return True
        return self.body(idx) / span < tolerance

    def midpoint(self, idx: int) -> float:
        return (self.o[idx] + self.c[idx]) / 2

    def has_downtrend_before(self, pattern_start_idx: int) -> bool:
        """
        Prüft, ob die 5 Kerzen DIREKT VOR dem Muster einen echten Abwärtstrend zeigen.
        """
        if pattern_start_idx < 0:
            pattern_start_idx = self.length + pattern_start_idx
            
        start = pattern_start_idx - 5
        if start < 0:
            return False
            
        for i in range(start, pattern_start_idx - 1):
            current_top = max(self.o[i], self.c[i])
            next_top = max(self.o[i+1], self.c[i+1])
            
            current_bottom = min(self.o[i], self.c[i])
            next_bottom = min(self.o[i+1], self.c[i+1])
            
            allowed_error = current_top * TREND_TOLERANCE_PCT
            
            if next_top > current_top + allowed_error or next_bottom > current_bottom + allowed_error:
                return False
                
        return True


def _detect_abandoned_baby(u: _CandleUtils) -> bool:
    """Erkennung: Bullish Abandoned Baby (Stärke 5) - Benötigt 5 Trendkerzen + 3 Musterkerzen = 8"""
    if u.length < 8 or not u.has_downtrend_before(-3):
        return False
        
    avg_span = (u.h[-3] - u.l[-3] + u.h[-2] - u.l[-2] + u.h[-1] - u.l[-1]) / 3
    allowed_overlap = avg_span * GAP_TOLERANCE_PCT
        
    return (
        u.is_bearish(-3)
        and u.is_doji(-2, tolerance=0.35)
        and (u.h[-2] - allowed_overlap) < u.l[-3]
        and u.is_bullish(-1)
        and (u.l[-1] + allowed_overlap) > u.h[-2]
    )


def _detect_morning_star(u: _CandleUtils) -> bool:
    """Erkennung: Morning Star (Stärke 4) - Benötigt 5 Trendkerzen + 3 Musterkerzen = 8"""
    if u.length < 8 or not u.has_downtrend_before(-3):
        return False
    
    # Kriterien für Morning Star:
    # 1. Bär (-3), 2. Star (-2), 3. Bulle (-1)
    # Exklusive Bedingung: Der Close von -1 muss deutlich über dem Midpoint von -3 liegen.
    return (
        u.is_bearish(-3)
        and u.is_bullish(-1)
        and u.c[-1] > u.midpoint(-3)
    )

def _detect_engulfing(u: _CandleUtils) -> bool:
    """Erkennung: Bullish Engulfing (Stärke 3)"""
    if u.length < 7 or not u.has_downtrend_before(-2):
        return False
        
    # --- NEUER, STRIKTER EXKLUSIVITÄTS-CHECK ---
    # Prüfe, ob die Konstellation die Anforderungen eines Morning Stars erfüllt.
    # Wenn ja, unterdrücken wir das Engulfing-Signal, damit der Morning Star gewinnt.
    if u.length >= 8 and u.has_downtrend_before(-3):
        is_morning_star_setup = (
            u.is_bearish(-3) 
            and u.body(-2) < u.body(-3) # Star ist kleiner als Bär
            and u.is_bullish(-1) 
            and u.c[-1] > u.midpoint(-3)
        )
        if is_morning_star_setup:
            return False
    # ------------------------------------------
        
    if not (u.is_bearish(-2) and u.is_bullish(-1)):
        return False
        
    k2_top = u.o[-2]
    k2_bottom = u.c[-2]
    
    engulfs_top = u.c[-1] >= (k2_top - u.body(-2) * (1 - ENGULFING_THRESHOLD))
    engulfs_bottom = u.o[-1] <= (k2_bottom + u.body(-2) * (1 - ENGULFING_THRESHOLD))
    
    return engulfs_top and engulfs_bottom and (u.body(-1) > u.body(-2) * ENGULFING_THRESHOLD)

def _detect_piercing(u: _CandleUtils) -> bool:
    """Erkennung: Piercing Line (Stärke 2) - Benötigt 5 Trendkerzen + 2 Musterkerzen = 7"""
    if u.length < 7 or not u.has_downtrend_before(-2):
        return False
        
    target_line = u.midpoint(-2) - (u.body(-2) * PIERCING_MIDPOINT_TOLERANCE)
    
    return (
        u.is_bearish(-2)
        and u.is_bullish(-1)
        and u.o[-1] < u.l[-2]
        and u.c[-1] > target_line
        and u.c[-1] <= u.o[-2]
    )


def _detect_hammer(u: _CandleUtils) -> bool:
    """Erkennung: Hammer (Stärke 1) - Benötigt 5 Trendkerzen + 1 Musterkerze = 6"""
    if u.length < 6 or not u.has_downtrend_before(-1):
        return False
        
    span = u.h[-1] - u.l[-1]
    if span == 0: return False
    
    return (
        u.body(-1) / span < 0.35
        and u.body(-1) / span > 0.02
        and u.lower_shadow(-1) >= span * 0.55
        and u.upper_shadow(-1) <= span * 0.15
    )


def detect_candle_pattern(df: pd.DataFrame, is_live_data: bool = False) -> dict:
    """Hauptfunktion zur Erkennung bullischer Trendwenden."""
    closed = df.iloc[:-1] if is_live_data else df

    if len(closed) < 6:
        return {"pattern": None, "strength": 0}

    u = _CandleUtils(closed.reset_index(drop=True))

    # WICHTIG: Die Reihenfolge ist korrekt. 
    # Wenn Abandoned Baby oder Morning Star zutreffen, MÜSSEN wir sofort returnen,
    # damit das Engulfing nicht fälschlicherweise als "stärkeres" oder "zweites" Signal erkannt wird.

    if _detect_abandoned_baby(u):
        return {"pattern": "Bullish Abandoned Baby", "strength": 5}

    if _detect_morning_star(u):
        return {"pattern": "Morning Star", "strength": 4}

    if _detect_engulfing(u):
        return {"pattern": "Bullish Engulfing", "strength": 3}

    if _detect_piercing(u):
        return {"pattern": "Piercing Line", "strength": 2}

    if _detect_hammer(u):
        return {"pattern": "Hammer", "strength": 1}

    return {"pattern": None, "strength": 0}
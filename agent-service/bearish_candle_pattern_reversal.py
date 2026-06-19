# ══════════════════════════════════════════════════════════════════════════════
# TREND AND PATTERN RULES (IMPLEMENTED VIA GLOBAL CONSTANTS):
#
# 1. Trend Definition:
#    - For ALL patterns, exactly 5 completed candles are used to establish 
#      the initial uptrend context before the pattern starts.
#    - The uptrend is validated by a continuous sequence of higher highs and 
#      higher lows based strictly on the candle BODIES.
#    - Increasing the trend length from 3 to 5 candles drastically reduces 
#      false signals inside horizontal choppy/flat sideways channels.
#    - A tight threshold (TREND_TOLERANCE_PCT) is applied to handle minor market 
#      noise without breaking the trend validity.
#
# 2. Pattern Windows and Alignments:
#    - Shooting Star: Requires 6 candles in total.
#      First 5 candles = Uptrend context | Last 1 candle = Shooting Star pattern.
#    - Bearish Engulfing & Dark Cloud Cover: Requires 7 candles in total.
#      First 5 candles = Uptrend context | Last 2 candles = Reversal pattern.
#    - Bearish Abandoned Baby: Requires 8 candles in total.
#      First 5 candles = Uptrend context | Last 3 candles = Reversal pattern.
#
# 3. Flexibilities / Tolerances:
#    - Real-world market deviations (imperfect gaps, incomplete engulfing bodies, 
#      and minor penetration levels) are dynamically handled via custom thresholds 
#      to prevent premature signal rejection while preserving pattern integrity.
# ══════════════════════════════════════════════════════════════════════════════

import logging

import pandas as pd

# ══════════════════════════════════════════════════════════════════════════════
# Globale Konfiguration / Toleranzen
# ══════════════════════════════════════════════════════════════════════════════
TREND_TOLERANCE_PCT = 0.005  # Erlaubter "Fehler" (Puffer) bei der Trendprüfung (0.5%)
GAP_TOLERANCE_PCT = 0.02     # Toleranz für Gap-Überschneidungen (2%)
ENGULFING_THRESHOLD = 0.95   # Prozentsatz, den der Körper umschlossen sein muss (95%)
DARK_CLOUD_MIDPOINT_TOLERANCE = 0.02 # Puffer für das Eindringen in die 50%-Linie (2%)

class _CandleUtils:
    """Hilfsfunktionen für Kerzenberechnungen basierend auf relativen Indizes von hinten."""

    def __init__(self, df: pd.DataFrame):
        self.o = df["open"].values
        self.h = df["high"].values
        self.l = df["low"].values
        self.c = df["close"].values
        self.length = len(df)

    def body(self, idx: int) -> float:
        return abs(self.c[idx] - self.o[idx])

    def is_bearish(self, idx: int) -> bool:
        logging.info("is_bullish " + str(self.c[idx] < self.o[idx]))
        return self.c[idx] < self.o[idx]

    def is_bullish(self, idx: int) -> bool:
        logging.info("is_bullish " + str(self.c[idx] > self.o[idx]))
        return self.c[idx] > self.o[idx]

    def lower_shadow(self, idx: int) -> float:
        return min(self.o[idx], self.c[idx]) - self.l[idx]

    def upper_shadow(self, idx: int) -> float:
        return self.h[idx] - max(self.o[idx], self.c[idx])

    def is_doji(self, idx: int, tolerance: float = 0.1) -> bool:
        span = self.h[idx] - self.l[idx]
        logging.info("is_doji " + str(self.h[idx] - self.l[idx]))
        if span == 0: return True
        logging.info("is_doji " + str(self.body(idx) / span < tolerance))
        return self.body(idx) / span < tolerance

    def midpoint(self, idx: int) -> float:
        return (self.o[idx] + self.c[idx]) / 2

    def has_uptrend_before(self, pattern_start_idx: int) -> bool:
        """
        Strikte Prüfung: Jeder Körper muss ein höheres Hoch und ein höheres Tief 
        als der Vorgänger haben (Higher Highs / Higher Lows).
        """
        start = pattern_start_idx - 5
        
        for offset in range(4):
            i_current = start + offset
            i_next = start + offset + 1
            
            # Die aktuellen Punkte
            curr_high = max(self.o[i_current], self.c[i_current])
            curr_low = min(self.o[i_current], self.c[i_current])
            
            # Die nächsten Punkte
            next_high = max(self.o[i_next], self.c[i_next])
            next_low = min(self.o[i_next], self.c[i_next])
            
            # Der Puffer
            allowed_error = curr_high * TREND_TOLERANCE_PCT
            
            # Strikte Bedingung: Wenn das nächste Hoch oder Tief NICHT über dem 
            # aktuellen liegt (minus den Puffer), ist der Aufwärtstrend unterbrochen.
            if next_high <= curr_high - allowed_error: 
                return False 
            if next_low <= curr_low - allowed_error: 
                return False
                
        return True

def _detect_bearish_abandoned_baby(u: _CandleUtils) -> bool:
    logging.info("_detect_bearish_abandoned_baby ")
    """
    Erkennung: Bearish Abandoned Baby (angepasste Toleranz)
    """
    # if u.length < 8 or not u.has_uptrend_before(-3):
    #    return False
        
    # Anstatt den Durchschnitt als starre Grenze zu nehmen, 
    # machen wir den Toleranz-Faktor für das Gap "weicher".
    # Wir verringern die Anforderung an das Gap, um engere Muster zuzulassen.
    avg_span = (u.h[-3] - u.l[-3] + u.h[-2] - u.l[-2] + u.h[-1] - u.l[-1]) / 3
    
    # Reduziere die Anforderung an die Isolation des Doji
    # Wir erlauben, dass das Doji fast den Körper berührt (kleinerer Faktor 0.005 statt 0.02)
    relaxed_gap = avg_span * -0.005 

    logging.info("is_gap " + str((u.l[-2] + relaxed_gap) >= u.h[-3]))
    logging.info("Lockere Prüfung nach unten " + str((u.h[-1] - relaxed_gap) <= u.l[-2]))
        
    return (
        u.is_bullish(-3)                                # Kerze -3 ist grün
        and u.is_doji(-2, tolerance=0.35)               # Kerze -2 ist ein Doji
        and (u.l[-2] + relaxed_gap) >= u.h[-3]          # Lockere Prüfung: Berührung/kleines Gap reicht
        and u.is_bearish(-1)                            # Kerze -1 ist rot
        and (u.h[-1] - relaxed_gap) <= u.l[-2]          # Lockere Prüfung nach unten
    )

def _detect_bearish_engulfing(u: _CandleUtils) -> bool:
    """
    Erkennung: Bearish Engulfing (Stärke 3)
    Bedingung: 5 Trendkerzen + 2 Musterkerzen = Mindestens 7 Kerzen Gesamtlänge.
    Muster-Struktur: Eine kleinere grüne Kerze (-2) wird vom Körper einer großen
    roten Kerze (-1) vollständig verschlungen.
    """
    if u.length < 7 or not u.has_uptrend_before(-2):
        return False
        
    # KORREKTUR: Die Logik für die Farben und das Umschließen:
    # -2 muss grün sein (is_bullish)
    # -1 muss rot sein (is_bearish)
    if not (u.is_bullish(-2) and u.is_bearish(-1)):
        return False
        
    # Die rote Kerze (-1) muss den Körper der grünen Kerze (-2) umschließen.
    # Umschließen heißt: 
    # Rotes Open >= Grünes Open 
    # Roter Close <= Grüner Close
    
    engulfs_top = u.o[-1] >= u.o[-2]
    engulfs_bottom = u.c[-1] <= u.c[-2]
    
    # Zusätzlich: Der rote Körper muss signifikant sein
    return engulfs_top and engulfs_bottom and (u.body(-1) > u.body(-2) * ENGULFING_THRESHOLD)

def _detect_dark_cloud_cover(u: _CandleUtils) -> bool:
    """
    Erkennung: Dark Cloud Cover (Stärke 4)
    Bedingung: 5 Trendkerzen + 2 Musterkerzen = Mindestens 7 Kerzen Gesamtlänge.
    Muster-Struktur: Eine starke grüne Kerze (-2), gefolgt von einer roten Kerze (-1),
    die über dem Hoch von -2 eröffnet (Gap-Up-Täuschung), dann aber tief nach unten
    dreht und unter der 50%-Linie des Körpers von -2 schließt.
    """
    if u.length < 7 or not u.has_uptrend_before(-2):
        return False
        
    target_line = u.midpoint(-2) + (u.body(-2) * DARK_CLOUD_MIDPOINT_TOLERANCE)
    
    return (
        u.is_bullish(-2)                    # Kerze -2 ist grün
        and u.is_bearish(-1)                # Kerze -1 ist rot
        and u.o[-1] > u.h[-2]               # Eröffnung über dem Höchstkurs der Vorkerze
        and u.c[-1] < target_line           # Schlusskurs fällt unter die 50%-Linie von -2
        and u.c[-1] >= u.o[-2]              # Schließt aber über/gleich dem Open von -2
    )


def _detect_shooting_star(u: _CandleUtils) -> bool:
    """
    Erkennung: Shooting Star (Stärke 2)
    Bedingung: 5 Trendkerzen + 1 Musterkerze = Mindestens 6 Kerzen Gesamtlänge.
    Muster-Struktur: Eine Kerze mit extrem langem oberen Schatten (mindestens 60%),
    einem kleinen Körper im unteren Drittel und so gut wie keinem unteren Schatten (max 10%).
    
    Zusatzfilter (Rauschen): Winzige "Zwerg-Kerzen" mitten im Chart-Rauschen werden blockiert,
    indem die Handelsspanne mit dem historischen Durchschnitt der Vorkerzen verglichen wird.
    """
    if u.length < 6 or not u.has_uptrend_before(-1):
        return False
        
    span = u.h[-1] - u.l[-1]
    if span == 0: return False
    
    # Rausch-Filter: Die Kerze muss signifikante Größe im Vergleich zu den 3 Vorkerzen haben
    avg_historical_span = ((u.h[-2] - u.l[-2]) + (u.h[-3] - u.l[-3]) + (u.h[-4] - u.l[-4])) / 3
    if span < avg_historical_span * 0.5:
        return False
        
    return (
        u.body(-1) / span < 0.30               # Kleiner Körper (maximal 30% der Spanne)
        and u.upper_shadow(-1) >= span * 0.60  # Dominanter oberer Schatten (mindestens 60%)
        and u.lower_shadow(-1) <= span * 0.10  # Kaum Lunte vorhanden (maximal 10%)
    )


def detect_bearish_pattern(df: pd.DataFrame, is_live_data: bool = False) -> dict:
    closed = df.iloc[:-1] if is_live_data else df
    if len(closed) < 6:
        return {"pattern": None, "strength": 0}

    u = _CandleUtils(closed.reset_index(drop=True))

    # 1. Stärkste Muster zuerst prüfen
    if _detect_bearish_abandoned_baby(u):
        return {"pattern": "Bearish Abandoned Baby", "strength": 5}

    if _detect_dark_cloud_cover(u): # Dark Cloud Cover ist stärker als Engulfing (4 vs 3)
        return {"pattern": "Dark Cloud Cover", "strength": 4}

    if _detect_bearish_engulfing(u):
        return {"pattern": "Bearish Engulfing", "strength": 3}

    if _detect_shooting_star(u):
        return {"pattern": "Shooting Star", "strength": 2}

    return {"pattern": None, "strength": 0}
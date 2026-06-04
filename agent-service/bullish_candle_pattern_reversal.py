"""
bullish_candle_pattern_reversal.py – Bullische Umkehrformationen
Gekapselte Erkennung unabhängig von den Trendindikatoren.

Unterstützte Muster (Priorität / Stärke):
  1. Bullish Abandoned Baby  (5) – 4-Kerzen: i0–i3 Kontext, i2 Doji+Gap, i3 bullish+Gap
  2. Morning Star            (4) – 4-Kerzen: i0–i3 Kontext, i2 Stern, i3 bullish
  3. Bullish Engulfing       (3) – 3-Kerzen: i0–i2 Kontext, i2 umschließt i1
  4. Piercing Line           (2) – 3-Kerzen: i0–i2 Kontext, i2 durchdringt i1
  5. Hammer                  (1) – 3-Kerzen: i0–i2 Kontext, i2 Hammer-Form

Wichtig: Die letzte (noch laufende) Kerze wird grundsätzlich ausgeschlossen.
         Nur abgeschlossene Kerzen werden analysiert.
"""

import pandas as pd


# ══════════════════════════════════════════════════════════════════════════════
# Utilities
# ══════════════════════════════════════════════════════════════════════════════

class _CandleUtils:
    """Gemeinsame Hilfsfunktionen für Kerzenberechnungen."""

    def __init__(self, o, h, l, c):
        self.o = o
        self.h = h
        self.l = l
        self.c = c

    def body(self, i: int) -> float:
        return abs(self.c[i] - self.o[i])

    def is_bearish(self, i: int) -> bool:
        return self.c[i] < self.o[i]

    def is_bullish(self, i: int) -> bool:
        return self.c[i] > self.o[i]

    def lower_shadow(self, i: int) -> float:
        return min(self.o[i], self.c[i]) - self.l[i]

    def upper_shadow(self, i: int) -> float:
        return self.h[i] - max(self.o[i], self.c[i])

    def is_doji(self, i: int, tolerance: float = 0.1) -> bool:
        """Doji: Körper sehr klein im Verhältnis zur Gesamtspanne."""
        span = self.h[i] - self.l[i]
        return span > 0 and self.body(i) / span < tolerance

    def midpoint(self, i: int) -> float:
        """Mittelpunkt des Kerzenkörpers."""
        return (self.o[i] + self.c[i]) / 2


# ══════════════════════════════════════════════════════════════════════════════
# Muster-Erkennungen (je eine Funktion pro Muster)
# ══════════════════════════════════════════════════════════════════════════════

def _detect_abandoned_baby(u: _CandleUtils) -> bool:
    """
    Bullish Abandoned Baby – 4 Kerzen (i0–i3).

    Abwärtskontext: i0 bearish, i1 bearish und schließt unter i0.
    Muster:
      i1: bearish (letzte Abwärtskerze vor dem Wendepunkt)
      i2: Doji mit Gap nach unten (i2-Hoch unter i1-Tief)
      i3: bullish mit Gap nach oben (i3-Tief über i2-Hoch)
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Abwärtskontext
    if not (u.is_bearish(0) and u.is_bearish(1) and c[1] < c[0]):
        return False
    # Muster
    return (
        u.is_bearish(1)
        and u.is_doji(2, tolerance=0.35)
        and h[2] < l[1]       # Gap nach unten: i2-Hoch unter i1-Tief
        and u.is_bullish(3)
        and l[3] > h[2]       # Gap nach oben: i3-Tief über i2-Hoch
    )


def _detect_morning_star(u: _CandleUtils) -> bool:
    """
    Morning Star – 4 Kerzen (i0–i3).

    Abwärtskontext: i0 bearish, i1 bearish und schließt unter i0.
    Muster:
      i1: große bearishe Kerze
      i2: kleiner Körper (Stern) unterhalb des i1-Körpers
      i3: bullish, Körper muss mindestens 60% des i1-Körpers betragen
          UND über 50% des i1-Körpers schließen – erst dann ist die
          Bestätigung stark genug für eine echte Umkehr.
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Abwärtskontext
    if not (u.is_bearish(0) and u.is_bearish(1) and c[1] < c[0]):
        return False

    avg_body    = (u.body(1) + u.body(2) + u.body(3)) / 3
    midpoint_i1 = u.midpoint(1)

    return (
        u.is_bearish(1) and u.body(1) > avg_body * 0.8
        and u.body(2) < u.body(1) * 0.4
        and max(o[2], c[2]) < min(o[1], c[1])  # Stern liegt unter i1-Körper
        and u.is_bullish(3)
        # i3 muss kräftig genug sein: Körper ≥ 50% von i1
        and u.body(3) >= u.body(1) * 0.5
        # i3 schließt deutlich über Mitte i1 (70% statt 50%)
        and c[3] > o[1] - u.body(1) * 0.3
    )


def _detect_engulfing(u: _CandleUtils) -> bool:
    """
    Bullish Engulfing – 3 Kerzen (i0–i2).

    Abwärtskontext: i0 bearish, i1 bearish und schließt unter i0.
    Muster:
      i1: bearish
      i2: bullish, umschließt i1 vollständig, schließt über i0-Schluss
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Abwärtskontext
    if not (u.is_bearish(0) and u.is_bearish(1) and c[1] < c[0]):
        return False
    # Muster
    return (
        u.is_bearish(1)
        and u.is_bullish(2)
        and o[2] <= c[1]          # öffnet unter/gleich Schluss i1
        and c[2] >= o[1]          # schließt über/gleich Öffnung i1
        and u.body(2) > u.body(1)
        and c[2] > c[0]           # über i0-Schluss – echte Umkehr
    )


def _detect_piercing(u: _CandleUtils) -> bool:
    """
    Piercing Line – 3 Kerzen (i0–i2).

    Abwärtskontext: i0 bearish, i1 bearish und schließt unter i0.
    Muster:
      i1: bearish
      i2: bullish, öffnet unter Tief i1, schließt über 50% des i1-Körpers
          aber nicht über i1-Open (sonst Engulfing)
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Abwärtskontext
    if not (u.is_bearish(0) and u.is_bearish(1) and c[1] < c[0]):
        return False
    # Muster
    i1_midpoint = u.midpoint(1)
    return (
        u.is_bearish(1)
        and u.is_bullish(2)
        and o[2] < l[1]           # öffnet unter Tief i1
        and c[2] > i1_midpoint    # schließt über 50% des i1-Körpers
        and c[2] <= o[1]          # schließt nicht über i1-Open (sonst Engulfing)
    )


def _detect_hammer(u: _CandleUtils) -> bool:
    """
    Hammer – 3 Kerzen (i0–i2).

    Abwärtskontext: i0 bearish, i1 bearish und schließt unter i0.
    Muster:
      i2: kleiner Körper oben, langer unterer Schatten (≥55% der Spanne),
          kaum oberer Schatten (≤15%), i2-Tief ist das niedrigste Tief aller 3 Kerzen.
    """
    l = u.l
    # Abwärtskontext
    if not (u.is_bearish(0) and u.is_bearish(1) and u.c[1] < u.c[0]):
        return False
    # Muster
    b2   = u.body(2)
    ls2  = u.lower_shadow(2)
    us2  = u.upper_shadow(2)
    span = u.h[2] - l[2]

    is_lowest_low = l[2] == min(l[0], l[1], l[2])

    return (
        span > 0
        and b2 / span < 0.35
        and b2 / span > 0.02
        and ls2 >= span * 0.55
        and us2 <= span * 0.15
        and is_lowest_low
    )


# ══════════════════════════════════════════════════════════════════════════════
# Öffentliche API
# ══════════════════════════════════════════════════════════════════════════════

def detect_candle_pattern(df: pd.DataFrame) -> dict:
    """
    Erkennt bullische Umkehrformationen aus abgeschlossenen Kerzen.

    Die letzte Kerze im DataFrame wird als laufend (unfertig) betrachtet
    und grundsätzlich ausgeschlossen. Die Analyse arbeitet auf df.iloc[:-1].

    Benötigt mindestens 5 Zeilen (4 abgeschlossene + 1 laufende).

    Parameter:
        df: DataFrame mit Spalten open, high, low, close (lowercase).

    Rückgabe:
        dict mit:
          pattern  (str | None) – Name des Musters oder None
          strength (int)        – Stärke 0–5
    """
    # Letzte (laufende) Kerze ausschließen
    closed = df.iloc[:-1]

    if len(closed) < 4:
        return {"pattern": None, "strength": 0}

    # ── 4-Kerzen-Muster: letzte 4 abgeschlossene Kerzen (i0–i3) ──────────
    w4 = closed.iloc[-4:].reset_index(drop=True)
    u4 = _CandleUtils(
        w4["open"].values, w4["high"].values,
        w4["low"].values,  w4["close"].values,
    )

    if _detect_abandoned_baby(u4):
        return {"pattern": "Bullish Abandoned Baby", "strength": 5}

    if _detect_morning_star(u4):
        return {"pattern": "Morning Star", "strength": 4}

    # ── 3-Kerzen-Muster: letzte 3 abgeschlossene Kerzen (i0–i2) ──────────
    w3 = closed.iloc[-3:].reset_index(drop=True)
    u3 = _CandleUtils(
        w3["open"].values, w3["high"].values,
        w3["low"].values,  w3["close"].values,
    )

    if _detect_engulfing(u3):
        return {"pattern": "Bullish Engulfing", "strength": 3}

    if _detect_piercing(u3):
        return {"pattern": "Piercing Line", "strength": 2}

    if _detect_hammer(u3):
        return {"pattern": "Hammer", "strength": 1}

    return {"pattern": None, "strength": 0}

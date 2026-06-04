"""
bearish_candle_pattern_reversal.py – Bearische Umkehrformationen
Gekapselte Erkennung unabhängig von den Trendindikatoren.

Unterstützte Muster (Priorität / Stärke):
  1. Bearish Abandoned Baby  (5) – 4-Kerzen: i0–i1 Aufwärtskontext, i2 Doji+Gap, i3 bearish+Gap
  2. Dark Cloud Cover        (4) – 3-Kerzen: i0–i1 Aufwärtskontext, i2 durchdringt i1 von oben
  3. Bearish Engulfing       (3) – 3-Kerzen: i0–i1 Aufwärtskontext, i2 umschließt i1 bearish
  4. Shooting Star           (2) – 3-Kerzen: i0–i1 Aufwärtskontext, i2 langer oberer Schatten

Jedes Muster ist in einer eigenen Funktion gekapselt.
Gemeinsame Hilfsfunktionen sind in _CandleUtils ausgelagert.
"""

import pandas as pd


# ══════════════════════════════════════════════════════════════════════════════
# Utilities (identisch mit bullish – eigene Kopie für Unabhängigkeit)
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
# Muster-Erkennungen
# ══════════════════════════════════════════════════════════════════════════════

def _detect_bearish_abandoned_baby(u: _CandleUtils) -> bool:
    """
    Bearish Abandoned Baby – 4 Kerzen (i0–i3).

    Aufwärtskontext: i0 bullish, i1 bullish und schließt über i0.
    Muster:
      i1: bullish (letzte Aufwärtskerze vor dem Wendepunkt)
      i2: Doji mit Gap nach oben (i2-Tief über i1-Hoch)
      i3: bearish mit Gap nach unten (i3-Hoch unter i2-Tief)
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Aufwärtskontext
    if not (u.is_bullish(0) and u.is_bullish(1) and c[1] > c[0]):
        return False
    return (
        u.is_bullish(1)
        and u.is_doji(2, tolerance=0.35)
        and l[2] > h[1]       # Gap nach oben: i2-Tief über i1-Hoch
        and u.is_bearish(3)
        and h[3] < l[2]       # Gap nach unten: i3-Hoch unter i2-Tief
    )


def _detect_dark_cloud_cover(u: _CandleUtils) -> bool:
    """
    Dark Cloud Cover – 3 Kerzen (i0–i2).

    Aufwärtskontext: i0 bullish, i1 bullish und schließt über i0.
    Muster:
      i1: große bullische Kerze
      i2: bearish, öffnet über Hoch i1, schließt unter 50% des i1-Körpers
          aber nicht unter i1-Open (sonst Engulfing)
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Aufwärtskontext
    if not (u.is_bullish(0) and u.is_bullish(1) and c[1] > c[0]):
        return False
    i1_midpoint = u.midpoint(1)
    return (
        u.is_bullish(1)
        and u.is_bearish(2)
        and o[2] > h[1]           # öffnet über Hoch i1
        and c[2] < i1_midpoint    # schließt unter 50% des i1-Körpers
        and c[2] >= o[1]          # schließt nicht unter i1-Open (sonst Engulfing)
    )


def _detect_bearish_engulfing(u: _CandleUtils) -> bool:
    """
    Bearish Engulfing – 3 Kerzen (i0–i2).

    Aufwärtskontext: i0 bullish, i1 bullish und schließt über i0.
    Muster:
      i1: bullish
      i2: bearish, umschließt i1 vollständig, schließt unter i0-Schluss
    """
    o, h, l, c = u.o, u.h, u.l, u.c
    # Aufwärtskontext
    if not (u.is_bullish(0) and u.is_bullish(1) and c[1] > c[0]):
        return False
    return (
        u.is_bullish(1)
        and u.is_bearish(2)
        and o[2] >= c[1]          # öffnet über/gleich Schluss i1
        and c[2] <= o[1]          # schließt unter/gleich Öffnung i1
        and u.body(2) > u.body(1)
        and c[2] < c[0]           # unter i0-Schluss – echte Umkehr
    )


def _detect_shooting_star(u: _CandleUtils) -> bool:
    """
    Shooting Star – 3 Kerzen (i0–i2).

    Aufwärtskontext: i0 bullish, i1 bullish und schließt über i0.
    Muster:
      i2: kleiner Körper unten, langer oberer Schatten (≥55% der Spanne),
          kaum unterer Schatten (≤15%), i2-Hoch ist das höchste Hoch aller 3 Kerzen.
    """
    h = u.h
    # Aufwärtskontext
    if not (u.is_bullish(0) and u.is_bullish(1) and u.c[1] > u.c[0]):
        return False

    b2   = u.body(2)
    us2  = u.upper_shadow(2)
    ls2  = u.lower_shadow(2)
    span = h[2] - u.l[2]

    is_highest_high = h[2] == max(h[0], h[1], h[2])

    return (
        span > 0
        and b2 / span < 0.35
        and b2 / span > 0.02
        and us2 >= span * 0.55    # langer oberer Schatten
        and ls2 <= span * 0.15    # kaum unterer Schatten
        and is_highest_high
    )


# ══════════════════════════════════════════════════════════════════════════════
# Öffentliche API
# ══════════════════════════════════════════════════════════════════════════════

def detect_bearish_candle_pattern(df: pd.DataFrame) -> dict:
    """
    Erkennt bearische Umkehrformationen aus abgeschlossenen Kerzen.

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

    if _detect_bearish_abandoned_baby(u4):
        return {"pattern": "Bearish Abandoned Baby", "strength": 5}

    # ── 3-Kerzen-Muster: letzte 3 abgeschlossene Kerzen (i0–i2) ──────────
    w3 = closed.iloc[-3:].reset_index(drop=True)
    u3 = _CandleUtils(
        w3["open"].values, w3["high"].values,
        w3["low"].values,  w3["close"].values,
    )

    if _detect_dark_cloud_cover(u3):
        return {"pattern": "Dark Cloud Cover", "strength": 4}

    if _detect_bearish_engulfing(u3):
        return {"pattern": "Bearish Engulfing", "strength": 3}

    if _detect_shooting_star(u3):
        return {"pattern": "Shooting Star", "strength": 2}

    return {"pattern": None, "strength": 0}

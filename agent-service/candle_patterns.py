"""
candle_patterns.py – Bullische Umkehrformationen
Gekapselte Erkennung unabhängig von den Trendindikatoren.

Unterstützte Muster (Priorität / Stärke):
  1. Bullish Abandoned Baby  (5) – Gap-Doji-Gap
  2. Morning Star            (4) – 3-Kerzen Umkehr
  3. Bullish Engulfing       (3) – 2-Kerzen Umkehr
  4. Piercing Line           (2) – 2-Kerzen Umkehr
  5. Hammer                  (1) – 1-Kerzen Muster

Abwärtskontext-Prüfung:
  Verwendet 5 Kerzen – die ersten 2 (i0, i1) als Trendkontext,
  die letzten 3 (i2, i3, i4) für die Mustererkennung.
  Ein Muster wird NUR erkannt wenn ein echter lokaler Abwärtstrend vorliegt.
"""

import pandas as pd


def detect_candle_pattern(df: pd.DataFrame) -> dict:
    """
    Erkennt bullische Umkehrformationen in den letzten 5 Kerzen.

    Parameter:
        df: DataFrame mit Spalten open, high, low, close (lowercase).
            Mindestens 5 Zeilen erforderlich.

    Rückgabe:
        dict mit:
          pattern  (str | None) – Name des Musters oder None
          strength (int)        – Stärke 0–5
    """
    if len(df) < 5:
        return {"pattern": None, "strength": 0}

    window = df.iloc[-5:].reset_index(drop=True)
    o = window["open"].values
    h = window["high"].values
    l = window["low"].values
    c = window["close"].values

    i0, i1, i2, i3, i4 = 0, 1, 2, 3, 4

    # ── Hilfsfunktionen ───────────────────────────────────────────────────

    def body(i: int) -> float:
        return abs(c[i] - o[i])

    def is_bearish(i: int) -> bool:
        return c[i] < o[i]

    def is_bullish(i: int) -> bool:
        return c[i] > o[i]

    def lower_shadow(i: int) -> float:
        return min(o[i], c[i]) - l[i]

    def upper_shadow(i: int) -> float:
        return h[i] - max(o[i], c[i])

    def is_doji(i: int, tolerance: float = 0.1) -> bool:
        """Doji: Körper sehr klein im Verhältnis zur Gesamtspanne."""
        span = h[i] - l[i]
        return span > 0 and body(i) / span < tolerance

    # ── Abwärtskontext prüfen ─────────────────────────────────────────────
    # Bedingung A: 3 aufeinanderfolgende fallende Schlusskurse
    trend_falling = c[i0] > c[i1] > c[i2]

    # Bedingung B: mind. 3 der ersten 4 Kerzen bearish
    bearish_count  = sum(is_bearish(i) for i in [i0, i1, i2, i3])
    mostly_bearish = bearish_count >= 3

    # Bedingung C: Gesamtbewegung abwärts – letzter Schluss unter erstem
    overall_decline = c[i4] < c[i0]

    # Ohne klaren Abwärtskontext kein bullisches Umkehrmuster
    if not (overall_decline and (trend_falling or mostly_bearish)):
        return {"pattern": None, "strength": 0}

    # ── 1. Bullish Abandoned Baby ─────────────────────────────────────────
    # i2: bearish | i3: Doji mit Gap nach unten | i4: bullish mit Gap nach oben
    # Doji-Toleranz 35% da echte Gaps das definitorische Merkmal sind
    if (
        is_bearish(i2)
        and is_doji(i3, tolerance=0.35)
        and h[i3] < l[i2]        # Gap nach unten: i3-Hoch unter i2-Tief
        and is_bullish(i4)
        and l[i4] > h[i3]        # Gap nach oben: i4-Tief über i3-Hoch
    ):
        return {"pattern": "Bullish Abandoned Baby", "strength": 5}

    # ── 2. Morning Star ───────────────────────────────────────────────────
    # i2: große bearishe Kerze | i3: kleiner Körper (Stern) | i4: bullish über Mitte i2
    avg_body    = (body(i2) + body(i3) + body(i4)) / 3
    midpoint_i2 = (o[i2] + c[i2]) / 2
    if (
        is_bearish(i2) and body(i2) > avg_body * 0.8
        and body(i3) < body(i2) * 0.4
        and max(o[i3], c[i3]) < min(o[i2], c[i2])  # Stern unter i2-Körper
        and is_bullish(i4)
        and c[i4] > midpoint_i2                      # i4 schließt über Mitte i2
    ):
        return {"pattern": "Morning Star", "strength": 4}

    # ── 3. Bullish Engulfing ──────────────────────────────────────────────
    # i3: bearish | i4: bullish, umschließt i3 vollständig
    # i4-Schluss über i2-Schluss: echter Aufwärtsimpuls, kein Rücksetzer
    if (
        is_bearish(i3)
        and is_bullish(i4)
        and o[i4] <= c[i3]        # Öffnet unter/gleich Schluss i3
        and c[i4] >= o[i3]        # Schließt über/gleich Öffnung i3
        and body(i4) > body(i3)
        and c[i4] > c[i2]         # Über Schluss i2 – echte Umkehr
    ):
        return {"pattern": "Bullish Engulfing", "strength": 3}

    # ── 4. Piercing Line ─────────────────────────────────────────────────
    # i3: bearish | i4: bullish, öffnet unter Tief i3, schließt über 50% i3-Körper
    # i4-Schluss über i2-Schluss aber unter Öffnung i3 (sonst Engulfing)
    i3_midpoint = (o[i3] + c[i3]) / 2
    if (
        is_bearish(i3)
        and is_bullish(i4)
        and o[i4] < l[i3]
        and c[i4] > i3_midpoint
        and c[i4] <= o[i3]
        and c[i4] > c[i2]
    ):
        return {"pattern": "Piercing Line", "strength": 2}

    # ── 5. Hammer ────────────────────────────────────────────────────────
    # i4: kleiner Körper oben, langer unterer Schatten, kaum oberer Schatten.
    # i4-Tief muss das niedrigste Tief aller 5 Kerzen sein (lokaler Tiefpunkt).
    b4   = body(i4)
    ls4  = lower_shadow(i4)
    us4  = upper_shadow(i4)
    span = h[i4] - l[i4]

    is_lowest_low = l[i4] == min(l[i0], l[i1], l[i2], l[i3], l[i4])

    if (
        span > 0
        and b4 / span < 0.35
        and b4 / span > 0.02
        and ls4 >= span * 0.55
        and us4 <= span * 0.15
        and is_lowest_low
    ):
        return {"pattern": "Hammer", "strength": 1}

    return {"pattern": None, "strength": 0}

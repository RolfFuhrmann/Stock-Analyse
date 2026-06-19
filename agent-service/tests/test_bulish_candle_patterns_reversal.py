"""
tests/test_bulish_candle_patterns_reversal.py
Aktualisierte Tests für die bullische Kerzenformations-Erkennung (5-Kerzen-Trend).
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pandas as pd
import pytest
from bullish_candle_pattern_reversal import detect_candle_pattern


def make_bar(o: float, h: float, l: float, c: float) -> dict:
    return {"open": o, "high": h, "low": l, "close": c}


def make_df_with_live_candle(bars: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(bars)
    dummy_live_candle = pd.DataFrame([make_bar(100, 101, 99, 100)])
    return pd.concat([df, dummy_live_candle], ignore_index=True)


# Feste Basis für Abwärtstrend: 5 Kerzen, die kontinuierlich fallen (Basis: Körper)
# Indizes innerhalb der Listenverkettung: K0 bis K4 bilden den Trend-Kontext
PERFECT_LONG_DOWNTREND = [
    make_bar(140, 142, 135, 136),   # K0
    make_bar(136, 138, 130, 131),   # K1
    make_bar(131, 133, 125, 126),   # K2
    make_bar(126, 128, 120, 121),   # K3
    make_bar(121, 123, 115, 116),   # K4
]


class TestBullishAbandonedBaby:
    def test_erkannt(self):
        # Muster startet nach den 5 Trendkerzen
        pattern_bars = [
            make_bar(116, 118, 110, 111),   # K5 (Relativ: -3) Bearish
            make_bar(100, 102, 99, 101),    # K6 (Relativ: -2) Doji mit Gap nach unten
            make_bar(106, 112, 105, 111),   # K7 (Relativ: -1) Bullish mit Gap nach oben
        ]
        df = make_df_with_live_candle(PERFECT_LONG_DOWNTREND + pattern_bars)
        r = detect_candle_pattern(df, is_live_data=True)
        assert r["pattern"] == "Bullish Abandoned Baby"
        assert r["strength"] == 5


class TestMorningStar:
    def test_erkannt(self):
        # Wir ändern das K6 (den Star) in ein Doji (Open=Close oder extrem kleiner Körper)
        # Damit kann K7 (die grüne Kerze) nicht mehr den Körper von K6 "verschlingen",
        # weil ein Doji keinen signifikanten Körper zum Umschließen hat.
        pattern_bars = [
            make_bar(110, 112, 108, 109),   # -3: Rote Bärenkerze
            make_bar(107, 108, 106, 107),   # -2: Kleinerer Körper
            make_bar(107, 115, 106, 112),   # -1: Grüne Kerze, schließt über Midpoint von -3        
        ]
        df = make_df_with_live_candle(PERFECT_LONG_DOWNTREND + pattern_bars)
        r = detect_candle_pattern(df, is_live_data=True)
        assert r["pattern"] == "Morning Star"
        assert r["strength"] == 4


class TestBullishEngulfing:
    def test_erkannt(self):
        pattern_bars = [
            make_bar(116, 118, 112, 113),   # K5 (Relativ: -2) Bearish
            make_bar(112, 119, 111, 118),   # K6 (Relativ: -1) Bullish umschließt K5
        ]
        df = make_df_with_live_candle(PERFECT_LONG_DOWNTREND + pattern_bars)
        r = detect_candle_pattern(df, is_live_data=True)
        assert r["pattern"] == "Bullish Engulfing"
        assert r["strength"] == 3


class TestPiercingLine:
    def test_erkannt(self):
        pattern_bars = [
            make_bar(116, 118, 111, 112),   # K5 (Relativ: -2) Bearish
            make_bar(110, 116, 109, 115),   # K6 (Relativ: -1) Schließt über 50%-Linie von K5
        ]
        df = make_df_with_live_candle(PERFECT_LONG_DOWNTREND + pattern_bars)
        r = detect_candle_pattern(df, is_live_data=True)
        assert r["pattern"] == "Piercing Line"
        assert r["strength"] == 2


class TestHammer:
    def test_erkannt(self):
        pattern_bars = [
            make_bar(115, 115.5, 109, 114.8), # K5 (Relativ: -1) Langer Schatten UNTEN
        ]
        df = make_df_with_live_candle(PERFECT_LONG_DOWNTREND + pattern_bars)
        r = detect_candle_pattern(df, is_live_data=True)
        assert r["pattern"] == "Hammer"
        assert r["strength"] == 1


class TestEdgeCases:
    def test_zu_wenig_daten(self):
        # 5 Kerzen reichen für keinen Hammer (benötigt 5 + 1 = 6)
        short_bars = [
            make_bar(120, 122, 115, 116),
            make_bar(116, 118, 110, 112),
            make_bar(112, 114, 108, 109),
            make_bar(109, 111, 105, 106),
            make_bar(106, 108, 102, 103),
        ]
        df = make_df_with_live_candle(short_bars)
        r = detect_candle_pattern(df, is_live_data=True)
        assert r["pattern"] is None
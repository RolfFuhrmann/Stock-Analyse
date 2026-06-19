"""
tests/test_bearish_candle_pattern_reversal.py
Aktualisierte Tests für die bearische Kerzenformations-Erkennung (5-Kerzen-Trend).
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pandas as pd
import pytest
from bearish_candle_pattern_reversal import detect_bearish_pattern


def make_bar(o: float, h: float, l: float, c: float) -> dict:
    return {"open": o, "high": h, "low": l, "close": c}


def make_df_with_live_candle(bars: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(bars)
    dummy_live_candle = pd.DataFrame([make_bar(100, 101, 99, 100)])
    return pd.concat([df, dummy_live_candle], ignore_index=True)


# Feste Basis für Aufwärtstrend: 5 Kerzen, die kontinuierlich steigen (Basis: Körper)
# Indizes innerhalb der Listenverkettung: K0 bis K4 bilden den Trend-Kontext
PERFECT_LONG_UPTREND = [
    make_bar(100, 105, 99, 104),    # K0
    make_bar(104, 110, 103, 109),   # K1
    make_bar(109, 115, 108, 114),   # K2
    make_bar(114, 120, 113, 119),   # K3
    make_bar(119, 125, 118, 124),   # K4
]


class TestBearishAbandonedBaby:
    def test_erkannt(self):
        pattern_bars = [
            make_bar(124, 130, 123, 129),   # K5 (Relativ: -3) Bullish
            make_bar(132, 133, 131, 132.5), # K6 (Relativ: -2) Doji mit Gap nach oben
            make_bar(130, 130.5, 123, 124), # K7 (Relativ: -1) Bearish mit Gap nach unten
        ]
        df = make_df_with_live_candle(PERFECT_LONG_UPTREND + pattern_bars)
        r = detect_bearish_pattern(df, is_live_data=True)
        assert r["pattern"] == "Bearish Abandoned Baby"
        assert r["strength"] == 5


class TestDarkCloudCover:
    def test_erkannt(self):
        pattern_bars = [
            make_bar(124, 131, 123, 130),   # K5 (Relativ: -2) Bullish
            make_bar(132, 133, 125, 126),   # K6 (Relativ: -1) Bearish dringt tief ein
        ]
        df = make_df_with_live_candle(PERFECT_LONG_UPTREND + pattern_bars)
        r = detect_bearish_pattern(df, is_live_data=True)
        assert r["pattern"] == "Dark Cloud Cover"
        assert r["strength"] == 4


class TestBearishEngulfing:
    def test_erkannt(self):
        pattern_bars = [
            make_bar(124, 130, 123, 129),   # K5 (Relativ: -2) Bullish
            make_bar(12cd9, 130, 122, 123),   # K6 (Relativ: -1) Bearish umschließt K5
        ]
        df = make_df_with_live_candle(PERFECT_LONG_UPTREND + pattern_bars)
        r = detect_bearish_pattern(df, is_live_data=True)
        assert r["pattern"] == "Bearish Engulfing"
        assert r["strength"] == 3


class TestShootingStar:
    def test_erkannt(self):
        # Wichtig: Der Shooting Star benötigt eine signifikante Handelsspanne, 
        # um den historischen Rauschfilter (avg_historical_span * 0.5) zu passieren!
        pattern_bars = [
            make_bar(125, 135, 124.5, 125.5), # K5 (Relativ: -1) Großer oberer Docht!
        ]
        df = make_df_with_live_candle(PERFECT_LONG_UPTREND + pattern_bars)
        r = detect_bearish_pattern(df, is_live_data=True)
        assert r["pattern"] == "Shooting Star"
        assert r["strength"] == 2


class TestBearishEdgeCases:
    def test_zu_wenig_daten(self):
        # 5 Kerzen reichen für keinen Shooting Star (benötigt 5 + 1 = 6)
        short_bars = [
            make_bar(100, 102, 99, 101),
            make_bar(101, 103, 100, 102),
            make_bar(102, 104, 101, 103),
            make_bar(103, 105, 102, 104),
            make_bar(104, 106, 103, 105),
        ]
        df = make_df_with_live_candle(short_bars)
        r = detect_bearish_pattern(df, is_live_data=True)
        assert r["pattern"] is None
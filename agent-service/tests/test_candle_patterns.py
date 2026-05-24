"""
tests/test_candle_patterns.py
Tests für die Kerzenformations-Erkennung in candle_patterns.py

Ausführen:
    cd agent-service
    python3 -m pytest tests/ -v --tb=short

Abdeckung anzeigen:
    python3 -m pytest tests/ --cov=candle_patterns --cov=trend_indicators --cov-report=term-missing
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pandas as pd
import pytest
from candle_patterns import detect_candle_pattern


# ── Hilfsfunktionen ───────────────────────────────────────────────────────────

def make_bar(o: float, h: float, l: float, c: float) -> dict:
    return {"open": o, "high": h, "low": l, "close": c}


def make_df(bars: list[dict]) -> pd.DataFrame:
    return pd.DataFrame(bars)


# ── Bullish Abandoned Baby (4 Kerzen: i0–i3) ─────────────────────────────────

class TestBullishAbandonedBaby:
    def test_erkannt(self):
        # i0: bearish, i1: bearish unter i0
        # i2: Doji mit Gap nach unten (Hoch < i1-Tief)
        # i3: bullish mit Gap nach oben (Tief > i2-Hoch)
        df = make_df([
            make_bar(115, 116, 113, 114),   # i0: bearish
            make_bar(114, 115, 108, 109),   # i1: bearish, Close=109 < 114 ✓, Tief=108
            make_bar(104, 106, 102, 104),   # i2: Doji, Hoch=106 < i1-Tief=108 ✓
            make_bar(108, 112, 107, 111),   # i3: bullish, Tief=107 > i2-Hoch=106 ✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Bullish Abandoned Baby"
        assert r["strength"] == 5

    def test_kein_muster_ohne_abwaertskontext(self):
        """i0 bullish → kein Abwärtskontext."""
        df = make_df([
            make_bar(110, 116, 109, 115),   # i0: bullish
            make_bar(114, 115, 108, 109),   # i1: bearish
            make_bar(104, 106, 102, 104),   # i2: Doji
            make_bar(108, 112, 107, 111),   # i3: bullish
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Abandoned Baby"

    def test_kein_muster_ohne_gap_unten(self):
        """i2-Hoch nicht unter i1-Tief → kein Gap nach unten."""
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 108, 109),   # i1: Tief=108
            make_bar(107, 110, 105, 107),   # i2: Hoch=110 > i1-Tief=108 → kein Gap
            make_bar(111, 114, 110, 113),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Abandoned Baby"

    def test_kein_muster_ohne_gap_oben(self):
        """i3-Tief nicht über i2-Hoch → kein Gap nach oben."""
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 108, 109),
            make_bar(104, 106, 102, 104),   # i2: Hoch=106
            make_bar(105, 112, 104, 111),   # i3: Tief=104 < i2-Hoch=106 → kein Gap
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Abandoned Baby"


# ── Morning Star (4 Kerzen: i0–i3) ───────────────────────────────────────────

class TestMorningStar:
    def test_erkannt(self):
        # i0: bearish, i1: bearish, großer Körper
        # i2: kleiner Körper unterhalb i1-Körper
        # i3: bullish, schließt über Mitte i1
        df = make_df([
            make_bar(115, 116, 112, 113),   # i0: bearish
            make_bar(113, 114, 103, 104),   # i1: bearish groß, Close=104, Mitte=108.5
            make_bar(103, 104, 101, 102),   # i2: Stern, max(o,c)=103 < min(o,c)[i1]=104 ✓
            make_bar(102, 113, 101, 112),   # i3: bullish, Close=112 > 108.5 ✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Morning Star"
        assert r["strength"] == 4

    def test_kein_muster_ohne_abwaertskontext(self):
        """i1 schließt nicht unter i0."""
        df = make_df([
            make_bar(100, 116, 99, 113),    # i0: bearish, Close=113
            make_bar(113, 115, 110, 114),   # i1: bearish, Close=114 > i0-Close=113 → kein Kontext
            make_bar(103, 104, 101, 102),
            make_bar(102, 113, 101, 112),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Morning Star"

    def test_kein_muster_stern_nicht_unter_i1(self):
        """i2-Körper nicht unterhalb i1-Körper."""
        df = make_df([
            make_bar(115, 116, 112, 113),
            make_bar(113, 114, 103, 104),   # i1: min(o,c)=104
            make_bar(106, 108, 105, 107),   # i2: max(o,c)=107 > 104 → nicht unter i1
            make_bar(102, 113, 101, 112),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Morning Star"

    def test_kein_muster_i3_nicht_ueber_mitte_i1(self):
        """i3 schließt nicht über Mitte i1."""
        df = make_df([
            make_bar(115, 116, 112, 113),
            make_bar(113, 114, 103, 104),   # i1: Mitte=(113+104)/2=108.5
            make_bar(103, 104, 101, 102),
            make_bar(102, 107, 101, 106),   # i3: Close=106 < Mitte 108.5
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Morning Star"


# ── Bullish Engulfing (3 Kerzen: i0–i2) ──────────────────────────────────────

class TestBullishEngulfing:
    def test_erkannt(self):
        # Vorlauf-Kerze + i0: bearish, i1: bearish unter i0, i2: bullish umschließt i1
        df = make_df([
            make_bar(122, 123, 120, 121),   # Vorlauf
            make_bar(121, 122, 118, 119),   # i0: bearish, Close=119
            make_bar(119, 120, 112, 113),   # i1: bearish, Close=113 < 119 ✓
            make_bar(112, 122, 111, 120),   # i2: bullish, umschließt i1, Close=120 > i0-Close=119 ✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Bullish Engulfing"
        assert r["strength"] == 3

    def test_kein_engulfing_ohne_abwaertskontext(self):
        """i1 schließt nicht unter i0."""
        df = make_df([
            make_bar(112, 123, 111, 121),   # Vorlauf
            make_bar(110, 121, 109, 119),   # i0: bearish, Close=119
            make_bar(119, 121, 112, 120),   # i1: bearish, Close=120 > 119 → kein Kontext
            make_bar(112, 122, 111, 121),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Engulfing"

    def test_kein_engulfing_i2_unter_i0_schluss(self):
        """i2-Schluss nicht über i0-Schluss → kein echter Impuls."""
        df = make_df([
            make_bar(111, 112, 110, 111),   # Vorlauf
            make_bar(110, 111, 108, 109),   # i0: Close=109
            make_bar(109, 110, 102, 103),   # i1: bearish, Close=103
            make_bar(101, 108, 100, 108),   # i2: bullish, Close=108 < i0-Close=109
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Engulfing"

    def test_kein_engulfing_aufwaertstrend(self):
        df = make_df([
            make_bar(99, 101, 98, 100),
            make_bar(100, 102, 99, 101),
            make_bar(101, 103, 100, 102),
            make_bar(101, 106, 100, 105),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None

    def test_kein_engulfing_koerper_kleiner(self):
        """i2-Körper kleiner als i1-Körper."""
        df = make_df([
            make_bar(121, 122, 119, 120),   # Vorlauf
            make_bar(120, 121, 118, 119),   # i0
            make_bar(119, 120, 112, 113),   # i1
            make_bar(113, 116, 112, 115),   # i2: Körper=2, i1-Körper=6 → zu klein
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Engulfing"


# ── Piercing Line (3 Kerzen: i0–i2) ──────────────────────────────────────────

class TestPiercingLine:
    def test_erkannt(self):
        # Vorlauf + i0: bearish, i1: bearish unter i0
        # i2: öffnet unter i1-Tief, schließt über 50% i1-Körper, nicht über i1-Open
        df = make_df([
            make_bar(116, 117, 114, 115),   # Vorlauf
            make_bar(115, 116, 113, 114),   # i0: bearish, Close=114
            make_bar(114, 115, 106, 108),   # i1: bearish, Open=114, Close=108, Mitte=111, Tief=106
            make_bar(105, 115, 104, 113),   # i2: Open=105<106✓, Close=113>111✓, <=114✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Piercing Line"
        assert r["strength"] == 2

    def test_kein_piercing_ohne_abwaertskontext(self):
        """i1 schließt nicht unter i0."""
        df = make_df([
            make_bar(109, 117, 108, 115),   # Vorlauf
            make_bar(108, 116, 107, 114),   # i0: bearish, Close=114
            make_bar(114, 115, 106, 115),   # i1: bullish Close=115 > 114 → kein Kontext
            make_bar(105, 115, 104, 113),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"

    def test_kein_piercing_oeffnung_nicht_unter_tief(self):
        """i2 öffnet nicht unter i1-Tief."""
        df = make_df([
            make_bar(116, 117, 114, 115),   # Vorlauf
            make_bar(115, 116, 113, 114),   # i0
            make_bar(114, 115, 106, 108),   # i1: Tief=106
            make_bar(107, 115, 106, 113),   # i2: Open=107 > 106 → nicht unter Tief
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"

    def test_kein_piercing_schluss_unter_mitte(self):
        """i2 schließt nicht über 50% des i1-Körpers."""
        df = make_df([
            make_bar(116, 117, 114, 115),   # Vorlauf
            make_bar(115, 116, 113, 114),   # i0
            make_bar(114, 115, 106, 108),   # i1: Mitte=111
            make_bar(105, 112, 104, 109),   # i2: Close=109 < 111
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"

    def test_piercing_wird_engulfing(self):
        """i2 schließt über i1-Open → Engulfing statt Piercing."""
        df = make_df([
            make_bar(116, 117, 114, 115),   # Vorlauf
            make_bar(115, 116, 113, 114),   # i0
            make_bar(114, 115, 106, 108),   # i1: Open=114
            make_bar(105, 117, 104, 115),   # i2: Close=115 > i1-Open=114 → Engulfing
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Bullish Engulfing"

    def test_kein_piercing_i1_bullish(self):
        """i1 bullish → Muster ungültig."""
        df = make_df([
            make_bar(116, 117, 114, 115),   # Vorlauf
            make_bar(115, 116, 113, 114),   # i0
            make_bar(108, 115, 107, 114),   # i1: bullish
            make_bar(105, 115, 104, 113),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"


# ── Hammer (3 Kerzen: i0–i2) ─────────────────────────────────────────────────

class TestHammer:
    def test_erkannt(self):
        # Vorlauf + i0: bearish, i1: bearish unter i0, i2: Hammer-Form, tiefstes Tief
        df = make_df([
            make_bar(109, 110, 107, 108),   # Vorlauf
            make_bar(108, 109, 106, 107),   # i0: bearish
            make_bar(107, 108, 105, 106),   # i1: bearish, Close=106 < 107 ✓
            make_bar(105, 105.5, 99, 104.8), # i2: Hammer, Tief=99 → niedrigstes ✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Hammer"
        assert r["strength"] == 1

    def test_kein_hammer_ohne_abwaertskontext(self):
        """i1 schließt nicht unter i0."""
        df = make_df([
            make_bar(106, 110, 105, 107),   # Vorlauf
            make_bar(105, 109, 104, 106),   # i0: bearish, Close=106
            make_bar(106, 108, 104, 107),   # i1: bearish, Close=107 > i0-Close=106 → kein Kontext
            make_bar(105, 105.5, 99, 104.8),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Hammer"

    def test_kein_hammer_nicht_tiefstes_tief(self):
        """i1 hat tieferes Tief als i2."""
        df = make_df([
            make_bar(109, 110, 107, 108),   # Vorlauf
            make_bar(108, 109, 106, 107),   # i0
            make_bar(107, 108, 97, 106),    # i1: Tief=97 → tiefer als Hammer
            make_bar(105, 105.5, 99, 104.8),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Hammer"

    def test_kein_hammer_koerper_zu_gross(self):
        """Körper zu groß im Verhältnis zur Spanne."""
        df = make_df([
            make_bar(109, 110, 107, 108),   # Vorlauf
            make_bar(108, 109, 106, 107),   # i0
            make_bar(107, 108, 105, 106),   # i1
            make_bar(100, 106, 96, 105),    # i2: Körper=5, Spanne=10 → 50% > 35%
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Hammer"

    def test_kein_hammer_aufwaertstrend(self):
        df = make_df([
            make_bar(99, 101, 98, 100),
            make_bar(100, 102, 99, 101),
            make_bar(101, 103, 100, 102),
            make_bar(102, 102.5, 96, 101.8),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None


# ── Allgemeine Randfälle ──────────────────────────────────────────────────────

class TestRandfaelle:
    def test_zu_wenig_kerzen(self):
        df = make_df([
            make_bar(108, 109, 106, 107),
            make_bar(107, 108, 105, 106),
            make_bar(106, 107, 104, 105),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None
        assert r["strength"] == 0

    def test_staerke_null_ohne_muster(self):
        df = make_df([
            make_bar(100, 102, 99, 101),
            make_bar(101, 103, 100, 102),
            make_bar(102, 104, 101, 103),
            make_bar(103, 105, 102, 104),
        ])
        r = detect_candle_pattern(df)
        assert r["strength"] == 0

    def test_genau_4_kerzen_reichen(self):
        """4 Kerzen sind das Minimum – kein Fehler."""
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 108, 109),
            make_bar(104, 106, 102, 104),
            make_bar(108, 112, 107, 111),
        ])
        r = detect_candle_pattern(df)
        assert isinstance(r["pattern"], (str, type(None)))
        assert isinstance(r["strength"], int)

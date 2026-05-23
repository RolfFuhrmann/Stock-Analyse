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


# ── Korrekte Erkennung im Abwärtstrend ───────────────────────────────────────

class TestBullishAbandonedBaby:
    def test_erkannt_im_abwaertstrend(self):
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(112, 113, 108, 109),  # i2: bearish
            make_bar(105, 106, 103, 104),  # i3: Doji mit Gap nach unten
            make_bar(108, 112, 107, 111),  # i4: bullish mit Gap nach oben
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Bullish Abandoned Baby"
        assert r["strength"] == 5

    def test_kein_muster_ohne_gap_unten(self):
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(112, 113, 108, 109),
            make_bar(108, 110, 107, 109),  # Kein Gap – Doji-Hoch=110 > i2-Tief=108
            make_bar(110, 114, 109, 113),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Abandoned Baby"

    def test_kein_muster_ohne_gap_oben(self):
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(112, 113, 108, 109),
            make_bar(105, 106, 103, 104),  # Gap nach unten vorhanden
            make_bar(105, 110, 104, 109),  # Kein Gap nach oben – Tief=104 < Doji-Hoch=106
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Abandoned Baby"


class TestMorningStar:
    def test_erkannt_im_abwaertstrend(self):
        df = make_df([
            make_bar(115, 116, 112, 113),
            make_bar(113, 114, 110, 111),
            make_bar(110, 111, 103, 104),  # i2: große bearishe Kerze
            make_bar(103, 104, 101, 102),  # i3: kleiner Körper (Stern)
            make_bar(102, 112, 101, 111),  # i4: bullish über Mitte i2
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Morning Star"
        assert r["strength"] == 4

    def test_kein_muster_wenn_stern_nicht_unter_k2(self):
        df = make_df([
            make_bar(115, 116, 112, 113),
            make_bar(113, 114, 110, 111),
            make_bar(110, 111, 103, 104),
            make_bar(106, 108, 105, 107),  # Stern NICHT unter K2-Körper (min=103)
            make_bar(102, 112, 101, 111),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Morning Star"

    def test_kein_muster_wenn_k4_nicht_ueber_mitte_k2(self):
        df = make_df([
            make_bar(115, 116, 112, 113),
            make_bar(113, 114, 110, 111),
            make_bar(110, 111, 103, 104),  # Mitte K2 = (110+104)/2 = 107
            make_bar(103, 104, 101, 102),
            make_bar(102, 106, 101, 105),  # K4 schließt bei 105 < Mitte 107
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Morning Star"


class TestBullishEngulfing:
    def test_erkannt_im_abwaertstrend(self):
        df = make_df([
            make_bar(120, 121, 118, 119),
            make_bar(119, 120, 116, 117),
            make_bar(117, 118, 114, 115),  # i2: Schluss=115
            make_bar(115, 116, 112, 113),  # i3: bearish
            make_bar(112, 118, 111, 117),  # i4: bullish, umschließt i3, > i2-Schluss
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Bullish Engulfing"
        assert r["strength"] == 3

    def test_kein_engulfing_k4_unter_k2_schluss(self):
        """Hauptfehler aus AAPL-Feedback: K4 tiefer als K2."""
        df = make_df([
            make_bar(110, 111, 108, 109),
            make_bar(109, 110, 106, 107),
            make_bar(107, 108, 104, 105),  # i2: Schluss=105
            make_bar(105, 106, 102, 103),  # i3: bearish
            make_bar(101, 106, 100, 104),  # i4: Schluss=104 < i2-Schluss=105
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Engulfing"

    def test_kein_engulfing_aufwaertstrend(self):
        df = make_df([
            make_bar(170, 172, 169, 171),
            make_bar(171, 173, 170, 172),
            make_bar(172, 174, 171, 173),
            make_bar(173, 174, 171, 172),
            make_bar(171, 176, 170, 175),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None

    def test_kein_engulfing_seitwaerts(self):
        df = make_df([
            make_bar(100, 102, 98, 99),
            make_bar(99, 101, 97, 100),
            make_bar(100, 102, 98, 99),
            make_bar(99, 101, 96, 97),
            make_bar(96, 102, 95, 101),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None

    def test_kein_engulfing_wenn_k4_kleiner_als_k3(self):
        df = make_df([
            make_bar(120, 121, 118, 119),
            make_bar(119, 120, 116, 117),
            make_bar(117, 118, 114, 115),
            make_bar(115, 116, 112, 113),
            make_bar(113, 115, 112, 114),  # Körper kleiner als K3
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Bullish Engulfing"


class TestPiercingLine:
    def test_erkannt_klassisch(self):
        # i2: bearish, i3: bearish mit langem Körper, schließt unter i2
        # i4: bullish, öffnet unter Tief i3, schließt über 50% i3-Körper
        # i3: Open=113, Close=108, Tief=106, Mitte=110.5
        # i4: Open=105 < Tief 106 ✓, Schluss=112 > 110.5 ✓, ≤ 113 ✓
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(113, 114, 111, 112),  # i2: bearish
            make_bar(113, 114, 106, 108),  # i3: bearish, Close=108 < i2-Close=112 ✓, Tief=106, Mitte=110.5
            make_bar(105, 114, 104, 112),  # i4: Open=105 < 106 ✓, Schluss=112 ≤ 113 ✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Piercing Line"
        assert r["strength"] == 2

    def test_erkannt_ohne_strengen_gesamtkontext(self):
        """Piercing Line erfordert nur i2 bearish + i3 schließt unter i2.
        Der übergeordnete Trendkontext (i0, i1) spielt keine Rolle mehr."""
        df = make_df([
            make_bar(100, 102, 99, 101),   # i0: bullish – kein Abwärtstrend
            make_bar(101, 103, 100, 102),  # i1: bullish
            make_bar(113, 114, 111, 112),  # i2: bearish
            make_bar(113, 114, 106, 108),  # i3: bearish, Close < i2-Close ✓, Tief=106, Mitte=110.5
            make_bar(105, 114, 104, 112),  # i4: bullish, Open=105 < 106 ✓, Schluss=112 ✓
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Piercing Line"
        assert r["strength"] == 2

    def test_kein_piercing_wenn_i4_nicht_unter_tief_i3(self):
        """i4 öffnet nicht unter dem Tief von i3."""
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(113, 114, 111, 112),
            make_bar(113, 114, 106, 108),  # i3: Tief=106
            make_bar(107, 114, 106, 112),  # i4: Open=107 > 106 → nicht unter Tief
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"

    def test_kein_piercing_wenn_i3_nicht_unter_i2_schliesst(self):
        """i3 schließt nicht unter i2 – kein Abwärtstrend."""
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(113, 114, 111, 108),  # i2: Close=108
            make_bar(113, 114, 106, 109),  # i3: Close=109 > i2-Close=108 → kein Abwärtstrend
            make_bar(105, 114, 104, 112),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"

    def test_kein_piercing_schluss_unter_mitte_i3(self):
        """i4 schließt nicht über 50% des i3-Körpers."""
        df = make_df([
            make_bar(115, 116, 113, 114),
            make_bar(114, 115, 111, 112),
            make_bar(113, 114, 111, 112),  # i2: bearish
            make_bar(113, 114, 106, 108),  # i3: Mitte=110.5
            make_bar(105, 111, 104, 109),  # i4: Schluss=109 < Mitte 110.5
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"

    def test_piercing_wird_zu_engulfing_wenn_hoch_genug(self):
        """Schließt i4 über der Öffnung von i3, greift Engulfing-Regel."""
        df = make_df([
            make_bar(112, 113, 110, 111),
            make_bar(111, 112, 108, 109),
            make_bar(110, 111, 107, 108),  # i2: bearish
            make_bar(108, 109, 103, 104),  # i3: bearish
            make_bar(102, 112, 101, 110),  # i4: Schluss=110 > Open i3=108 → Engulfing
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Bullish Engulfing"

    def test_kein_piercing_i2_bullish(self):
        """i2 ist bullish – Abwärtskontext fehlt."""
        df = make_df([
            make_bar(110, 111, 109, 110),
            make_bar(110, 111, 109, 110),
            make_bar(108, 114, 107, 113),  # i2: bullish
            make_bar(113, 114, 106, 108),  # i3: bearish
            make_bar(105, 114, 104, 112),  # i4: bullish
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Piercing Line"


class TestHammer:
    def test_erkannt_als_tiefste_kerze(self):
        df = make_df([
            make_bar(108, 109, 106, 107),
            make_bar(107, 108, 105, 106),
            make_bar(106, 107, 104, 105),
            make_bar(104, 105, 103, 104),
            make_bar(103, 103.5, 99, 102.8),  # Tief=99 → niedrigstes Tief
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] == "Hammer"
        assert r["strength"] == 1

    def test_kein_hammer_wenn_nicht_tiefstes_tief(self):
        df = make_df([
            make_bar(108, 109, 106, 107),
            make_bar(107, 108, 105, 106),
            make_bar(106, 107, 104, 105),
            make_bar(104, 105, 97, 104),   # Tief=97 → tiefer als Hammer
            make_bar(103, 103.5, 99, 102.8),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Hammer"

    def test_kein_hammer_aufwaertstrend(self):
        df = make_df([
            make_bar(100, 102, 99, 101),
            make_bar(101, 103, 100, 102),
            make_bar(102, 104, 101, 103),
            make_bar(103, 105, 102, 104),
            make_bar(104, 104.5, 100, 103.8),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None

    def test_kein_hammer_zu_grosser_koerper(self):
        df = make_df([
            make_bar(108, 109, 106, 107),
            make_bar(107, 108, 105, 106),
            make_bar(106, 107, 104, 105),
            make_bar(104, 105, 103, 104),
            make_bar(100, 106, 96, 105),   # Körper=5, Spanne=10 → 50% > 35%
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] != "Hammer"


# ── Abwärtskontext ────────────────────────────────────────────────────────────

class TestAbwaertskontext:
    def test_kein_muster_zu_wenig_kerzen(self):
        df = make_df([
            make_bar(108, 109, 106, 107),
            make_bar(107, 108, 105, 106),
            make_bar(106, 107, 104, 105),
            make_bar(104, 109, 103, 108),
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None
        assert r["strength"] == 0

    def test_kein_muster_gesamtbewegung_aufwaerts(self):
        """overall_decline schlägt fehl wenn letzter Schluss über erstem."""
        df = make_df([
            make_bar(100, 102, 98, 99),
            make_bar(101, 103, 99, 100),
            make_bar(99, 101, 98, 100),
            make_bar(100, 102, 97, 98),
            make_bar(97, 104, 96, 103),   # Schluss=103 > i0-Schluss=99
        ])
        r = detect_candle_pattern(df)
        assert r["pattern"] is None

    def test_staerke_null_wenn_kein_muster(self):
        df = make_df([
            make_bar(100, 102, 99, 101),
            make_bar(101, 103, 100, 102),
            make_bar(102, 104, 101, 103),
            make_bar(103, 105, 102, 104),
            make_bar(104, 106, 103, 105),
        ])
        r = detect_candle_pattern(df)
        assert r["strength"] == 0

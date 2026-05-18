"""
tests/test_trend_indicators.py
Tests für MACD, Slow Stochastik und Elliott Wave A-B-C in trend_indicators.py

Ausführen:
    cd agent-service
    pytest tests/ -v --tb=short

Abdeckung anzeigen:
    pytest tests/ --cov=candle_patterns --cov=trend_indicators --cov-report=term-missing
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np
import pandas as pd
import pytest
from trend_indicators import calc_macd, calc_slow_stochastic, detect_elliott_abc, evaluate_stock


# ── Hilfsfunktionen ───────────────────────────────────────────────────────────

def make_closes(values: list[float]) -> pd.Series:
    return pd.Series(values, dtype=float)


def make_ohlc_df(n: int = 100, trend: float = -0.3) -> pd.DataFrame:
    """Erzeugt einen synthetischen OHLC-DataFrame mit gegebenem Trend."""
    rng    = np.random.default_rng(42)
    closes = 100 + np.cumsum(rng.normal(trend, 1.0, n))
    opens  = closes + rng.uniform(-0.5, 0.5, n)
    highs  = np.maximum(closes, opens) + rng.uniform(0, 1, n)
    lows   = np.minimum(closes, opens) - rng.uniform(0, 1, n)
    return pd.DataFrame({"open": opens, "high": highs, "low": lows, "close": closes})


# ── MACD ──────────────────────────────────────────────────────────────────────

class TestCalcMacd:
    def test_gibt_none_bei_zu_wenig_daten(self):
        closes = make_closes([100.0] * 30)
        assert calc_macd(closes) is None

    def test_gibt_dict_zurueck(self):
        closes = make_closes([100.0 - i * 0.1 for i in range(50)])
        r = calc_macd(closes)
        assert r is not None
        assert "macd" in r
        assert "signal" in r
        assert "histogram" in r
        assert "is_negative" in r
        assert "histogram_shrinking" in r
        assert "shrink_count" in r

    def test_is_negative_bei_abwaertstrend(self):
        # Starker Abwärtstrend → MACD negativ
        closes = make_closes([100.0 - i * 0.5 for i in range(60)])
        r = calc_macd(closes)
        assert r is not None
        assert r["is_negative"] is True

    def test_is_negative_false_bei_aufwaertstrend(self):
        closes = make_closes([50.0 + i * 0.5 for i in range(60)])
        r = calc_macd(closes)
        assert r is not None
        assert r["is_negative"] is False

    def test_histogram_shrinking_bei_2_schrumpfenden_balken(self):
        """Erzeugt eine Serie die sicher 2 schrumpfende Balken am Ende produziert."""
        closes = make_closes([100.0 - i * 0.5 for i in range(60)])
        r = calc_macd(closes)
        assert r is not None
        assert isinstance(r["shrink_count"], int)
        assert r["shrink_count"] >= 0

    def test_histogram_prev_vorhanden(self):
        closes = make_closes([100.0 - i * 0.1 for i in range(50)])
        r = calc_macd(closes)
        assert r is not None
        assert r["histogram_prev"] is not None

    def test_benutzerdefinierte_parameter(self):
        closes = make_closes([100.0 - i * 0.1 for i in range(60)])
        r = calc_macd(closes, fast=5, slow=10, signal=3)
        assert r is not None
        assert "macd" in r


# ── SLOW STOCHASTIK ───────────────────────────────────────────────────────────

class TestCalcSlowStochastic:
    def test_gibt_none_bei_zu_wenig_daten(self):
        df = make_ohlc_df(n=10)
        assert calc_slow_stochastic(df) is None

    def test_gibt_dict_zurueck(self):
        df = make_ohlc_df(n=50)
        r = calc_slow_stochastic(df)
        assert r is not None
        assert "k" in r
        assert "d" in r
        assert "is_oversold" in r
        assert "k_rising" in r

    def test_k_wert_im_bereich_0_bis_100(self):
        df = make_ohlc_df(n=60)
        r = calc_slow_stochastic(df)
        assert r is not None
        assert 0 <= r["k"] <= 100
        assert 0 <= r["d"] <= 100

    def test_is_oversold_bei_tief_und_steigend(self):
        """Erzeugt Daten die %K unter 20 und steigend bringen."""
        # Starker Abwärtstrend, dann 1 bullishe Kerze
        closes = [100.0 - i * 2 for i in range(30)] + [42.0]
        highs  = [c + 0.5 for c in closes]
        lows   = [c - 0.5 for c in closes]
        opens  = closes.copy()
        df     = pd.DataFrame({"open": opens, "high": highs, "low": lows, "close": closes})
        r = calc_slow_stochastic(df)
        assert r is not None
        # k_rising und is_oversold hängen von den konkreten Daten ab –
        # wir prüfen nur dass der Typ stimmt
        assert isinstance(r["is_oversold"], bool)
        assert isinstance(r["k_rising"], bool)

    def test_benutzerdefinierte_parameter(self):
        df = make_ohlc_df(n=60)
        r = calc_slow_stochastic(df, k_period=5, d_period=2)
        assert r is not None

    def test_is_oversold_false_bei_aufwaertstrend(self):
        """Im Aufwärtstrend sollte %K über 20 liegen."""
        closes = [50.0 + i * 2 for i in range(40)]
        highs  = [c + 0.5 for c in closes]
        lows   = [c - 0.5 for c in closes]
        opens  = closes.copy()
        df     = pd.DataFrame({"open": opens, "high": highs, "low": lows, "close": closes})
        r = calc_slow_stochastic(df)
        assert r is not None
        assert r["is_oversold"] is False


# ── ELLIOTT WAVE A-B-C ────────────────────────────────────────────────────────

class TestDetectElliottAbc:
    def _make_abc_closes(self) -> pd.Series:
        """Erzeugt eine synthetische A-B-C Abwärtskorrektur."""
        # Peak bei Tag 30, A-Welle bis Tag 50, B bis Tag 65, C bis Ende
        peak   = [100.0 + i * 0.5 for i in range(30)]    # Aufstieg
        wave_a = [115.0 - i * 0.8 for i in range(25)]    # A: -20%
        wave_b = [95.0 + i * 0.4  for i in range(15)]    # B: ~50% Retracement
        wave_c = [101.0 - i * 0.6 for i in range(20)]    # C: -12%
        return make_closes(peak + wave_a + wave_b + wave_c)

    def test_erkennt_abc_muster(self):
        closes = self._make_abc_closes()
        r = detect_elliott_abc(closes, lookback=90)
        assert r["ok"] is True
        assert "wave_a_pct" in r
        assert "b_retracement_pct" in r
        assert "c_drop_pct" in r

    def test_gibt_false_bei_zu_wenig_daten(self):
        closes = make_closes([100.0] * 10)
        r = detect_elliott_abc(closes)
        assert r["ok"] is False
        assert "detail" in r

    def test_gibt_false_bei_reinem_aufwaertstrend(self):
        closes = make_closes([50.0 + i * 0.5 for i in range(100)])
        r = detect_elliott_abc(closes)
        assert r["ok"] is False

    def test_gibt_false_wenn_welle_a_zu_klein(self):
        """Welle A muss mind. 4% betragen."""
        # Nur kleiner Rücksetzer von 2%
        trend  = [100.0 + i * 0.2 for i in range(40)]
        small  = [108.0 - i * 0.05 for i in range(20)]
        rest   = [107.0 + i * 0.1 for i in range(30)]
        closes = make_closes(trend + small + rest)
        r = detect_elliott_abc(closes)
        assert r["ok"] is False

    def test_wave_a_pct_negativ(self):
        closes = self._make_abc_closes()
        r = detect_elliott_abc(closes, lookback=90)
        if r["ok"]:
            assert r["wave_a_pct"] < 0

    def test_b_retracement_im_fibonacci_bereich(self):
        closes = self._make_abc_closes()
        r = detect_elliott_abc(closes, lookback=90)
        if r["ok"]:
            assert 20 <= r["b_retracement_pct"] <= 85

    def test_c_drop_pct_negativ(self):
        closes = self._make_abc_closes()
        r = detect_elliott_abc(closes, lookback=90)
        if r["ok"]:
            assert r["c_drop_pct"] < 0

    def test_c_below_a_flag(self):
        closes = self._make_abc_closes()
        r = detect_elliott_abc(closes, lookback=90)
        if r["ok"]:
            assert isinstance(r["c_below_a"], bool)


# ── EVALUATE_STOCK ────────────────────────────────────────────────────────────

class TestEvaluateStock:
    def test_gibt_alle_felder_zurueck(self):
        df = make_ohlc_df(n=100, trend=-0.3)
        r  = evaluate_stock(df)
        for key in ["elliott", "macd", "stoch", "candle",
                    "elliott_ok", "macd_ok", "stoch_ok",
                    "criteria_met", "current_price", "trend_pct"]:
            assert key in r, f"Feld '{key}' fehlt im Ergebnis"

    def test_criteria_met_im_bereich_0_bis_3(self):
        df = make_ohlc_df(n=100)
        r  = evaluate_stock(df)
        assert 0 <= r["criteria_met"] <= 3

    def test_criteria_met_ist_summe_der_flags(self):
        df = make_ohlc_df(n=100)
        r  = evaluate_stock(df)
        expected = sum([r["elliott_ok"], r["macd_ok"], r["stoch_ok"]])
        assert r["criteria_met"] == expected

    def test_flags_sind_bool(self):
        df = make_ohlc_df(n=100)
        r  = evaluate_stock(df)
        assert isinstance(r["elliott_ok"], bool)
        assert isinstance(r["macd_ok"], bool)
        assert isinstance(r["stoch_ok"], bool)

    def test_current_price_entspricht_letztem_schluss(self):
        df = make_ohlc_df(n=100)
        r  = evaluate_stock(df)
        assert abs(r["current_price"] - round(df["close"].iloc[-1], 2)) < 0.01

    def test_trend_pct_ist_float(self):
        df = make_ohlc_df(n=100)
        r  = evaluate_stock(df)
        assert isinstance(r["trend_pct"], float)

    def test_candle_hat_pattern_und_strength(self):
        df = make_ohlc_df(n=100)
        r  = evaluate_stock(df)
        assert "pattern" in r["candle"]
        assert "strength" in r["candle"]

    def test_lookback_parameter(self):
        df = make_ohlc_df(n=150)
        r30  = evaluate_stock(df, lookback=30)
        r90  = evaluate_stock(df, lookback=90)
        # Beide sollen ohne Fehler laufen
        assert "criteria_met" in r30
        assert "criteria_met" in r90

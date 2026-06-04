"""
indicators.py – Kompatibilitäts-Shim
Leitet alle Aufrufe an die neuen gekapselten Module weiter.
Kein Breaking Change für bestehende Imports.
"""

from bullish_reversal_indicator import (
    evaluate_stock,
    detect_elliott_abc,
    calc_macd,
    calc_slow_stochastic,
)
from bullish_candle_pattern_reversal import detect_candle_pattern

__all__ = [
    "evaluate_stock",
    "detect_elliott_abc",
    "calc_macd",
    "calc_slow_stochastic",
    "detect_candle_pattern",
]

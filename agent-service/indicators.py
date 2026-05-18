"""
indicators.py – Kompatibilitäts-Shim

Importiert alle Funktionen aus den aufgesplitteten Modulen:
  - trend_indicators.py  (Elliott Wave, MACD, Stochastik, evaluate_stock)
  - candle_patterns.py   (Kerzenformationen)

main.py importiert weiterhin von hier – kein Breaking Change.
"""

from trend_indicators import (   # noqa: F401
    calc_macd,
    calc_slow_stochastic,
    detect_elliott_abc,
    evaluate_stock,
)

from candle_patterns import detect_candle_pattern  # noqa: F401

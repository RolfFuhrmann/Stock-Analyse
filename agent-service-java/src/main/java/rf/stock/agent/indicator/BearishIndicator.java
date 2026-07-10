package rf.stock.agent.indicator;

import rf.stock.agent.candle.BearishCandlePatterns;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.IndicatorResult;
import rf.stock.agent.model.OhlcvBar;

import java.util.Arrays;
import java.util.List;

/**
 * Bearische Trendumkehr-Indikatoren.
 * Portiert aus bearish_reversal_indicator.py.
 *
 * Erkennt: Aufwärtswelle (Elliott 1-2-3) + MACD > 0 + Stochastik > 80
 * → trend_direction in main.py wird auf "bullish" gesetzt (Semantik-Invertierung, wie Python)
 */
public class BearishIndicator {

    private static final int MACD_FAST   = 12;
    private static final int MACD_SLOW   = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int STOCH_K     = 14;
    private static final int STOCH_D     = 3;

    /**
     * Führt alle bearischen Indikatoren aus.
     */
    public static IndicatorResult evaluate(List<OhlcvBar> bars, int lookback) {
        if (bars == null || bars.size() < 30) return IndicatorResult.empty();

        double[] closes = bars.stream().mapToDouble(OhlcvBar::close).toArray();

        boolean elliottOk = detectElliott123Up(closes, lookback);
        boolean macdOk    = calcMacdIsPositive(closes);
        boolean stochOk   = calcStochIsOverbought(bars);

        CandlePatternResult candle = BearishCandlePatterns.detect(bars);

        int criteriaMet = (elliottOk ? 1 : 0) + (macdOk ? 1 : 0) + (stochOk ? 1 : 0);

        return new IndicatorResult(elliottOk, macdOk, stochOk, criteriaMet, candle);
    }

    // ── MACD ─────────────────────────────────────────────────────────────────

    /** Gibt true zurück wenn MACD-Histogramm positiv ist. */
    static boolean calcMacdIsPositive(double[] closes) {
        if (closes.length < MACD_SLOW + MACD_SIGNAL) return false;

        double[] emaFast    = BullishIndicator.ema(closes, MACD_FAST);
        double[] emaSlow    = BullishIndicator.ema(closes, MACD_SLOW);
        double[] macdLine   = BullishIndicator.subtract(emaFast, emaSlow);
        double[] signalLine = BullishIndicator.ema(macdLine, MACD_SIGNAL);
        double[] histogram  = BullishIndicator.subtract(macdLine, signalLine);

        return histogram[histogram.length - 1] > 0;
    }

    // ── SLOW STOCHASTIK ───────────────────────────────────────────────────────

    /** Gibt true zurück wenn Slow %K über 80 (überkauft). */
    static boolean calcStochIsOverbought(List<OhlcvBar> bars) {
        int minLen = STOCH_K + STOCH_D * 2;
        if (bars.size() < minLen) return false;

        int n = bars.size();
        double[] rawK = new double[n];

        for (int i = STOCH_K - 1; i < n; i++) {
            double lowestLow   = Double.MAX_VALUE;
            double highestHigh = Double.MIN_VALUE;
            for (int j = i - STOCH_K + 1; j <= i; j++) {
                lowestLow   = Math.min(lowestLow,   bars.get(j).low());
                highestHigh = Math.max(highestHigh, bars.get(j).high());
            }
            double denom = highestHigh - lowestLow;
            rawK[i] = denom == 0 ? Double.NaN : (bars.get(i).close() - lowestLow) / denom * 100;
        }

        double[] slowK = BullishIndicator.sma(rawK, STOCH_D);
        double   kVal  = slowK[slowK.length - 1];

        return !Double.isNaN(kVal) && kVal > 80.0;
    }

    // ── ELLIOTT WAVE 1-2-3 (AUFWÄRTS) ────────────────────────────────────────

    /**
     * Erkennt einen aufwärtsgerichteten Elliott-Impuls 1-2-3.
     * Identische Logik wie Python detect_elliott_123_up().
     */
    static boolean detectElliott123Up(double[] closes, int lookback) {
        int start = Math.max(0, closes.length - lookback);
        double[] data = Arrays.copyOfRange(closes, start, closes.length);
        int n = data.length;

        if (n < 20) return false;

        // Trough in den ersten 60%
        int troughWindow = (int) (n * 0.60);
        int troughIdx    = BullishIndicator.argMin(data, 0, troughWindow);
        double troughVal = data[troughIdx];

        if (troughIdx > n - 8) return false;

        // Welle 1: Anstieg vom Trough
        int w1End = (int) (n * 0.85);
        if (w1End <= troughIdx + 3) return false;
        int    w1HighIdx = BullishIndicator.argMax(data, troughIdx + 1, w1End);
        double w1HighVal = data[w1HighIdx];
        double wave1Pct  = (w1HighVal - troughVal) / troughVal * 100;
        if (wave1Pct < 4.0) return false;

        // Welle 2: Korrektur (20–85% Fibonacci), 2-Tief über Trough
        if (w1HighIdx + 3 >= n) return false;
        int    w2LowIdx      = BullishIndicator.argMin(data, w1HighIdx + 1, n);
        double w2LowVal      = data[w2LowIdx];
        double w12Range      = w1HighVal - troughVal;
        double w2Retracement = w12Range == 0 ? 0 : (w1HighVal - w2LowVal) / w12Range;

        if (w2LowVal <= troughVal) return false;
        if (w2Retracement < 0.20 || w2Retracement > 0.85) return false;

        // Welle 3: Weiterer Anstieg nach Welle 2
        if (w2LowIdx + 3 >= n) return false;
        int    w3HighIdx = BullishIndicator.argMax(data, w2LowIdx + 1, n);
        double w3HighVal = data[w3HighIdx];
        double w3RisePct = (w3HighVal - w2LowVal) / w2LowVal * 100;

        return w3RisePct >= 2.0;
    }
}

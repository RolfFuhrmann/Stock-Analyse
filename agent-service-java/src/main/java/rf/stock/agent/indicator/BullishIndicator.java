package rf.stock.agent.indicator;

import rf.stock.agent.candle.BullishCandlePatterns;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.IndicatorResult;
import rf.stock.agent.model.OhlcvBar;

import java.util.List;

/**
 * Bullische Trendumkehr-Indikatoren.
 * Portiert aus bullish_reversal_indicator.py.
 *
 * Erkennt: Abwärtswelle (Elliott A-B-C) + MACD-Histogramm unter 0 + Stochastik unter 20
 * → trend_direction in main.py wird auf "bearish" gesetzt (Semantik-Invertierung, wie Python)
 */
public class BullishIndicator {

    // MACD-Standardparameter
    private static final int MACD_FAST   = 12;
    private static final int MACD_SLOW   = 26;
    private static final int MACD_SIGNAL = 9;

    // Stochastik-Parameter
    private static final int STOCH_K = 14;
    private static final int STOCH_D = 3;

    /**
     * Führt alle Indikatoren aus und gibt das kombinierte Ergebnis zurück.
     *
     * @param bars     chronologisch aufsteigende OHLCV-Kerzen
     * @param lookback Anzahl Kerzen für Elliott-Wave-Analyse
     */
    public static IndicatorResult evaluate(List<OhlcvBar> bars, int lookback) {
        if (bars == null || bars.size() < 30) return IndicatorResult.empty();

        double[] closes = bars.stream().mapToDouble(OhlcvBar::close).toArray();

        boolean elliottOk = detectElliottABC(closes, lookback);
        boolean macdOk    = calcMacdIsNegative(closes);
        boolean stochOk   = calcStochIsOversold(bars);

        CandlePatternResult candle = BullishCandlePatterns.detect(bars);

        int criteriaMet = (elliottOk ? 1 : 0) + (macdOk ? 1 : 0) + (stochOk ? 1 : 0);

        return new IndicatorResult(elliottOk, macdOk, stochOk, criteriaMet, candle);
    }

    // ── MACD ─────────────────────────────────────────────────────────────────

    /**
     * Gibt true zurück wenn das MACD-Histogramm der letzten Kerze negativ ist.
     * Berechnung: EMA(fast) - EMA(slow) - Signal
     */
    static boolean calcMacdIsNegative(double[] closes) {
        if (closes.length < MACD_SLOW + MACD_SIGNAL) return false;

        double[] emaFast   = ema(closes, MACD_FAST);
        double[] emaSlow   = ema(closes, MACD_SLOW);
        double[] macdLine  = subtract(emaFast, emaSlow);
        double[] signalLine = ema(macdLine, MACD_SIGNAL);
        double[] histogram  = subtract(macdLine, signalLine);

        return histogram[histogram.length - 1] < 0;
    }

    // ── SLOW STOCHASTIK ───────────────────────────────────────────────────────

    /**
     * Gibt true zurück wenn Slow %K unter 20 (überverkauft).
     */
    static boolean calcStochIsOversold(List<OhlcvBar> bars) {
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

        double[] slowK = sma(rawK, STOCH_D);
        double   kVal  = slowK[slowK.length - 1];

        return !Double.isNaN(kVal) && kVal < 20.0;
    }

    // ── ELLIOTT WAVE A-B-C (ABWÄRTS) ─────────────────────────────────────────

    /**
     * Erkennt eine abwärtsgerichtete Elliott-Korrektur A-B-C.
     * Identische Logik wie Python detect_elliott_abc().
     */
    static boolean detectElliottABC(double[] closes, int lookback) {
        // Letzten 'lookback' Werte nehmen
        int start = Math.max(0, closes.length - lookback);
        double[] data = java.util.Arrays.copyOfRange(closes, start, closes.length);
        int n = data.length;

        if (n < 20) return false;

        // Peak in den ersten 60%
        int peakWindow = (int) (n * 0.60);
        int peakIdx    = argMax(data, 0, peakWindow);
        double peakVal = data[peakIdx];

        if (peakIdx > n - 8) return false;

        // Welle A: Abfall vom Peak
        int aEnd = (int) (n * 0.85);
        if (aEnd <= peakIdx + 3) return false;
        int    aLowIdx  = argMin(data, peakIdx + 1, aEnd);
        double aLowVal  = data[aLowIdx];
        double waveAPct = (aLowVal - peakVal) / peakVal * 100;
        if (waveAPct > -4.0) return false;

        // Welle B: Gegenbewegung (20–85% Fibonacci)
        if (aLowIdx + 3 >= n) return false;
        int    bHighIdx     = argMax(data, aLowIdx + 1, n);
        double bHighVal     = data[bHighIdx];
        double abRange      = peakVal - aLowVal;
        double bRetracement = abRange == 0 ? 0 : (bHighVal - aLowVal) / abRange;

        if (bHighVal >= peakVal) return false;
        if (bRetracement < 0.20 || bRetracement > 0.85) return false;

        // Welle C: Weiterer Abfall nach B
        if (bHighIdx + 3 >= n) return false;
        double cLowVal  = min(data, bHighIdx + 1, n);
        double cDropPct = (cLowVal - bHighVal) / bHighVal * 100;

        return cDropPct <= -2.0;
    }

    // ── Mathematische Hilfsfunktionen ─────────────────────────────────────────

    /** Exponentieller gleitender Durchschnitt (Wilder-Methode, adjust=False wie Python). */
    static double[] ema(double[] values, int period) {
        double[] result = new double[values.length];
        double multiplier = 2.0 / (period + 1);
        result[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            result[i] = values[i] * multiplier + result[i - 1] * (1 - multiplier);
        }
        return result;
    }

    /** Einfacher gleitender Durchschnitt – überspringt NaN-Werte. */
    static double[] sma(double[] values, int period) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            if (i < period - 1) {
                result[i] = Double.NaN;
                continue;
            }
            double sum = 0;
            int count  = 0;
            for (int j = i - period + 1; j <= i; j++) {
                if (!Double.isNaN(values[j])) {
                    sum += values[j];
                    count++;
                }
            }
            result[i] = count > 0 ? sum / count : Double.NaN;
        }
        return result;
    }

    static double[] subtract(double[] a, double[] b) {
        int len    = Math.min(a.length, b.length);
        double[] r = new double[len];
        for (int i = 0; i < len; i++) r[i] = a[i] - b[i];
        return r;
    }

    static int argMax(double[] arr, int from, int to) {
        int idx = from;
        for (int i = from + 1; i < to; i++) {
            if (arr[i] > arr[idx]) idx = i;
        }
        return idx;
    }

    static int argMin(double[] arr, int from, int to) {
        int idx = from;
        for (int i = from + 1; i < to; i++) {
            if (arr[i] < arr[idx]) idx = i;
        }
        return idx;
    }

    static double min(double[] arr, int from, int to) {
        double m = arr[from];
        for (int i = from + 1; i < to; i++) {
            if (arr[i] < m) m = arr[i];
        }
        return m;
    }
}

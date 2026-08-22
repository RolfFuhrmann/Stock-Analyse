package rf.stock.agent.indicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.elliott.ElliottAnalysisResult;
import org.ta4j.core.indicators.elliott.ElliottDegree;
import org.ta4j.core.indicators.elliott.ElliottPhase;
import org.ta4j.core.indicators.elliott.ElliottScenario;
import org.ta4j.core.indicators.elliott.ElliottScenarioSet;

import rf.stock.agent.candle.BullishCandlePatterns;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.IndicatorResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Bullische Trendumkehr-Indikatoren.
 * Portiert aus bullish_reversal_indicator.py.
 *
 * Erkennt: Abwärtswelle (Elliott A-B-C) + MACD-Histogramm unter 0 + Stochastik
 * unter 20
 * → trend_direction in main.py wird auf "bearish" gesetzt
 * (Semantik-Invertierung, wie Python)
 */
public class BullishIndicator {

    private static final Logger log = LoggerFactory.getLogger(BullishIndicator.class);

    // MACD-Standardparameter
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
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
    public static IndicatorResult evaluate(List<OhlcvBar> bars, int elliottLookback) {
        if (bars == null || bars.size() < 30)
            return IndicatorResult.empty();

        double[] closes = bars.stream().mapToDouble(OhlcvBar::close).toArray();

        ElliottAnalysisUtil.ElliottCheckResult elliott = checkElliottABC(bars, elliottLookback);
        boolean macdOk = calcMacdIsNegative(closes);
        boolean stochOk = calcStochIsOversold(bars);

        CandlePatternResult candle = BullishCandlePatterns.detect(bars);

        int criteriaMet = (elliott.genuine() ? 1 : 0) + (macdOk ? 1 : 0) + (stochOk ? 1 : 0);

        return new IndicatorResult(elliott.genuine(), elliott.stage(), elliott.chart(), macdOk, stochOk, criteriaMet,
                candle);
    }

    // ── MACD ─────────────────────────────────────────────────────────────────

    /**
     * Gibt true zurück wenn das MACD-Histogramm der letzten Kerze negativ ist.
     * Berechnung: EMA(fast) - EMA(slow) - Signal
     */
    static boolean calcMacdIsNegative(double[] closes) {
        if (closes.length < MACD_SLOW + MACD_SIGNAL)
            return false;

        double[] emaFast = ema(closes, MACD_FAST);
        double[] emaSlow = ema(closes, MACD_SLOW);
        double[] macdLine = subtract(emaFast, emaSlow);
        double[] signalLine = ema(macdLine, MACD_SIGNAL);
        double[] histogram = subtract(macdLine, signalLine);

        return histogram[histogram.length - 1] < 0;
    }

    // ── SLOW STOCHASTIK ───────────────────────────────────────────────────────

    /**
     * Gibt true zurück wenn Slow %K unter 20 (überverkauft).
     */
    static boolean calcStochIsOversold(List<OhlcvBar> bars) {
        int minLen = STOCH_K + STOCH_D * 2;
        if (bars.size() < minLen)
            return false;

        int n = bars.size();
        double[] rawK = new double[n];

        for (int i = STOCH_K - 1; i < n; i++) {
            double lowestLow = Double.MAX_VALUE;
            double highestHigh = Double.MIN_VALUE;
            for (int j = i - STOCH_K + 1; j <= i; j++) {
                lowestLow = Math.min(lowestLow, bars.get(j).low());
                highestHigh = Math.max(highestHigh, bars.get(j).high());
            }
            double denom = highestHigh - lowestLow;
            rawK[i] = denom == 0 ? Double.NaN : (bars.get(i).close() - lowestLow) / denom * 100;
        }

        double[] slowK = sma(rawK, STOCH_D);
        double kVal = slowK[slowK.length - 1];

        return !Double.isNaN(kVal) && kVal < 20.0;
    }

    // ── ELLIOTT WAVE A-B-C (ABWÄRTS) - via ta4j ──────────────────────────────

    /**
     * Mindest-Umkehr in Prozent, ab der eine Gegenbewegung als "echte" Umkehr
     * zählt (Rauschfilter). Wird von der pausierten Uptrend-Peak-Funktion
     * unten weiterverwendet (siehe dortiger Kommentar).
     */
    static final double MIN_SWING_PCT = 1.5;

    static boolean detectElliottABC(List<OhlcvBar> bars, int lookback) {
        return checkElliottABC(bars, lookback).genuine();
    }

    static ElliottAnalysisUtil.ElliottCheckResult checkElliottABC(List<OhlcvBar> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        List<OhlcvBar> data = bars.subList(start, bars.size());
        if (data.size() < 20) {
            return ElliottAnalysisUtil.ElliottCheckResult.NONE;
        }

        BarSeries series = ElliottAnalysisUtil.toBarSeries(data);
        ElliottDegree degree = ElliottAnalysisUtil.selectDegree(data.size());
        Optional<ElliottAnalysisResult> analysisOpt = ElliottAnalysisUtil.analyze(series, degree);
        if (analysisOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("ta4j Elliott A-B-C Check: Degree={} keine Analyse für diesen Degree verfügbar", degree);
            }
            return ElliottAnalysisUtil.ElliottCheckResult.NONE;
        }

        ElliottAnalysisResult analysis = analysisOpt.get();
        int index = analysis.index();
        ElliottScenarioSet scenarioSet = analysis.scenarios();

        Optional<ElliottScenario> selected = ElliottAnalysisUtil.selectScenario(scenarioSet,
                scenario -> scenario.type().isCorrective());
        if (selected.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("ta4j Elliott A-B-C Check: Degree={} kein passendes Szenario gefunden [barIndex={}]",
                        degree, index);
            }
            return ElliottAnalysisUtil.ElliottCheckResult.NONE;
        }

        ElliottScenario scenario = selected.get();
        boolean genuine = scenario.hasKnownDirection()
                && scenario.isBearish()
                && scenario.currentPhase() == ElliottPhase.CORRECTIVE_C
                && scenario.confidence().isAboveThreshold(ElliottAnalysisUtil.MIN_CONFIDENCE);
        String stage = ElliottAnalysisUtil.describeStage(scenario);
        rf.stock.agent.model.ElliottChartData chart = ElliottAnalysisUtil.buildChartData(data, scenario);

        if (log.isDebugEnabled()) {
            log.debug("ta4j Elliott A-B-C Check: Degree={} Phase={} Typ={} Richtung={} Konfidenz={}% "
                    + "Invalidierung={} Ziel={} Wellenanzahl={} Wellen=[{}] [barIndex={}] -> {}",
                    degree, scenario.currentPhase(), scenario.type(), scenario.isBullish() ? "bullish" : "bearish",
                    Math.round(scenario.confidence().asPercentage() * 10) / 10.0,
                    scenario.invalidationPrice(), scenario.primaryTarget(), scenario.waveCount(),
                    ElliottAnalysisUtil.describeSwings(data, scenario), index,
                    genuine ? "OK" : "kein Treffer");
        }

        return new ElliottAnalysisUtil.ElliottCheckResult(genuine, stage, chart);
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * Läuft ab startIdx durch die Low-Werte und verfolgt das laufende Tief.
     * Sobald das HIGH einer späteren Kerze seit diesem Tief um mindestens
     * minSwingPct Prozent wieder gestiegen ist, gilt die Umkehr als bestätigt
     * und der Index des Tiefs wird zurückgegeben. Wird bis zum Ende von
     * endExclusive keine Umkehr bestätigt, wird der Index des bis dahin
     * tiefsten Punkts zurückgegeben.
     * Inside Days (High/Low vollständig innerhalb der Vorkerze) zählen NICHT
     * als Bestätigung – sie sind reine Konsolidierung ohne eigenständige
     * Kursaussage und würden sonst z.B. innerhalb der großen Handelsspanne
     * einer volatilen Tiefkerze fälschlich eine Umkehr vortäuschen.
     */
    static int findConfirmedLow(double[] lows, double[] highs, int startIdx, int endExclusive,
            double minSwingPct) {
        int lowIdx = startIdx;
        double lowVal = lows[startIdx];

        for (int i = startIdx + 1; i < endExclusive; i++) {
            if (lows[i] < lowVal) {
                lowVal = lows[i];
                lowIdx = i;
                continue;
            }
            if (isInsideDay(highs, lows, i)) {
                continue;
            }
            if ((highs[i] - lowVal) / lowVal * 100.0 >= minSwingPct) {
                return lowIdx;
            }
        }

        return lowIdx;
    }

    /**
     * Analog zu {@link #findConfirmedLow}, nur für ein bestätigtes Hoch (High/Low
     * vertauscht).
     */
    static int findConfirmedHigh(double[] highs, double[] lows, int startIdx, int endExclusive,
            double minSwingPct) {
        int highIdx = startIdx;
        double highVal = highs[startIdx];

        for (int i = startIdx + 1; i < endExclusive; i++) {
            if (highs[i] > highVal) {
                highVal = highs[i];
                highIdx = i;
                continue;
            }
            if (isInsideDay(highs, lows, i)) {
                continue;
            }
            if ((highVal - lows[i]) / highVal * 100.0 >= minSwingPct) {
                return highIdx;
            }
        }

        return highIdx;
    }

    /** Kerze i liegt mit High UND Low vollständig innerhalb der Vorkerze (i-1). */
    private static boolean isInsideDay(double[] highs, double[] lows, int i) {
        return highs[i] <= highs[i - 1] && lows[i] >= lows[i - 1];
    }

    // ── UPTREND-PEAK MIT KORREKTUR (STRUKTURELLE HÖHERE HOCHS/TIEFS) ──────────
    // PAUSIERT (07/2026): Rolf hat entschieden, stattdessen auf Java 25 +
    // ta4j umzusteigen (seit 31.07. via ElliottWaveAnalysisRunner statt der
    // reinen ElliottWaveFacade - löst genau dieses Problem bereits über
    // Multi-Degree-Analyse und kontinuierliches Confidence-Scoring statt
    // hartem Pass/Fail). Code bleibt als Referenz/Fallback stehen, ist aber
    // NICHT in evaluate() verdrahtet.

    // Mindestanzahl aufeinanderfolgender Anstiege (Hochs oder Tiefs), damit
    // ein Uptrend als strukturell belastbar gilt (klassische Dow-Theorie:
    // höhere Hochs und/oder höhere Tiefs). 2 Anstiege = mindestens 3
    // aufeinanderfolgende steigende Swing-Werte.
    private static final int UPTREND_MIN_RISING_SWINGS = 2;

    /**
     * Erkennt einen strukturellen Aufwärtstrend (mehrere aufeinanderfolgende
     * höhere Hochs und/oder höhere Tiefs im ZigZag) und bestätigt dessen
     * Peak erst, wenn danach eine echte, ZigZag-bestätigte Korrektur
     * einsetzt.
     *
     * Unterschied zu detectElliottABC(): Dort ist der Peak schlicht das
     * globale Maximum im Analysefenster. Hier wird der Peak stattdessen
     * strukturell über eine Folge steigender Swings hergeleitet - das
     * erkennt auch dann einen sinnvollen "Trend-Peak", wenn sich die
     * Elliott-Wellenzählung in einer kleinen Teilbewegung "verheddert"
     * (Praxisbeispiel Amazon (AMZN), 07/2026: der 5-Wellen-Zähler
     * bestätigte Welle 5 bereits nach 3 Wochen bei 256,18, obwohl die
     * Rally strukturell bis mindestens 278,56 weiterlief).
     *
     * Vorgehen:
     * 1. Kompletten ZigZag über das Analysefenster aufbauen (abwechselnd
     * bestätigtes Tief/Hoch, MIN_SWING_PCT).
     * 2. Längste Folge aufeinanderfolgender STEIGENDER Hochs bzw. Tiefs
     * suchen (mindestens UPTREND_MIN_RISING_SWINGS Anstiege).
     * 3. Peak = letztes Hoch dieser Folge (bei einer Tief-Folge: das
     * erste Hoch danach).
     * 4. Nach dem Peak muss eine echte, ZigZag-bestätigte Korrektur von
     * mindestens MIN_SWING_PCT folgen - sonst könnte der Trend
     * einfach unverändert weiterlaufen, kein bestätigter Peak.
     */
    static boolean detectUptrendPeakWithCorrection(List<OhlcvBar> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        List<OhlcvBar> data = bars.subList(start, bars.size());
        int n = data.size();
        if (n < 20) {
            return false;
        }

        double[] highs = data.stream().mapToDouble(OhlcvBar::high).toArray();
        double[] lows = data.stream().mapToDouble(OhlcvBar::low).toArray();

        List<double[]> swings = buildZigZag(highs, lows, n);
        if (swings.size() < 4) {
            return false; // zu wenig Struktur für einen belastbaren Trend
        }

        List<double[]> swingHighs = new ArrayList<>();
        List<double[]> swingLows = new ArrayList<>();
        for (double[] s : swings) {
            (s[1] == 1.0 ? swingHighs : swingLows).add(s);
        }

        int peakIdx = findStructuralUptrendPeak(swingHighs, swingLows);
        if (peakIdx < 0) {
            return false; // keine ausreichend belastbare Trendstruktur gefunden
        }
        if (peakIdx + 2 >= n) {
            return false; // kein Platz mehr für eine Korrektur
        }

        int correctionIdx = findConfirmedLow(lows, highs, peakIdx + 1, n, MIN_SWING_PCT);
        double correctionDropPct = (highs[peakIdx] - lows[correctionIdx]) / highs[peakIdx] * 100;
        boolean confirmed = correctionDropPct >= MIN_SWING_PCT;

        if (confirmed && log.isDebugEnabled()) {
            log.debug("Uptrend-Peak mit Korrektur erkannt: Peak={} ({}) Korrektur-Tief={} ({}) "
                    + "Rueckgang={}% [Index Peak={}/{} Korrektur={}]",
                    data.get(peakIdx).date(), highs[peakIdx], data.get(correctionIdx).date(),
                    lows[correctionIdx], round(correctionDropPct), peakIdx, n, correctionIdx);
        }

        return confirmed;
    }

    /** Baut einen vollständigen ZigZag (abwechselnd Tief/Hoch) über [0, n). */
    private static List<double[]> buildZigZag(double[] highs, double[] lows, int n) {
        List<double[]> swings = new ArrayList<>();
        int idx = 0;
        boolean seekingLow = true;
        while (idx < n) {
            if (seekingLow) {
                int lowIdx = findConfirmedLow(lows, highs, idx, n, MIN_SWING_PCT);
                swings.add(new double[] { lowIdx, 0.0, lows[lowIdx] });
                if (lowIdx + 1 >= n) {
                    break;
                }
                idx = lowIdx + 1;
            } else {
                int highIdx = findConfirmedHigh(highs, lows, idx, n, MIN_SWING_PCT);
                swings.add(new double[] { highIdx, 1.0, highs[highIdx] });
                if (highIdx + 1 >= n) {
                    break;
                }
                idx = highIdx + 1;
            }
            seekingLow = !seekingLow;
        }
        return swings;
    }

    /**
     * Sucht unter den Swing-Hochs bzw. -Tiefs die längste Folge steigender
     * Werte und liefert den Kerzenindex des zugehörigen Peaks zurück
     * (letztes Hoch der Folge, bzw. bei einer Tief-Folge das erste Hoch
     * danach). -1, falls keine ausreichend lange Folge existiert.
     */
    private static int findStructuralUptrendPeak(List<double[]> swingHighs, List<double[]> swingLows) {
        int highRunEndPos = longestRisingRunEndPosition(swingHighs);
        int highPeakBarIdx = highRunEndPos >= 0 ? (int) swingHighs.get(highRunEndPos)[0] : -1;

        int lowRunEndPos = longestRisingRunEndPosition(swingLows);
        int lowPeakBarIdx = -1;
        if (lowRunEndPos >= 0) {
            double lastLowBarIdx = swingLows.get(lowRunEndPos)[0];
            for (double[] h : swingHighs) {
                if (h[0] > lastLowBarIdx) {
                    lowPeakBarIdx = (int) h[0];
                    break;
                }
            }
        }

        if (highPeakBarIdx < 0 && lowPeakBarIdx < 0) {
            return -1;
        }
        // Den späteren (aktuelleren) der beiden Kandidaten bevorzugen.
        return Math.max(highPeakBarIdx, lowPeakBarIdx);
    }

    /**
     * Position (im übergebenen Punkte-Array) des letzten Werts der längsten
     * Folge streng steigender Werte, oder -1 falls keine Folge mit
     * mindestens UPTREND_MIN_RISING_SWINGS Anstiegen existiert. Erwartet
     * double[]{kerzenIndex, typ, wert} pro Punkt.
     */
    private static int longestRisingRunEndPosition(List<double[]> points) {
        if (points.size() < UPTREND_MIN_RISING_SWINGS + 1) {
            return -1;
        }
        int bestLen = 1;
        int bestEndPos = -1;
        int curLen = 1;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i)[2] > points.get(i - 1)[2]) {
                curLen++;
            } else {
                curLen = 1;
            }
            if (curLen > bestLen) {
                bestLen = curLen;
                bestEndPos = i;
            }
        }
        if (bestLen < UPTREND_MIN_RISING_SWINGS + 1) {
            return -1;
        }
        return bestEndPos;
    }

    // ── Mathematische Hilfsfunktionen ─────────────────────────────────────────

    /**
     * Exponentieller gleitender Durchschnitt (Wilder-Methode, adjust=False wie
     * Python).
     */
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
            int count = 0;
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
        int len = Math.min(a.length, b.length);
        double[] r = new double[len];
        for (int i = 0; i < len; i++)
            r[i] = a[i] - b[i];
        return r;
    }

    static int argMax(double[] arr, int from, int to) {
        int idx = from;
        for (int i = from + 1; i < to; i++) {
            if (arr[i] > arr[idx])
                idx = i;
        }
        return idx;
    }

    static int argMin(double[] arr, int from, int to) {
        int idx = from;
        for (int i = from + 1; i < to; i++) {
            if (arr[i] < arr[idx])
                idx = i;
        }
        return idx;
    }

    static double min(double[] arr, int from, int to) {
        double m = arr[from];
        for (int i = from + 1; i < to; i++) {
            if (arr[i] < m)
                m = arr[i];
        }
        return m;
    }
}

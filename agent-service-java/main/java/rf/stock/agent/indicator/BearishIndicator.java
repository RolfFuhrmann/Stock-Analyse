package rf.stock.agent.indicator;

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

import rf.stock.agent.candle.BearishCandlePatterns;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.IndicatorResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Bearische Trendumkehr-Indikatoren.
 * Erkennt: Aufwärtswelle (Elliott 1-2-3-4-5) + MACD > 0 + Stochastik > 80
 * → trend_direction wird auf "bullish" gesetzt (Semantik-Invertierung, wie
 * Python).
 */
public class BearishIndicator {

    private static final Logger log = LoggerFactory.getLogger(BearishIndicator.class);

    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int STOCH_K = 14;
    private static final int STOCH_D = 3;

    public static IndicatorResult evaluate(List<OhlcvBar> bars, int elliottLookback) {
        if (bars == null || bars.size() < 30)
            return IndicatorResult.empty();

        double[] closes = bars.stream().mapToDouble(OhlcvBar::close).toArray();

        ElliottAnalysisUtil.ElliottCheckResult elliott = checkElliottImpulseUp(bars, elliottLookback);
        boolean macdOk = calcMacdIsPositive(closes);
        boolean stochOk = calcStochIsOverbought(bars);

        CandlePatternResult candle = BearishCandlePatterns.detect(bars);

        int criteriaMet = (elliott.genuine() ? 1 : 0) + (macdOk ? 1 : 0) + (stochOk ? 1 : 0);

        return new IndicatorResult(elliott.genuine(), elliott.stage(), elliott.chart(), macdOk, stochOk, criteriaMet,
                candle);
    }

    // ── MACD ─────────────────────────────────────────────────────────────────

    static boolean calcMacdIsPositive(double[] closes) {
        if (closes.length < MACD_SLOW + MACD_SIGNAL)
            return false;

        double[] emaFast = BullishIndicator.ema(closes, MACD_FAST);
        double[] emaSlow = BullishIndicator.ema(closes, MACD_SLOW);
        double[] macdLine = BullishIndicator.subtract(emaFast, emaSlow);
        double[] signalLine = BullishIndicator.ema(macdLine, MACD_SIGNAL);
        double[] histogram = BullishIndicator.subtract(macdLine, signalLine);

        return histogram[histogram.length - 1] > 0;
    }

    // ── SLOW STOCHASTIK ───────────────────────────────────────────────────────

    static boolean calcStochIsOverbought(List<OhlcvBar> bars) {
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

        double[] slowK = BullishIndicator.sma(rawK, STOCH_D);
        double kVal = slowK[slowK.length - 1];

        return !Double.isNaN(kVal) && kVal > 80.0;
    }

    // ── ELLIOTT WAVE 1-2-3-4-5 (AUFWÄRTS) - via ta4j ─────────────────────────
    // Geteilte Hilfsmethoden (Degree-Wahl, Runner, Szenario-Auswahl,
    // Stage-/Swing-Beschreibung) liegen seit 01.08. in ElliottAnalysisUtil,
    // da BullishIndicator dieselbe Kalibrierung braucht.

    static boolean detectElliottImpulseUp(List<OhlcvBar> bars, int lookback) {
        return checkElliottImpulseUp(bars, lookback).genuine();
    }

    static ElliottAnalysisUtil.ElliottCheckResult checkElliottImpulseUp(List<OhlcvBar> bars, int lookback) {
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
                log.debug("ta4j Elliott 1-2-3-4-5 Check: Degree={} keine Analyse für diesen Degree verfügbar",
                        degree);
            }
            return ElliottAnalysisUtil.ElliottCheckResult.NONE;
        }

        ElliottAnalysisResult analysis = analysisOpt.get();
        int index = analysis.index();
        ElliottScenarioSet scenarioSet = analysis.scenarios();

        Optional<ElliottScenario> selected = ElliottAnalysisUtil.selectScenario(scenarioSet,
                scenario -> scenario.type().isImpulse());
        if (selected.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("ta4j Elliott 1-2-3-4-5 Check: Degree={} kein passendes Szenario gefunden [barIndex={}]",
                        degree, index);
            }
            return ElliottAnalysisUtil.ElliottCheckResult.NONE;
        }

        ElliottScenario scenario = selected.get();
        boolean genuine = scenario.hasKnownDirection()
                && scenario.isBullish()
                && scenario.currentPhase() == ElliottPhase.WAVE5
                && scenario.confidence().isAboveThreshold(ElliottAnalysisUtil.MIN_CONFIDENCE);
        String stage = ElliottAnalysisUtil.describeStage(scenario);
        rf.stock.agent.model.ElliottChartData chart = ElliottAnalysisUtil.buildChartData(data, scenario);

        if (log.isDebugEnabled()) {
            log.debug("ta4j Elliott 1-2-3-4-5 Check: Degree={} Phase={} Typ={} Richtung={} Konfidenz={}% "
                    + "Invalidierung={} Ziel={} Wellenanzahl={} Wellen=[{}] [barIndex={}] -> {}",
                    degree, scenario.currentPhase(), scenario.type(), scenario.isBullish() ? "bullish" : "bearish",
                    Math.round(scenario.confidence().asPercentage() * 10) / 10.0,
                    scenario.invalidationPrice(), scenario.primaryTarget(),
                    scenario.waveCount(), ElliottAnalysisUtil.describeSwings(data, scenario), index,
                    genuine ? "OK" : "kein Treffer");
        }

        return new ElliottAnalysisUtil.ElliottCheckResult(genuine, stage, chart);
    }
}

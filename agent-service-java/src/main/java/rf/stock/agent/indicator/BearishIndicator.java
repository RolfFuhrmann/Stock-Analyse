package rf.stock.agent.indicator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.elliott.ElliottAnalysisResult;
import org.ta4j.core.indicators.elliott.ElliottDegree;
import org.ta4j.core.indicators.elliott.ElliottLogicProfile;
import org.ta4j.core.indicators.elliott.ElliottPhase;
import org.ta4j.core.indicators.elliott.ElliottScenario;
import org.ta4j.core.indicators.elliott.ElliottScenarioSet;
import org.ta4j.core.indicators.elliott.ElliottSwing;
import org.ta4j.core.indicators.elliott.ElliottWaveAnalysisResult;
import org.ta4j.core.indicators.elliott.ElliottWaveAnalysisRunner;

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

    public static IndicatorResult evaluate(List<OhlcvBar> bars, int lookback) {
        if (bars == null || bars.size() < 30)
            return IndicatorResult.empty();

        double[] closes = bars.stream().mapToDouble(OhlcvBar::close).toArray();

        ElliottCheckResult elliott = checkElliottImpulseUp(bars, lookback);
        boolean macdOk = calcMacdIsPositive(closes);
        boolean stochOk = calcStochIsOverbought(bars);

        CandlePatternResult candle = BearishCandlePatterns.detect(bars);

        int criteriaMet = (elliott.genuine() ? 1 : 0) + (macdOk ? 1 : 0) + (stochOk ? 1 : 0);

        return new IndicatorResult(elliott.genuine(), elliott.stage(), macdOk, stochOk, criteriaMet, candle);
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

    static final double MIN_CONFIDENCE = 0.6;

    /**
     * Cross-Degree-Validierung: der Runner analysiert zusätzlich eine Stufe
     * höher/tiefer als den gewählten Degree und gleicht die Szenarien ab.
     * Konfiguration übernommen aus dem Praxistest mit dem ta4j-eigenen
     * ElliottWavePresetDemo (live-Modus), das mit dem HIERARCHICAL_SWING-Profil
     * den DIS-Strukturanker exakt auf den 27.03. gesetzt hat - unabhängig
     * bestätigt durch manuelle Wellenzählung. Siehe Roadmap in CLAUDE.md.
     *
     * Voraussetzung: ta4j-core >=0.22.7 (ElliottLogicProfile existiert erst ab
     * dieser Version - pom.xml wurde am 31.07. entsprechend angehoben).
     */
    private static final int HIGHER_DEGREES = 1;
    private static final int LOWER_DEGREES = 1;
    private static final double RUNNER_MIN_CONFIDENCE = 0.15;
    private static final int RUNNER_MAX_SCENARIOS = 5;

    record ElliottCheckResult(boolean genuine, String stage) {
        static final ElliottCheckResult NONE = new ElliottCheckResult(false, "");
    }

    /**
     * Wählt den zur tatsächlichen Bar-Anzahl passenden Elliott-Degree über
     * ta4js eigene Heuristik statt eines fest verdrahteten Werts. Grund:
     * INTERMEDIATE empfiehlt laut ta4j-Doku 180-400 Tagesbars Historie; unser
     * lookback (Default 90, im DIS-Fall real ~65 Bars) liegt deutlich darunter
     * -> ta4j würde ohnehin zu MINOR (60-180 Bars) raten.
     */
    static ElliottDegree selectDegree(int barCount) {
        List<ElliottDegree> recommended = ElliottDegree.getRecommendedDegrees(Duration.ofDays(1), barCount);
        return recommended.isEmpty() ? ElliottDegree.MINOR : recommended.get(0);
    }

    /**
     * Führt die Elliott-Wave-Analyse über den {@link ElliottWaveAnalysisRunner}
     * statt der nackten {@code ElliottWaveFacade} aus. Ersetzt den händisch
     * kalibrierten Compressor-Ansatz (Stand 30.07.) - der Runner mit dem
     * HIERARCHICAL_SWING-Profil und Cross-Degree-Validierung (+-1 Grad) hat
     * sich im DIS-Praxisfall als deutlich treffsicherer erwiesen. Zentral für
     * Bullish- und Bearish-Check, damit beide Richtungen dieselbe Kalibrierung
     * verwenden.
     */
    static Optional<ElliottAnalysisResult> analyze(BarSeries series, ElliottDegree degree) {
        ElliottWaveAnalysisRunner runner = ElliottWaveAnalysisRunner.builder()
                .degree(degree)
                .logicProfile(ElliottLogicProfile.HIERARCHICAL_SWING)
                .higherDegrees(HIGHER_DEGREES)
                .lowerDegrees(LOWER_DEGREES)
                .minConfidence(RUNNER_MIN_CONFIDENCE)
                .maxScenarios(RUNNER_MAX_SCENARIOS)
                .build();
        ElliottWaveAnalysisResult result = runner.analyze(series);
        return result.analysisFor(degree).map(ElliottWaveAnalysisResult.DegreeAnalysis::analysis);
    }

    static Optional<ElliottScenario> selectScenario(ElliottScenarioSet scenarioSet,
            Predicate<ElliottScenario> typeMatches) {
        Optional<ElliottScenario> base = scenarioSet.base();
        if (base.filter(typeMatches).isPresent()) {
            return base;
        }
        return scenarioSet.alternatives().stream()
                .filter(typeMatches)
                .max(Comparator.comparingDouble(scenario -> scenario.confidence().asPercentage()));
    }

    static String describeStage(ElliottScenario scenario) {
        ElliottPhase phase = scenario.currentPhase();
        if (phase == null) {
            return "";
        }
        return switch (phase) {
            case WAVE1 -> "Welle 1 im Entstehen";
            case WAVE2 -> "Welle 1 abgeschlossen, Welle 2 im Entstehen";
            case WAVE3 -> "Wellen 1-2 abgeschlossen, Welle 3 im Entstehen";
            case WAVE4 -> "Wellen 1-3 abgeschlossen, Welle 4 im Entstehen";
            case WAVE5 -> "Wellen 1-4 abgeschlossen, Welle 5 im Entstehen";
            case CORRECTIVE_A -> "Welle A im Entstehen";
            case CORRECTIVE_B -> "A abgeschlossen, B im Entstehen";
            case CORRECTIVE_C -> "A-B abgeschlossen, C im Entstehen";
            default -> phase.name();
        };
    }

    static String describeSwings(List<OhlcvBar> data, ElliottScenario scenario) {
        List<ElliottSwing> swings = scenario.swings();
        if (swings.isEmpty()) {
            return "";
        }
        boolean impulse = scenario.type().isImpulse();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < swings.size(); i++) {
            ElliottSwing swing = swings.get(i);
            String label = impulse ? String.valueOf(i + 1) : String.valueOf((char) ('A' + i));
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(label).append(": ");
            if (swing.fromIndex() >= 0 && swing.fromIndex() < data.size()) {
                sb.append(data.get(swing.fromIndex()).date()).append(" (")
                        .append(BullishIndicator.round(swing.fromPrice().doubleValue())).append(")");
            } else {
                sb.append("?");
            }
            sb.append(" -> ");
            if (swing.toIndex() >= 0 && swing.toIndex() < data.size()) {
                sb.append(data.get(swing.toIndex()).date()).append(" (")
                        .append(BullishIndicator.round(swing.toPrice().doubleValue())).append(")");
            } else {
                sb.append("?");
            }
        }
        return sb.toString();
    }

    static boolean detectElliottImpulseUp(List<OhlcvBar> bars, int lookback) {
        return checkElliottImpulseUp(bars, lookback).genuine();
    }

    static ElliottCheckResult checkElliottImpulseUp(List<OhlcvBar> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        List<OhlcvBar> data = bars.subList(start, bars.size());
        if (data.size() < 20) {
            return ElliottCheckResult.NONE;
        }

        BarSeries series = toBarSeries(data);
        ElliottDegree degree = selectDegree(data.size());
        Optional<ElliottAnalysisResult> analysisOpt = analyze(series, degree);
        if (analysisOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("ta4j Elliott 1-2-3-4-5 Check: Degree={} keine Analyse für diesen Degree verfügbar",
                        degree);
            }
            return ElliottCheckResult.NONE;
        }

        ElliottAnalysisResult analysis = analysisOpt.get();
        int index = analysis.index();
        ElliottScenarioSet scenarioSet = analysis.scenarios();

        Optional<ElliottScenario> selected = selectScenario(scenarioSet, scenario -> scenario.type().isImpulse());
        if (selected.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("ta4j Elliott 1-2-3-4-5 Check: Degree={} kein passendes Szenario gefunden [barIndex={}]",
                        degree, index);
            }
            return ElliottCheckResult.NONE;
        }

        ElliottScenario scenario = selected.get();
        boolean genuine = scenario.hasKnownDirection()
                && scenario.isBullish()
                && scenario.currentPhase() == ElliottPhase.WAVE5
                && scenario.confidence().isAboveThreshold(MIN_CONFIDENCE);
        String stage = describeStage(scenario);

        if (log.isDebugEnabled()) {
            log.debug("ta4j Elliott 1-2-3-4-5 Check: Degree={} Phase={} Typ={} Richtung={} Konfidenz={}% "
                    + "Invalidierung={} Ziel={} Wellenanzahl={} Wellen=[{}] [barIndex={}] -> {}",
                    degree, scenario.currentPhase(), scenario.type(), scenario.isBullish() ? "bullish" : "bearish",
                    Math.round(scenario.confidence().asPercentage() * 10) / 10.0,
                    scenario.invalidationPrice(), scenario.primaryTarget(),
                    scenario.waveCount(), describeSwings(data, scenario), index, genuine ? "OK" : "kein Treffer");
        }

        return new ElliottCheckResult(genuine, stage);
    }

    static BarSeries toBarSeries(List<OhlcvBar> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("agent-service-analysis").build();
        Instant previousEndTime = null;
        for (OhlcvBar bar : bars) {
            Instant endTime = parseBarDate(bar.date());
            Duration period = previousEndTime == null
                    ? Duration.ofDays(1)
                    : Duration.between(previousEndTime, endTime);
            if (period.isZero() || period.isNegative()) {
                period = Duration.ofDays(1);
            }
            series.barBuilder()
                    .timePeriod(period)
                    .endTime(endTime)
                    .openPrice(bar.open())
                    .highPrice(bar.high())
                    .lowPrice(bar.low())
                    .closePrice(bar.close())
                    .volume(bar.volume() == null ? 0d : bar.volume())
                    .add();
            previousEndTime = endTime;
        }
        return series;
    }

    static Instant parseBarDate(String date) {
        try {
            return Instant.parse(date);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(date).atZone(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e2) {
                return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        }
    }
}

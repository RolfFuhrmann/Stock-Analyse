package rf.stock.agent.indicator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.elliott.ElliottDegree;
import org.ta4j.core.indicators.elliott.ElliottPhase;
import org.ta4j.core.indicators.elliott.ElliottScenario;
import org.ta4j.core.indicators.elliott.ElliottWaveFacade;

import rf.stock.agent.candle.BearishCandlePatterns;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.IndicatorResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Bearische Trendumkehr-Indikatoren.
 * Portiert aus bearish_reversal_indicator.py.
 *
 * Erkennt: Aufwärtswelle (Elliott 1-2-3-4-5) + MACD > 0 + Stochastik > 80
 * → trend_direction in main.py wird auf "bullish" gesetzt
 * (Semantik-Invertierung, wie Python)
 */
public class BearishIndicator {

    private static final Logger log = LoggerFactory.getLogger(BearishIndicator.class);

    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int STOCH_K = 14;
    private static final int STOCH_D = 3;

    /**
     * Führt alle bearischen Indikatoren aus.
     */
    public static IndicatorResult evaluate(List<OhlcvBar> bars, int lookback) {
        if (bars == null || bars.size() < 30)
            return IndicatorResult.empty();

        double[] closes = bars.stream().mapToDouble(OhlcvBar::close).toArray();

        boolean elliottOk = detectElliottImpulseUp(bars, lookback);
        boolean macdOk = calcMacdIsPositive(closes);
        boolean stochOk = calcStochIsOverbought(bars);

        CandlePatternResult candle = BearishCandlePatterns.detect(bars);

        int criteriaMet = (elliottOk ? 1 : 0) + (macdOk ? 1 : 0) + (stochOk ? 1 : 0);

        return new IndicatorResult(elliottOk, macdOk, stochOk, criteriaMet, candle);
    }

    // ── MACD ─────────────────────────────────────────────────────────────────

    /** Gibt true zurück wenn MACD-Histogramm positiv ist. */
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

    /** Gibt true zurück wenn Slow %K über 80 (überkauft). */
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

    // Wellen-Grad für die Elliott-Analyse. Bei ~90 Tageskerzen Fensterbreite
    // ist MINOR ein vernünftiger Startpunkt (siehe ta4j-Wiki: "Daily bars:
    // PRIMARY through MINUTE degrees") - bei Bedarf anhand echter Logs
    // nachjustieren. Von BullishIndicator mitgenutzt (dieselbe Frage:
    // "wie sicher müssen wir uns sein").
    static final ElliottDegree DEGREE = ElliottDegree.MINOR;

    // Mindest-Konfidenz (0.0-1.0), ab der ein Szenario als belastbar genug
    // gilt, um das Kriterium auszulösen. ta4j bewertet kontinuierlich statt
    // hartem Pass/Fail (Fibonacci-Nähe, Zeit-Proportionen, Alternation,
    // Channel-Einhaltung, Struktur-Vollständigkeit) - siehe ta4j-Wiki
    // "Confidence Scoring". 0.6 ist ein konservativer Startwert.
    static final double MIN_CONFIDENCE = 0.6;

    /**
     * Erkennt eine vollständige, klassisch-konforme Elliott-Impulswelle
     * 1-2-3-4-5 (Aufwärts) über ta4j's ElliottWaveFacade. Ersetzt die
     * handgestrickte Vorgängerlogik (eigene ZigZag-Bestätigung, feste
     * Fibonacci-Bänder, harte Pass/Fail-Regeln) durch ein ausgereiftes,
     * szenario-basiertes Modell mit kontinuierlichem Confidence-Scoring
     * (siehe ta4j-Wiki: Elliott Wave Indicators).
     *
     * Ein Treffer erfordert:
     * - Ein Basis-Szenario (höchste Konfidenz) existiert für den
     * aktuellen Balken.
     * - Der Szenario-Typ ist ein Impuls (IMPULSE, nicht korrektiv).
     * - Die Richtung ist bullisch (Welle 1 aufwärts).
     * - Die aktuelle Phase ist WAVE5 (die Struktur ist vollständig, nicht
     * nur "in Bildung").
     * - Die Konfidenz liegt über MIN_CONFIDENCE.
     */
    static boolean detectElliottImpulseUp(List<OhlcvBar> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        List<OhlcvBar> data = bars.subList(start, bars.size());
        if (data.size() < 20) {
            return false;
        }

        BarSeries series = toBarSeries(data);
        int index = series.getEndIndex();

        ElliottWaveFacade facade = ElliottWaveFacade.zigZag(series, DEGREE);
        Optional<ElliottScenario> baseCase = facade.primaryScenario(index);
        if (baseCase.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("ta4j Elliott 1-2-3-4-5 Check: kein Basis-Szenario gefunden [barIndex={}]", index);
            }
            return false;
        }

        ElliottScenario scenario = baseCase.get();
        boolean genuine = scenario.type().isImpulse()
                && scenario.hasKnownDirection()
                && scenario.isBullish()
                && scenario.currentPhase() == ElliottPhase.WAVE5
                && scenario.confidence().isAboveThreshold(MIN_CONFIDENCE);

        if (log.isDebugEnabled()) {
            log.debug("ta4j Elliott 1-2-3-4-5 Check: Phase={} Typ={} Richtung={} Konfidenz={}% "
                    + "Invalidierung={} Ziel={} Wellenanzahl={} [barIndex={}] -> {}",
                    scenario.currentPhase(), scenario.type(), scenario.isBullish() ? "bullish" : "bearish",
                    Math.round(scenario.confidence().asPercentage() * 10) / 10.0,
                    scenario.invalidationPrice(), scenario.primaryTarget(),
                    scenario.waveCount(), index, genuine ? "OK" : "kein Treffer");
        }

        return genuine;
    }

    /**
     * Wandelt unsere OHLCV-Kerzen in eine ta4j-BarSeries um. Von BullishIndicator
     * mitgenutzt.
     */
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

    /**
     * bar.date() kann drei Formate haben:
     * - reines Datum (1d): "2026-05-19"
     * - lokaler Zeitstempel ohne Zone (1h/4h, von twelvedata so geliefert):
     * "2026-05-19T12:30:00"
     * - vollqualifizierter ISO-Instant mit Zone/Offset: "2026-05-19T12:30:00Z"
     * Reihenfolge ist wichtig: erst der spezifischste Versuch (Instant mit
     * Zone), dann LocalDateTime (hat "T", aber keine Zone), zuletzt
     * LocalDate (nur Datum, kein "T") - jeder fehlgeschlagene Versuch wirft
     * eine DateTimeParseException, mit der zum nächsten Format
     * weitergereicht wird. Ohne den LocalDateTime-Zwischenschritt schlagen
     * 1H/4H-Zeitstempel fehl (siehe Praxisfall AMZN 1H, 07/2026).
     */
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

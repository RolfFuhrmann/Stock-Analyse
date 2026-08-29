package rf.stock.agent.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Shooting Star/Bearish Engulfing/Dark Cloud Cover sind seit 24.08.
 * ta4j-basiert mit einer GD20→GD50→GD200-Kaskade (Uptrend: Schlusskurs ÜBER
 * dem GD) statt der alten CandleUtils-Trendprüfung - siehe
 * BearishCandlePatterns.java-Klassenkommentar und CandleGdCascade. Dieselbe
 * Anpassung wie bei BullishCandlePatternsTest: Uptrend-Präfixe auf >=18-19
 * Kerzen verlängert (risingRun()/decliningRun()), damit die GD-Berechnung
 * überhaupt Werte liefert, und der bar()-Helper nutzt fortlaufende statt
 * konstante Daten (ElliottAnalysisUtil.toBarSeries() braucht eine strikt
 * aufsteigende endTime pro Kerze). Bearish Abandoned Baby ist von beidem
 * nicht betroffen (weiterhin Eigenentwicklung, kein GD, kein toBarSeries()).
 */
class BearishCandlePatternsTest {

    @Test
    void shouldDetectShootingStarAfterUptrend() {

        List<OhlcvBar> bars = concat(
                risingRun(19, 6.0, 0.35),

                // Shooting Star
                List.of(bar(12.5, 13.5, 12.45, 12.6)));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Shooting Star", result.pattern());
        assertEquals(20, result.gdPeriod());
    }

    @Test
    void shouldNotDetectShootingStarWithoutUptrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(19, 20.0, 0.4),

                // Shooting-Star-Form vorhanden, aber kein Uptrend davor
                List.of(bar(12.5, 13.5, 12.45, 12.6)));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectBearishEngulfingAfterUptrend() {

        List<OhlcvBar> bars = concat(
                risingRun(18, 6.0, 0.36),

                List.of(
                        // grüne Kerze, Teil des Uptrends
                        bar(12.0, 12.6, 11.8, 12.5),

                        // rote Kerze engulfed den Body
                        bar(12.6, 12.7, 11.85, 11.9)));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Bearish Engulfing", result.pattern());
        assertEquals(20, result.gdPeriod());
    }

    @Test
    void shouldNotDetectBearishEngulfingWithoutUptrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(18, 20.0, 0.4),

                List.of(
                        bar(12.0, 12.6, 11.8, 12.5),
                        bar(12.6, 12.7, 11.85, 11.9)));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectDarkCloudCoverAfterUptrend() {

        List<OhlcvBar> bars = concat(
                risingRun(18, 6.0, 0.36),

                List.of(
                        // grüne Kerze, Teil des Uptrends
                        bar(12.0, 12.6, 11.8, 12.5),

                        // rote Kerze schließt tief in den Body der grünen Kerze
                        bar(12.7, 12.9, 12.1, 12.2)));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Dark Cloud Cover", result.pattern());
        assertEquals(20, result.gdPeriod());
    }

    @Test
    void shouldNotDetectDarkCloudCoverWithoutUptrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(18, 20.0, 0.4),

                List.of(
                        bar(12.0, 12.6, 11.8, 12.5),
                        bar(12.7, 12.9, 12.1, 12.2)));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectBearishAbandonedBaby() {

        List<OhlcvBar> bars = List.of(
                // Füllkerzen (nur für Mindestlänge, kein Trend erforderlich)
                bar(9.0, 9.3, 8.8, 9.1),
                bar(9.1, 9.4, 8.9, 9.2),
                bar(9.2, 9.5, 9.0, 9.3),
                bar(9.3, 9.6, 9.1, 9.4),
                bar(9.4, 9.7, 9.2, 9.5),

                // lange grüne Kerze
                bar(10.0, 10.6, 9.9, 10.5),

                // Doji mit Gap nach oben
                bar(10.8, 10.85, 10.75, 10.82),

                // lange rote Kerze mit Gap nach unten
                bar(10.65, 10.68, 10.0, 10.05));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Bearish Abandoned Baby", result.pattern());
        assertNull(result.gdPeriod());
    }

    /** Erzeugt `count` konsekutive bullische Kerzen, steigend von startPrice in stepPerBar-Schritten. */
    private static List<OhlcvBar> risingRun(int count, double startPrice, double stepPerBar) {
        List<OhlcvBar> bars = new ArrayList<>();
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            double open = price;
            double close = price + stepPerBar;
            double high = close + stepPerBar * 0.2;
            double low = open - stepPerBar * 0.2;
            bars.add(bar(open, high, low, close));
            price = close;
        }
        return bars;
    }

    /** Erzeugt `count` konsekutive bearische Kerzen, fallend von startPrice in stepPerBar-Schritten. */
    private static List<OhlcvBar> decliningRun(int count, double startPrice, double stepPerBar) {
        List<OhlcvBar> bars = new ArrayList<>();
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            double open = price;
            double close = price - stepPerBar;
            double high = open + stepPerBar * 0.2;
            double low = close - stepPerBar * 0.2;
            bars.add(bar(open, high, low, close));
            price = close;
        }
        return bars;
    }

    @SafeVarargs
    private static List<OhlcvBar> concat(List<OhlcvBar>... parts) {
        List<OhlcvBar> result = new ArrayList<>();
        for (List<OhlcvBar> part : parts) {
            result.addAll(part);
        }
        return result;
    }

    /**
     * Fortlaufender Zähler statt Fixdatum - siehe BullishCandlePatternsTest für
     * die Begründung (ElliottAnalysisUtil.toBarSeries() braucht eine strikt
     * aufsteigende endTime pro Kerze).
     */
    private static int dayOffset = 0;

    private static OhlcvBar bar(double open,
            double high,
            double low,
            double close) {

        return new OhlcvBar(
                java.time.LocalDate.of(2025, 1, 1).plusDays(dayOffset++).toString(),
                open,
                high,
                low,
                close,
                1000d);
    }
}

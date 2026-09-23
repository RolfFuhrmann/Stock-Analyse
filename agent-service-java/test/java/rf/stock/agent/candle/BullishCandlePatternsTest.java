package rf.stock.agent.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Hammer/Bullish Engulfing/Morning Star/Piercing Line sind seit 23./24.08.
 * ta4j-basiert (Geometrie nachgebaut aus ta4js RealBodyIndicator/Bar/Num,
 * siehe BullishCandlePatterns.java-Klassenkommentar) mit einer GD200→GD50→
 * GD20-Kaskade statt der alten CandleUtils-Trendprüfung als Downtrend-
 * Vorbedingung. Mit nur ~19-20 Testkerzen ist die "GD200"/"GD50" faktisch
 * der Durchschnitt aller verfügbaren Kerzen (ta4js SMAIndicator liefert bei
 * zu wenig Historie einen Partial-Average statt eines Fehlers) - bei den
 * hier verwendeten, durchgängig fallenden Kursreihen bestätigt GD200 daher
 * praktisch immer als Erstes den Downtrend (assertEquals(200,
 * result.gdPeriod()) in den Erkennungs-Tests unten). Ein echter Test für den
 * Kaskaden-Fallback auf GD50/GD20 (Kurs nur kurzfristig unter dem kürzeren
 * GD, langfristig aber noch über GD200) bräuchte deutlich mehr als 200
 * Testkerzen und ist hier bewusst ausgespart.
 * Abandoned Baby ist von alldem nicht betroffen (weiterhin Eigenentwicklung,
 * kein GD involviert).
 */
class BullishCandlePatternsTest {

    @Test
    void shouldDetectHammerAfterLowerHighsAndLowerLows() {

        List<OhlcvBar> bars = concat(
                // 19 Kerzen Downtrend (GD-kompatibel)
                decliningRun(19, 30.0, 0.7),

                // Hammer
                List.of(bar(16.40, 16.55, 14.30, 16.48)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Hammer", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldDetectHammerAfterLowerCloses() {

        List<OhlcvBar> bars = concat(
                decliningRun(19, 30.0, 0.65),

                // Hammer
                List.of(bar(17.20, 17.35, 15.20, 17.28)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Hammer", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldNotDetectHammerWithoutDowntrend() {

        List<OhlcvBar> bars = concat(
                risingRun(19, 12.0, 0.5),

                // Hammerform vorhanden, aber kein Downtrend davor
                List.of(bar(22.0, 22.1, 19.8, 22.05)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectAbandonedBabyAfterLowerHighsAndLowerLowsDowntrend() {

        List<OhlcvBar> bars = List.of(

                // Downtrend (5 Kerzen)
                bar(20.0, 20.5, 19.0, 19.2),
                bar(19.3, 19.8, 18.2, 18.4),
                bar(18.5, 18.9, 17.5, 17.8),
                bar(17.9, 18.2, 16.8, 17.2),
                bar(17.3, 17.6, 16.1, 16.5),

                // große rote Kerze
                bar(16.3, 16.5, 14.2, 14.4),

                // Doji mit Gap Down
                bar(13.7, 13.8, 13.6, 13.71),

                // große grüne Kerze mit Gap Up
                bar(14.1, 16.2, 14.0, 15.9));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Bullish Abandoned Baby", result.pattern());
    }

    @Test
    void shouldDetectAbandonedBabyAfterLowerClosesDowntrend() {

        List<OhlcvBar> bars = List.of(

                bar(20.0, 20.6, 19.6, 19.5),
                bar(19.6, 20.2, 19.1, 19.0),
                bar(19.2, 19.9, 18.9, 18.4),
                bar(18.6, 19.4, 18.2, 17.9),
                bar(18.0, 18.8, 17.5, 17.3),

                // große rote Kerze
                bar(17.2, 17.4, 15.0, 15.2),

                // Doji
                bar(14.6, 14.7, 14.5, 14.61),

                // große grüne Kerze
                bar(14.9, 17.0, 14.8, 16.8));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Bullish Abandoned Baby", result.pattern());
    }

    @Test
    void shouldNotDetectAbandonedBabyWithoutDowntrend() {

        List<OhlcvBar> bars = List.of(

                bar(20, 21, 19, 20.5),
                bar(20.5, 21.5, 20, 21),
                bar(21, 22, 20.5, 21.5),
                bar(21.5, 22.5, 21.2, 22),
                bar(22, 23, 21.5, 22.4),

                // rote Kerze
                bar(22.3, 22.5, 20.2, 20.4),

                // Doji
                bar(19.7, 19.8, 19.6, 19.71),

                // grüne Kerze
                bar(20.0, 22.3, 19.9, 22.0));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectBullishEngulfingAfterLowerHighsAndLowerLowsDowntrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(18, 30.0, 0.75),

                List.of(
                        // kleine rote Kerze, Teil des Downtrends
                        bar(17.3, 17.6, 16.1, 16.5),

                        // große grüne Kerze engulfed den Body
                        bar(16.4, 17.8, 16.2, 17.6)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Bullish Engulfing", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldDetectBullishEngulfingAfterLowerClosesDowntrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(18, 30.0, 0.7),

                List.of(
                        // kleine rote Kerze, Teil des Downtrends
                        bar(17.8, 18.0, 16.5, 16.8),

                        // engulfing
                        bar(16.6, 18.3, 16.5, 18.2)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Bullish Engulfing", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldNotDetectBullishEngulfingWithoutDowntrend() {

        List<OhlcvBar> bars = concat(
                risingRun(18, 12.0, 0.5),

                List.of(
                        bar(22.3, 22.5, 21.7, 21.8),
                        bar(21.6, 23.0, 21.5, 22.8)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectPiercingLineAfterDowntrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(18, 30.0, 0.75),

                List.of(
                        // große rote Kerze, Teil des Downtrends
                        bar(17.3, 17.6, 16.0, 16.3),

                        // Piercing: öffnet unter dem Vortages-Close, schließt weit über der Body-Mitte
                        bar(16.1, 17.2, 16.0, 17.0)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Piercing Line", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldNotDetectPiercingLineWithoutDowntrend() {

        List<OhlcvBar> bars = concat(
                risingRun(18, 12.0, 0.5),

                List.of(
                        bar(22.3, 22.6, 21.6, 21.9),
                        bar(21.7, 23.0, 21.6, 22.7)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
    }

    @Test
    void shouldDetectMorningStarAfterLowerHighsAndLowerLowsDowntrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(17, 30.0, 0.8),

                List.of(
                        // Lange rote Kerze
                        bar(17.1, 17.3, 15.0, 15.2),

                        // Stern
                        bar(14.8, 14.9, 14.4, 14.7),

                        // Lange grüne Kerze
                        bar(14.8, 17.4, 14.7, 16.8)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Morning Star", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldDetectMorningStarAfterLowerClosesDowntrend() {

        List<OhlcvBar> bars = concat(
                decliningRun(17, 30.0, 0.75),

                List.of(
                        // Lange rote Kerze
                        bar(17.8, 18.0, 15.6, 15.8),

                        // Stern
                        bar(15.4, 15.5, 15.1, 15.3),

                        // Lange grüne Kerze
                        bar(15.4, 18.1, 15.3, 17.4)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Morning Star", result.pattern());
        assertEquals(200, result.gdPeriod());
    }

    @Test
    void shouldNotDetectMorningStarWithoutDowntrend() {

        List<OhlcvBar> bars = concat(
                risingRun(17, 12.0, 0.5),

                List.of(
                        bar(22.0, 22.2, 19.8, 20.0),
                        bar(19.6, 19.7, 19.3, 19.5),
                        bar(19.6, 22.3, 19.5, 21.8)));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
        assertNull(result.gdPeriod());
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

    @SafeVarargs
    private static List<OhlcvBar> concat(List<OhlcvBar>... parts) {
        List<OhlcvBar> result = new ArrayList<>();
        for (List<OhlcvBar> part : parts) {
            result.addAll(part);
        }
        return result;
    }

    /**
     * Fortlaufender Zähler statt Fixdatum: ElliottAnalysisUtil.toBarSeries()
     * (jetzt auch von BullishCandlePatterns genutzt) braucht pro Kerze eine
     * strikt aufsteigende endTime - die alte CandleUtils-Logik ignorierte das
     * Datum komplett, daher hatten alle Testkerzen bisher denselben Fixwert
     * ("2025-01-01"), was jetzt zu einem Fehler bei der BarSeries-Konstruktion
     * führen würde.
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

package rf.stock.agent.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

class BearishCandlePatternsTest {

    @Test
    void shouldDetectShootingStarAfterUptrend() {

        List<OhlcvBar> bars = List.of(
                // 5 Kerzen Uptrend
                bar(10.0, 10.6, 9.8, 10.5),
                bar(10.5, 11.1, 10.3, 11.0),
                bar(11.0, 11.6, 10.8, 11.5),
                bar(11.5, 12.1, 11.3, 12.0),
                bar(12.0, 12.6, 11.8, 12.5),

                // Shooting Star
                bar(12.5, 13.5, 12.45, 12.6));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Shooting Star", result.pattern());
    }

    @Test
    void shouldDetectBearishEngulfingAfterUptrend() {

        List<OhlcvBar> bars = List.of(
                // Uptrend (4 Kerzen)
                bar(10.0, 10.6, 9.8, 10.5),
                bar(10.5, 11.1, 10.3, 11.0),
                bar(11.0, 11.6, 10.8, 11.5),
                bar(11.5, 12.1, 11.3, 12.0),

                // grüne Kerze, Teil des Uptrends
                bar(12.0, 12.6, 11.8, 12.5),

                // rote Kerze engulfed den Body
                bar(12.6, 12.7, 11.85, 11.9));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Bearish Engulfing", result.pattern());
    }

    @Test
    void shouldNotDetectBearishEngulfingWithoutUptrend() {

        List<OhlcvBar> bars = List.of(
                bar(20, 19, 21, 19.5),
                bar(19.5, 18.5, 20, 19.0),
                bar(19.0, 18.0, 19.5, 18.4),
                bar(18.4, 17.4, 18.9, 17.9),

                bar(17.7, 18.5, 17.2, 18.3),

                bar(18.4, 18.6, 17.1, 17.3));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertNull(result.pattern());
    }

    @Test
    void shouldDetectDarkCloudCoverAfterUptrend() {

        List<OhlcvBar> bars = List.of(
                // Uptrend (4 Kerzen)
                bar(10.0, 10.6, 9.8, 10.5),
                bar(10.5, 11.1, 10.3, 11.0),
                bar(11.0, 11.6, 10.8, 11.5),
                bar(11.5, 12.1, 11.3, 12.0),

                // grüne Kerze, Teil des Uptrends
                bar(12.0, 12.6, 11.8, 12.5),

                // rote Kerze schließt tief in den Body der grünen Kerze
                bar(12.7, 12.9, 12.1, 12.2));

        CandlePatternResult result = BearishCandlePatterns.detect(bars);

        assertEquals("Dark Cloud Cover", result.pattern());
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
    }

    private static OhlcvBar bar(double open,
            double high,
            double low,
            double close) {

        return new OhlcvBar(
                "2025-01-01",
                open,
                high,
                low,
                close,
                1000d);
    }
}

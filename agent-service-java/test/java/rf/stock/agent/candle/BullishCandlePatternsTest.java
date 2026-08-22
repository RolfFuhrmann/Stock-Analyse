package rf.stock.agent.candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

class BullishCandlePatternsTest {

    @Test
    void shouldDetectHammerAfterLowerHighsAndLowerLows() {

        List<OhlcvBar> bars = List.of(
                // 5 Kerzen Downtrend
                bar(20.0, 20.5, 19.0, 19.2), // bearish
                bar(19.3, 19.8, 18.2, 18.4), // bearish
                bar(18.5, 18.9, 17.5, 17.8), // bearish
                bar(17.9, 18.2, 16.8, 17.2), // bearish
                bar(17.3, 17.6, 16.1, 16.5), // bearish

                // Hammer
                bar(16.40, 16.55, 14.30, 16.48));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Hammer", result.pattern());
    }

    @Test
    void shouldDetectHammerAfterLowerCloses() {

        List<OhlcvBar> bars = List.of(
                bar(20.0, 20.6, 19.6, 19.5), // bearish
                bar(19.6, 20.2, 19.1, 19.0), // bearish
                bar(19.2, 19.9, 18.9, 18.4), // bearish
                bar(18.6, 19.4, 18.2, 17.9), // bearish
                bar(18.0, 18.8, 17.5, 17.3), // bearish

                // Hammer
                bar(17.20, 17.35, 15.20, 17.28));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Hammer", result.pattern());
    }

    @Test
    void shouldNotDetectHammerWithoutDowntrend() {

        List<OhlcvBar> bars = List.of(
                bar(20, 21, 19, 20.5),
                bar(20.5, 21.5, 20, 21),
                bar(21, 22, 20.5, 21.6),
                bar(21.6, 22.2, 21.2, 21.8),
                bar(21.8, 22.5, 21.5, 22.0),

                // Hammerform vorhanden
                bar(22.0, 22.1, 19.8, 22.05));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
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
    }

    @Test
    void shouldDetectBullishEngulfingAfterLowerHighsAndLowerLowsDowntrend() {

        List<OhlcvBar> bars = List.of(

                // Downtrend (4 Kerzen)
                bar(20.0, 20.5, 19.0, 19.2),
                bar(19.3, 19.8, 18.2, 18.4),
                bar(18.5, 18.9, 17.5, 17.8),
                bar(17.9, 18.2, 16.8, 17.2),

                // kleine rote Kerze, Teil des Downtrends
                bar(17.3, 17.6, 16.1, 16.5),

                // große grüne Kerze engulfed den Body
                bar(16.4, 17.8, 16.2, 17.6));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Bullish Engulfing", result.pattern());
    }

    @Test
    void shouldDetectBullishEngulfingAfterLowerClosesDowntrend() {

        List<OhlcvBar> bars = List.of(

                // Downtrend (4 Kerzen)
                bar(20.0, 20.6, 19.6, 19.5),
                bar(19.6, 20.2, 19.1, 19.0),
                bar(19.2, 19.9, 18.9, 18.4),
                bar(18.6, 19.4, 18.2, 17.9),

                // kleine rote Kerze, Teil des Downtrends
                bar(17.8, 18.0, 16.5, 16.8),

                // engulfing
                bar(16.6, 18.3, 16.5, 18.2));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Bullish Engulfing", result.pattern());
    }

    @Test
    void shouldNotDetectBullishEngulfingWithoutDowntrend() {

        List<OhlcvBar> bars = List.of(

                bar(20, 21, 19, 20.5),
                bar(20.5, 21.5, 20, 21),
                bar(21, 22, 20.5, 21.6),
                bar(21.6, 22.5, 21.2, 22),

                bar(22.3, 22.5, 21.7, 21.8),

                bar(21.6, 23.0, 21.5, 22.8));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
    }

    @Test
    void shouldDetectMorningStarAfterLowerHighsAndLowerLowsDowntrend() {

        List<OhlcvBar> bars = List.of(

                // Downtrend (4 Kerzen)
                bar(20.0, 20.5, 19.0, 19.2),
                bar(19.3, 19.8, 18.2, 18.4),
                bar(18.5, 18.9, 17.5, 17.8),
                bar(17.9, 18.2, 16.8, 17.2),

                // Lange rote Kerze
                bar(17.1, 17.3, 15.0, 15.2),

                // Stern
                bar(14.8, 14.9, 14.4, 14.7),

                // Lange grüne Kerze
                bar(14.8, 17.4, 14.7, 16.8));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Morning Star", result.pattern());
    }

    @Test
    void shouldDetectMorningStarAfterLowerClosesDowntrend() {

        List<OhlcvBar> bars = List.of(

                bar(20.0, 20.6, 19.6, 19.5),
                bar(19.6, 20.2, 19.1, 19.0),
                bar(19.2, 19.9, 18.9, 18.4),
                bar(18.6, 19.4, 18.2, 17.9),

                // Lange rote Kerze
                bar(17.8, 18.0, 15.6, 15.8),

                // Stern
                bar(15.4, 15.5, 15.1, 15.3),

                // Lange grüne Kerze
                bar(15.4, 18.1, 15.3, 17.4));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertEquals("Morning Star", result.pattern());
    }

    @Test
    void shouldNotDetectMorningStarWithoutDowntrend() {

        List<OhlcvBar> bars = List.of(

                bar(20, 21, 19, 20.5),
                bar(20.5, 21.5, 20, 21),
                bar(21, 22, 20.5, 21.6),
                bar(21.6, 22.5, 21.2, 22),

                bar(22.0, 22.2, 19.8, 20.0),

                bar(19.6, 19.7, 19.3, 19.5),

                bar(19.6, 22.3, 19.5, 21.8));

        CandlePatternResult result = BullishCandlePatterns.detect(bars);

        assertNull(result.pattern());
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
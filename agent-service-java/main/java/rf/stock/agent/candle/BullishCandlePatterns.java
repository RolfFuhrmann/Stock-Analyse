package rf.stock.agent.candle;

import java.util.List;

import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Erkennung bullischer Kerzen-Formationen (Reversals nach Abwärtstrend).
 * Bullish Abandoned Baby
 * Morning Star
 * Bullish Engulfing
 * Piercing Line
 * Hammer
 */

public class BullishCandlePatterns {
    private BullishCandlePatterns() {
        /* This utility class should not be instantiated */
    }

    public static CandlePatternResult detect(List<OhlcvBar> candleSticksToValidate) {
        if (candleSticksToValidate == null || candleSticksToValidate.size() < 6) {
            return CandlePatternResult.none();
        }

        CandleUtils candleSticksUtil = new CandleUtils(candleSticksToValidate);

        if (detectAbandonedBaby(candleSticksUtil))
            return new CandlePatternResult("Bullish Abandoned Baby", 5);
        if (detectMorningStar(candleSticksUtil))
            return new CandlePatternResult("Morning Star", 4);
        if (detectEngulfing(candleSticksUtil))
            return new CandlePatternResult("Bullish Engulfing", 3);
        if (detectPiercing(candleSticksUtil))
            return new CandlePatternResult("Piercing Line", 2);
        if (detectHammer(candleSticksUtil))
            return new CandlePatternResult("Hammer", 1);

        return CandlePatternResult.none();
    }

    /**
     * Regel:
     * Auf eine große rote Kerze folgt ein Doji komplett isoliert durch ein Gap
     * (Abstand) nach unten.
     * Die dritte, große grüne Kerze springt per Gap wieder nach oben.
     */
    private static boolean detectAbandonedBaby(CandleUtils candleSticksUtil) {

        if (candleSticksUtil.length < 8 || !candleSticksUtil.hasDowntrendBefore(-3, 4)) {
            return false;
        }

        return candleSticksUtil.isBearish(-3)
                && candleSticksUtil.isDoji(-2, 0.35)
                && candleSticksUtil.high(-2) < candleSticksUtil.low(-3)
                && candleSticksUtil.isBullish(-1)
                && candleSticksUtil.low(-1) > candleSticksUtil.high(-2);
    }

    /**
     * Regel:
     * Lange rote Kerze, gefolgt von einer kurzen, tiefer liegenden Kerze (Farbe
     * egal, oft als "Stern" bezeichnet) und einer anschließenden langen grünen
     * Kerze.
     * Die lange rote Kerze ist bewusst Teil des geforderten Downtrends (daher
     * patternStartIndex -2 statt -3 und Mindestlänge 7 statt 8).
     */
    private static boolean detectMorningStar(CandleUtils candleSticksUtil) {

        if (candleSticksUtil.length < 7 || !candleSticksUtil.hasDowntrendBefore(-2, 5)) {
            return false;
        }

        return candleSticksUtil.isBearish(-3)
                && candleSticksUtil.body(-2) < candleSticksUtil.body(-3)
                && candleSticksUtil.high(-2) < candleSticksUtil.high(-3)
                && candleSticksUtil.low(-2) < candleSticksUtil.low(-3)
                && candleSticksUtil.isBullish(-1)
                && candleSticksUtil.close(-1) > candleSticksUtil.midpoint(-3)
                && candleSticksUtil.body(-1) > candleSticksUtil.body(-2);
    }

    /**
     * Regel:
     * Eine kleine rote Kerze wird am nächsten Zeitraum vollständig vom großen
     * Körper einer grünen Kerze umschlossen.
     * Analog zum Morning Star ist die kleine rote Kerze bewusst Teil des
     * geforderten Downtrends (daher patternStartIndex -1 statt -2 und
     * Mindestlänge 6 statt 7).
     */
    private static boolean detectEngulfing(CandleUtils candleSticksUtil) {

        if (candleSticksUtil.length < 6 || !candleSticksUtil.hasDowntrendBefore(-1, 5)) {
            return false;
        }

        // Exklusivität:
        // Ein Morning Star hat Vorrang vor einem Bullish Engulfing.
        if (candleSticksUtil.length >= 8 && candleSticksUtil.hasDowntrendBefore(-3, 4)) {

            boolean isMorningStarSetup = candleSticksUtil.isBearish(-3)
                    && candleSticksUtil.body(-2) < candleSticksUtil.body(-3)
                    && candleSticksUtil.isBullish(-1)
                    && candleSticksUtil.close(-1) > candleSticksUtil.midpoint(-3);

            if (isMorningStarSetup) {
                return false;
            }
        }

        return candleSticksUtil.isBearish(-2)
                && candleSticksUtil.isBullish(-1)
                && candleSticksUtil.open(-1) <= candleSticksUtil.close(-2)
                && candleSticksUtil.close(-1) >= candleSticksUtil.open(-2)
                && candleSticksUtil.body(-1) > candleSticksUtil.body(-2);
    }

    /**
     * Regel:
     * Eine große rote Kerze wird gefolgt von einer grünen Kerze, die unter dem Tief
     * der roten Kerze eröffnet, dann aber weit nach oben zieht und über 50% des
     * roten Körpers schließt.
     */
    private static boolean detectPiercing(CandleUtils candleSticksUtil) {
        if (candleSticksUtil.length < 7 || !candleSticksUtil.hasDowntrendBefore(-2, 5))
            return false;

        double targetLine = candleSticksUtil.midpoint(-2) - (candleSticksUtil.body(-2));

        return candleSticksUtil.isBearish(-2)
                && candleSticksUtil.isBullish(-1)
                && candleSticksUtil.open(-1) < candleSticksUtil.low(-2)
                && candleSticksUtil.close(-1) > targetLine
                && candleSticksUtil.close(-1) <= candleSticksUtil.open(-2);
    }

    /**
     * Regel:
     * Kleiner Körper am oberen Ende der Spanne mit einem sehr langen unteren
     * Schatten (mindestens doppelt so lang wie der Körper)
     */
    private static boolean detectHammer(CandleUtils candleSticksUtil) {
        if (candleSticksUtil.length < 6 || !candleSticksUtil.hasDowntrendBefore(-1, 5))
            return false;

        double span = candleSticksUtil.high(-1) - candleSticksUtil.low(-1);
        if (span == 0)
            return false;

        return candleSticksUtil.body(-1) / span < 0.35
                && candleSticksUtil.body(-1) / span > 0.02
                && candleSticksUtil.lowerShadow(-1) >= span * 0.55
                && candleSticksUtil.upperShadow(-1) <= span * 0.15;
    }
}

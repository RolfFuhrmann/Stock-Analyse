package rf.stock.agent.candle;

import java.util.List;

import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Erkennung bearischer Kerzen-Formationen (Reversals nach Aufwärtstrend).
 * Bearish Abandoned Baby
 * Dark Cloud Cover
 * Bearish Engulfing
 * Shooting Star
 */
public class BearishCandlePatterns {
    private BearishCandlePatterns() {
        /* This utility class should not be instantiated */
    }

    public static CandlePatternResult detect(List<OhlcvBar> candleSticksToValidate) {
        if (candleSticksToValidate == null || candleSticksToValidate.size() < 6) {
            return CandlePatternResult.none();
        }

        CandleUtils candleSticksUtil = new CandleUtils(candleSticksToValidate);

        if (detectAbandonedBaby(candleSticksUtil))
            return new CandlePatternResult("Bearish Abandoned Baby", 5);
        if (detectDarkCloudCover(candleSticksUtil))
            return new CandlePatternResult("Dark Cloud Cover", 4);
        if (detectEngulfing(candleSticksUtil))
            return new CandlePatternResult("Bearish Engulfing", 3);
        if (detectShootingStar(candleSticksUtil))
            return new CandlePatternResult("Shooting Star", 2);

        return CandlePatternResult.none();
    }

    /**
     * Regel:
     * Auf eine lange grüne Kerze folgt ein Doji komplett isoliert durch ein Gap
     * (Abstand) nach oben. Die dritte, lange rote Kerze öffnet mit einem Gap nach
     * unten.
     */
    private static boolean detectAbandonedBaby(CandleUtils candleSticksUtil) {

        if (candleSticksUtil.length < 8) {
            return false;
        }

        return candleSticksUtil.isBullish(-3)
                && candleSticksUtil.isDoji(-2, 0.35)
                && candleSticksUtil.low(-2) > candleSticksUtil.high(-3)
                && candleSticksUtil.isBearish(-1)
                && candleSticksUtil.high(-1) < candleSticksUtil.low(-2);
    }

    /**
     * Regel:
     * Eine große grüne Kerze wird gefolgt von einer roten Kerze, die über dem Hoch
     * der grünen Kerze eröffnet, dann aber tief abstürzt und **unter der 50%-Linie
     * des grünen Körpers** schließt.
     */
    private static boolean detectDarkCloudCover(CandleUtils candleSticksUtil) {

        if (candleSticksUtil.length < 6 || !candleSticksUtil.hasUptrendBefore(-1, 5)) {
            return false;
        }

        double targetLine = candleSticksUtil.midpoint(-2);

        return candleSticksUtil.isBullish(-2)
                && candleSticksUtil.isBearish(-1)
                && candleSticksUtil.open(-1) > candleSticksUtil.high(-2)
                && candleSticksUtil.close(-1) < targetLine
                && candleSticksUtil.close(-1) >= candleSticksUtil.open(-2);
    }

    /**
     * Regel:
     * Der Körper einer kleinen grünen Kerze wird am Folgetag vollständig vom
     * großen, massiven Körper einer roten Kerze umschlossen (verschlungen).
     */
    private static boolean detectEngulfing(CandleUtils candleSticksUtil) {

        if (candleSticksUtil.length < 6 || !candleSticksUtil.hasUptrendBefore(-1, 5)) {
            return false;
        }

        return candleSticksUtil.isBullish(-2)
                && candleSticksUtil.isBearish(-1)
                && candleSticksUtil.open(-1) >= candleSticksUtil.open(-2)
                && candleSticksUtil.close(-1) <= candleSticksUtil.close(-2)
                && candleSticksUtil.body(-1) > candleSticksUtil.body(-2);
    }

    /**
     * Regel:
     * Kleiner Körper (Farbe rot ist bärischer, grün aber auch valide) am unteren
     * Ende der Spanne mit einem extrem langen oberen Schatten (mindestens doppelt
     * so lang wie der Körper).
     */
    private static boolean detectShootingStar(CandleUtils candleSticksUtil) {
        if (candleSticksUtil.length < 6 || !candleSticksUtil.hasUptrendBefore(-1, 5))
            return false;

        double span = candleSticksUtil.high(-1) - candleSticksUtil.low(-1);
        if (span == 0)
            return false;

        return candleSticksUtil.body(-1) / span < 0.30
                && candleSticksUtil.upperShadow(-1) >= span * 0.60
                && candleSticksUtil.lowerShadow(-1) <= span * 0.10;
    }
}

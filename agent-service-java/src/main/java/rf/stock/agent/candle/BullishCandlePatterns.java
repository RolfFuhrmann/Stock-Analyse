package rf.stock.agent.candle;

import java.util.List;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.candles.RealBodyIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import rf.stock.agent.candle.BullishEngulfing.BullishEngulfingPattern;
import rf.stock.agent.candle.Hammer.HammerPattern;
import rf.stock.agent.indicator.ElliottAnalysisUtil;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Erkennung bullischer Kerzen-Formationen (Reversals nach Abwärtstrend).
 * Bullish Abandoned Baby
 * Morning Star
 * Bullish Engulfing
 * Piercing Line
 * Hammer
 *
 * Seit 23.08. auf ta4j umgestellt, AUSSER Abandoned Baby - dafür gibt es
 * keinen ta4j-Indikator, bleibt daher unverändert bei der Eigenentwicklung
 * (CandleUtils, negative Indizierung).
 *
 * WICHTIG (korrigiert 23.08., nach Prüfung des echten ta4j-0.24.1-JARs):
 * ta4js Hammer-/MorningStar-/Piercing-Indikatoren binden ihre Trendprüfung
 * WEITERHIN fest an einen internen, nicht austauschbaren ADX-basierten
 * DownTrendIndicator (byte-identisch zu 0.22.7 - eine zunächst angenommene
 * Rule-Injection-Erweiterung existiert in 0.24.1 NICHT, per javap/strings-
 * Analyse des JARs verifiziert). Deshalb: Geometrie mit ta4js eigenen,
 * wiederverwendbaren Bausteinen (RealBodyIndicator, Bar/Num) nachgebaut -
 * identische Formeln und Default-Schwellwerte wie in ta4js Originalklassen -
 * aber OHNE deren eingebaute Trendprüfung, kombiniert mit einer eigenen
 * GD-Kaskade statt ADX.
 *
 * GD-Kaskade (24.08., Rolfs Wunsch): jedes der verbliebenen ta4j-basierten
 * Muster (Morning Star, Piercing Line) wird zuerst mit GD200 auf Downtrend
 * geprüft, dann GD50, dann GD20 - der erste GD, unter dem der Schlusskurs
 * liegt, gilt als Bestätigung (gdPeriod im Ergebnis).
 *
 * Piercing: ta4j hat zwei Implementierungen (PiercingIndicator vs.
 * PiercingLineIndicator) - hier an PiercingLineIndicator angelehnt (neuer,
 * @since 0.22.3, mit explizit konfigurierbaren Gap-/Penetrations-Schwellen
 * statt fest verdrahtet).
 *
 * Hammer (06.09.) und Bullish Engulfing (07.09.) sind komplett eigenständig
 * gekapselt (candle/Hammer/HammerPattern bzw.
 * candle/BullishEngulfing/BullishEngulfingPattern) - beide mit eigener
 * Trendprüfung statt GD-Kaskade (Hammer: ADX/ta4js DownTrendIndicator,
 * Bullish Engulfing: neues Periodentief/EngulfingNewLowRule), eigenen
 * Bestätigungsfällen (Hammer: Close > Hammer-Close; Bullish Engulfing:
 * Close ODER High der 3. Kerze über der Engulfing-Kerze) und
 * candleDates/confirmed im CandlePatternResult statt gdPeriod.
 */

public class BullishCandlePatterns {

    private BullishCandlePatterns() {
        /* This utility class should not be instantiated */
    }

    public static CandlePatternResult detect(List<OhlcvBar> candleSticksToValidate) {
        if (candleSticksToValidate == null || candleSticksToValidate.size() < 6) {
            return CandlePatternResult.none();
        }

        // Abandoned Baby bleibt Eigenentwicklung (kein ta4j-Äquivalent vorhanden, kein GD).
        CandleUtils candleSticksUtil = new CandleUtils(candleSticksToValidate);
        if (detectAbandonedBaby(candleSticksUtil))
            return new CandlePatternResult("Bullish Abandoned Baby", 5, null, null, false);

        BarSeries series = ElliottAnalysisUtil.toBarSeries(candleSticksToValidate);
        int index = series.getEndIndex();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator[] gdIndicators = CandleGdCascade.buildGdIndicators(closePrice);

        if (morningStarGeometry(series, index)) {
            CandlePatternResult result = CandleGdCascade.withGdCascade(closePrice, gdIndicators, index,
                    "Morning Star", 4, true);
            if (result != null)
                return result;
        }
        // Bullish Engulfing: komplett gekapselt in candle/BullishEngulfing/
        // BullishEngulfingPattern (07.09.), analog zu Hammer. Trend hier über
        // EngulfingNewLowRule (neues Periodentief) statt GD-Kaskade.
        BullishEngulfingPattern.EngulfingMatch engulfingMatch = new BullishEngulfingPattern(series)
                .isBullishEngulfing(index);
        if (engulfingMatch.matched()) {
            boolean confirmed = engulfingMatch.engulfingCase() == BullishEngulfingPattern.EngulfingCase.ENGULFING_CONFIRMED;
            return new CandlePatternResult("Bullish Engulfing", 3, null, engulfingMatch.candleDates(), confirmed);
        }
        if (piercingLineGeometry(series, index)) {
            CandlePatternResult result = CandleGdCascade.withGdCascade(closePrice, gdIndicators, index,
                    "Piercing Line", 2, true);
            if (result != null)
                return result;
        }

        // Hammer: komplett gekapselt in candle/Hammer/HammerPattern (06.09.).
        // Trend hier ADX statt GD-Kaskade - siehe HammerPattern-Klassenkommentar.
        // "confirmed" (07.09.) ersetzt den vorherigen Namens-Suffix "(bestätigt)" -
        // der Pattern-Name bleibt jetzt für beide Fälle einheitlich "Hammer".
        HammerPattern.HammerMatch hammerMatch = new HammerPattern(series).isHammer(index);
        if (hammerMatch.matched()) {
            boolean confirmed = hammerMatch.hammerCase() == HammerPattern.HammerCase.HAMMER_CONFIRMED;
            return new CandlePatternResult("Hammer", 1, null, hammerMatch.candleDates(), confirmed);
        }

        return CandlePatternResult.none();
    }

    /**
     * Regel (unverändert, Eigenentwicklung - siehe Klassenkommentar):
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
     * Geometrie 1:1 nachgebaut aus ta4js MorningStarIndicator (Default-
     * Schwellwerte: smallBodyThresholdPercentage=0.015,
     * bigBodyThresholdPercentage=0.03) - OHNE die dort fest eingebaute
     * ADX-Trendprüfung (siehe Klassenkommentar).
     */
    private static boolean morningStarGeometry(BarSeries series, int index) {
        if (index < 2) {
            return false;
        }
        RealBodyIndicator body = new RealBodyIndicator(series);

        Bar firstBar = series.getBar(index - 2);
        Bar secondBar = series.getBar(index - 1);
        Bar thirdBar = series.getBar(index);

        Num smallBodyThreshold = series.numFactory().numOf(0.015);
        Num bigBodyThreshold = series.numFactory().numOf(0.03);

        Num firstBarPercentage = body.getValue(index - 2).abs().dividedBy(firstBar.getOpenPrice());
        Num secondBarPercentage = body.getValue(index - 1).abs().dividedBy(secondBar.getOpenPrice());
        Num thirdBarPercentage = body.getValue(index).abs().dividedBy(thirdBar.getOpenPrice());
        Num firstBarMiddlePoint = firstBar.getOpenPrice()
                .minus(firstBar.getClosePrice())
                .dividedBy(series.numFactory().numOf(2))
                .plus(firstBar.getClosePrice());

        return firstBar.isBearish() && firstBarPercentage.isGreaterThanOrEqual(bigBodyThreshold)
                && secondBar.getOpenPrice().isLessThan(firstBar.getClosePrice())
                && secondBarPercentage.isLessThanOrEqual(smallBodyThreshold)
                && thirdBar.getClosePrice().isGreaterThan(firstBarMiddlePoint) && thirdBar.isBullish()
                && thirdBarPercentage.isGreaterThanOrEqual(bigBodyThreshold);
    }

    /**
     * Geometrie 1:1 nachgebaut aus ta4js PiercingLineIndicator (Default-
     * Schwellwerte: bigBodyThresholdPercentage=0.03, gapThresholdPercentage=0.0,
     * penetrationThresholdPercentage=0.5) - OHNE die dort fest eingebaute
     * Trendprüfung (siehe Klassenkommentar).
     */
    private static boolean piercingLineGeometry(BarSeries series, int index) {
        if (index < 1) {
            return false;
        }
        RealBodyIndicator body = new RealBodyIndicator(series);

        Bar firstBar = series.getBar(index - 1);
        Bar secondBar = series.getBar(index);
        Num firstOpenPrice = firstBar.getOpenPrice();
        Num secondOpenPrice = secondBar.getOpenPrice();
        Num firstClosePrice = firstBar.getClosePrice();

        if (isInvalidDenominator(firstOpenPrice) || isInvalidDenominator(secondOpenPrice)
                || isInvalidDenominator(firstClosePrice)) {
            return false;
        }

        Num bigBodyThreshold = series.numFactory().numOf(0.03);
        Num penetrationThreshold = series.numFactory().numOf(0.5);

        Num firstBodyRatio = body.getValue(index - 1).abs().dividedBy(firstOpenPrice);
        Num secondBodyRatio = body.getValue(index).abs().dividedBy(secondOpenPrice);
        Num firstBodySize = firstOpenPrice.minus(firstClosePrice);
        Num requiredClose = firstClosePrice.plus(firstBodySize.multipliedBy(penetrationThreshold));

        return firstBar.isBearish() && firstBodyRatio.isGreaterThanOrEqual(bigBodyThreshold)
                && secondBar.isBullish() && secondBodyRatio.isGreaterThanOrEqual(bigBodyThreshold)
                && secondOpenPrice.isLessThan(firstClosePrice)
                && secondBar.getClosePrice().isGreaterThan(requiredClose)
                && secondBar.getClosePrice().isLessThan(firstOpenPrice);
    }

    private static boolean isInvalidDenominator(Num value) {
        return Num.isNaNOrNull(value) || Double.isNaN(value.doubleValue()) || value.isZero();
    }
}

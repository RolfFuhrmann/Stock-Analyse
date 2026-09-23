package rf.stock.agent.candle;

import java.util.List;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.candles.BearishEngulfingIndicator;
import org.ta4j.core.indicators.candles.RealBodyIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import rf.stock.agent.indicator.ElliottAnalysisUtil;
import rf.stock.agent.model.CandlePatternResult;
import rf.stock.agent.model.OhlcvBar;

/**
 * Erkennung bearischer Kerzen-Formationen (Reversals nach Aufwärtstrend).
 * Bearish Abandoned Baby
 * Dark Cloud Cover
 * Bearish Engulfing
 * Shooting Star
 *
 * Seit 24.08. auf ta4j umgestellt, AUSSER Abandoned Baby - analog zu
 * BullishCandlePatterns (siehe dortiger Klassenkommentar für den
 * technischen Hintergrund: ta4js Shooting-Star-/Dark-Cloud-Indikatoren
 * binden ihre Trendprüfung fest an einen internen, nicht austauschbaren
 * ADX-basierten UpTrendIndicator - per Analyse des echten ta4j-0.24.1-JARs
 * verifiziert, keine Rule-Injection verfügbar). Deshalb: Geometrie mit
 * ta4js eigenen, wiederverwendbaren Bausteinen (RealBodyIndicator, Bar/Num)
 * nachgebaut - identische Formeln und Default-Schwellwerte wie in ta4js
 * Originalklassen - aber OHNE deren eingebaute Trendprüfung, kombiniert mit
 * derselben GD-Kaskade wie bei den bullischen Mustern (siehe
 * CandleGdCascade), hier aber auf Uptrend geprüft (Schlusskurs ÜBER dem GD)
 * und in UMGEKEHRTER Reihenfolge: erst GD20, dann GD50, dann GD200 (Rolfs
 * ausdrücklicher Wunsch, 24.08. - bei den bullischen Mustern bleibt es bei
 * GD200→GD50→GD20).
 *
 * Bearish Engulfing hat in ta4j gar keine eingebaute Trendprüfung - dort
 * wird ta4js BearishEngulfingIndicator unverändert für die reine Geometrie
 * übernommen und die GD-Kaskade separat davor gehängt.
 *
 * Dark Cloud: ta4j hat zwei Implementierungen (DarkCloudIndicator vs.
 * DarkCloudCoverIndicator) - hier an DarkCloudCoverIndicator angelehnt
 * (neuer, @since 0.22.3, mit explizit konfigurierbaren Gap-/Penetrations-
 * Schwellen statt fest verdrahtet - dieselbe Wahl wie PiercingLineIndicator
 * bei den bullischen Mustern).
 *
 * Shooting-Star-Geometrie am 24.08. auf Rolfs Nachfrage ("bist du sicher,
 * dass hier ta4j eingebaut ist?") gegen den echten 0.24.1-Bytecode
 * verifiziert: Konstantenpool von ShootingStarIndicator.class enthält exakt
 * dieselben Feldnamen, lokalen Variablennamen UND Zahlenkonstanten (1.0/2.0)
 * wie die vendorte 0.22.7-Quelle - die Formel ist unverändert korrekt
 * übernommen. ta4js Regel prüft nur Schatten-zu-Body-VERHÄLTNISSE, keine
 * absolute Mindestgröße - eine unauffällig kleine Kerze kann die Ratios
 * rechnerisch erfüllen, ohne optisch wie ein "echter" Shooting Star zu
 * wirken. Bekannte Grenze reiner Verhältnis-Regeln, kein Implementierungsfehler.
 *
 * WICHTIG: Rolfs eigene Einschätzung dazu ("Die Praxis wird zeigen ob es
 * tatsächlich funktioniert", 24.08.) gilt unverändert - insbesondere die
 * GD-Kalibrierung (Periodenlänge, evtl. Steigungskriterium) ist noch nicht
 * an echten Marktdaten validiert.
 */

public class BearishCandlePatterns {
    private BearishCandlePatterns() {
        /* This utility class should not be instantiated */
    }

    public static CandlePatternResult detect(List<OhlcvBar> candleSticksToValidate) {
        if (candleSticksToValidate == null || candleSticksToValidate.size() < 6) {
            return CandlePatternResult.none();
        }

        // Abandoned Baby bleibt Eigenentwicklung (kein ta4j-Äquivalent vorhanden, kein GD).
        CandleUtils candleSticksUtil = new CandleUtils(candleSticksToValidate);
        if (detectAbandonedBaby(candleSticksUtil))
            return new CandlePatternResult("Bearish Abandoned Baby", 5, null, null, false);

        BarSeries series = ElliottAnalysisUtil.toBarSeries(candleSticksToValidate);
        int index = series.getEndIndex();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator[] gdIndicators = CandleGdCascade.buildGdIndicators(closePrice);

        if (darkCloudCoverGeometry(series, index)) {
            CandlePatternResult result = CandleGdCascade.withGdCascade(closePrice, gdIndicators, index,
                    "Dark Cloud Cover", 4, false);
            if (result != null)
                return result;
        }
        if (new BearishEngulfingIndicator(series).getValue(index)) {
            CandlePatternResult result = CandleGdCascade.withGdCascade(closePrice, gdIndicators, index,
                    "Bearish Engulfing", 3, false);
            if (result != null)
                return result;
        }
        if (shootingStarGeometry(series, index)) {
            CandlePatternResult result = CandleGdCascade.withGdCascade(closePrice, gdIndicators, index,
                    "Shooting Star", 2, false);
            if (result != null)
                return result;
        }

        return CandlePatternResult.none();
    }

    /**
     * Regel (unverändert, Eigenentwicklung - siehe Klassenkommentar):
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
     * Geometrie 1:1 nachgebaut aus ta4js DarkCloudCoverIndicator (Default-
     * Schwellwerte: bigBodyThresholdPercentage=0.03, gapThresholdPercentage=0.0,
     * penetrationThresholdPercentage=0.5) - OHNE die dort fest eingebaute
     * ADX/UpTrend-Prüfung (siehe Klassenkommentar).
     */
    private static boolean darkCloudCoverGeometry(BarSeries series, int index) {
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
        Num gapThreshold = series.numFactory().zero();
        Num penetrationThreshold = series.numFactory().numOf(0.5);

        Num firstBodyRatio = body.getValue(index - 1).abs().dividedBy(firstOpenPrice);
        Num secondBodyRatio = body.getValue(index).abs().dividedBy(secondOpenPrice);
        Num firstBodySize = firstClosePrice.minus(firstOpenPrice);
        Num requiredClose = firstClosePrice.minus(firstBodySize.multipliedBy(penetrationThreshold));
        Num gapRatio = secondOpenPrice.minus(firstClosePrice).dividedBy(firstClosePrice);

        if (isInvalidDenominator(requiredClose) || isInvalidDenominator(gapRatio)) {
            return false;
        }

        return firstBar.isBullish() && firstBodyRatio.isGreaterThanOrEqual(bigBodyThreshold)
                && secondBar.isBearish() && secondBodyRatio.isGreaterThanOrEqual(bigBodyThreshold)
                && secondOpenPrice.isGreaterThan(firstClosePrice)
                && gapRatio.isGreaterThanOrEqual(gapThreshold)
                && secondBar.getClosePrice().isLessThan(requiredClose)
                && secondBar.getClosePrice().isGreaterThan(firstOpenPrice);
    }

    private static boolean isInvalidDenominator(Num value) {
        return Num.isNaNOrNull(value) || Double.isNaN(value.doubleValue()) || value.isZero();
    }

    /**
     * Geometrie 1:1 nachgebaut aus ta4js ShootingStarIndicator (Default-
     * Schwellwerte: bodyToBottomWickRatio=1.0, bodyToUpperWickRatio=2.0) - OHNE
     * die dort fest eingebaute ADX/UpTrend-Prüfung (siehe Klassenkommentar).
     */
    private static boolean shootingStarGeometry(BarSeries series, int index) {
        RealBodyIndicator body = new RealBodyIndicator(series);

        Bar bar = series.getBar(index);
        Num openPrice = bar.getOpenPrice();
        Num closePrice = bar.getClosePrice();
        Num lowPrice = bar.getLowPrice();
        Num highPrice = bar.getHighPrice();

        Num bodyHeight = body.getValue(index).abs();
        if (bodyHeight.isZero()) {
            return false;
        }

        Num upperBodyBoundary = openPrice.max(closePrice);
        Num bottomBodyBoundary = openPrice.min(closePrice);
        Num bottomWickHeight = bottomBodyBoundary.minus(lowPrice);
        Num upperWickHeight = highPrice.minus(upperBodyBoundary);

        Num bodyToBottomWickRatio = series.numFactory().numOf(1.0);
        Num bodyToUpperWickRatio = series.numFactory().numOf(2.0);

        return upperWickHeight.dividedBy(bodyHeight).isGreaterThan(bodyToUpperWickRatio)
                && bottomWickHeight.dividedBy(bodyHeight).isLessThanOrEqual(bodyToBottomWickRatio);
    }
}

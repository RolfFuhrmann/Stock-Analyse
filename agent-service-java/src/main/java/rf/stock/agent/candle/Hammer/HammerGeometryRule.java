package rf.stock.agent.candle.Hammer;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.candles.RealBodyIndicator;
import org.ta4j.core.num.Num;

/**
 * Reine Kerzenform-Prüfung für den Hammer - identische Formel und
 * Default-Schwellwerte wie ta4js HammerIndicator (bodyToBottomWickRatio=2.0,
 * bodyToUpperWickRatio=1.0), aber OHNE dessen fest eingebaute
 * DownTrendIndicator-Prüfung (siehe AdxDowntrendRule - wird in
 * HammerPattern separat und entkoppelt kombiniert).
 *
 * Fast-Doji-Fallback (06.09., Rolfs Beobachtung anhand einer echten Boeing-
 * Kerze vom 28.08.): Bei einem Body nahe Null wird das Verhältnis
 * oberer-Docht/Body numerisch instabil - ein absolut winziger oberer Docht
 * kann trotzdem ein Verhältnis weit über der 1.0-Schwelle ergeben, einfach
 * weil der Nenner (Body) fast null ist. Beispiel: Body=0.081% vom Kurs,
 * oberer Docht=0.295% vom Kurs (auf dem Chart kaum wahrnehmbar) -> Ratio
 * 3.65, obwohl der Docht absolut betrachtet winzig ist.
 *
 * Fallback-Regel: Ist der Body kleiner als NEAR_DOJI_BODY_THRESHOLD_PCT vom
 * Schlusskurs, wird zusätzlich (als Alternative zur Ratio-zum-Body-Prüfung)
 * geprüft, ob die Dochte relativ zur GESAMTEN Kerzen-Range "hammerartig"
 * verteilt sind: oberer Docht <= 30% der Range UND unterer Docht >= 60% der
 * Range. Ein echter symmetrischer Doji (große Dochte auf beiden Seiten,
 * z.B. je ~45%) fällt weiterhin durch, da der untere Docht dann die 60%-
 * Schwelle nicht erreicht.
 */
public class HammerGeometryRule implements Rule {

    private static final double BODY_TO_BOTTOM_WICK_RATIO = 2.0;
    private static final double BODY_TO_UPPER_WICK_RATIO = 1.0;

    /** Body < 0.15% vom Kurs gilt als Fast-Doji. */
    private static final double NEAR_DOJI_BODY_THRESHOLD_PCT = 0.0015;
    /** Fast-Doji-Fallback: oberer Docht darf höchstens 30% der Gesamt-Range sein. */
    private static final double NEAR_DOJI_UPPER_WICK_MAX_RANGE_RATIO = 0.30;
    /** Fast-Doji-Fallback: unterer Docht muss mindestens 60% der Gesamt-Range sein. */
    private static final double NEAR_DOJI_LOWER_WICK_MIN_RANGE_RATIO = 0.60;

    private final BarSeries series;
    private final RealBodyIndicator body;

    public HammerGeometryRule(BarSeries series) {
        this.series = series;
        this.body = new RealBodyIndicator(series);
    }

    @Override
    public boolean isSatisfied(int index) {
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

        var numFactory = series.numFactory();

        boolean standardGeometryOk = bottomWickHeight.dividedBy(bodyHeight)
                .isGreaterThan(numFactory.numOf(BODY_TO_BOTTOM_WICK_RATIO))
                && upperWickHeight.dividedBy(bodyHeight).isLessThanOrEqual(numFactory.numOf(BODY_TO_UPPER_WICK_RATIO));

        if (standardGeometryOk) {
            return true;
        }

        // Fast-Doji-Fallback
        if (closePrice.isZero()) {
            return false;
        }
        Num bodyPct = bodyHeight.dividedBy(closePrice).abs();
        boolean isNearDoji = bodyPct.isLessThan(numFactory.numOf(NEAR_DOJI_BODY_THRESHOLD_PCT));
        if (!isNearDoji) {
            return false;
        }

        Num range = highPrice.minus(lowPrice);
        if (range.isZero()) {
            return false;
        }
        Num upperWickRangeRatio = upperWickHeight.dividedBy(range);
        Num lowerWickRangeRatio = bottomWickHeight.dividedBy(range);

        return upperWickRangeRatio.isLessThanOrEqual(numFactory.numOf(NEAR_DOJI_UPPER_WICK_MAX_RANGE_RATIO))
                && lowerWickRangeRatio.isGreaterThanOrEqual(numFactory.numOf(NEAR_DOJI_LOWER_WICK_MIN_RANGE_RATIO));
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

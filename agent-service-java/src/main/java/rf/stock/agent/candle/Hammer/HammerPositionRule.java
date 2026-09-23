package rf.stock.agent.candle.Hammer;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.Num;

public class HammerPositionRule implements Rule {

    private final BarSeries series;

    /*
     * Anteil der Previous-Candle-Range, der als
     * "unteres Drittel" betrachtet wird.
     *
     * 0.333333... = unteres Drittel
     */
    private final Num lowerThird;

    /*
     * Maximale Distanz zwischen Hammer-Body und
     * Previous-Body, relativ zur Previous-Range.
     *
     * Beispiel:
     * 0.05 = maximal 5 % der Previous-Range
     * unterhalb des Previous-Bodys.
     */
    private final Num bodyTolerance;

    public HammerPositionRule(
            BarSeries series,
            double lowerThird,
            double bodyTolerance) {

        this.series = series;
        this.lowerThird = series.numFactory().numOf(lowerThird);
        this.bodyTolerance = series.numFactory().numOf(bodyTolerance);
    }

    @Override
    public boolean isSatisfied(int index) {

        if (index < 1 || index > series.getEndIndex()) {
            return false;
        }

        var hammer = series.getBar(index);
        var previous = series.getBar(index - 1);

        Num previousHigh = previous.getHighPrice();
        Num previousLow = previous.getLowPrice();

        Num previousRange = previousHigh.minus(previousLow);

        if (previousRange.isZero()) {
            return false;
        }

        /*
         * -----------------------------
         * Hammer Body
         * -----------------------------
         */

        Num hammerBodyLow = min(
                hammer.getOpenPrice(),
                hammer.getClosePrice());

        Num hammerBodyHigh = max(
                hammer.getOpenPrice(),
                hammer.getClosePrice());

        /*
         * -----------------------------
         * Previous Body
         * -----------------------------
         */

        Num previousBodyLow = min(
                previous.getOpenPrice(),
                previous.getClosePrice());

        /*
         * =====================================================
         * Bedingung A:
         *
         * Der komplette Hammer-Body befindet sich
         * im unteren Drittel der Previous-Candle-Range.
         *
         * Grenze des unteren Drittels:
         *
         * Previous Low + 1/3 * Previous Range
         * =====================================================
         */

        Num lowerThirdBoundary = previousLow.plus(
                previousRange.multipliedBy(lowerThird));

        boolean bodyInLowerThird = hammerBodyLow.isGreaterThanOrEqual(previousLow)
                && hammerBodyHigh.isLessThanOrEqual(
                        lowerThirdBoundary);

        /*
         * =====================================================
         * Bedingung B:
         *
         * Hammer-Body befindet sich knapp unterhalb
         * des Previous-Bodys.
         *
         * Dafür muss der obere Rand des Hammer-Bodys
         * unterhalb des unteren Randes des Previous-Bodys
         * liegen.
         *
         * Der Abstand darf maximal bodyTolerance *
         * PreviousRange betragen.
         * =====================================================
         */

        Num maxAllowedGap = previousRange.multipliedBy(bodyTolerance);

        Num gap = previousBodyLow.minus(hammerBodyHigh);

        boolean bodyJustBelowPreviousBody = hammerBodyHigh.isLessThan(previousBodyLow)
                && gap.isLessThanOrEqual(maxAllowedGap);

        return bodyInLowerThird
                || bodyJustBelowPreviousBody;
    }

    private Num min(Num a, Num b) {
        return a.isLessThan(b) ? a : b;
    }

    private Num max(Num a, Num b) {
        return a.isGreaterThan(b) ? a : b;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

package rf.stock.agent.candle.Hammer;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.IsFallingRule;

public class DowntrendRule implements Rule {

    private final BarSeries series;
    private final SMAIndicator sma;
    private final int fallingPeriod;
    private final int priceActionPeriod;
    private final int requiredPriceActionBars;

    /**
     * @param series                  BarSeries
     * @param smaPeriod               SMA-Periode, z.B. 20
     * @param fallingPeriod           Anzahl Bars, über die SMA fallen muss, z.B. 5
     * @param priceActionPeriod       Anzahl Bars für High/Low-Prüfung, z.B. 5
     * @param requiredPriceActionBars Anzahl erforderlicher Lower-High/Lower-Low
     *                                Bars, z.B. 4
     */
    public DowntrendRule(
            BarSeries series,
            int smaPeriod,
            int fallingPeriod,
            int priceActionPeriod,
            int requiredPriceActionBars) {

        this.series = series;
        this.fallingPeriod = fallingPeriod;
        this.priceActionPeriod = priceActionPeriod;
        this.requiredPriceActionBars = requiredPriceActionBars;

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        this.sma = new SMAIndicator(close, smaPeriod);
    }

    @Override
    public boolean isSatisfied(int index) {

        if (index < 1 || index > series.getEndIndex()) {
            return false;
        }

        /*
         * Variante 1:
         *
         * SMA muss über die letzten fallingPeriod Bars
         * strikt fallen.
         */
        boolean smaFalling = index >= fallingPeriod
                && new IsFallingRule(sma, fallingPeriod)
                        .isSatisfied(index);

        /*
         * Variante 2:
         *
         * Innerhalb der letzten priceActionPeriod Bars
         * müssen mindestens requiredPriceActionBars Bars
         * entweder ein Lower High oder ein Lower Low haben.
         */
        boolean priceActionDowntrend = hasEnoughLowerHighOrLowerLow(index);

        return smaFalling || priceActionDowntrend;
    }

    private boolean hasEnoughLowerHighOrLowerLow(int index) {

        /*
         * Wir benötigen pro betrachteter Kerze eine
         * vorherige Kerze.
         *
         * Beispiel:
         *
         * priceActionPeriod = 5
         *
         * geprüft werden:
         * index-4 gegen index-5
         * index-3 gegen index-4
         * index-2 gegen index-3
         * index-1 gegen index-2
         * index gegen index-1
         */
        if (index < priceActionPeriod) {
            return false;
        }

        int count = 0;

        int firstIndex = index - priceActionPeriod + 1;

        for (int i = firstIndex; i <= index; i++) {

            var currentBar = series.getBar(i);
            var previousBar = series.getBar(i - 1);

            boolean lowerHigh = currentBar.getHighPrice()
                    .isLessThan(previousBar.getHighPrice());

            boolean lowerLow = currentBar.getLowPrice()
                    .isLessThan(previousBar.getLowPrice());

            if (lowerHigh || lowerLow) {
                count++;
            }
        }

        return count >= requiredPriceActionBars;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

package rf.stock.agent.candle.Hammer;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;

public class HammerOpenRule implements Rule {

    private final BarSeries series;

    public HammerOpenRule(BarSeries series) {
        this.series = series;
    }

    @Override
    public boolean isSatisfied(int index) {

        if (index < 1 || index > series.getEndIndex()) {
            return false;
        }

        var hammer = series.getBar(index);
        var previous = series.getBar(index - 1);

        return hammer.getOpenPrice()
                .isLessThan(previous.getClosePrice());
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

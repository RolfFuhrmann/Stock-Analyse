package rf.stock.agent.candle.Hammer;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.candles.HammerIndicator;

public class HammerRule implements Rule {

    private final HammerIndicator hammerIndicator;

    public HammerRule(BarSeries series) {
        this.hammerIndicator = new HammerIndicator(series);
    }

    @Override
    public boolean isSatisfied(int index) {

        if (index < 0 || index > hammerIndicator.getBarSeries().getEndIndex()) {
            return false;
        }

        return hammerIndicator.getValue(index);
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isSatisfied'");
    }
}
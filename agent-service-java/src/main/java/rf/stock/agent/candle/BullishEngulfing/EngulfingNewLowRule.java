package rf.stock.agent.candle.BullishEngulfing;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

/**
 * Ursprüngliche Umsetzung (07.09.) von Regel 1 der Bullish-Engulfing-
 * Definition: "Die 1. Kerze liegt im Kursverlauf unter der Vorgängerkerze.
 * Ihr Schlusskurs markiert ein neues Periodentief innerhalb des
 * kurzfristigen Trends."
 *
 * Am 08.09. durch Rolf präzisiert: gemeint war ein VORHERGEHENDER
 * DOWNTREND, nicht wörtlich ein neues Periodentief. Wird seitdem in
 * BullishEngulfingPattern NICHT mehr verwendet - ersetzt durch
 * rf.stock.agent.util.AdxDowntrendRule (dieselbe ADX-Mechanik wie bei
 * Hammer). Bleibt analog zu candle/Hammer/DowntrendRule.java als
 * eigenständige, unabhängig nutzbare Regel im Repo erhalten.
 *
 * Prüft für die Kerze bei index, ob ihr Schlusskurs das Minimum der
 * Schlusskurse über die letzten lookback Kerzen (inklusive index selbst)
 * ist - ein "neues Periodentief".
 *
 * lookback ist bewusst als Konstruktor-Parameter tunbar gehalten (Default
 * 10 - "kurzfristiger Trend"), da die Vorgabe keine exakte Periodenlänge
 * nennt.
 */
public class EngulfingNewLowRule implements Rule {

    public static final int DEFAULT_LOOKBACK = 10;

    private final ClosePriceIndicator closePrice;
    private final int lookback;

    public EngulfingNewLowRule(BarSeries series) {
        this(series, DEFAULT_LOOKBACK);
    }

    public EngulfingNewLowRule(BarSeries series, int lookback) {
        this.closePrice = new ClosePriceIndicator(series);
        this.lookback = lookback;
    }

    @Override
    public boolean isSatisfied(int index) {
        if (index < 0) {
            return false;
        }

        Num closeAtIndex = closePrice.getValue(index);
        int start = Math.max(0, index - lookback + 1);

        Num minOfPriorBars = null;
        for (int i = start; i < index; i++) {
            Num c = closePrice.getValue(i);
            if (minOfPriorBars == null || c.isLessThan(minOfPriorBars)) {
                minOfPriorBars = c;
            }
        }

        // Keine vorherigen Bars im Fenster (z.B. index==0): vacuously ein neues Tief.
        return minOfPriorBars == null || closeAtIndex.isLessThanOrEqual(minOfPriorBars);
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

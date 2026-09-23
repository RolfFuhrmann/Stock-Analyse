package rf.stock.agent.candle.BullishEngulfing;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.candles.BullishEngulfingIndicator;

/**
 * Regel 2+3 der Bullish-Engulfing-Definition (07.09.):
 * "Erste Kerze (bärisch): kleinere, rote Kerze, die den Abwärtstrend
 * fortsetzt. Zweite Kerze (bullisch): lange, grüne Kerze, deren Realkörper
 * den Realkörper der ersten Kerze vollständig umschließt."
 *
 * Im Gegensatz zu ta4js HammerIndicator (siehe HammerGeometryRule/
 * AdxDowntrendRule-Diskussion vom 06.09.) bindet BullishEngulfingIndicator
 * KEINE Trendprüfung ein - reine 2-Kerzen-Geometrie:
 * Vorkerze bärisch UND aktuelle Kerze bullisch UND aktueller Open kleiner
 * als Vorkerzen-Open UND -Close UND aktueller Close größer als Vorkerzen-
 * Open UND -Close. Das erfüllt Regel 2+3 vollständig (die "kleinere"/
 * "lange" Körper-Eigenschaft folgt zwingend aus dem vollständigen
 * Umschließen) - deshalb hier direkte Wiederverwendung ohne eigenen
 * Nachbau, keine Entkopplung nötig.
 */
public class EngulfingGeometryRule implements Rule {

    private final BullishEngulfingIndicator indicator;

    public EngulfingGeometryRule(BarSeries series) {
        this.indicator = new BullishEngulfingIndicator(series);
    }

    @Override
    public boolean isSatisfied(int index) {
        return Boolean.TRUE.equals(indicator.getValue(index));
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

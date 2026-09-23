package rf.stock.agent.candle.Hammer;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.trend.DownTrendIndicator;

/**
 * Downtrend-Bestätigung über ta4js eigenen, unveränderten DownTrendIndicator
 * (ADX(barCount) > 25 UND -DI(barCount)[index-1] > +DI(barCount)[index-1] -
 * siehe org.ta4j.core.indicators.trend.DownTrendIndicator, 0.24.1).
 *
 * Entscheidung vom 06.09.: ADX bleibt als Trend-Baustein für die
 * Hammer-Erkennung erhalten - KEINE eigene SMA-/Price-Action-basierte
 * Downtrend-Erkennung (siehe DowntrendRule.java - bleibt im Repo, wird aber
 * bewusst nicht verwendet).
 *
 * Warum eine eigene Klasse statt ta4js HammerIndicator direkt zu nutzen:
 * HammerIndicator bindet Geometrie UND diese Trendprüfung fest zu einem
 * einzigen Boolean zusammen, ohne Möglichkeit die Geometrie auszutauschen
 * (siehe HammerGeometryRule.java - Fast-Doji-Fallback wäre mit
 * HammerIndicator nicht umsetzbar). Deshalb wird DownTrendIndicator hier
 * separat instanziiert und per UND mit der eigenen Geometrie sowie
 * HammerOpenRule/HammerPositionRule kombiniert (siehe HammerPattern) -
 * inhaltlich identisch zu dem, was HammerIndicator intern für den
 * Trend-Teil tut, nur entkoppelt von dessen Geometrie.
 */
public class AdxDowntrendRule implements Rule {

    /** Gleicher Default wie in ta4js HammerIndicator(series)-Konstruktor. */
    public static final int DEFAULT_UNSTABLE_BARS = 5;

    private final DownTrendIndicator downTrendIndicator;

    public AdxDowntrendRule(BarSeries series) {
        this(series, DEFAULT_UNSTABLE_BARS);
    }

    public AdxDowntrendRule(BarSeries series, int unstableBars) {
        this.downTrendIndicator = new DownTrendIndicator(series, unstableBars);
    }

    @Override
    public boolean isSatisfied(int index) {
        return Boolean.TRUE.equals(downTrendIndicator.getValue(index));
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return isSatisfied(index);
    }
}

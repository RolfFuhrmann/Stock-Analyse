package rf.stock.agent.util;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.trend.DownTrendIndicator;

/**
 * Downtrend-Bestätigung über ta4js eigenen, unveränderten DownTrendIndicator
 * (ADX(barCount) > 25 UND -DI(barCount)[index-1] > +DI(barCount)[index-1] -
 * siehe org.ta4j.core.indicators.trend.DownTrendIndicator, 0.24.1).
 *
 * Ursprünglich (06.09.) für die Hammer-Erkennung gebaut (siehe
 * candle/Hammer/HammerPattern) - Entscheidung damals: ADX bleibt als
 * Trend-Baustein erhalten, KEINE eigene SMA-/Price-Action-basierte
 * Downtrend-Erkennung (siehe candle/Hammer/DowntrendRule.java - bleibt im
 * Repo, wird aber bewusst nicht verwendet).
 *
 * Am 08.09. hierher nach rf.stock.agent.util verschoben, da auch
 * candle/BullishEngulfing/BullishEngulfingPattern denselben Trend-Baustein
 * nutzt - generische, pattern-übergreifende Klassen gehören hierher statt
 * in einen einzelnen Pattern-Unterordner.
 *
 * Warum eine eigene Klasse statt ta4js HammerIndicator direkt zu nutzen:
 * HammerIndicator bindet Geometrie UND diese Trendprüfung fest zu einem
 * einzigen Boolean zusammen, ohne Möglichkeit die Geometrie auszutauschen
 * (siehe HammerGeometryRule.java - Fast-Doji-Fallback wäre mit
 * HammerIndicator nicht umsetzbar). Deshalb wird DownTrendIndicator hier
 * separat instanziiert und per UND mit der jeweiligen Pattern-Geometrie
 * kombiniert - inhaltlich identisch zu dem, was HammerIndicator intern für
 * den Trend-Teil tut, nur entkoppelt und wiederverwendbar.
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

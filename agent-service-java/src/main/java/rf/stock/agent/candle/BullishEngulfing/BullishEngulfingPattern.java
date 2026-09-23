package rf.stock.agent.candle.BullishEngulfing;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;

import rf.stock.agent.util.AdxDowntrendRule;

/**
 * Komplett gekapselte Bullish-Engulfing-Erkennung (07.09., Trend-Baustein
 * am 08.09. korrigiert), analog zu candle/Hammer/HammerPattern.
 *
 * Regel 1 (Position/Trend): ursprünglich (07.09.) als EngulfingNewLowRule
 * umgesetzt ("neues Periodentief") - Rolf hat das am 08.09. präzisiert:
 * gemeint war ein VORHERGEHENDER DOWNTREND, nicht wörtlich ein neues Tief.
 * Läuft jetzt über AdxDowntrendRule (dieselbe ADX(5)-Mechanik wie bei
 * Hammer, siehe rf.stock.agent.util.AdxDowntrendRule) - dafür nach
 * rf.stock.agent.util verschoben, da jetzt von zwei Mustern genutzt.
 * EngulfingNewLowRule.java bleibt im Repo, wird aber bewusst nicht mehr
 * verwendet (analog zu candle/Hammer/DowntrendRule.java).
 * Regel 2+3 (Geometrie): EngulfingGeometryRule - ta4js unveränderter
 * BullishEngulfingIndicator (kein Trend eingebaut, siehe dortiger
 * Klassenkommentar - deshalb hier keine Entkopplung wie bei Hammer nötig).
 * Regel 4 (Bestätigung): dritte Kerze schließt höher ODER überschreitet das
 * Hoch der Engulfing-Kerze.
 *
 * Zwei Fälle, die BullishCandlePatterns über isBullishEngulfing(index)
 * abfragt:
 * - ENGULFING: die geprüfte Kerze selbst ist die Engulfing-Kerze (Regel 1
 *   bezogen auf die Vorkerze, Regel 2+3 auf das Paar Vorkerze/aktuelle
 *   Kerze).
 * - ENGULFING_CONFIRMED: die VORKERZE (index-1) war eine gültige Engulfing-
 *   Kerze, und die aktuelle Kerze bestätigt sie (Regel 4).
 *
 * candleDates in EngulfingMatch: Datum/Daten der am Signal beteiligten
 * Kerze(n), zur Weitergabe an den Client (Candlestick-Pattern-Spalte).
 */
public class BullishEngulfingPattern {

    public enum EngulfingCase {
        ENGULFING,
        ENGULFING_CONFIRMED
    }

    public record EngulfingMatch(boolean matched, EngulfingCase engulfingCase, List<LocalDate> candleDates) {
        private static final EngulfingMatch NONE = new EngulfingMatch(false, null, null);

        static EngulfingMatch none() {
            return NONE;
        }
    }

    private final BarSeries series;
    private final Rule adxDowntrend;
    private final Rule geometry;

    public BullishEngulfingPattern(BarSeries series) {
        this.series = series;
        this.adxDowntrend = new AdxDowntrendRule(series);
        this.geometry = new EngulfingGeometryRule(series);
    }

    /**
     * Prüft für die Kerze bei index beide Fälle (siehe Klassenkommentar).
     */
    public EngulfingMatch isBullishEngulfing(int index) {
        if (isPureEngulfing(index)) {
            return new EngulfingMatch(true, EngulfingCase.ENGULFING, List.of(dateOf(index)));
        }
        if (index >= 1 && isPureEngulfing(index - 1) && isConfirmation(index)) {
            return new EngulfingMatch(true, EngulfingCase.ENGULFING_CONFIRMED,
                    List.of(dateOf(index - 1), dateOf(index)));
        }
        return EngulfingMatch.none();
    }

    /**
     * Regel 1 bezieht sich auf die ERSTE (bärische) Kerze, also index-1
     * relativ zur eigentlichen Engulfing-Kerze bei index. Regel 2+3
     * vergleichen ohnehin direkt index-1 gegen index.
     */
    private boolean isPureEngulfing(int index) {
        return index >= 1 && adxDowntrend.isSatisfied(index - 1) && geometry.isSatisfied(index);
    }

    /** Regel 4: dritte Kerze schließt höher ODER überschreitet das Hoch der Engulfing-Kerze. */
    private boolean isConfirmation(int index) {
        boolean closesHigher = series.getBar(index).getClosePrice()
                .isGreaterThan(series.getBar(index - 1).getClosePrice());
        boolean exceedsHigh = series.getBar(index).getHighPrice()
                .isGreaterThan(series.getBar(index - 1).getHighPrice());
        return closesHigher || exceedsHigh;
    }

    private LocalDate dateOf(int index) {
        return series.getBar(index).getEndTime().atZone(ZoneOffset.UTC).toLocalDate();
    }
}


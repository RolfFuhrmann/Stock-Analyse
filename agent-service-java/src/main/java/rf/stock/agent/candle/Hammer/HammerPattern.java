package rf.stock.agent.candle.Hammer;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;

import rf.stock.agent.util.AdxDowntrendRule;

/**
 * Komplett gekapselte Hammer-Erkennung (06.09.).
 *
 * Bausteine:
 * 1. Downtrend: ADX (AdxDowntrendRule aus rf.stock.agent.util, ta4js
 *    unveränderter DownTrendIndicator).
 *    Entscheidung vom 06.09.: ADX bleibt, KEINE eigene SMA-/Price-Action-
 *    Downtrend-Erkennung. DowntrendRule.java bleibt im Repo erhalten,
 *    wird hier aber bewusst NICHT verwendet.
 * 2. Hammer-Form: HammerGeometryRule (ta4j-Formel + Fast-Doji-Fallback)
 * 3. Hammer Open < Previous Close: HammerOpenRule
 * 4. Hammer-Position relativ zur Vorkerze: HammerPositionRule
 *
 * Zwei Fälle, die BullishCandlePatterns über isHammer(index) abfragt:
 * - HAMMER: die geprüfte Kerze selbst erfüllt alle vier Kriterien.
 * - HAMMER_CONFIRMED: die VORKERZE (index-1) erfüllte alle vier Kriterien,
 *   und die aktuelle Kerze bestätigt sie durch einen höheren Schlusskurs
 *   (Close[index] > Close[index-1] - Rolfs Vorgabe vom 06.09.).
 *
 * candleDates in HammerMatch: Datum/Daten der am Signal beteiligten
 * Kerze(n), zur Weitergabe an den Client (Candlestick-Pattern-Spalte).
 */
public class HammerPattern {

    public enum HammerCase {
        HAMMER,
        HAMMER_CONFIRMED
    }

    public record HammerMatch(boolean matched, HammerCase hammerCase, List<LocalDate> candleDates) {
        private static final HammerMatch NONE = new HammerMatch(false, null, null);

        static HammerMatch none() {
            return NONE;
        }
    }

    private final BarSeries series;
    private final Rule pureHammerRule;

    public HammerPattern(BarSeries series) {
        this.series = series;

        Rule adxDowntrend = new AdxDowntrendRule(series);
        Rule geometry = new HammerGeometryRule(series);
        Rule hammerOpen = new HammerOpenRule(series);
        Rule hammerPosition = new HammerPositionRule(series, 1.0 / 3.0, 0.05);

        this.pureHammerRule = adxDowntrend.and(geometry).and(hammerOpen).and(hammerPosition);
    }

    /**
     * Prüft für die Kerze bei index beide Fälle (siehe Klassenkommentar).
     */
    public HammerMatch isHammer(int index) {
        if (isPureHammer(index)) {
            return new HammerMatch(true, HammerCase.HAMMER, List.of(dateOf(index)));
        }
        if (index >= 1 && isPureHammer(index - 1) && isConfirmation(index)) {
            return new HammerMatch(true, HammerCase.HAMMER_CONFIRMED, List.of(dateOf(index - 1), dateOf(index)));
        }
        return HammerMatch.none();
    }

    private boolean isPureHammer(int index) {
        return index >= 0 && pureHammerRule.isSatisfied(index);
    }

    /** Bestätigung: aktuelle Kerze schließt höher als der Hammer (Rolfs Vorgabe, 06.09.). */
    private boolean isConfirmation(int index) {
        return series.getBar(index).getClosePrice().isGreaterThan(series.getBar(index - 1).getClosePrice());
    }

    private LocalDate dateOf(int index) {
        return series.getBar(index).getEndTime().atZone(ZoneOffset.UTC).toLocalDate();
    }
}

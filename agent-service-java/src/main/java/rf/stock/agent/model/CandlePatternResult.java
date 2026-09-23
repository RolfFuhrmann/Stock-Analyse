package rf.stock.agent.model;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Allgemeingültiges Ergebnis der Candlestick-Pattern-Erkennung (06.09.,
 * überarbeitet 07.09. auf Wunsch von Rolf: EIN wiederverwendbares Objekt für
 * alle Muster statt verstreuter Einzelfelder in StockResult).
 *
 * pattern:      z.B. "Hammer", "Morning Star" oder null (kein Muster).
 * strength:     0–5, 0 = kein Muster.
 * gdPeriod:     welcher gleitende Durchschnitt (20/50/200) den Trend
 *               bestätigt hat (siehe CandleGdCascade) - null bei ADX-
 *               basierten Mustern (aktuell nur Hammer, siehe HammerPattern),
 *               bei "Bullish/Bearish Abandoned Baby" (alte ZigZag-basierte
 *               Trendprüfung) und bei fehlendem Muster.
 * candleDates:  Datum/Daten der am Muster beteiligten Kerze(n), für die
 *               Anzeige in der Candlestick-Pattern-Spalte im Client.
 *               Enthält bei einer Bestätigung (confirmed=true) BEIDE Daten
 *               (Muster-Kerze + Bestätigungskerze). Aktuell nur für Hammer
 *               befüllt - bei den übrigen Mustern (noch) null, aber
 *               strukturell für alle Muster nutzbar.
 * confirmed:    true, wenn nicht die Musterkerze selbst, sondern eine
 *               nachfolgende Kerze das eigentliche Signal auslöst (z.B.
 *               Hammer + Bestätigung durch höheren Schlusskurs am Folgetag -
 *               siehe HammerPattern.HammerCase.HAMMER_CONFIRMED). Aktuell
 *               nur für Hammer relevant, bei den übrigen (einstufigen)
 *               Mustern immer false.
 */
public record CandlePatternResult(
    String pattern,
    int strength,
    @JsonProperty("gd_period") Integer gdPeriod,
    @JsonProperty("candle_dates") List<LocalDate> candleDates,
    boolean confirmed
) {
    public static CandlePatternResult none() {
        return new CandlePatternResult(null, 0, null, null, false);
    }
}

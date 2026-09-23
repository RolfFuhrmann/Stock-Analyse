package rf.stock.agent.util;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Handelstage-Arithmetik (09.09.) - für den Live-Write-back in
 * DailyBarWriteBackService: "5 Börsentage zurück vom letzten in der DB
 * gefundenen Datum" als Sicherheitsmarge für nachträgliche Kurskorrekturen.
 *
 * Bewusst einfach gehalten: überspringt nur Wochenenden (Samstag/Sonntag),
 * keine Feiertagskalender-Logik - wäre für den Zweck (kleine Sicherheits-
 * marge, keine exakte Börsenkalender-Anforderung) unnötiger Aufwand.
 */
public final class TradingDayUtil {

    private TradingDayUtil() {
    }

    /**
     * Liefert das Datum, das {@code tradingDays} Handelstage (Mo-Fr) vor
     * {@code date} liegt.
     */
    public static LocalDate minusTradingDays(LocalDate date, int tradingDays) {
        LocalDate result = date;
        int remaining = tradingDays;
        while (remaining > 0) {
            result = result.minusDays(1);
            if (result.getDayOfWeek() != DayOfWeek.SATURDAY && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return result;
    }
}

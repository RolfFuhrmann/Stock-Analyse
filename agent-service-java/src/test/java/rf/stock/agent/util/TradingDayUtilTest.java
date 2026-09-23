package rf.stock.agent.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Tests für TradingDayUtil (09.09.), genutzt vom DailyBarWriteBackService
 * für die "5 Handelstage zurück"-Sicherheitsmarge.
 */
class TradingDayUtilTest {

    @Test
    void should_skip_weekend_when_counting_back_from_monday() {
        // Montag, 5 Handelstage zurück -> muss den Sa/So dazwischen überspringen
        LocalDate monday = LocalDate.of(2026, 9, 7); // Montag
        LocalDate result = TradingDayUtil.minusTradingDays(monday, 5);

        // Mo(-1)=Fr, Di(-2)=Do, Mi(-3)=Mi, Do(-4)=Di, Fr(-5)=Mo -> Sa/So übersprungen
        assertEquals(LocalDate.of(2026, 8, 31), result);
    }

    @Test
    void should_stay_within_week_when_no_weekend_involved() {
        // Donnerstag, 2 Handelstage zurück -> Dienstag, kein Wochenende im Weg
        LocalDate thursday = LocalDate.of(2026, 9, 10);
        LocalDate result = TradingDayUtil.minusTradingDays(thursday, 2);

        assertEquals(LocalDate.of(2026, 9, 8), result);
    }

    @Test
    void should_skip_weekend_starting_point_correctly() {
        // Start an einem Sonntag - erster Schritt zurück landet auf Samstag,
        // muss ebenfalls übersprungen werden
        LocalDate sunday = LocalDate.of(2026, 9, 6);
        LocalDate result = TradingDayUtil.minusTradingDays(sunday, 1);

        assertEquals(LocalDate.of(2026, 9, 4), result); // Freitag
    }

    @Test
    void zero_trading_days_should_return_same_date() {
        LocalDate date = LocalDate.of(2026, 9, 9);
        assertEquals(date, TradingDayUtil.minusTradingDays(date, 0));
    }
}

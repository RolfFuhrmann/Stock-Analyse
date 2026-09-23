package rf.stock.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.OhlcvBar;

/**
 * Tests für die Cutoff-Logik des Intraday-Write-backs (20.09.) - dieselbe
 * Regel wie beim 1d-Write-back: 5 Handelstage vor dem neuesten DB-Zeitstempel.
 */
class IntradayBarWriteBackServiceTest {

    @Test
    void should_use_five_trading_days_before_latest_db_time_as_cutoff() {
        // Freitag, 18.09. -> 5 Handelstage zurück = Freitag, 11.09.
        LocalDate cutoff = IntradayBarWriteBackService.cutoffFor(LocalDateTime.parse("2026-09-18T16:00:00"));

        assertEquals(LocalDate.of(2026, 9, 11), cutoff);
    }

    @Test
    void should_skip_weekend_when_latest_db_time_is_monday() {
        // Montag, 21.09. -> 5 Handelstage zurück = Montag, 14.09. (Wochenende übersprungen)
        LocalDate cutoff = IntradayBarWriteBackService.cutoffFor(LocalDateTime.parse("2026-09-21T10:00:00"));

        assertEquals(LocalDate.of(2026, 9, 14), cutoff);
    }

    @Test
    void should_keep_bars_from_cutoff_day_onwards() {
        List<OhlcvBar> bars = List.of(
                bar("2026-09-10T16:00:00"),
                bar("2026-09-11T09:00:00"),
                bar("2026-09-18T09:00:00"));

        List<OhlcvBar> result = IntradayBarWriteBackService.filterFromCutoff(bars, LocalDate.of(2026, 9, 11));

        assertEquals(2, result.size());
        assertEquals("2026-09-11T09:00:00", result.getFirst().date());
    }

    @Test
    void should_keep_all_bars_when_ticker_is_new_in_db() {
        List<OhlcvBar> bars = List.of(bar("2026-01-05T09:00:00"), bar("2026-09-18T09:00:00"));

        List<OhlcvBar> result = IntradayBarWriteBackService.filterFromCutoff(bars, LocalDate.MIN);

        assertEquals(2, result.size());
    }

    private static OhlcvBar bar(String date) {
        return new OhlcvBar(date, 1, 1, 1, 1, 1.0);
    }
}

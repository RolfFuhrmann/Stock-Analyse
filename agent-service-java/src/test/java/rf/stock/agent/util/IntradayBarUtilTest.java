package rf.stock.agent.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.OhlcvBar;

/**
 * Tests für IntradayBarUtil (20.09.). Erwartungswerte der Aggregation sind
 * gegen die Python-Referenz im history-fetcher (_aggregate_1h_to_4h) mit
 * identischem Input geprüft - beide müssen dieselben Blöcke liefern.
 */
class IntradayBarUtilTest {

    @Test
    void should_cut_offset_from_yahoo_timestamp() {
        assertEquals("2026-09-18T09:00:00",
                IntradayBarUtil.normalizeTimestamp("2026-09-18T09:00:00+02:00"));
    }

    @Test
    void should_replace_space_separator_from_twelvedata() {
        assertEquals("2026-09-18T13:30:00",
                IntradayBarUtil.normalizeTimestamp("2026-09-18 13:30:00"));
    }

    @Test
    void should_keep_date_only_value_unchanged() {
        assertEquals("2026-09-18", IntradayBarUtil.normalizeTimestamp("2026-09-18"));
    }

    @Test
    void should_sort_bars_ascending_when_normalizing() {
        List<OhlcvBar> bars = List.of(
                bar("2026-09-18T11:00:00+02:00", 1, 1, 1, 1, 1.0),
                bar("2026-09-18T09:00:00+02:00", 1, 1, 1, 1, 1.0));

        List<OhlcvBar> result = IntradayBarUtil.normalize(bars);

        assertEquals("2026-09-18T09:00:00", result.get(0).date());
        assertEquals("2026-09-18T11:00:00", result.get(1).date());
    }

    @Test
    void should_build_three_blocks_for_xetra_day() {
        // Xetra: Stundenkerzen 09:00 bis 17:00 -> Blöcke 08:00 (3 Kerzen), 12:00 (4), 16:00 (2)
        List<OhlcvBar> xetra = new ArrayList<>();
        for (int hour = 9; hour <= 17; hour++) {
            xetra.add(bar(String.format("2026-09-18T%02d:00:00", hour),
                    100 + hour, 110 + hour, 90 + hour, 101 + hour, 10.0 * hour));
        }

        List<OhlcvBar> result = IntradayBarUtil.aggregateTo4h(xetra);

        assertEquals(3, result.size());
        OhlcvBar first = result.get(0);
        assertEquals("2026-09-18T08:00:00", first.date());
        assertEquals(109.0, first.open());   // Open der ersten Kerze (09:00)
        assertEquals(121.0, first.high());   // höchstes High (11:00)
        assertEquals(99.0, first.low());     // tiefstes Low (09:00)
        assertEquals(112.0, first.close());  // Close der letzten Kerze (11:00)
        assertEquals(300.0, first.volume()); // 90 + 100 + 110
        assertEquals("2026-09-18T12:00:00", result.get(1).date());
        assertEquals("2026-09-18T16:00:00", result.get(2).date());
    }

    @Test
    void should_build_two_blocks_for_us_day_with_half_hour_bars() {
        // US-Handel: 09:30 bis 15:30 -> Blöcke 08:00 (3 Kerzen) und 12:00 (4 Kerzen)
        List<OhlcvBar> us = new ArrayList<>();
        for (int hour = 9; hour <= 15; hour++) {
            us.add(bar(String.format("2026-09-18T%02d:30:00", hour), 1, 2, 0.5, 1.5, null));
        }

        List<OhlcvBar> result = IntradayBarUtil.aggregateTo4h(us);

        assertEquals(2, result.size());
        assertEquals("2026-09-18T08:00:00", result.get(0).date());
        assertEquals("2026-09-18T12:00:00", result.get(1).date());
        assertNull(result.get(0).volume()); // kein Volumen vorhanden -> null statt 0
    }

    @Test
    void should_drop_incomplete_block_with_single_bar() {
        List<OhlcvBar> result = IntradayBarUtil.aggregateTo4h(
                List.of(bar("2026-09-18T09:00:00", 1, 1, 1, 1, 1.0)));

        assertTrue(result.isEmpty());
    }

    @Test
    void should_aggregate_unsorted_input_chronologically() {
        List<OhlcvBar> unsorted = List.of(
                bar("2026-09-18T11:00:00", 3, 3, 3, 3, 1.0),
                bar("2026-09-18T09:00:00", 1, 1, 1, 1, 1.0),
                bar("2026-09-18T10:00:00", 2, 2, 2, 2, 1.0));

        OhlcvBar block = IntradayBarUtil.aggregateTo4h(unsorted).getFirst();

        assertEquals(1.0, block.open());
        assertEquals(3.0, block.close());
    }

    private static OhlcvBar bar(String date, double open, double high, double low, double close, Double volume) {
        return new OhlcvBar(date, open, high, low, close, volume);
    }
}

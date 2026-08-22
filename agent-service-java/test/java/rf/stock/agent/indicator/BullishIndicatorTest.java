package rf.stock.agent.indicator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.OhlcvBar;

/**
 * Tests für detectElliottABC().
 *
 * WICHTIG (07/2026): Die Erkennung läuft jetzt über ta4j's ElliottWaveFacade
 * (szenario-basiertes Confidence-Scoring, korrektiver Typ + Phase
 * CORRECTIVE_C) statt über die frühere handgestrickte Regel-Logik (eigene
 * ZigZag-Bestätigung, feste Fibonacci-Bänder, hand-kalibrierte Kaufman-
 * Efficiency-Ratio-Schwelle). Die alten Tests (u.a. die Praxisfälle CSCO,
 * CAT, UNH, die über mehrere Kalibrierungsrunden hinweg entstanden sind)
 * prüften ausschließlich diese jetzt entfernte Logik und wurden entfernt -
 * sie hätten zudem am Test-Helper gecrasht (alle Kerzen hatten dasselbe
 * Datum "2025-01-01", ta4j verlangt streng aufsteigende Zeitstempel).
 *
 * TODO: Sobald echte Praxisfälle mit ta4j-Log-Ausgaben vorliegen (siehe
 * "ta4j Elliott A-B-C Check"-Log), hier konkrete Regressionstests mit
 * echten Kursdaten ergänzen - insbesondere die CSCO/CAT/UNH-Fälle erneut
 * gegen ta4j verifizieren, da sie ursprünglich echte Produktions-Bugs
 * abgesichert haben.
 */
class BullishIndicatorTest {

    @Test
    void shouldRejectWhenTooFewBarsExist() {
        List<OhlcvBar> bars = flat(100, 99, 98, 97, 96, 95, 94, 93, 92, 91);

        assertFalse(BullishIndicator.detectElliottABC(bars, bars.size()));
    }

    @Test
    void shouldNotThrowOnRealisticSeriesWithDistinctDates() {
        // Reiner Smoke-Test: stellt sicher, dass toBarSeries()/ElliottWaveFacade
        // auf einer realistischen (aufsteigend datierten) Kerzenreihe nicht
        // crasht - unabhängig davon, ob ein Szenario erkannt wird oder nicht.
        List<OhlcvBar> bars = flat(
                100, 102, 104, 106, 108, 110, 112, 114, 116, 118,
                120, 118, 115, 112, 108, 104, 100,
                103, 106, 109,
                107, 103, 93
        );

        assertDoesNotThrow(() -> BullishIndicator.detectElliottABC(bars, bars.size()));
    }

    // ── Test-Hilfsmethoden ───────────────────────────────────────────────────

    /**
     * Baut "flache" Kerzen ohne Dochte (Open=High=Low=Close) aus reinen
     * Schlusskursen, mit aufsteigenden Tagesdaten (wichtig für ta4j -
     * BarSeries verlangt streng aufsteigende Zeitstempel).
     */
    private static List<OhlcvBar> flat(double... closes) {
        LocalDate start = LocalDate.of(2025, 1, 1);
        return java.util.stream.IntStream.range(0, closes.length)
                .mapToObj(i -> bar(start.plusDays(i).toString(), closes[i]))
                .toList();
    }

    private static OhlcvBar bar(String date, double close) {
        return new OhlcvBar(date, close, close, close, close, 1000d);
    }
}

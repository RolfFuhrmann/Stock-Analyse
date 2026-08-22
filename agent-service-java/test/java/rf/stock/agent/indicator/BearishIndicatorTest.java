package rf.stock.agent.indicator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import rf.stock.agent.model.OhlcvBar;

/**
 * Tests für detectElliottImpulseUp().
 *
 * WICHTIG (07/2026): Die Erkennung läuft jetzt über ta4j's ElliottWaveFacade
 * (szenario-basiertes Confidence-Scoring) statt über die frühere
 * handgestrickte Regel-Logik (eigene ZigZag-Bestätigung, feste
 * Fibonacci-Bänder, harte Pass/Fail-Regeln). Die alten Tests, die gezielt
 * unsere eigenen Schwellenwerte trafen (Wave4-Overlap, Wave3-kürzeste-Welle),
 * sagen über ta4j's Verhalten nichts mehr aus und wurden entfernt.
 *
 * TODO: Sobald echte Praxisfälle mit ta4j-Log-Ausgaben vorliegen (siehe
 * "Impuls-Check"-Kalibrierung bei BullishIndicator als Vorbild), hier
 * konkrete Regressionstests mit echten Kursdaten ergänzen.
 */
class BearishIndicatorTest {

    @Test
    void shouldRejectWhenTooFewBarsExist() {
        List<OhlcvBar> bars = flat(100, 105, 110, 108, 112);

        assertFalse(BearishIndicator.detectElliottImpulseUp(bars, bars.size()));
    }

    @Test
    void shouldNotThrowOnRealisticSeriesWithDistinctDates() {
        // Reiner Smoke-Test: stellt sicher, dass toBarSeries()/ElliottWaveFacade
        // auf einer realistischen (aufsteigend datierten) Kerzenreihe nicht
        // crasht - unabhängig davon, ob ein Szenario erkannt wird oder nicht.
        List<OhlcvBar> bars = flat(
                100, 103, 106, 109, 112, 115, 113, 111, 114, 118, 122, 126, 130,
                134, 138, 134, 131, 133, 137, 141, 145, 143, 140, 138, 142
        );

        assertDoesNotThrow(() -> BearishIndicator.detectElliottImpulseUp(bars, bars.size()));
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

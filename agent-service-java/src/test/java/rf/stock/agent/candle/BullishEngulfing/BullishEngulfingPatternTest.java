package rf.stock.agent.candle.BullishEngulfing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Rule;

/**
 * Tests für die Bullish-Engulfing-Pattern-Erkennung (07.09., Trend-Baustein
 * am 08.09. korrigiert), analog zu candle/Hammer/HammerPatternTest.
 *
 * Regeln (Rolfs Vorgabe):
 * 1. Vorhergehender Downtrend - ursprünglich (07.09.) als EngulfingNewLowRule
 *    ("neues Periodentief") umgesetzt, am 08.09. präzisiert: gemeint war ein
 *    allgemeiner Downtrend, jetzt über AdxDowntrendRule (dieselbe ADX(5)-
 *    Mechanik wie bei Hammer, siehe rf.stock.agent.util). EngulfingNewLowRule
 *    bleibt im Repo, wird aber nicht mehr verwendet (analog zu Hammers
 *    DowntrendRule) - eigene Tests dafür bleiben unten trotzdem als
 *    Dokumentation der Klasse erhalten.
 * 2+3. Erste Kerze bärisch/klein, zweite Kerze bullisch/lang und umschließt
 *    die erste vollständig (EngulfingGeometryRule, wrapt ta4js
 *    unveränderten BullishEngulfingIndicator - kein Trend eingebaut, daher
 *    keine Entkopplung wie bei HammerIndicator nötig).
 * 4. Bestätigung: dritte Kerze schließt höher ODER überschreitet das Hoch
 *    der Engulfing-Kerze (BullishEngulfingPattern.isConfirmation).
 *
 * Alle Testdaten numerisch vorab mit Python gegen die exakten Regel-
 * Formeln geprüft (siehe Analyse vom 07./08.09.), nicht nur angenommen -
 * gelernt aus den Korrekturen bei HammerPatternTest. Die Umstellung von
 * "neues Periodentief" auf ADX am 08.09. wurde erneut numerisch geprüft:
 * beide Mechanismen liefern für dieselben Testdaten dasselbe Ergebnis.
 *
 * ta4j: 0.24.1 / JUnit: 5
 */
class BullishEngulfingPatternTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private BarSeries createSeries(double[][] candles) {

        BarSeries series = new BaseBarSeriesBuilder()
                .withName("engulfing-test")
                .build();

        for (int i = 0; i < candles.length; i++) {
            double[] candle = candles[i];
            series.barBuilder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(START.plusSeconds(i * 60L))
                    .openPrice(candle[0])
                    .highPrice(candle[1])
                    .lowPrice(candle[2])
                    .closePrice(candle[3])
                    .volume(100)
                    .amount(100)
                    .add();
        }

        return series;
    }

    // ============================================================
    // 1. EngulfingNewLowRule (seit 08.09. NICHT mehr in BullishEngulfingPattern
    //    verwendet, siehe Klassenkommentar - Tests bleiben als eigenständige
    //    Dokumentation der Klasse erhalten, analog zu Hammers DowntrendRule)
    // ============================================================

    @Test
    void newLow_should_be_true_when_close_is_lowest_in_lookback() {

        double[][] candles = new double[10][4];
        for (int i = 0; i < 9; i++) {
            double o = 100 - i;
            double c = 99 - i;
            candles[i] = new double[] { o, o + 0.5, c - 0.5, c };
        }
        // Letzte Kerze: bärisch, Close=89 - neues Tief (bisheriges Minimum war 91)
        candles[9] = new double[] { 91, 91.3, 88.7, 89 };

        BarSeries series = createSeries(candles);
        Rule rule = new EngulfingNewLowRule(series);

        assertTrue(rule.isSatisfied(series.getEndIndex()));
    }

    @Test
    void newLow_should_be_false_when_close_is_above_recent_lows() {

        double[][] candles = new double[10][4];
        for (int i = 0; i < 9; i++) {
            double o = 50 + i;
            double c = 51 + i;
            candles[i] = new double[] { o, c + 0.5, o - 0.5, c };
        }
        // Bärischer Rücksetzer, aber weit über dem historischen Tief (51)
        candles[9] = new double[] { 59.5, 60, 58, 58.5 };

        BarSeries series = createSeries(candles);
        Rule rule = new EngulfingNewLowRule(series);

        assertFalse(rule.isSatisfied(series.getEndIndex()));
    }

    // ============================================================
    // 2. EngulfingGeometryRule (reiner ta4j-BullishEngulfingIndicator-Wrapper)
    // ============================================================

    @Test
    void geometry_should_be_true_for_clear_engulfing_pair() {

        double[][] candles = {
                { 91, 91.3, 88.7, 89 }, // bärisch
                { 88.5, 92.3, 88.3, 92 } // bullisch, umschließt vollständig
        };

        BarSeries series = createSeries(candles);
        Rule rule = new EngulfingGeometryRule(series);

        assertTrue(rule.isSatisfied(series.getEndIndex()));
    }

    @Test
    void geometry_should_be_false_when_second_candle_does_not_fully_engulf() {

        double[][] candles = {
                { 91, 91.3, 88.7, 89 }, // bärisch
                { 90, 91, 88, 90.5 }    // bullisch, aber Open liegt NICHT unter Vorkerzen-Close
        };

        BarSeries series = createSeries(candles);
        Rule rule = new EngulfingGeometryRule(series);

        assertFalse(rule.isSatisfied(series.getEndIndex()));
    }

    // ============================================================
    // 3. BullishEngulfingPattern - Fall 1 (ENGULFING)
    // ============================================================

    @Test
    void full_pattern_should_detect_engulfing_in_adx_downtrend() {

        // WICHTIG (09.09., Bugfix nach fehlgeschlagenem mvn test): ta4js
        // echter DownTrendIndicator braucht laut getCountOfUnstableBars()-
        // Kette mindestens 11 Bars Vorlauf, nicht 5 wie ursprünglich
        // angenommen - darunter liefert er IMMER false. Die vorherige
        // 9-Bar-Version dieses Tests (Kerze 1 bei Index 9) lag darunter.
        // Fix: 14 fallende Bars (Kerze 1 jetzt bei Index 14), numerisch neu
        // gegen die exakte unstableBars-Kette verifiziert.
        double[][] candles = new double[16][4];
        for (int i = 0; i < 14; i++) {
            double o = 100 - i;
            double c = 99 - i;
            candles[i] = new double[] { o, o + 0.5, c - 0.5, c };
        }
        candles[14] = new double[] { 86, 86.3, 83.7, 84 };    // Kerze 1 (bärisch, ADX-Downtrend, numerisch geprüft)
        candles[15] = new double[] { 83.5, 87.3, 83.3, 87 };  // Kerze 2 (Engulfing)

        BarSeries series = createSeries(candles);
        BullishEngulfingPattern pattern = new BullishEngulfingPattern(series);

        BullishEngulfingPattern.EngulfingMatch match = pattern.isBullishEngulfing(series.getEndIndex());

        assertTrue(match.matched(), "Engulfing im ADX-Downtrend sollte erkannt werden");
        assertEquals(BullishEngulfingPattern.EngulfingCase.ENGULFING, match.engulfingCase());
        assertEquals(1, match.candleDates().size());
    }

    @Test
    void full_pattern_should_be_false_without_downtrend() {

        double[][] candles = new double[16][4];
        for (int i = 0; i < 14; i++) {
            double o = 50 + i;
            double c = 51 + i;
            candles[i] = new double[] { o, c + 0.5, o - 0.5, c };
        }
        candles[14] = new double[] { 64.5, 65, 63, 63.5 };  // bärischer Rücksetzer im Aufwärtstrend, KEIN ADX-Downtrend
        candles[15] = new double[] { 63, 65.5, 62.5, 65 };  // Geometrie wäre erfüllt, aber Regel 1 (Downtrend) fehlt

        BarSeries series = createSeries(candles);
        BullishEngulfingPattern pattern = new BullishEngulfingPattern(series);

        assertFalse(
                pattern.isBullishEngulfing(series.getEndIndex()).matched(),
                "Ohne ADX-Downtrend darf kein Signal erzeugt werden, auch wenn die Geometrie passt");
    }

    // ============================================================
    // 4. BullishEngulfingPattern - Fall 2 (ENGULFING_CONFIRMED)
    // ============================================================

    @Test
    void full_pattern_should_confirm_via_higher_close() {

        double[][] candles = new double[17][4];
        for (int i = 0; i < 14; i++) {
            double o = 100 - i;
            double c = 99 - i;
            candles[i] = new double[] { o, o + 0.5, c - 0.5, c };
        }
        candles[14] = new double[] { 86, 86.3, 83.7, 84 };
        candles[15] = new double[] { 83.5, 87.3, 83.3, 87 };
        candles[16] = new double[] { 87, 87.5, 86.5, 88 }; // schließt höher als Kerze 2 (87)

        BarSeries series = createSeries(candles);
        BullishEngulfingPattern pattern = new BullishEngulfingPattern(series);

        BullishEngulfingPattern.EngulfingMatch match = pattern.isBullishEngulfing(series.getEndIndex());

        assertTrue(match.matched());
        assertEquals(BullishEngulfingPattern.EngulfingCase.ENGULFING_CONFIRMED, match.engulfingCase());
        assertEquals(2, match.candleDates().size());
    }

    @Test
    void full_pattern_should_confirm_via_higher_high_even_without_higher_close() {

        double[][] candles = new double[17][4];
        for (int i = 0; i < 14; i++) {
            double o = 100 - i;
            double c = 99 - i;
            candles[i] = new double[] { o, o + 0.5, c - 0.5, c };
        }
        candles[14] = new double[] { 86, 86.3, 83.7, 84 };
        candles[15] = new double[] { 83.5, 87.3, 83.3, 87 };
        // Close (86.5) liegt UNTER Kerze 2 (87), aber High (88) überschreitet Kerze-2-Hoch (87.3)
        candles[16] = new double[] { 86, 88, 85, 86.5 };

        BarSeries series = createSeries(candles);
        BullishEngulfingPattern pattern = new BullishEngulfingPattern(series);

        BullishEngulfingPattern.EngulfingMatch match = pattern.isBullishEngulfing(series.getEndIndex());

        assertTrue(match.matched(), "Bestätigung über das Hoch allein muss ausreichen");
        assertEquals(BullishEngulfingPattern.EngulfingCase.ENGULFING_CONFIRMED, match.engulfingCase());
    }

    @Test
    void full_pattern_should_not_confirm_without_higher_close_or_high() {

        double[][] candles = new double[17][4];
        for (int i = 0; i < 14; i++) {
            double o = 100 - i;
            double c = 99 - i;
            candles[i] = new double[] { o, o + 0.5, c - 0.5, c };
        }
        candles[14] = new double[] { 86, 86.3, 83.7, 84 };
        candles[15] = new double[] { 83.5, 87.3, 83.3, 87 };
        candles[16] = new double[] { 85, 86, 84, 85.5 }; // weder Close noch High höher als Kerze 2

        BarSeries series = createSeries(candles);
        BullishEngulfingPattern pattern = new BullishEngulfingPattern(series);

        assertFalse(
                pattern.isBullishEngulfing(series.getEndIndex()).matched(),
                "Ohne höheren Close oder höheres Hoch darf keine Bestätigung erkannt werden");
    }
}

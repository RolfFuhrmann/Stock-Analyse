package rf.stock.agent.candle.Hammer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Rule;

/**
 * Tests für die Hammer-Pattern-Erkennung.
 *
 * Stand 06.09.: HammerPattern komplett gekapselt, Trend über ADX (ta4js
 * unveränderter DownTrendIndicator, siehe AdxDowntrendRule) statt der
 * eigenen DowntrendRule (bleibt im Repo, aber unverwendet - Tests dafür
 * unten bleiben trotzdem als eigenständige Dokumentation der Klasse
 * erhalten). Neu: HammerGeometryRule (ta4j-Formel + Fast-Doji-Fallback),
 * und HammerPattern.isHammer(index) liefert jetzt ein HammerMatch mit
 * Fall (HAMMER / HAMMER_CONFIRMED) und den betroffenen Kerzen-Daten statt
 * eines reinen Booleans.
 *
 * Die HammerPattern-Tests unten nutzen bewusst zwei unterschiedliche
 * Datenquellen:
 * - Synthetische, monotone Fallserien (wie fallingCandles()) - numerisch
 *   vorab mit der exakten ta4j-ADX(5)/DI(5)-Formel geprüft (siehe
 *   check_synthetic_adx.py / verify_new_test_data.py in der Analyse vom
 *   06.09.), da ADX ein komplett anderes Warmup-/Schwellenverhalten hat
 *   als die vorherige SMA-basierte DowntrendRule.
 * - Echte Boeing-Kursdaten (BA, 01.07.-04.09.2026), mit denen der Hammer-
 *   Fall vom 04.09.-Screenshot konkret validiert wurde: 31.08. ist ein
 *   gültiger Hammer, 03.09. sieht geometrisch wie ein Hammer aus, scheitert
 *   aber an HammerOpenRule/HammerPositionRule.
 *
 * ta4j: 0.24.1
 * JUnit: 5
 */
class HammerPatternTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    // ============================================================
    // Helper
    // ============================================================

    /**
     * Erzeugt eine BarSeries aus OHLC-Daten.
     *
     * candle:
     * [0] = Open
     * [1] = High
     * [2] = Low
     * [3] = Close
     */
    private BarSeries createSeries(double[][] candles) {

        BarSeries series = new BaseBarSeriesBuilder()
                .withName("hammer-test")
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

    /**
     * Erzeugt eine BarSeries aus echten Tages-OHLC-Daten mit echtem Datum
     * (für die Boeing-Testfälle - im Gegensatz zu createSeries() oben, wo
     * das Datum irrelevant ist).
     */
    private BarSeries createDailySeries(String[] isoDates, double[][] candles) {

        BarSeries series = new BaseBarSeriesBuilder()
                .withName("hammer-test-daily")
                .build();

        for (int i = 0; i < candles.length; i++) {
            double[] candle = candles[i];
            series.barBuilder()
                    .timePeriod(Duration.ofDays(1))
                    .endTime(Instant.parse(isoDates[i] + "T00:00:00Z"))
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

    /**
     * Erzeugt eine einfache fallende Serie.
     *
     * Numerisch geprüft (06.09.): produziert mit der echten ta4j-ADX(5)-
     * Formel ADX=100 (maximale Trendstärke) und -DI klar dominant ab
     * spätestens Bar 15 - eignet sich also weiterhin als Downtrend-
     * Testdatensatz, jetzt für die ADX-basierte Prüfung statt der
     * ursprünglichen SMA-Regel.
     */
    private double[][] fallingCandles(int count) {

        double[][] candles = new double[count][4];

        for (int i = 0; i < count; i++) {

            double close = 200 - i * 2;

            candles[i][0] = close + 1; // Open
            candles[i][1] = close + 2; // High
            candles[i][2] = close - 2; // Low
            candles[i][3] = close; // Close
        }

        return candles;
    }

    // ============================================================
    // 1. Downtrend - SMA (DowntrendRule bleibt im Repo, aber unverwendet
    //    in HammerPattern - Tests hier dokumentieren nur die Klasse selbst)
    // ============================================================

    @Test
    void downtrend_should_be_true_when_sma_is_falling() {

        double[][] candles = fallingCandles(30);

        BarSeries series = createSeries(candles);

        Rule rule = new DowntrendRule(
                series,
                20,
                5,
                5,
                4);

        assertTrue(
                rule.isSatisfied(series.getEndIndex()),
                "Downtrend sollte durch fallende SMA erkannt werden");
    }

    @Test
    void downtrend_should_be_false_when_sma_is_not_falling() {

        double[][] candles = new double[30][4];

        for (int i = 0; i < candles.length; i++) {

            candles[i][0] = 100;
            candles[i][1] = 102;
            candles[i][2] = 98;
            candles[i][3] = 100;
        }

        BarSeries series = createSeries(candles);

        Rule rule = new DowntrendRule(
                series,
                20,
                5,
                5,
                4);

        assertFalse(
                rule.isSatisfied(series.getEndIndex()),
                "Bei unveränderter SMA darf kein Downtrend erkannt werden");
    }

    // ============================================================
    // 2. Downtrend - Price Action (DowntrendRule, unverwendet - siehe oben)
    // ============================================================

    @Test
    void downtrend_should_be_true_with_exactly_four_lower_high_or_lower_low_bars() {

        double[][] candles = {

                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },

                { 99, 104, 96, 99 },
                { 99, 106, 94, 100 },
                { 99, 103, 95, 99 },
                { 98, 104, 93, 99 },
                { 99, 105, 94, 100 }
        };

        BarSeries series = createSeries(candles);

        Rule rule = new DowntrendRule(
                series,
                20,
                5,
                5,
                4);

        assertTrue(
                rule.isSatisfied(series.getEndIndex()),
                "Exakt 4 von 5 Price-Action-Signalen müssen ausreichen");
    }

    @Test
    void downtrend_should_be_false_with_only_three_lower_high_or_lower_low_bars() {

        double[][] candles = {

                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },
                { 100, 105, 95, 100 },

                { 99, 104, 96, 99 },
                { 99, 106, 94, 100 },
                { 99, 103, 95, 99 },
                { 99, 105, 95, 100 },
                { 100, 106, 96, 101 }
        };

        BarSeries series = createSeries(candles);

        Rule rule = new DowntrendRule(
                series,
                20,
                5,
                5,
                4);

        assertFalse(
                rule.isSatisfied(series.getEndIndex()),
                "3 von 5 Price-Action-Signalen dürfen nicht ausreichen");
    }

    // ============================================================
    // 3. HammerRule (ta4js echter HammerIndicator, bleibt unverändert im
    //    Repo, wird von HammerPattern NICHT mehr verwendet - siehe
    //    HammerGeometryRule/AdxDowntrendRule stattdessen)
    // ============================================================

    @Test
    void hammer_rule_should_not_be_detected_for_normal_candle() {

        double[][] candles = {

                { 100, 103, 98, 101 },
                { 101, 104, 99, 100 },
                { 100, 102, 97, 99 },
                { 99, 101, 96, 98 },

                /*
                 * Kein Hammer:
                 * relativ großer Body,
                 * kurzer unterer Wick.
                 */
                { 97, 103, 94, 102 }
        };

        BarSeries series = createSeries(candles);

        HammerRule rule = new HammerRule(series);

        assertFalse(
                rule.isSatisfied(series.getEndIndex()),
                "Die letzte Kerze sollte kein Hammer sein");
    }

    // ============================================================
    // 4. HammerGeometryRule - Standard-Formel (identisch zu ta4js
    //    HammerIndicator-Geometrie)
    // ============================================================

    @Test
    void geometry_should_be_true_for_clear_hammer_shape() {

        double[][] candles = {
                { 100, 103, 98, 101 },

                /*
                 * Hammer: Open=97, High=99, Low=87, Close=98
                 * Body=1, unterer Docht=10 (Ratio 10 > 2), oberer Docht=1 (Ratio 1 <= 1)
                 */
                { 97, 99, 87, 98 }
        };

        BarSeries series = createSeries(candles);
        Rule rule = new HammerGeometryRule(series);

        assertTrue(rule.isSatisfied(series.getEndIndex()));
    }

    @Test
    void geometry_should_be_false_for_normal_candle() {

        double[][] candles = {
                { 100, 103, 98, 101 },
                { 97, 103, 94, 102 } // großer Body, kurzer unterer Docht
        };

        BarSeries series = createSeries(candles);
        Rule rule = new HammerGeometryRule(series);

        assertFalse(rule.isSatisfied(series.getEndIndex()));
    }

    // ============================================================
    // 5. HammerGeometryRule - Fast-Doji-Fallback (06.09., Rolfs Beobachtung
    //    anhand der echten Boeing-Kerze vom 28.08.2026)
    // ============================================================

    @Test
    void geometry_fast_doji_fallback_should_accept_boeing_28_08_candle() {

        /*
         * Echte Boeing-Kerze 28.08.2026: O=209.99 H=210.61 L=207.55 C=209.82
         * Body=0.17 (0.081% vom Kurs) - Fast-Doji.
         * Oberer Docht=0.62 -> Ratio zum Body 3.65 (> 1.0, Standard-Formel
         * würde ablehnen), aber nur 20.3% der Gesamt-Range (<=30%).
         * Unterer Docht=2.27 -> 74.2% der Gesamt-Range (>=60%).
         * -> Fallback greift, Kerze gilt als Hammer.
         */
        double[][] candles = {
                { 210.00, 210.70, 207.50, 210.00 },
                { 209.99, 210.61, 207.55, 209.82 }
        };

        BarSeries series = createSeries(candles);
        Rule rule = new HammerGeometryRule(series);

        assertTrue(
                rule.isSatisfied(series.getEndIndex()),
                "Fast-Doji mit dominant unterem Docht sollte über den Fallback erkannt werden");
    }

    @Test
    void geometry_fast_doji_fallback_should_reject_symmetric_doji() {

        /*
         * Symmetrischer Doji: Body winzig, aber Dochte auf BEIDEN Seiten
         * groß (je ~45% der Range) - kein Hammer, da unterer Docht die
         * 60%-Schwelle des Fallbacks nicht erreicht.
         */
        double[][] candles = {
                { 100.00, 105.00, 95.00, 100.00 },
                { 100.05, 104.50, 95.50, 100.00 }
        };

        BarSeries series = createSeries(candles);
        Rule rule = new HammerGeometryRule(series);

        assertFalse(
                rule.isSatisfied(series.getEndIndex()),
                "Ein symmetrischer Doji darf nicht als Hammer durchgehen");
    }

    // ============================================================
    // 6. HammerOpenRule / HammerPositionRule (unverändert - siehe auch
    //    Bugfix vom 06.09.: isSatisfied(index, TradingRecord) warf zuvor
    //    UnsupportedOperationException, was jede AndRule-Verkettung zum
    //    Absturz gebracht hätte)
    // ============================================================

    @Test
    void hammer_open_below_previous_close_should_be_true() {

        double[][] candles = {
                { 100, 102, 98, 100 },
                { 99, 101, 90, 100 }
        };

        BarSeries series = createSeries(candles);
        HammerOpenRule rule = new HammerOpenRule(series);

        assertTrue(rule.isSatisfied(series.getEndIndex()));
    }

    @Test
    void hammer_open_above_previous_close_should_be_false() {

        double[][] candles = {
                { 100, 102, 98, 100 },
                { 101, 103, 90, 102 }
        };

        BarSeries series = createSeries(candles);
        HammerOpenRule rule = new HammerOpenRule(series);

        assertFalse(rule.isSatisfied(series.getEndIndex()));
    }

    @Test
    void and_rule_chain_should_not_throw_when_position_rule_is_reached() {

        /*
         * Regressionstest für den Bugfix vom 06.09.: sowohl HammerOpenRule
         * als auch HammerPositionRule implementierten isSatisfied(index,
         * TradingRecord) ursprünglich nur als "throw new
         * UnsupportedOperationException" - Rule.isSatisfied(index) (ohne
         * TradingRecord) ruft aber laut ta4j-Quellcode IMMER intern die
         * 2-Parameter-Variante auf (siehe AndRule.evaluateChildRule). Ohne
         * den Fix wäre dieser Test mit einer Exception abgebrochen statt
         * ein Boolean-Ergebnis zu liefern.
         */
        double[][] candles = {
                { 100, 110, 90, 105 },
                { 92, 96, 85, 94 }
        };

        BarSeries series = createSeries(candles);
        Rule rule = new HammerOpenRule(series).and(new HammerPositionRule(series, 1.0 / 3.0, 0.05));

        assertTrue(rule.isSatisfied(series.getEndIndex()));
    }

    // ============================================================
    // 7. HammerPattern - vollständige Erkennung, Fall 1 (HAMMER) mit
    //    synthetischer, numerisch geprüfter ADX-Downtrend-Serie
    // ============================================================

    @Test
    void full_pattern_should_detect_hammer_case_in_adx_downtrend() {

        double[][] candles = fallingCandles(20);
        double[][] full = new double[21][4];
        System.arraycopy(candles, 0, full, 0, 20);

        /*
         * Hammer bei Index 20, numerisch vorab geprüft (06.09.):
         * ADX(20)=100, -DI(19)=50 > +DI(19)=0 -> Downtrend erfüllt.
         * Vorkerze (19): O=163 H=164 L=160 C=162, Range=4,
         * unteres Drittel bis 161.33.
         * Hammer: O=160.5 C=161 (Body=[160.5,161], liegt im unteren
         * Drittel), H=161.3 L=155 (unterer Docht 5.5, Ratio 11 > 2;
         * oberer Docht 0.3, Ratio 0.6 <= 1). Open 160.5 < PrevClose 162.
         */
        full[20] = new double[] { 160.5, 161.3, 155, 161 };

        BarSeries series = createSeries(full);
        HammerPattern pattern = new HammerPattern(series);

        HammerPattern.HammerMatch match = pattern.isHammer(series.getEndIndex());

        assertTrue(match.matched(), "Hammer im ADX-Downtrend sollte erkannt werden");
        assertEquals(HammerPattern.HammerCase.HAMMER, match.hammerCase());
        assertEquals(1, match.candleDates().size());
    }

    @Test
    void full_pattern_should_be_false_without_downtrend() {

        double[][] candles = new double[21][4];
        for (int i = 0; i < 20; i++) {
            candles[i] = new double[] { 100, 102, 98, 100 }; // Seitwärtsmarkt, kein Trend
        }
        candles[20] = new double[] { 99, 101, 90, 100 }; // Hammer-Form vorhanden

        BarSeries series = createSeries(candles);
        HammerPattern pattern = new HammerPattern(series);

        assertFalse(
                pattern.isHammer(series.getEndIndex()).matched(),
                "Hammer ohne ADX-Downtrend darf kein Signal erzeugen");
    }

    // ============================================================
    // 8. HammerPattern - Fall 2 (HAMMER_CONFIRMED), numerisch geprüft
    // ============================================================

    @Test
    void full_pattern_should_detect_hammer_confirmed_case() {

        double[][] candles = fallingCandles(20);
        double[][] full = new double[22][4];
        System.arraycopy(candles, 0, full, 0, 20);

        // Hammer bei Index 20 (siehe full_pattern_should_detect_hammer_case_in_adx_downtrend)
        full[20] = new double[] { 160.5, 161.3, 155, 161 };
        // Bestätigung bei Index 21: Close 163 > Hammer-Close 161
        full[21] = new double[] { 161, 165, 160, 163 };

        BarSeries series = createSeries(full);
        HammerPattern pattern = new HammerPattern(series);

        HammerPattern.HammerMatch match = pattern.isHammer(series.getEndIndex());

        assertTrue(match.matched(), "Bestätigungskerze nach gültigem Hammer sollte erkannt werden");
        assertEquals(HammerPattern.HammerCase.HAMMER_CONFIRMED, match.hammerCase());
        assertEquals(2, match.candleDates().size(), "Beide Kerzen (Hammer + Bestätigung) sollten im Datum enthalten sein");
    }

    @Test
    void full_pattern_should_not_confirm_when_close_is_not_higher() {

        double[][] candles = fallingCandles(20);
        double[][] full = new double[22][4];
        System.arraycopy(candles, 0, full, 0, 20);

        full[20] = new double[] { 160.5, 161.3, 155, 161 };
        // Schließt NICHT höher als der Hammer (160 < 161) -> keine Bestätigung
        full[21] = new double[] { 161, 162, 158, 160 };

        BarSeries series = createSeries(full);
        HammerPattern pattern = new HammerPattern(series);

        assertFalse(
                pattern.isHammer(series.getEndIndex()).matched(),
                "Ohne höheren Schlusskurs darf keine Bestätigung erkannt werden");
    }

    // ============================================================
    // 9. HammerPattern mit echten Boeing-Daten (BA, 01.07.-04.09.2026)
    //    Validiert am 06.09. konkret gegen den Hammer-Fall aus dem
    //    04.09.-Screenshot.
    // ============================================================

    @Test
    void boeing_2026_08_31_should_be_a_valid_hammer() {

        // WICHTIG (09.09., Bugfix nach fehlgeschlagenem mvn test): ta4js
        // echter DownTrendIndicator braucht laut getCountOfUnstableBars()-
        // Kette (ADXIndicator+DI-Indikatoren zusammengerechnet) mindestens
        // 11 Bars Vorlauf, nicht 5 wie ursprünglich angenommen - darunter
        // liefert er IMMER false, unabhängig vom Kursverlauf. Die vorherige
        // 10-Bar-Version dieses Tests (Index 9 beim 31.08.) lag damit unter
        // der echten Schwelle und schlug fehl. Fix: komplette Kursreihe ab
        // 01.07. (43 Bars, Index 42 beim 31.08.) - exakt dieselben Daten,
        // mit denen am 06.09. bereits ADX=78.63 für den 31.08. verifiziert
        // wurde. Werte 1:1 aus stock_data_ohlcv_daily_ENR_HOT.csv.
        String[] dates = {
                "2026-07-01", "2026-07-02", "2026-07-06", "2026-07-07", "2026-07-08",
                "2026-07-09", "2026-07-10", "2026-07-13", "2026-07-14", "2026-07-15",
                "2026-07-16", "2026-07-17", "2026-07-20", "2026-07-21", "2026-07-22",
                "2026-07-23", "2026-07-24", "2026-07-27", "2026-07-28", "2026-07-29",
                "2026-07-30", "2026-07-31", "2026-08-03", "2026-08-04", "2026-08-05",
                "2026-08-06", "2026-08-07", "2026-08-10", "2026-08-11", "2026-08-12",
                "2026-08-13", "2026-08-14", "2026-08-17", "2026-08-18", "2026-08-19",
                "2026-08-20", "2026-08-21", "2026-08-24", "2026-08-25", "2026-08-26",
                "2026-08-27", "2026-08-28", "2026-08-31"
        };
        double[][] candles = {
                { 217.26, 220.75, 216.16, 218.58 },
                { 221.00, 227.52, 220.68, 226.49 },
                { 227.15, 234.85, 227.00, 234.54 },
                { 235.90, 237.48, 230.46, 231.68 },
                { 227.00, 228.90, 222.75, 224.95 },
                { 225.30, 225.50, 222.69, 223.11 },
                { 223.69, 223.84, 219.57, 222.28 },
                { 222.00, 222.00, 215.11, 215.51 },
                { 216.96, 220.29, 216.38, 217.11 },
                { 217.79, 221.93, 217.25, 218.12 },
                { 216.52, 217.96, 214.20, 214.34 },
                { 211.50, 216.96, 211.00, 214.03 },
                { 216.18, 216.73, 208.70, 209.48 },
                { 209.48, 211.70, 203.90, 204.80 },
                { 205.00, 209.25, 204.88, 208.65 },
                { 206.00, 211.30, 205.06, 209.23 },
                { 210.09, 212.49, 208.40, 209.52 },
                { 212.54, 215.22, 211.15, 211.50 },
                { 214.00, 223.77, 209.35, 221.56 },
                { 218.50, 219.79, 210.54, 214.01 },
                { 214.94, 221.64, 213.00, 220.90 },
                { 221.16, 221.50, 211.30, 216.14 },
                { 220.10, 234.07, 219.75, 233.46 },
                { 236.76, 238.17, 232.88, 237.16 },
                { 237.95, 241.09, 236.62, 240.19 },
                { 240.00, 240.37, 231.33, 232.19 },
                { 234.06, 235.60, 230.86, 234.42 },
                { 234.25, 236.49, 231.68, 232.79 },
                { 235.61, 237.11, 232.83, 233.24 },
                { 234.30, 234.59, 229.25, 231.20 },
                { 232.27, 233.58, 228.18, 230.33 },
                { 231.63, 233.43, 229.88, 231.67 },
                { 230.34, 231.35, 225.95, 225.95 },
                { 225.10, 226.11, 222.58, 223.06 },
                { 223.51, 224.20, 220.20, 222.20 },
                { 220.40, 221.11, 214.12, 215.10 },
                { 215.67, 216.46, 212.75, 214.20 },
                { 212.86, 213.31, 208.70, 210.46 },
                { 212.50, 212.94, 210.11, 211.08 },
                { 211.78, 212.60, 209.66, 212.09 },
                { 211.51, 211.94, 209.11, 209.89 },
                { 209.99, 210.61, 207.55, 209.82 },
                { 208.50, 208.77, 206.27, 207.78 }
        };

        BarSeries series = createDailySeries(dates, candles);
        HammerPattern pattern = new HammerPattern(series);

        HammerPattern.HammerMatch match = pattern.isHammer(series.getEndIndex());

        assertTrue(match.matched(), "31.08. sollte als gültiger Hammer erkannt werden");
        assertEquals(LocalDate.of(2026, 8, 31), match.candleDates().get(0));
    }

    @Test
    void boeing_2026_09_03_looks_like_hammer_but_fails_open_and_position_rule() {

        // Komplette Historie ab 01.07. (46 Bars) - derselbe Grund wie beim
        // 31.08.-Test oben: unter 11 Bars Vorlauf liefert ta4js
        // DownTrendIndicator immer false, unabhängig vom Kursverlauf. Mit
        // nur 11 Bars (vorherige Version) wäre dieser Test zwar trotzdem
        // grün gewesen, aber aus dem falschen Grund (ADX-Warmup statt
        // tatsächlich HammerOpenRule/HammerPositionRule) - jetzt testet er
        // wieder eindeutig das, was er soll.
        String[] dates = {
                "2026-07-01", "2026-07-02", "2026-07-06", "2026-07-07", "2026-07-08",
                "2026-07-09", "2026-07-10", "2026-07-13", "2026-07-14", "2026-07-15",
                "2026-07-16", "2026-07-17", "2026-07-20", "2026-07-21", "2026-07-22",
                "2026-07-23", "2026-07-24", "2026-07-27", "2026-07-28", "2026-07-29",
                "2026-07-30", "2026-07-31", "2026-08-03", "2026-08-04", "2026-08-05",
                "2026-08-06", "2026-08-07", "2026-08-10", "2026-08-11", "2026-08-12",
                "2026-08-13", "2026-08-14", "2026-08-17", "2026-08-18", "2026-08-19",
                "2026-08-20", "2026-08-21", "2026-08-24", "2026-08-25", "2026-08-26",
                "2026-08-27", "2026-08-28", "2026-08-31", "2026-09-01", "2026-09-02",
                "2026-09-03"
        };
        double[][] candles = {
                { 217.26, 220.75, 216.16, 218.58 },
                { 221.00, 227.52, 220.68, 226.49 },
                { 227.15, 234.85, 227.00, 234.54 },
                { 235.90, 237.48, 230.46, 231.68 },
                { 227.00, 228.90, 222.75, 224.95 },
                { 225.30, 225.50, 222.69, 223.11 },
                { 223.69, 223.84, 219.57, 222.28 },
                { 222.00, 222.00, 215.11, 215.51 },
                { 216.96, 220.29, 216.38, 217.11 },
                { 217.79, 221.93, 217.25, 218.12 },
                { 216.52, 217.96, 214.20, 214.34 },
                { 211.50, 216.96, 211.00, 214.03 },
                { 216.18, 216.73, 208.70, 209.48 },
                { 209.48, 211.70, 203.90, 204.80 },
                { 205.00, 209.25, 204.88, 208.65 },
                { 206.00, 211.30, 205.06, 209.23 },
                { 210.09, 212.49, 208.40, 209.52 },
                { 212.54, 215.22, 211.15, 211.50 },
                { 214.00, 223.77, 209.35, 221.56 },
                { 218.50, 219.79, 210.54, 214.01 },
                { 214.94, 221.64, 213.00, 220.90 },
                { 221.16, 221.50, 211.30, 216.14 },
                { 220.10, 234.07, 219.75, 233.46 },
                { 236.76, 238.17, 232.88, 237.16 },
                { 237.95, 241.09, 236.62, 240.19 },
                { 240.00, 240.37, 231.33, 232.19 },
                { 234.06, 235.60, 230.86, 234.42 },
                { 234.25, 236.49, 231.68, 232.79 },
                { 235.61, 237.11, 232.83, 233.24 },
                { 234.30, 234.59, 229.25, 231.20 },
                { 232.27, 233.58, 228.18, 230.33 },
                { 231.63, 233.43, 229.88, 231.67 },
                { 230.34, 231.35, 225.95, 225.95 },
                { 225.10, 226.11, 222.58, 223.06 },
                { 223.51, 224.20, 220.20, 222.20 },
                { 220.40, 221.11, 214.12, 215.10 },
                { 215.67, 216.46, 212.75, 214.20 },
                { 212.86, 213.31, 208.70, 210.46 },
                { 212.50, 212.94, 210.11, 211.08 },
                { 211.78, 212.60, 209.66, 212.09 },
                { 211.51, 211.94, 209.11, 209.89 },
                { 209.99, 210.61, 207.55, 209.82 },
                { 208.50, 208.77, 206.27, 207.78 },
                { 206.70, 208.11, 205.50, 205.66 },
                { 207.00, 210.65, 206.42, 208.87 },
                { 209.94, 211.06, 205.83, 210.51 }
        };

        BarSeries series = createDailySeries(dates, candles);
        HammerPattern pattern = new HammerPattern(series);

        HammerPattern.HammerMatch match = pattern.isHammer(series.getEndIndex());

        assertFalse(
                match.matched(),
                "03.09. hat zwar eine Hammer-Form, öffnet aber ÜBER dem Vortages-Close "
                        + "(HammerOpenRule) und der Body liegt über der Vortages-Range "
                        + "(HammerPositionRule) - beides muss das Signal verhindern");
    }
}

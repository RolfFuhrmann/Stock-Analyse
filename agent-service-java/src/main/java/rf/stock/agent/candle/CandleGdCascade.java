package rf.stock.agent.candle;

import java.util.List;

import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import rf.stock.agent.model.CandlePatternResult;

/**
 * Gemeinsame GD-Kaskade für BullishCandlePatterns (Downtrend, Schlusskurs
 * UNTER dem GD) und BearishCandlePatterns (Uptrend, Schlusskurs ÜBER dem GD)
 * - siehe jeweiligen Klassenkommentar für den fachlichen Hintergrund. Aus
 * beiden Klassen ausgelagert, um die Kaskaden-Logik nicht doppelt zu
 * pflegen.
 *
 * Reihenfolge bewusst unterschiedlich (24.08., Rolfs Wunsch):
 * bullisch (Downtrend) prüft zuerst GD200, dann GD50, dann GD20.
 * bearisch (Uptrend) prüft zuerst GD20, dann GD50, dann GD200.
 *
 * Nur noch gdPeriod im Ergebnis, keine Chart-Daten mehr (24.08.: die
 * Chart-Visualisierung im Client wurde auf Rolfs Wunsch wieder entfernt -
 * "Den Chart benötige ich nicht. Der Text reicht mir.").
 */
final class CandleGdCascade {

    /** Feste Indikator-Reihenfolge [200, 50, 20] - die Iterationsreihenfolge in withGdCascade() bestimmt die Prüfrichtung. */
    private static final int[] GD_PERIODS = { 200, 50, 20 };

    private CandleGdCascade() {
        /* This utility class should not be instantiated */
    }

    static SMAIndicator[] buildGdIndicators(ClosePriceIndicator closePrice) {
        return new SMAIndicator[] {
                new SMAIndicator(closePrice, 200),
                new SMAIndicator(closePrice, 50),
                new SMAIndicator(closePrice, 20),
        };
    }

    /**
     * Prüft für ein geometrisch bereits bestätigtes Muster die GD-Kaskade und
     * liefert bei Treffer das CandlePatternResult mit dem bestätigenden
     * gdPeriod. Gibt null zurück, wenn keiner der drei GDs den Trend
     * bestätigt (Muster geometrisch vorhanden, aber kein Trend-Kontext).
     *
     * @param downtrend true = Schlusskurs muss UNTER dem GD liegen, Prüfreihenfolge
     *                  GD200→GD50→GD20 (bullische Reversal-Muster); false = ÜBER dem GD,
     *                  Prüfreihenfolge GD20→GD50→GD200 (bearische Reversal-Muster)
     */
    static CandlePatternResult withGdCascade(ClosePriceIndicator closePrice, SMAIndicator[] gdIndicators, int index,
            String pattern, int strength, boolean downtrend) {

        Num close = closePrice.getValue(index);
        // downtrend: Indizes 0,1,2 -> GD200,GD50,GD20. uptrend: Indizes 2,1,0 -> GD20,GD50,GD200.
        for (int step = 0; step < GD_PERIODS.length; step++) {
            int i = downtrend ? step : GD_PERIODS.length - 1 - step;
            SMAIndicator sma = gdIndicators[i];
            boolean confirmed = downtrend ? close.isLessThan(sma.getValue(index)) : close.isGreaterThan(sma.getValue(index));
            if (confirmed) {
                return new CandlePatternResult(pattern, strength, GD_PERIODS[i], null, false);
            }
        }
        return null;
    }
}

package rf.stock.agent.candle;

import java.util.List;

import rf.stock.agent.model.OhlcvBar;

/**
 * Hilfsfunktionen für Kerzenberechnungen.
 * Arbeitet mit negativen Indizes (von hinten) genau wie die Python-Vorlage:
 * idx=-1 → letzte Kerze, idx=-2 → vorletzte usw.
 */
public class CandleUtils {

    private final double[] o;
    private final double[] h;
    private final double[] l;
    private final double[] c;
    public final int length;

    public CandleUtils(List<OhlcvBar> bars) {
        this.length = bars.size();
        this.o = new double[length];
        this.h = new double[length];
        this.l = new double[length];
        this.c = new double[length];
        for (int i = 0; i < length; i++) {
            o[i] = bars.get(i).open();
            h[i] = bars.get(i).high();
            l[i] = bars.get(i).low();
            c[i] = bars.get(i).close();
        }
    }

    /** Konvertiert negativen Index (von hinten) in absoluten Index. */
    private int idx(int negIdx) {
        return negIdx < 0 ? length + negIdx : negIdx;
    }

    public double open(int negIdx) {
        return o[idx(negIdx)];
    }

    public double high(int negIdx) {
        return h[idx(negIdx)];
    }

    public double low(int negIdx) {
        return l[idx(negIdx)];
    }

    public double close(int negIdx) {
        return c[idx(negIdx)];
    }

    /** Absolute Körpergröße der Kerze. */
    public double body(int negIdx) {
        return Math.abs(c[idx(negIdx)] - o[idx(negIdx)]);
    }

    public boolean isBearish(int negIdx) {
        return c[idx(negIdx)] < o[idx(negIdx)];
    }

    public boolean isBullish(int negIdx) {
        return c[idx(negIdx)] > o[idx(negIdx)];
    }

    public double lowerShadow(int negIdx) {
        int i = idx(negIdx);
        return Math.min(o[i], c[i]) - l[i];
    }

    public double upperShadow(int negIdx) {
        int i = idx(negIdx);
        return h[i] - Math.max(o[i], c[i]);
    }

    public boolean isDoji(int negIdx, double tolerance) {
        int i = idx(negIdx);
        double span = h[i] - l[i];
        if (span == 0)
            return true;
        return body(negIdx) / span < tolerance;
    }

    public double midpoint(int negIdx) {
        int i = idx(negIdx);
        return (o[i] + c[i]) / 2.0;
    }

    /**
     * Prüft, ob vor der Musterkerze an patternStartIndex ein Abwärtstrend vorliegt.
     * Das Fenster reicht von patternStartIndex - downtrendLength bis patternStartIndex
     * (exklusiv) und schließt damit patternStartIndex selbst NICHT ein – soll eine
     * Musterkerze (z.B. die lange rote Kerze eines Morning Star) Teil des Trends
     * sein, muss patternStartIndex entsprechend auf die Kerze DANACH zeigen.
     */
    public boolean hasDowntrendBefore(int patternStartIndex, int downtrendLength) {
        int absStart = length + patternStartIndex;
        int start = absStart - downtrendLength;

        if (start < 0) {
            return false;
        }

        return hasEnoughBearishCandles(start, absStart)
                && (hasLowerCloses(start, absStart) || hasLowerHighsAndLows(start, absStart));
    }

    private boolean hasEnoughBearishCandles(int start, int end) {
        int bearish = 0;

        for (int i = start; i < end; i++) {
            if (isBearish(i)) {
                bearish++;
            }
        }

        return bearish >= 3;
    }

    private boolean hasLowerCloses(int start, int end) {
        for (int i = start; i < end - 1; i++) {
            if (close(i + 1) >= close(i)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasLowerHighsAndLows(int start, int end) {
        for (int i = start; i < end - 1; i++) {
            if (high(i + 1) >= high(i) || low(i + 1) >= low(i)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Strikte Aufwärtstrend-Prüfung (für bearische Muster): Jede Kerze muss einen
     * höheren Schlusskurs ODER ein höheres Hoch und Tief als der Vorgänger haben
     * (Higher Closes bzw. Higher Highs / Higher Lows) – ohne Toleranz.
     * Das Fenster reicht von patternStartIndex - trendLength bis patternStartIndex
     * (exklusiv) und schließt patternStartIndex selbst NICHT ein – soll eine
     * Musterkerze (z.B. die grüne Kerze vor einem Bearish Engulfing) Teil des
     * Trends sein, muss patternStartIndex entsprechend auf die Kerze DANACH zeigen.
     */
    public boolean hasUptrendBefore(int patternStartIndex, int trendLength) {
        int absStart = length + patternStartIndex;
        int start = absStart - trendLength;

        if (start < 0) {
            return false;
        }

        return hasEnoughBullishCandles(start, absStart)
                && (hasHigherCloses(start, absStart) || hasHigherHighsAndLows(start, absStart));
    }

    private boolean hasEnoughBullishCandles(int start, int end) {
        int bullish = 0;

        for (int i = start; i < end; i++) {
            if (isBullish(i)) {
                bullish++;
            }
        }

        return bullish >= 3;
    }

    private boolean hasHigherCloses(int start, int end) {
        for (int i = start; i < end - 1; i++) {
            if (close(i + 1) <= close(i)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasHigherHighsAndLows(int start, int end) {
        for (int i = start; i < end - 1; i++) {
            if (high(i + 1) <= high(i) || low(i + 1) <= low(i)) {
                return false;
            }
        }

        return true;
    }
}

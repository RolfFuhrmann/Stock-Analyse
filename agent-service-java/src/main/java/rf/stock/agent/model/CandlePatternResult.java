package rf.stock.agent.model;

/**
 * Ergebnis der Candlestick-Pattern-Erkennung.
 * pattern ist null wenn kein Muster erkannt wurde.
 */
public record CandlePatternResult(
    String pattern,   // z.B. "Hammer", "Morning Star" oder null
    int    strength   // 0–5, 0 = kein Muster
) {
    public static CandlePatternResult none() {
        return new CandlePatternResult(null, 0);
    }
}

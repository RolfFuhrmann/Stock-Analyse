package rf.stock.agent.model;

/**
 * Ergebnis der Candlestick-Pattern-Erkennung.
 * pattern ist null wenn kein Muster erkannt wurde.
 * gdPeriod: welcher gleitende Durchschnitt (20/50/200) den Trend für
 * dieses Muster bestätigt hat (siehe CandleGdCascade) - null bei
 * "Bullish/Bearish Abandoned Baby" (nutzt weiterhin die alte ZigZag-
 * basierte Trendprüfung, kein GD) und bei fehlendem Muster.
 */
public record CandlePatternResult(
    String pattern,   // z.B. "Hammer", "Morning Star" oder null
    int    strength,  // 0–5, 0 = kein Muster
    Integer gdPeriod
) {
    public static CandlePatternResult none() {
        return new CandlePatternResult(null, 0, null);
    }
}

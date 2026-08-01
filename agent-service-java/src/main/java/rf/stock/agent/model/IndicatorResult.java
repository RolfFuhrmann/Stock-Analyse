package rf.stock.agent.model;

/**
 * Kombiniertes Ergebnis aus Elliott + MACD + Stochastik + Candle.
 * Wird von BullishIndicator und BearishIndicator zurückgegeben.
 *
 * elliottStage: menschenlesende Beschreibung des AKTUELLEN Wellen-Standes aus
 * ta4j (z.B. "A-B abgeschlossen, C im Entstehen"), unabhängig davon, ob
 * elliottOk true/false ist - zeigt auch Zwischenstadien, die (noch) nicht die
 * Konfidenz-/Vollständigkeits-Schwelle erreichen. Leer, wenn ta4j gar kein
 * Szenario findet.
 */
public record IndicatorResult(
    boolean elliottOk,
    String  elliottStage,
    boolean macdOk,
    boolean stochOk,
    int     criteriaMet,
    CandlePatternResult candle
) {
    public static IndicatorResult empty() {
        return new IndicatorResult(false, "", false, false, 0, CandlePatternResult.none());
    }
}

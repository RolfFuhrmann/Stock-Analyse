package rf.stock.agent.model;

/**
 * Kombiniertes Ergebnis aus Elliott + MACD + Stochastik + Candle.
 * Wird von BullishIndicator und BearishIndicator zurückgegeben.
 */
public record IndicatorResult(
    boolean elliottOk,
    boolean macdOk,
    boolean stochOk,
    int     criteriaMet,
    CandlePatternResult candle
) {
    public static IndicatorResult empty() {
        return new IndicatorResult(false, false, false, 0, CandlePatternResult.none());
    }
}

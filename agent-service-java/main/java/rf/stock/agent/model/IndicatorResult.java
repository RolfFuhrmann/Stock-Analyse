package rf.stock.agent.model;

/**
 * Kombiniertes Ergebnis aus Elliott + MACD + Stochastik + Candle.
 * Wird von BullishIndicator und BearishIndicator zurückgegeben.
 *
 * elliottStage: kompakte Darstellung der bereits VOLLENDETEN Wellen der
 * aktuell laufenden Welle aus ta4j (z.B. "A-B-" wenn Korrektur-Welle C im
 * Entstehen ist, "1-2-" wenn Impuls-Welle 3 im Entstehen ist), unabhängig
 * davon, ob elliottOk true/false ist - zeigt auch Zwischenstadien, die
 * (noch) nicht die Konfidenz-/Vollständigkeits-Schwelle erreichen. Seit
 * 03.08. mit Kursziel für die Vollendung der laufenden Welle, sofern
 * vorhanden, z.B. "A-B- -> 50% 75,00" (Preis von ta4j, Prozentzahl eigene
 * Näherung - siehe ElliottAnalysisUtil.describeTarget()). Leer, wenn noch
 * keine Welle abgeschlossen ist oder ta4j gar kein Szenario findet.
 *
 * elliottChart: strukturierte Bars/Swings/Ziel-Daten für die Chart-
 * Visualisierung im Frontend ("Option C"), null falls ta4j kein Szenario
 * findet - unabhängig von elliottOk (siehe ElliottChartData).
 */
public record IndicatorResult(
    boolean elliottOk,
    String  elliottStage,
    ElliottChartData elliottChart,
    boolean macdOk,
    boolean stochOk,
    int     criteriaMet,
    CandlePatternResult candle
) {
    public static IndicatorResult empty() {
        return new IndicatorResult(false, "", null, false, false, 0, CandlePatternResult.none());
    }
}

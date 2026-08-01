package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Analyse-Ergebnis pro Ticker – wird als SSE "result"-Event an Angular gepusht.
 * JSON-Feldnamen exakt wie im Python-Agent (snake_case) – der Angular-Client
 * erwartet dieses Format und darf nicht angepasst werden müssen.
 */
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StockResult(
    String ticker,
    String name,
    String interval,

    @JsonProperty("current_price")   Double currentPrice,
    @JsonProperty("trend_pct")       Double trendPct,
    @JsonProperty("trend_direction") String trendDirection,

    /**
     * Eigenständige, vom "gewonnenen" Indikator UNABHÄNGIGE Richtung, rein aus
     * den Rohwerten von MACD-Histogramm und Stochastik abgeleitet.
     * MACD-Histogramm gibt die Richtung vor ("bullish" bei > 0, "bearish" bei
     * < 0). Ist die Stochastik neutral (zwischen 20 und 80), gilt schlicht das
     * MACD-Vorzeichen. Nur wenn die Stochastik im Extrembereich der
     * GEGENTEILIGEN Richtung widerspricht – überkauft (> 80) bei negativem
     * MACD-H, oder überverkauft (< 20) bei positivem MACD-H –, gilt das als
     * Divergenz und das Feld ist null (Fallback, sollte praktisch nie
     * vorkommen).
     * Im Gegensatz zu trend_direction/elliott_wave (die vom Elliott-Wave- bzw.
     * Candle-Kriterium des jeweils "gewinnenden" Indikators abhängen) ist dieses
     * Feld immer eindeutig und unabhängig interpretierbar.
     */
    @JsonProperty("macd_stoch_direction") String macdStochDirection,

    @JsonProperty("elliott_wave")    boolean elliottWave,

    /**
     * Menschenlesbare Beschreibung des aktuellen ta4j-Wellen-Zwischenstands
     * (z.B. "A-B abgeschlossen, C im Entstehen"), unabhängig davon, ob
     * elliott_wave true/false ist. Zeigt auch Muster, die (noch) nicht die
     * Konfidenz-/Vollständigkeits-Schwelle für elliott_wave=true erreichen.
     * Leerstring, falls ta4j gar kein Szenario findet (z.B. zu wenig Daten).
     */
    @JsonProperty("elliott_wave_stage") String elliottWaveStage,

    @JsonProperty("stochastic")      boolean stochastic,
    @JsonProperty("macd_histogram")  boolean macdHistogram,
    @JsonProperty("criteria_met")    int criteriaMet,

    String source,

    @JsonProperty("candle_pattern")  String candlePattern,
    @JsonProperty("candle_strength") int candleStrength,

    // ML-Felder
    @JsonProperty("reversal_prob")   Double reversalProb,
    @JsonProperty("reversal_pct")    Double reversalPct,
    @JsonProperty("ml_signal")       String mlSignal,
    @JsonProperty("ml_confidence")   String mlConfidence,
    @JsonProperty("ml_available")    boolean mlAvailable,

    String error
) {
    /** Fehler-Ergebnis ohne Analyse-Daten. */
    public static StockResult error(String ticker, String name, String interval,
                                    String source, String errorMsg) {
        return StockResult.builder()
            .ticker(ticker)
            .name(name)
            .interval(interval)
            .elliottWave(false)
            .elliottWaveStage("")
            .stochastic(false)
            .macdHistogram(false)
            .criteriaMet(0)
            .source(source)
            .candleStrength(0)
            .mlSignal("none")
            .mlConfidence("low")
            .mlAvailable(false)
            .error(errorMsg)
            .build();
    }
}

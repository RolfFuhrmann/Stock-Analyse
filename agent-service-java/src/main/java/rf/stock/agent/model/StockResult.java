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

    @JsonProperty("elliott_wave")    boolean elliottWave,
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

package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request-Body für POST /analyze/stream.
 * Identisch zum Python-Agent – API-Vertrag darf nicht gebrochen werden.
 */
public record AnalyzeRequest(
    List<String> tickers,
    String source,
    String interval,
    @JsonProperty("lookback_days") Integer lookbackDays,
    @JsonProperty("session_id")    String sessionId,
    @JsonProperty("include_ml")    Boolean includeMl
) {
    public String sourceOrDefault()   { return source   != null ? source   : "yahoo"; }
    public String intervalOrDefault() { return interval != null ? interval : "1d"; }
    public boolean includeMlOrDefault() { return includeMl == null || includeMl; }
}

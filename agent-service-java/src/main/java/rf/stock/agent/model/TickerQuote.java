package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Quote-Payload aus dem SSE-Stream der Daten-Services.
 * Felder sind optional – bei Fehlern fehlt bars, error ist gesetzt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerQuote(
    String ticker,
    List<OhlcvBar> bars,
    @JsonProperty("longName")  String longName,
    @JsonProperty("shortName") String shortName,
    String name,
    String error
) {
    public boolean hasError() {
        return error != null && !error.isBlank();
    }

    public boolean hasBars() {
        return bars != null && !bars.isEmpty();
    }
}

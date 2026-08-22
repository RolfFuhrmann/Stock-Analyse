package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Quote-Payload aus dem SSE-Stream der Daten-Services.
 * Felder sind optional – bei Fehlern fehlt bars, error ist gesetzt.
 * currency: ISO-4217-Code (z.B. "USD", "EUR"), direkt von Yahoo
 * (info["currency"]) bzw. TwelveData übernommen - null, falls der jeweilige
 * Daten-Service das (noch) nicht liefert. Genauer als die alte, rein auf dem
 * Ticker-Suffix (".DE") basierende Heuristik im Frontend, die bei frei
 * konfigurierbaren, gemischten Ticker-Listen (z.B. US- und CH-Werte
 * zusammen) falsch liegen kann.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerQuote(
    String ticker,
    List<OhlcvBar> bars,
    @JsonProperty("longName")  String longName,
    @JsonProperty("shortName") String shortName,
    String name,
    String currency,
    String error
) {
    public boolean hasError() {
        return error != null && !error.isBlank();
    }

    public boolean hasBars() {
        return bars != null && !bars.isEmpty();
    }
}

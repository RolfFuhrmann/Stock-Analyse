package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Einzelne OHLCV-Kerze aus dem Yahoo- oder TwelveData-SSE-Stream.
 * "date" kann ein reines Datum (1d) oder ein ISO-Timestamp (1h/4h) sein.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OhlcvBar(
    String date,
    double open,
    double high,
    double low,
    double close,
    Double volume
) {}

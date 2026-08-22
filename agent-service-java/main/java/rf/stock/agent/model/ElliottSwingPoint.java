package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ein Wellen-Bein eines Elliott-Szenarios für die Chart-Visualisierung.
 * label: "A"/"B"/"C" bei einem Korrektur-Szenario, "1"-"5" bei einem
 * Impuls-Szenario (siehe ElliottAnalysisUtil.describeStage()).
 * from/to-Date sind dieselben Datumsstrings wie in OhlcvBar.date().
 */
public record ElliottSwingPoint(
    String label,
    @JsonProperty("from_date")  String fromDate,
    @JsonProperty("from_price") double fromPrice,
    @JsonProperty("to_date")    String toDate,
    @JsonProperty("to_price")   double toPrice
) {}

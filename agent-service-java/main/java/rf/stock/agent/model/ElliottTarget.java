package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kursziel der aktuell laufenden Welle (siehe
 * ElliottAnalysisUtil.describeTarget()). price kommt direkt von ta4j
 * (ElliottScenario.primaryTarget()). retracementPct ist unsere eigene
 * Näherung - null, falls sich kein sinnvolles Retracement berechnen ließ
 * (z.B. zu wenige Wellen-Beine für eine Referenzwelle).
 */
public record ElliottTarget(
    double price,
    @JsonProperty("retracement_pct") Long retracementPct
) {}

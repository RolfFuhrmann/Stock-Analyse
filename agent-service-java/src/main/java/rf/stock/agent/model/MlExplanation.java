package rf.stock.agent.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Erklärung eines ML-Modellwerts (21.09.): Wie setzt sich der Wert aus den
 * einzelnen Merkmalen zusammen? Der ml-service berechnet sie (XGBoost
 * pred_contribs), der Agent reicht sie unverändert an den Client durch
 * ("ml_explanation" im StockResult) - dieselben snake_case-Namen auf beiden Seiten.
 *
 * Es gilt: basePct + Summe(factors.effectPp) + otherPp = probPct.
 *
 * @param interval         Zeitrahmen der Vorhersage ("1d"/"4h"/"1h")
 * @param basePct          Ausgangswert des Modells ohne Kenntnis der Merkmale
 * @param probPct          Modellwert dieser Vorhersage in Prozent
 * @param factors          Die Merkmale mit dem größten Einfluss, nach Betrag sortiert
 * @param otherPp          Summe der Einflüsse aller nicht einzeln aufgeführten Merkmale
 * @param otherCount       Anzahl dieser übrigen Merkmale
 * @param typicalScorePct  Typischer Modellwert für diesen Zeitrahmen (Mittel aus dem Training), null bei älteren Modellen
 * @param actualRatePct    Anteil tatsächlicher Anstiege für diesen Zeitrahmen im Training, null bei älteren Modellen
 * @param forecastHorizon        Vorhersage-Horizont in Kerzen (29.09., Reversal-Label statt reinem Anstiegs-Label)
 * @param reversalThresholdPct   Mindest-Anstieg in %, um als Umkehr zu gelten
 * @param downtrendThresholdPct  Downtrend-Vorbedingung: Mindest-Abstand zum Referenz-Hoch in %, null bei Modellen vor dem 29.09.
 * @param downtrendLookback      Anzahl Kerzen für das Referenz-Hoch der Downtrend-Vorbedingung, null bei Modellen vor dem 29.09.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MlExplanation(
        String interval,
        @JsonProperty("base_pct")           Double basePct,
        @JsonProperty("prob_pct")           Double probPct,
        List<Factor> factors,
        @JsonProperty("other_pp")           Double otherPp,
        @JsonProperty("other_count")        Integer otherCount,
        @JsonProperty("typical_score_pct")  Double typicalScorePct,
        @JsonProperty("actual_rate_pct")    Double actualRatePct,
        @JsonProperty("forecast_horizon")        Integer forecastHorizon,
        @JsonProperty("reversal_threshold_pct")  Double reversalThresholdPct,
        @JsonProperty("downtrend_threshold_pct") Double downtrendThresholdPct,
        @JsonProperty("downtrend_lookback")      Integer downtrendLookback) {

    /**
     * Ein Merkmal mit seinem Einfluss.
     *
     * @param feature   technischer Name (z.B. "vol_20d")
     * @param label     deutsche Bezeichnung
     * @param value     aktueller Rohwert
     * @param valueText aufbereiteter Wert für die Anzeige (z.B. "4,1 %")
     * @param effectPp  Einfluss in Prozentpunkten: positiv schiebt den Wert nach oben, negativ nach unten
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Factor(
            String feature,
            String label,
            Double value,
            @JsonProperty("value_text") String valueText,
            @JsonProperty("effect_pp")  Double effectPp) {
    }
}

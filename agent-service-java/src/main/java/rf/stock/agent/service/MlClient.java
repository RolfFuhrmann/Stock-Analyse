package rf.stock.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import rf.stock.agent.config.ServiceConfig;

import java.time.Duration;
import java.util.Map;

/**
 * Client für den ML-Service (Reversal-Wahrscheinlichkeit).
 * Timeout strikt eingehalten – darf den SSE-Stream nicht blockieren.
 */
@Component
public class MlClient {

    private final WebClient webClient;
    private final String    mlUrl;
    private final Duration  timeout;

    public MlClient(
        WebClient.Builder builder,
        ServiceConfig serviceConfig,
        @Value("${analysis.ml-timeout-seconds:5}") int timeoutSeconds
    ) {
        this.webClient = builder.build();
        this.mlUrl     = serviceConfig.mlUrl();
        this.timeout   = Duration.ofSeconds(timeoutSeconds);
    }

    /** Standard-Antwort bei Fehlern oder Timeout – ML darf den Analyse-Stream nie blockieren. */
    public static MlSignal defaults() {
        return new MlSignal(null, null, "none", "low", false);
    }

    /**
     * Ruft die Umkehrwahrscheinlichkeit für einen Ticker ab.
     * interval wird mitgeschickt, damit das Modell den richtigen interval_code verwendet.
     */
    public Mono<MlSignal> fetchSignal(String ticker, String interval, int lookbackDays) {
        Map<String, Object> body = Map.of(
            "interval", interval,
            "lookback_days", lookbackDays
        );

        return webClient.post()
            .uri(mlUrl + "/predict/{ticker}", ticker)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(MlResponse.class)
            .map(MlClient::toSignal)
            .timeout(timeout)
            .onErrorResume(e -> Mono.just(defaults()));
    }

    private static MlSignal toSignal(MlResponse r) {
        return new MlSignal(
            r.reversalProb(),
            r.reversalPct(),
            r.signal() != null ? r.signal() : "none",
            r.confidence() != null ? r.confidence() : "low",
            r.modelAvailable() != null ? r.modelAvailable() : true
        );
    }

    /** Vereinfachtes, internes Ergebnis-Objekt für den AnalysisService. */
    public record MlSignal(
        Double reversalProb,
        Double reversalPct,
        String signal,
        String confidence,
        boolean modelAvailable
    ) {}

    /** Rohe Antwort vom ML-Service (Python, snake_case). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MlResponse(
        @JsonProperty("reversal_prob")   Double reversalProb,
        @JsonProperty("reversal_pct")    Double reversalPct,
        String signal,
        String confidence,
        @JsonProperty("model_available") Boolean modelAvailable
    ) {}
}

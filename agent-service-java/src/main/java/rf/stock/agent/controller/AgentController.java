package rf.stock.agent.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import rf.stock.agent.config.ServiceConfig;
import rf.stock.agent.model.AnalyzeRequest;
import rf.stock.agent.model.StockResult;
import rf.stock.agent.service.AnalysisService;

import java.util.Map;
import java.util.UUID;

/**
 * REST-Endpunkte des Agent Service.
 * API-Vertrag identisch zum ehemaligen Python-Agent – der Angular-Client
 * zeigt seit der Migration ohne Anpassung auf diesen Service (Port 8016).
 * Der Python-Agent (Port 8010) wurde am 09.09. entfernt (siehe
 * docker-compose.yml) - vollständig durch diesen Service ersetzt.
 */
@RestController
public class AgentController {

    private final AnalysisService analysisService;
    private final ServiceConfig   serviceConfig;
    private final ObjectMapper    objectMapper;

    public AgentController(AnalysisService analysisService, ServiceConfig serviceConfig, ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.serviceConfig   = serviceConfig;
        this.objectMapper    = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
            "status",         "ok",
            "service",        "agent-service-java",
            "yahoo_url",      serviceConfig.yahooUrl(),
            "twelvedata_url", serviceConfig.twelvedataUrl(),
            "ml_url",         serviceConfig.mlUrl()
        );
    }

    /**
     * Streamt die Analyse-Ergebnisse per Server-Sent-Events.
     * Ein "result"-Event pro Ticker, abgeschlossen mit "done".
     */
    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamAnalysis(@RequestBody AnalyzeRequest request) {
        if (request.tickers() == null || request.tickers().isEmpty()) {
            return Flux.just(
                ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"error\":\"Ticker-Liste ist leer\"}")
                    .build()
            );
        }

        String sessionId = (request.sessionId() != null && !request.sessionId().isBlank())
            ? request.sessionId()
            : UUID.randomUUID().toString();

        analysisService.registerSession(sessionId);

        int lookback = request.lookbackDays() != null
            ? request.lookbackDays()
            : AnalysisService.defaultLookback(request.intervalOrDefault());

        Flux<ServerSentEvent<String>> resultEvents = analysisService.analyze(
                request.tickers(),
                request.sourceOrDefault(),
                request.intervalOrDefault(),
                lookback,
                sessionId,
                request.includeMlOrDefault()
            )
            .map(this::toResultEvent);

        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
            .event("done")
            .data("{\"message\":\"Analyse abgeschlossen\"}")
            .build();

        return resultEvents.concatWith(Flux.just(doneEvent));
    }

    private ServerSentEvent<String> toResultEvent(StockResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            return ServerSentEvent.<String>builder().event("result").data(json).build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"error\":\"Serialisierungsfehler\"}")
                .build();
        }
    }

    @PostMapping("/analyze/stop")
    public Map<String, String> stopAnalysis(@RequestBody StopRequest request) {
        boolean found = analysisService.stopSession(request.sessionId());
        return Map.of(
            "status",     found ? "stopped" : "not_found",
            "session_id", request.sessionId()
        );
    }

    public record StopRequest(@JsonAlias("session_id") String sessionId) {}
}


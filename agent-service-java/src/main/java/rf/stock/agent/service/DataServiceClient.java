package rf.stock.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import rf.stock.agent.model.TickerQuote;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE-Client für Yahoo- und TwelveData-Service.
 * Konsumiert den "quote"-Event-Stream von POST /quotes/stream und
 * gibt einen Flux<TickerQuote> zurück – ein Element pro empfangenem Ticker.
 *
 * Analog zum SSE-Parsing-Code in main.py (analysis_stream, 1d-Pfad).
 */
@Component
public class DataServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DataServiceClient.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    public DataServiceClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        // Kein globaler Timeout – SSE-Streams laufen so lange wie nötig
        this.webClient    = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Abonniert den SSE-Stream eines Daten-Service (Yahoo oder TwelveData) mit
     * dem Standard-Intervall des jeweiligen Service (Tageskerzen).
     *
     * @param serviceUrl Basis-URL des Daten-Service
     * @param tickers    Ticker-Liste
     * @param outputsize Anzahl Kerzen (lookback + Puffer)
     * @return Flux mit einem TickerQuote-Element pro "quote"-Event; endet bei "done"
     */
    public Flux<TickerQuote> streamQuotes(String serviceUrl, List<String> tickers, int outputsize) {
        return streamQuotes(serviceUrl, tickers, outputsize, null);
    }

    /**
     * Wie {@link #streamQuotes(String, List, int)}, aber mit explizitem Intervall.
     *
     * @param interval Intervall in der Schreibweise des jeweiligen Service:
     *                 Yahoo "1d"/"1h", TwelveData "1day"/"1h"/"4h".
     *                 null = Standard des Service (Tageskerzen), das Feld wird dann
     *                 gar nicht erst mitgeschickt.
     */
    public Flux<TickerQuote> streamQuotes(String serviceUrl, List<String> tickers, int outputsize, String interval) {
        Map<String, Object> body = new HashMap<>();
        body.put("tickers", tickers);
        body.put("outputsize", outputsize);
        if (interval != null) {
            body.put("interval", interval);
        }

        return webClient.post()
            .uri(serviceUrl + "/quotes/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(SSE_TYPE)
            .takeUntil(evt -> "done".equals(evt.event()))
            .filter(evt -> "quote".equals(evt.event()) && evt.data() != null)
            .mapNotNull(evt -> parseQuote(evt.data()))
            .onErrorResume(e -> {
                log.error("SSE-Stream-Fehler [{}]: {}", serviceUrl, e.getMessage());
                return Flux.empty();
            });
    }

    private TickerQuote parseQuote(String json) {
        try {
            return objectMapper.readValue(json, TickerQuote.class);
        } catch (Exception e) {
            log.error("Parse-Fehler bei Quote-JSON: {}", e.getMessage());
            return null;
        }
    }
}

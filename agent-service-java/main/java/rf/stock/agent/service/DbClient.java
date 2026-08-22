package rf.stock.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import rf.stock.agent.config.ServiceConfig;
import rf.stock.agent.model.OhlcvBar;
import rf.stock.agent.model.TickerQuote;

import java.time.Duration;
import java.util.List;

/**
 * Client für den DB-Access-Service.
 * Liefert 4h/1h-Kerzen direkt aus der DB statt über SSE
 * (analog zu _fetch_db_quote() im Python-Agent).
 */
@Component
public class DbClient {

    private final WebClient webClient;
    private final String    dbUrl;
    private final Duration  timeout;

    public DbClient(
        WebClient.Builder builder,
        ServiceConfig serviceConfig,
        @Value("${analysis.db-timeout-seconds:10}") int timeoutSeconds
    ) {
        this.webClient = builder.build();
        this.dbUrl     = serviceConfig.dbUrl();
        this.timeout   = Duration.ofSeconds(timeoutSeconds);
    }

    /**
     * Lädt die neuesten N Kerzen für einen Ticker aus der DB.
     * interval: "4h" oder "1h" – "1d" wird hier nicht unterstützt (läuft über SSE).
     */
    public Mono<TickerQuote> fetchQuote(String ticker, String interval, int n) {
        String path = interval.equals("4h")
            ? "/api/ohlcv/4h/{ticker}/latest"
            : "/api/ohlcv/hourly/{ticker}/latest";

        return webClient.get()
            .uri(dbUrl + path + "?n={n}", ticker, n)
            .retrieve()
            .bodyToFlux(DbBar.class)
            .collectList()
            .map(rawBars -> toQuote(ticker, rawBars))
            .timeout(timeout)
            .onErrorResume(e -> Mono.just(
                new TickerQuote(ticker, List.of(), null, null, null, null, e.getMessage())
            ));
    }

    private TickerQuote toQuote(String ticker, List<DbBar> rawBars) {
        if (rawBars.isEmpty()) {
            return new TickerQuote(ticker, List.of(), null, null, null, null, "Keine Daten in DB");
        }
        List<OhlcvBar> bars = rawBars.stream()
            .map(b -> new OhlcvBar(b.tradeTime(), b.open(), b.high(), b.low(), b.close(), b.volume()))
            .toList();
        return new TickerQuote(ticker, bars, null, null, null, null, null);
    }

    /** Antwortformat vom DB-Access-Service (Spring Boot, camelCase). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DbBar(
        String ticker,
        @JsonProperty("tradeTime") String tradeTime,
        double open,
        double high,
        double low,
        double close,
        Double volume
    ) {}
}

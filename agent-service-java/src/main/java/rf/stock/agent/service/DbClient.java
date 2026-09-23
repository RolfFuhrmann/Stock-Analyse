package rf.stock.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import rf.stock.agent.config.ServiceConfig;
import rf.stock.agent.indicator.ElliottAnalysisUtil;
import rf.stock.agent.model.OhlcvBar;
import rf.stock.agent.model.TickerQuote;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Client für den DB-Access-Service.
 * Liefert 4h/1h-Kerzen direkt aus der DB statt über SSE
 * (analog zu _fetch_db_quote() im Python-Agent).
 *
 * Seit 09.09. zusätzlich Schreibzugriff (siehe DailyBarWriteBackService):
 * Live-1d-Analysen schreiben die aktuellsten Tageskerzen in die DB zurück,
 * damit aktiv genutzte Ticker taggenau frisch bleiben, ohne auf den
 * täglichen history-fetcher-Cron warten zu müssen.
 *
 * Seit 20.09. dasselbe für 1h/4h (siehe IntradayBarWriteBackService).
 * fetchQuote() wird von der Analyse seither nicht mehr benutzt (4h/1h werden
 * live abgerufen), bleibt aber als DB-Fallback erhalten.
 */
@Component
public class DbClient {

    private static final Logger log = LoggerFactory.getLogger(DbClient.class);

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

    /**
     * Letztes in der DB vorhandenes Datum für einen Ticker (Tageskerzen).
     * Liefert ein leeres Mono, wenn noch keine Daten vorhanden sind ODER
     * bei einem Fehler - DailyBarWriteBackService behandelt beide Fälle
     * gleich (dann wird ohne Cutoff alles Abgerufene geschrieben).
     */
    public Mono<LocalDate> fetchLatestDailyDate(String ticker) {
        return webClient.get()
            .uri(dbUrl + "/api/ohlcv/daily/{ticker}/latest?n=1", ticker)
            .retrieve()
            .bodyToFlux(DailyDateOnly.class)
            .next()
            .map(DailyDateOnly::tradeDate)
            .timeout(timeout)
            .onErrorResume(e -> {
                log.debug("fetchLatestDailyDate [{}] fehlgeschlagen (wird als 'kein Vorwissen' behandelt): {}",
                        ticker, e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * Schreibt Tageskerzen in die DB zurück (Upsert, siehe
     * OhlcvService.bulkInsertDaily in stock-data-db-access). Fehler werden
     * NICHT hier behandelt - DailyBarWriteBackService entscheidet, wie mit
     * einem fehlgeschlagenen Write-back umgegangen wird (fire-and-forget,
     * darf die eigentliche Analyse-Antwort nie beeinflussen).
     */
    public Mono<Void> writeBackDailyBars(String ticker, String source, List<OhlcvBar> bars) {
        if (bars.isEmpty()) {
            return Mono.empty();
        }

        List<DailyBarWrite> barWrites = bars.stream()
            .map(b -> new DailyBarWrite(
                ElliottAnalysisUtil.parseBarDate(b.date()).atZone(ZoneOffset.UTC).toLocalDate(),
                BigDecimal.valueOf(b.open()),
                BigDecimal.valueOf(b.high()),
                BigDecimal.valueOf(b.low()),
                BigDecimal.valueOf(b.close()),
                b.volume() != null ? Math.round(b.volume()) : null
            ))
            .toList();

        BulkWriteRequest req = new BulkWriteRequest(ticker, source, barWrites);

        return webClient.post()
            .uri(dbUrl + "/api/ohlcv/daily/bulk")
            .bodyValue(req)
            .retrieve()
            .bodyToMono(Void.class)
            .timeout(timeout);
    }

    /**
     * Zeitpunkt der neuesten Stundenkerze in der DB (Lokalzeit-Zeitstempel wie
     * gespeichert). Leeres Mono bei "keine Daten" ODER Fehler - siehe
     * {@link #fetchLatestDailyDate(String)}.
     */
    public Mono<LocalDateTime> fetchLatestHourlyTime(String ticker) {
        return fetchLatestBarTime("/api/ohlcv/hourly/{ticker}/latest?n=1", ticker);
    }

    /** Zeitpunkt der neuesten 4h-Kerze in der DB - Verhalten wie {@link #fetchLatestHourlyTime}. */
    public Mono<LocalDateTime> fetchLatestFourHourlyTime(String ticker) {
        return fetchLatestBarTime("/api/ohlcv/4h/{ticker}/latest?n=1", ticker);
    }

    private Mono<LocalDateTime> fetchLatestBarTime(String path, String ticker) {
        return webClient.get()
            .uri(dbUrl + path, ticker)
            .retrieve()
            .bodyToFlux(TimeOnly.class)
            .next()
            .map(t -> LocalDateTime.parse(t.tradeTime()))
            .timeout(timeout)
            .onErrorResume(e -> {
                log.debug("fetchLatestBarTime [{}] {} fehlgeschlagen (wird als 'kein Vorwissen' behandelt): {}",
                        ticker, path, e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * Schreibt Stundenkerzen in die DB zurück (Upsert, siehe
     * OhlcvService.bulkInsertHourly in stock-data-db-access). Die Zeitstempel
     * der Kerzen müssen bereits normalisiert sein (IntradayBarUtil.normalize).
     * Fehlerbehandlung wie bei {@link #writeBackDailyBars}: nicht hier.
     */
    public Mono<Void> writeBackHourlyBars(String ticker, String source, List<OhlcvBar> bars) {
        return postIntradayBars("/api/ohlcv/hourly/bulk", ticker, source, bars);
    }

    /** Schreibt 4h-Kerzen in die DB zurück (Upsert) - siehe {@link #writeBackHourlyBars}. */
    public Mono<Void> writeBackFourHourlyBars(String ticker, String source, List<OhlcvBar> bars) {
        return postIntradayBars("/api/ohlcv/4h/bulk", ticker, source, bars);
    }

    private Mono<Void> postIntradayBars(String path, String ticker, String source, List<OhlcvBar> bars) {
        if (bars.isEmpty()) {
            return Mono.empty();
        }

        List<IntradayBarWrite> barWrites = bars.stream()
            .map(b -> new IntradayBarWrite(
                b.date(),
                BigDecimal.valueOf(b.open()),
                BigDecimal.valueOf(b.high()),
                BigDecimal.valueOf(b.low()),
                BigDecimal.valueOf(b.close()),
                b.volume() != null ? Math.round(b.volume()) : null
            ))
            .toList();

        return webClient.post()
            .uri(dbUrl + path)
            .bodyValue(new IntradayBulkWriteRequest(ticker, source, barWrites))
            .retrieve()
            .bodyToMono(Void.class)
            .timeout(timeout);
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

    /** Nur das Datumsfeld aus OhlcvDailyResponse - siehe fetchLatestDailyDate(). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DailyDateOnly(@JsonProperty("tradeDate") LocalDate tradeDate) {}

    /** Muss exakt zu OhlcvDailyBulkRequest in stock-data-db-access passen. */
    private record BulkWriteRequest(String ticker, String source, List<DailyBarWrite> bars) {}

    private record DailyBarWrite(
        @JsonProperty("tradeDate") LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume
    ) {}

    /** Nur das Zeitfeld aus OhlcvHourlyResponse/OhlcvFourHourlyResponse - siehe fetchLatestBarTime(). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TimeOnly(@JsonProperty("tradeTime") String tradeTime) {}

    /** Muss exakt zu OhlcvHourlyBulkRequest / OhlcvFourHourlyBulkRequest in stock-data-db-access passen. */
    private record IntradayBulkWriteRequest(String ticker, String source, List<IntradayBarWrite> bars) {}

    /** tradeTime als ISO-String "yyyy-MM-ddTHH:mm:ss" - der DB-Service bindet ihn zu LocalDateTime. */
    private record IntradayBarWrite(
        @JsonProperty("tradeTime") String tradeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume
    ) {}
}

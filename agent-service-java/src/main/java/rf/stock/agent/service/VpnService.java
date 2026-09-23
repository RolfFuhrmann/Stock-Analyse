package rf.stock.agent.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import rf.stock.agent.config.ServiceConfig;
import rf.stock.agent.model.VpnInfo;
import rf.stock.agent.model.VpnRotateResult;

/**
 * VPN-Steuerung für die Einstellungen im Client (21.09.).
 *
 * Yahoo-Abrufe laufen über den VPN-Container (Gluetun); der yahoo-service
 * teilt sich dessen Netzwerk. Zwei Quellen:
 * - Gluetun-Steuerungs-Server (Port 8000): Tunnel-Status und Neustart per
 *   PUT /v1/vpn/status. Nur die in Gluetuns config.toml freigegebenen Routen
 *   sind erreichbar (GET /v1/vpn/status, PUT /v1/vpn/status) - die Settings-
 *   Route mit den WireGuard-Schlüsseln bleibt bewusst gesperrt.
 * - yahoo-service GET /ip: Ausgangs-IP samt Standort. Gluetuns eigene
 *   Public-IP-Route liefert nach einem Neustart oft nichts (Abruf direkt nach
 *   dem Tunnelaufbau schlägt fehl), die Abfrage aus dem VPN-Netz funktioniert
 *   dagegen verlässlich.
 *
 * IP-Wechsel: VPN stoppen und wieder starten - Gluetun wählt beim Start einen
 * Server aus dem gefilterten Pool (bei ProtonVPN Free klein), es kann also
 * derselbe Server wiederkommen. Deshalb wird bis zu MAX_ATTEMPTS Mal
 * wiederholt, bis sich die IP ändert.
 */
@Service
public class VpnService {

    private static final Logger log = LoggerFactory.getLogger(VpnService.class);

    static final int MAX_ATTEMPTS = 3;
    private static final Duration STOP_PAUSE      = Duration.ofSeconds(3);
    private static final Duration POLL_INTERVAL   = Duration.ofSeconds(3);
    private static final Duration TUNNEL_TIMEOUT  = Duration.ofSeconds(90);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;
    private final String       controlUrl;
    private final String       yahooUrl;

    /** Verhindert parallele Wechsel (Doppelklick, zweiter Browser-Tab). */
    private final AtomicBoolean rotating = new AtomicBoolean(false);

    public VpnService(WebClient.Builder builder, ServiceConfig serviceConfig, ObjectMapper objectMapper) {
        this.webClient    = builder.build();
        this.objectMapper = objectMapper;
        this.controlUrl   = serviceConfig.vpnControlUrl();
        this.yahooUrl     = serviceConfig.yahooUrl();
    }

    // ── Info ─────────────────────────────────────────────────────────────────

    /** Tunnel-Status und Ausgangs-IP samt Standort. Teilausfälle werden im Feld "error" gemeldet. */
    public Mono<VpnInfo> info() {
        Mono<StatusResult> status = fetchVpnStatus()
                .map(value -> new StatusResult(value, null))
                .onErrorResume(e -> {
                    log.warn("VPN-Status nicht abrufbar: {}", e.toString());
                    return Mono.just(new StatusResult(null, describe(e)));
                })
                .defaultIfEmpty(new StatusResult(null, "leere Antwort der VPN-Steuerung"));

        Mono<ExitInfo> exit = fetchExitInfo().defaultIfEmpty(ExitInfo.EMPTY);

        return Mono.zip(status, exit).map(t -> {
            StatusResult vpn = t.getT1();
            ExitInfo e = t.getT2();
            String error = null;
            if (vpn.status() == null) {
                error = "VPN-Status nicht abrufbar: " + vpn.error();
            } else if (e.ip() == null && "running".equals(vpn.status())) {
                error = "Keine Ausgangs-IP ermittelbar - Tunnel wird ggf. gerade aufgebaut";
            }
            return new VpnInfo(vpn.status(), e.ip(), e.city(), e.region(), e.country(), e.organization(), error);
        });
    }

    // ── IP-Wechsel ───────────────────────────────────────────────────────────

    /**
     * Wechselt die VPN-IP (Stopp/Start des Tunnels). Meldet Fehler im Ergebnis
     * statt als Exception.
     *
     * Der Wechsel läuft unabhängig vom HTTP-Aufrufer zu Ende: Schließt der
     * Nutzer den Browser-Tab mitten im Wechsel, bricht WebFlux die Antwort ab -
     * ohne Entkopplung bliebe das VPN im Zustand "stopped" hängen (Gluetun
     * startet es dann nicht von selbst). Deshalb wird der Ablauf sofort selbst
     * abonniert und das Ergebnis per cache() an den Aufrufer weitergereicht.
     */
    public Mono<VpnRotateResult> rotate() {
        if (!rotating.compareAndSet(false, true)) {
            return Mono.just(VpnRotateResult.failed("Ein IP-Wechsel läuft bereits"));
        }

        Mono<VpnRotateResult> job = fetchExitInfo()
                .mapNotNull(ExitInfo::ip)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(oldIp -> attempt(1, oldIp.orElse(null)))
                .onErrorResume(e -> {
                    log.warn("IP-Wechsel fehlgeschlagen: {}", e.toString());
                    // Nach einem Fehler nicht im Zustand "stopped" zurücklassen
                    return ensureVpnRunning().then(Mono.just(VpnRotateResult.failed(describe(e))));
                })
                .doFinally(signal -> rotating.set(false))
                .cache();

        job.subscribe(result -> { }, error -> log.warn("IP-Wechsel: unerwarteter Fehler", error));
        return job;
    }

    /** Best effort: startet das VPN, falls ein fehlgeschlagener Wechsel es gestoppt hat. */
    private Mono<Void> ensureVpnRunning() {
        return setVpnStatus("running").onErrorResume(e -> {
            log.warn("VPN konnte nach Fehler nicht neu gestartet werden: {}", e.getMessage());
            return Mono.empty();
        });
    }

    private Mono<VpnRotateResult> attempt(int attempt, String oldIp) {
        log.info("VPN-IP-Wechsel: Versuch {}/{} (bisherige IP {})", attempt, MAX_ATTEMPTS, oldIp);
        return setVpnStatus("stopped")
                .then(Mono.delay(STOP_PAUSE))
                .then(setVpnStatus("running"))
                .then(waitForExitIp())
                .flatMap(newIp -> {
                    if (newIp.equals(oldIp) && attempt < MAX_ATTEMPTS) {
                        log.info("VPN-IP unverändert ({}), neuer Versuch", newIp);
                        return attempt(attempt + 1, oldIp);
                    }
                    log.info("VPN-IP-Wechsel beendet: {} -> {} ({} Versuch(e))", oldIp, newIp, attempt);
                    return Mono.just(VpnRotateResult.success(oldIp, newIp, attempt));
                });
    }

    /** Fragt alle POLL_INTERVAL die Ausgangs-IP ab, bis der Tunnel wieder eine liefert. */
    private Mono<String> waitForExitIp() {
        return Flux.interval(POLL_INTERVAL)
                .concatMap(tick -> fetchExitInfo().mapNotNull(ExitInfo::ip))
                .next()
                .timeout(TUNNEL_TIMEOUT, Mono.error(new TunnelTimeoutException()));
    }

    // ── HTTP-Aufrufe ─────────────────────────────────────────────────────────

    /**
     * Liest den Tunnel-Status von Gluetun. Die Antwort wird bewusst als Text
     * gelesen und selbst geparst: WebClient dekodiert nur, wenn der
     * Content-Type der Antwort zum Zieltyp passt - auf den ist bei Gluetuns
     * Steuerungs-Server kein Verlass.
     */
    private Mono<String> fetchVpnStatus() {
        return webClient.get()
                .uri(controlUrl + "/v1/vpn/status")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .mapNotNull(this::parseStatus);
    }

    private String parseStatus(String body) {
        try {
            JsonNode status = objectMapper.readTree(body).path("status");
            return status.isMissingNode() || status.isNull() ? null : status.asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unerwartete Antwort der VPN-Steuerung: " + body, e);
        }
    }

    private Mono<Void> setVpnStatus(String status) {
        return webClient.put()
                .uri(controlUrl + "/v1/vpn/status")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", status))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(REQUEST_TIMEOUT);
    }

    /** Leeres Mono, wenn die Abfrage fehlschlägt (z.B. Tunnel gerade unten) - Aufrufer entscheiden, wie sie damit umgehen. */
    private Mono<ExitInfo> fetchExitInfo() {
        return webClient.get()
                .uri(yahooUrl + "/ip")
                .retrieve()
                .bodyToMono(ExitInfo.class)
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(e -> {
                    log.debug("Ausgangs-IP nicht abrufbar: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private static String describe(Throwable e) {
        if (e instanceof TunnelTimeoutException) {
            return "Der VPN-Tunnel wurde nach " + TUNNEL_TIMEOUT.toSeconds()
                    + " s nicht wieder aufgebaut - Gluetun versucht es ggf. selbst erneut, danach 'Aktualisieren' drücken";
        }
        if (e instanceof TimeoutException) {
            return "VPN-Steuerung antwortet nicht innerhalb von " + REQUEST_TIMEOUT.toSeconds() + " s";
        }
        if (e instanceof WebClientResponseException r) {
            if (r.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                return "VPN-Steuerung verweigert den Zugriff (config.toml in Gluetun prüfen)";
            }
            return "VPN-Steuerung antwortet mit " + r.getStatusCode().value();
        }
        if (e instanceof WebClientRequestException) {
            return "VPN-Steuerung nicht erreichbar (" + e.getMessage() + ")";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Tunnel kam nach dem Neustart nicht innerhalb von TUNNEL_TIMEOUT zurück (unterscheidbar von HTTP-Timeouts). */
    private static final class TunnelTimeoutException extends RuntimeException {
        TunnelTimeoutException() {
            super("Tunnel nicht wieder aufgebaut", null, false, false);
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /** Ergebnis der Statusabfrage: entweder der Status oder der Grund, warum er fehlt. */
    private record StatusResult(String status, String error) {}

    /** Antwort von yahoo-service GET /ip. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExitInfo(
            @JsonProperty("ip") String ip,
            @JsonProperty("city") String city,
            @JsonProperty("region") String region,
            @JsonProperty("country") String country,
            @JsonProperty("organization") String organization) {

        static final ExitInfo EMPTY = new ExitInfo(null, null, null, null, null);
    }
}

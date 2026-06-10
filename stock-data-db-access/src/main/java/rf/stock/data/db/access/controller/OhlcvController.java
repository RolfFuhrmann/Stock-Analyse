package rf.stock.data.db.access.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rf.stock.data.db.access.busines.OhlcvDtos.*;
import rf.stock.data.db.access.busines.OhlcvService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * REST-Endpunkte für historische Kursdaten (OHLCV).
 *
 * Basis-URL: /api/ohlcv
 *
 * ── TickerMeta ───────────────────────────────────────────────────────────────
 *   GET    /api/ohlcv/meta                     – alle bekannten Ticker
 *   GET    /api/ohlcv/meta/{ticker}            – Stammdaten + Abdeckung
 *   POST   /api/ohlcv/meta                     – Ticker anlegen
 *   PUT    /api/ohlcv/meta/{ticker}            – Ticker anlegen oder aktualisieren (Upsert)
 *
 * ── Tageskerzen ──────────────────────────────────────────────────────────────
 *   GET    /api/ohlcv/daily/{ticker}           – alle Tageskerzen
 *   GET    /api/ohlcv/daily/{ticker}?from=&to= – Datumsbereich
 *   GET    /api/ohlcv/daily/{ticker}/latest?n= – neueste N Kerzen
 *   POST   /api/ohlcv/daily/bulk              – Bulk-Insert (history-fetcher)
 *
 * ── Stundenkerzen ────────────────────────────────────────────────────────────
 *   GET    /api/ohlcv/hourly/{ticker}           – alle Stundenkerzen
 *   GET    /api/ohlcv/hourly/{ticker}?from=&to= – Zeitbereich
 *   GET    /api/ohlcv/hourly/{ticker}/latest?n= – neueste N Kerzen
 *   POST   /api/ohlcv/hourly/bulk              – Bulk-Insert (history-fetcher)
 *
 * ── Fetch-Log ────────────────────────────────────────────────────────────────
 *   GET    /api/ohlcv/fetch-log/{ticker}       – Abruf-Protokoll pro Ticker
 *   GET    /api/ohlcv/fetch-log/errors?hours=  – Fehler der letzten N Stunden
 *   POST   /api/ohlcv/fetch-log               – Eintrag schreiben (history-fetcher)
 *
 * ── Monitoring ───────────────────────────────────────────────────────────────
 *   GET    /api/ohlcv/coverage                 – Datenbestand-Übersicht
 */
@RestController
@RequestMapping("/api/ohlcv")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OhlcvController {

    private final OhlcvService service;

    // ── TickerMeta ────────────────────────────────────────────────────────────

    @GetMapping("/meta")
    public ResponseEntity<List<TickerMetaResponse>> getAllMeta() {
        return ResponseEntity.ok(service.getAllMeta());
    }

    @GetMapping("/meta/{ticker}")
    public ResponseEntity<TickerMetaResponse> getMeta(@PathVariable String ticker) {
        return ResponseEntity.ok(service.getMetaByTicker(ticker.toUpperCase()));
    }

    @PostMapping("/meta")
    public ResponseEntity<TickerMetaResponse> createMeta(
        @Valid @RequestBody TickerMetaRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createMeta(req));
    }

    /** Upsert: legt an oder aktualisiert – vom history-fetcher genutzt. */
    @PutMapping("/meta/{ticker}")
    public ResponseEntity<TickerMetaResponse> upsertMeta(
        @PathVariable String ticker,
        @Valid @RequestBody TickerMetaRequest req
    ) {
        return ResponseEntity.ok(service.upsertMeta(req));
    }

    // ── Tageskerzen ───────────────────────────────────────────────────────────

    @GetMapping("/daily/{ticker}")
    public ResponseEntity<List<OhlcvDailyResponse>> getDailyBars(
        @PathVariable String ticker,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        String t = ticker.toUpperCase();
        if (from != null && to != null) {
            return ResponseEntity.ok(service.getDailyBars(t, from, to));
        }
        return ResponseEntity.ok(service.getDailyBars(t));
    }

    @GetMapping("/daily/{ticker}/latest")
    public ResponseEntity<List<OhlcvDailyResponse>> getLatestDailyBars(
        @PathVariable String ticker,
        @RequestParam(defaultValue = "90") int n
    ) {
        return ResponseEntity.ok(service.getLatestDailyBars(ticker.toUpperCase(), n));
    }

    /**
     * Bulk-Insert: vom history-fetcher aufgerufen.
     * Bereits vorhandene Kerzen werden übersprungen (Idempotenz).
     */
    @PostMapping("/daily/bulk")
    public ResponseEntity<OhlcvDailyBulkResponse> bulkInsertDaily(
        @Valid @RequestBody OhlcvDailyBulkRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.bulkInsertDaily(req));
    }

    // ── Stundenkerzen ─────────────────────────────────────────────────────────

    @GetMapping("/hourly/{ticker}")
    public ResponseEntity<List<OhlcvHourlyResponse>> getHourlyBars(
        @PathVariable String ticker,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        String t = ticker.toUpperCase();
        if (from != null && to != null) {
            return ResponseEntity.ok(service.getHourlyBars(t, from, to));
        }
        return ResponseEntity.ok(service.getHourlyBars(t));
    }

    @GetMapping("/hourly/{ticker}/latest")
    public ResponseEntity<List<OhlcvHourlyResponse>> getLatestHourlyBars(
        @PathVariable String ticker,
        @RequestParam(defaultValue = "168") int n   // 168 = 1 Woche in Stunden
    ) {
        return ResponseEntity.ok(service.getLatestHourlyBars(ticker.toUpperCase(), n));
    }

    @PostMapping("/hourly/bulk")
    public ResponseEntity<OhlcvHourlyBulkResponse> bulkInsertHourly(
        @Valid @RequestBody OhlcvHourlyBulkRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.bulkInsertHourly(req));
    }

    // ── Fetch-Log ─────────────────────────────────────────────────────────────

    @PostMapping("/fetch-log")
    public ResponseEntity<FetchLogResponse> logFetch(
        @Valid @RequestBody FetchLogRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.logFetch(req));
    }

    @GetMapping("/fetch-log/{ticker}")
    public ResponseEntity<List<FetchLogResponse>> getLogByTicker(
        @PathVariable String ticker
    ) {
        return ResponseEntity.ok(service.getLogByTicker(ticker.toUpperCase()));
    }

    @GetMapping("/fetch-log/errors")
    public ResponseEntity<List<FetchLogResponse>> getRecentErrors(
        @RequestParam(defaultValue = "24") int hours
    ) {
        return ResponseEntity.ok(service.getRecentErrors(hours));
    }

    // ── Monitoring ────────────────────────────────────────────────────────────

    @GetMapping("/coverage")
    public ResponseEntity<CoverageSummaryResponse> getCoverage() {
        return ResponseEntity.ok(service.getCoverage());
    }
}

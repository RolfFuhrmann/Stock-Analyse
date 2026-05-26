package rf.stock.data.db.access.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rf.stock.data.db.access.busines.TickerDtos.*;
import rf.stock.data.db.access.busines.TickerListService;

import java.util.List;

/**
 * REST-Controller für die Listen-Verwaltung.
 *
 * Basis-URL: /api/lists
 *
 * Listen:
 *   GET    /api/lists                        → alle Listen (ohne Symbole)
 *   GET    /api/lists/{id}                   → Liste mit allen Symbolen
 *   GET    /api/lists/code/{code}            → Liste per Code (z.B. "DAX40")
 *   POST   /api/lists                        → neue Liste anlegen
 *   PUT    /api/lists/{id}                   → Liste umbenennen/beschreiben
 *   DELETE /api/lists/{id}                   → Liste + alle Symbole löschen
 *
 * Symbole:
 *   GET    /api/lists/{id}/symbols           → alle Symbole einer Liste
 *   POST   /api/lists/{id}/symbols           → Symbol hinzufügen
 *   PUT    /api/lists/{id}/symbols/{symId}   → Symbol ändern
 *   DELETE /api/lists/{id}/symbols/{symId}   → Symbol entfernen
 *
 * Agent-Service Integration:
 *   GET    /api/lists/code/{code}/yahoo-symbols → nur yahoo_symbols (für Analyse-Requests)
 */
@RestController
@RequestMapping("/api/lists")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TickerListController {

    private final TickerListService service;

    // ── Listen ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<TickerListResponse>> getAllLists() {
        return ResponseEntity.ok(service.getAllLists());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TickerListDetailResponse> getListById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getListById(id));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<TickerListDetailResponse> getListByCode(@PathVariable String code) {
        return ResponseEntity.ok(service.getListByCode(code.toUpperCase()));
    }

    /** Gibt nur die yahoo_symbols zurück – für den agent-service direkt verwendbar. */
    @GetMapping("/code/{code}/yahoo-symbols")
    public ResponseEntity<YahooSymbolsResponse> getYahooSymbols(@PathVariable String code) {
        return ResponseEntity.ok(service.getYahooSymbols(code.toUpperCase()));
    }

    @PostMapping
    public ResponseEntity<TickerListResponse> createList(@Valid @RequestBody TickerListRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createList(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TickerListResponse> updateList(
        @PathVariable Long id,
        @Valid @RequestBody TickerListRequest request
    ) {
        return ResponseEntity.ok(service.updateList(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteList(@PathVariable Long id) {
        service.deleteList(id);
        return ResponseEntity.noContent().build();
    }

    // ── Symbole ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/symbols")
    public ResponseEntity<List<TickerSymbolResponse>> getSymbols(@PathVariable Long id) {
        return ResponseEntity.ok(service.getSymbolsByListId(id));
    }

    @PostMapping("/{id}/symbols")
    public ResponseEntity<TickerSymbolResponse> addSymbol(
        @PathVariable Long id,
        @Valid @RequestBody TickerSymbolRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addSymbol(id, request));
    }

    @PutMapping("/{id}/symbols/{symbolId}")
    public ResponseEntity<TickerSymbolResponse> updateSymbol(
        @PathVariable Long id,
        @PathVariable Long symbolId,
        @Valid @RequestBody TickerSymbolRequest request
    ) {
        return ResponseEntity.ok(service.updateSymbol(id, symbolId, request));
    }

    @DeleteMapping("/{id}/symbols/{symbolId}")
    public ResponseEntity<Void> deleteSymbol(
        @PathVariable Long id,
        @PathVariable Long symbolId
    ) {
        service.deleteSymbol(id, symbolId);
        return ResponseEntity.noContent().build();
    }
}

package rf.stock.data.db.access.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rf.stock.data.db.access.busines.TickerDtos.*;
import rf.stock.data.db.access.busines.TickerListService;

import java.util.List;

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

    /** raw_symbols + source – für den agent-service direkt verwendbar. */
    @GetMapping("/code/{code}/raw-symbols")
    public ResponseEntity<RawSymbolsResponse> getRawSymbols(@PathVariable String code) {
        return ResponseEntity.ok(service.getRawSymbols(code.toUpperCase()));
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

package rf.stock.data.db.access.busines;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** DTO-Klassen als Java Records – immutable, kein Boilerplate. */
public final class TickerDtos {

    private TickerDtos() {}

    // ── TickerList ────────────────────────────────────────────────────────────

    public record TickerListRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50)  String code,
        @Size(max = 255)           String description
    ) {}

    public record TickerListResponse(
        Long          id,
        String        name,
        String        code,
        String        description,
        int           symbolCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    /** Volle Liste inkl. aller Symbole – für Detail-Endpunkt. */
    public record TickerListDetailResponse(
        Long                       id,
        String                     name,
        String                     code,
        String                     description,
        List<TickerSymbolResponse> symbols,
        LocalDateTime              createdAt,
        LocalDateTime              updatedAt
    ) {}

    // ── TickerSymbol ──────────────────────────────────────────────────────────

    public record TickerSymbolRequest(
        @NotBlank @Size(max = 20) String rawSymbol,
        @NotNull                  Exchange exchange,
        @Size(max = 100)          String displayName
    ) {}

    public record TickerSymbolResponse(
        Long          id,
        String        rawSymbol,
        String        yahooSymbol,
        Exchange      exchange,
        String        displayName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    /** Kompakte Antwort für den agent-service: nur die yahoo_symbols einer Liste. */
    public record YahooSymbolsResponse(
        String       listCode,
        String       listName,
        List<String> yahooSymbols
    ) {}
}

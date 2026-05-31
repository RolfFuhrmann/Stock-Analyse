package rf.stock.data.db.access.busines;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class TickerDtos {

    private TickerDtos() {}

    // ── TickerList ────────────────────────────────────────────────────────────

    public record TickerListRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50)  String code,
        @Size(max = 255)           String description,
        @NotBlank @Pattern(regexp = "yahoo|twelvedata") String source,
        /** RAW | XETRA | CUSTOM */
        @NotBlank @Pattern(regexp = "RAW|XETRA|CUSTOM") String tickerFormat,
        /** Nur bei tickerFormat=CUSTOM, z.B. ".PA" */
        @Size(max = 10) String customSuffix
    ) {}

    public record TickerListResponse(
        Long          id,
        String        name,
        String        code,
        String        description,
        String        source,
        String        tickerFormat,
        String        customSuffix,
        int           symbolCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record TickerListDetailResponse(
        Long                       id,
        String                     name,
        String                     code,
        String                     description,
        String                     source,
        String                     tickerFormat,
        String                     customSuffix,
        List<TickerSymbolResponse> symbols,
        LocalDateTime              createdAt,
        LocalDateTime              updatedAt
    ) {}

    // ── TickerSymbol ──────────────────────────────────────────────────────────

    public record TickerSymbolRequest(
        @NotBlank @Size(max = 20) String rawSymbol,
        @Size(max = 100)          String displayName
    ) {}

    public record TickerSymbolResponse(
        Long          id,
        String        rawSymbol,
        String        displayName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record RawSymbolsResponse(
        String       listCode,
        String       listName,
        String       source,
        String       tickerFormat,
        String       customSuffix,
        List<String> rawSymbols
    ) {}
}

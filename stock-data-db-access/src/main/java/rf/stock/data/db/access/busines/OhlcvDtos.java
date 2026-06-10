package rf.stock.data.db.access.busines;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs für OHLCV-Kursdaten und Ticker-Metadaten.
 * Strikte Trennung zwischen Request (Eingabe) und Response (Ausgabe).
 */
public final class OhlcvDtos {

    private OhlcvDtos() {}

    // ── TickerMeta ────────────────────────────────────────────────────────────

    public record TickerMetaRequest(
        @NotBlank @Size(max = 30) String ticker,
        @NotBlank @Size(max = 20) String rawSymbol,
        @NotBlank @Pattern(regexp = "yahoo|twelvedata") String source,
        @Size(max = 200) String companyName,
        @Size(max = 12)  String isin,
        @Size(max = 100) String sector,
        @Size(max = 50)  String country
    ) {}

    public record TickerMetaResponse(
        Long          id,
        String        ticker,
        String        rawSymbol,
        String        source,
        String        companyName,
        String        isin,
        String        sector,
        String        country,
        LocalDateTime lastRefreshed,
        long          dailyBars,
        long          hourlyBars,
        LocalDate     oldestDaily,
        LocalDate     newestDaily,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    // ── OhlcvDaily ────────────────────────────────────────────────────────────

    public record OhlcvDailyRequest(
        @NotBlank @Size(max = 30) String ticker,
        @NotNull LocalDate tradeDate,
        @NotNull @DecimalMin("0.0") BigDecimal open,
        @NotNull @DecimalMin("0.0") BigDecimal high,
        @NotNull @DecimalMin("0.0") BigDecimal low,
        @NotNull @DecimalMin("0.0") BigDecimal close,
        Long volume,
        @NotBlank @Pattern(regexp = "yahoo|twelvedata") String source
    ) {}

    /** Bulk-Einfügen mehrerer Tageskerzen für einen Ticker in einem Request. */
    public record OhlcvDailyBulkRequest(
        @NotBlank @Size(max = 30) String ticker,
        @NotBlank @Pattern(regexp = "yahoo|twelvedata") String source,
        @NotNull @Size(min = 1, max = 2000) List<@Valid OhlcvDailyBarRequest> bars
    ) {}

    public record OhlcvDailyBarRequest(
        @NotNull LocalDate tradeDate,
        @NotNull @DecimalMin("0.0") BigDecimal open,
        @NotNull @DecimalMin("0.0") BigDecimal high,
        @NotNull @DecimalMin("0.0") BigDecimal low,
        @NotNull @DecimalMin("0.0") BigDecimal close,
        Long volume
    ) {}

    public record OhlcvDailyResponse(
        Long       id,
        String     ticker,
        LocalDate  tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long       volume,
        String     source,
        LocalDateTime fetchedAt
    ) {}

    public record OhlcvDailyBulkResponse(
        String ticker,
        int    inserted,
        int    skipped,
        String message
    ) {}

    // ── OhlcvHourly ───────────────────────────────────────────────────────────

    public record OhlcvHourlyBulkRequest(
        @NotBlank @Size(max = 30) String ticker,
        @NotBlank @Pattern(regexp = "yahoo|twelvedata") String source,
        @NotNull @Size(min = 1, max = 5000) List<@Valid OhlcvHourlyBarRequest> bars
    ) {}

    public record OhlcvHourlyBarRequest(
        @NotNull LocalDateTime tradeTime,
        @NotNull @DecimalMin("0.0") BigDecimal open,
        @NotNull @DecimalMin("0.0") BigDecimal high,
        @NotNull @DecimalMin("0.0") BigDecimal low,
        @NotNull @DecimalMin("0.0") BigDecimal close,
        Long volume
    ) {}

    public record OhlcvHourlyResponse(
        Long          id,
        String        ticker,
        LocalDateTime tradeTime,
        BigDecimal    open,
        BigDecimal    high,
        BigDecimal    low,
        BigDecimal    close,
        Long          volume,
        String        source,
        LocalDateTime fetchedAt
    ) {}

    public record OhlcvHourlyBulkResponse(
        String ticker,
        int    inserted,
        int    skipped,
        String message
    ) {}

    // ── FetchLog ──────────────────────────────────────────────────────────────

    public record FetchLogRequest(
        @NotBlank @Size(max = 30)  String ticker,
        @NotBlank @Pattern(regexp = "daily|hourly") String intervalType,
        @NotBlank @Size(max = 20)  String source,
        @NotBlank @Pattern(regexp = "SUCCESS|ERROR|PARTIAL") String status,
        int barsFetched,
        @Size(max = 500) String errorMsg
    ) {}

    public record FetchLogResponse(
        Long          id,
        String        ticker,
        String        intervalType,
        String        source,
        String        status,
        int           barsFetched,
        String        errorMsg,
        LocalDateTime runAt
    ) {}

    // ── Coverage-Summary ──────────────────────────────────────────────────────

    /** Übersicht über den Datenbestand – für Monitoring im Angular-Client. */
    public record CoverageSummaryResponse(
        int           totalTickers,
        long          totalDailyBars,
        long          totalHourlyBars,
        LocalDate     oldestDailyBar,
        LocalDate     newestDailyBar,
        LocalDateTime oldestHourlyBar,
        LocalDateTime newestHourlyBar,
        List<TickerCoverageResponse> tickers
    ) {}

    public record TickerCoverageResponse(
        String        ticker,
        String        companyName,
        long          dailyBars,
        long          hourlyBars,
        LocalDate     oldestDaily,
        LocalDate     newestDaily,
        String        lastFetchStatus
    ) {}
}

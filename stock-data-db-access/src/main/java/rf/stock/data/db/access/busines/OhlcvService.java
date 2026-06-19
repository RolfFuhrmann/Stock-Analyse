package rf.stock.data.db.access.busines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rf.stock.data.db.access.busines.OhlcvDtos.*;
import rf.stock.data.db.access.exception.DuplicateResourceException;
import rf.stock.data.db.access.exception.ResourceNotFoundException;
import rf.stock.data.db.access.model.*;
import rf.stock.data.db.access.repository.OhlcvFourHourlyRepository;
import rf.stock.data.db.access.repository.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OhlcvService {

    private final TickerMetaRepository   metaRepo;
    private final OhlcvDailyRepository   dailyRepo;
    private final OhlcvHourlyRepository      hourlyRepo;
    private final OhlcvFourHourlyRepository  fourHourlyRepo;
    private final FetchLogRepository          fetchLogRepo;

    // ── TickerMeta ────────────────────────────────────────────────────────────

    public List<TickerMetaResponse> getAllMeta() {
        return metaRepo.findAll().stream().map(this::toMetaResponse).toList();
    }

    public TickerMetaResponse getMetaByTicker(String ticker) {
        return toMetaResponse(findMeta(ticker));
    }

    @Transactional
    public TickerMetaResponse createMeta(TickerMetaRequest req) {
        if (metaRepo.existsByTicker(req.ticker())) {
            throw new DuplicateResourceException(
                "TickerMeta für '" + req.ticker() + "' existiert bereits");
        }
        TickerMeta meta = TickerMeta.builder()
            .ticker(req.ticker())
            .rawSymbol(req.rawSymbol().toUpperCase())
            .source(req.source())
            .companyName(req.companyName())
            .isin(req.isin())
            .sector(req.sector())
            .country(req.country())
            .lastRefreshed(LocalDateTime.now())
            .build();
        return toMetaResponse(metaRepo.save(meta));
    }

    @Transactional
    public TickerMetaResponse upsertMeta(TickerMetaRequest req) {
        TickerMeta meta = metaRepo.findByTicker(req.ticker()).orElseGet(() ->
            TickerMeta.builder()
                .ticker(req.ticker())
                .rawSymbol(req.rawSymbol().toUpperCase())
                .source(req.source())
                .build()
        );
        meta.setRawSymbol(req.rawSymbol().toUpperCase());
        meta.setSource(req.source());
        meta.setCompanyName(req.companyName());
        meta.setIsin(req.isin());
        meta.setSector(req.sector());
        meta.setCountry(req.country());
        meta.setLastRefreshed(LocalDateTime.now());
        return toMetaResponse(metaRepo.save(meta));
    }

    // ── OhlcvDaily ────────────────────────────────────────────────────────────

    /** Alle Tageskerzen für einen Ticker – chronologisch aufsteigend. */
    public List<OhlcvDailyResponse> getDailyBars(String ticker) {
        return dailyRepo.findByTickerOrderByTradeDateAsc(ticker)
            .stream().map(this::toDailyResponse).toList();
    }

    /** Tageskerzen für einen Ticker in einem Datumsbereich. */
    public List<OhlcvDailyResponse> getDailyBars(String ticker, LocalDate from, LocalDate to) {
        return dailyRepo.findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker, from, to)
            .stream().map(this::toDailyResponse).toList();
    }

    /** Die neuesten N Tageskerzen – für den täglichen Analyse-Lauf. */
    public List<OhlcvDailyResponse> getLatestDailyBars(String ticker, int limit) {
        // findLatestByTicker gibt DESC zurück → umkehren für chronologische Reihenfolge
        List<OhlcvDaily> bars = dailyRepo.findLatestByTicker(ticker, limit);
        List<OhlcvDaily> sorted = new ArrayList<>(bars);
        sorted.sort((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));
        return sorted.stream().map(this::toDailyResponse).toList();
    }

    /**
     * Bulk-Einfügen von Tageskerzen.
     * Bereits vorhandene (ticker + trade_date) werden übersprungen (kein Update),
     * da historische Schlusskurse sich nicht ändern.
     * Gibt die Anzahl eingefügter und übersprungener Kerzen zurück.
     */
    @Transactional
    public OhlcvDailyBulkResponse bulkInsertDaily(OhlcvDailyBulkRequest req) {
        int inserted = 0;
        int skipped  = 0;

        for (OhlcvDailyBarRequest bar : req.bars()) {
            if (dailyRepo.existsByTickerAndTradeDate(req.ticker(), bar.tradeDate())) {
                skipped++;
                continue;
            }
            OhlcvDaily entity = OhlcvDaily.builder()
                .ticker(req.ticker())
                .tradeDate(bar.tradeDate())
                .open(bar.open())
                .high(bar.high())
                .low(bar.low())
                .close(bar.close())
                .volume(bar.volume())
                .source(req.source())
                .build();
            dailyRepo.save(entity);
            inserted++;
        }

        log.info("ohlcv_daily [{}]: {} eingefügt, {} übersprungen", req.ticker(), inserted, skipped);
        return new OhlcvDailyBulkResponse(
            req.ticker(), inserted, skipped,
            inserted + " neue Kerzen gespeichert, " + skipped + " bereits vorhanden"
        );
    }

    // ── OhlcvHourly ───────────────────────────────────────────────────────────

    public List<OhlcvHourlyResponse> getHourlyBars(String ticker) {
        return hourlyRepo.findByTickerOrderByTradeTimeAsc(ticker)
            .stream().map(this::toHourlyResponse).toList();
    }

    public List<OhlcvHourlyResponse> getHourlyBars(String ticker, LocalDateTime from, LocalDateTime to) {
        return hourlyRepo.findByTickerAndTradeTimeBetweenOrderByTradeTimeAsc(ticker, from, to)
            .stream().map(this::toHourlyResponse).toList();
    }

    public List<OhlcvHourlyResponse> getLatestHourlyBars(String ticker, int limit) {
        List<OhlcvHourly> bars = hourlyRepo.findLatestByTicker(ticker, limit);
        List<OhlcvHourly> sorted = new ArrayList<>(bars);
        sorted.sort((a, b) -> a.getTradeTime().compareTo(b.getTradeTime()));
        return sorted.stream().map(this::toHourlyResponse).toList();
    }

    @Transactional
    public OhlcvHourlyBulkResponse bulkInsertHourly(OhlcvHourlyBulkRequest req) {
        int inserted = 0;
        int skipped  = 0;

        for (OhlcvHourlyBarRequest bar : req.bars()) {
            if (hourlyRepo.existsByTickerAndTradeTime(req.ticker(), bar.tradeTime())) {
                skipped++;
                continue;
            }
            OhlcvHourly entity = OhlcvHourly.builder()
                .ticker(req.ticker())
                .tradeTime(bar.tradeTime())
                .open(bar.open())
                .high(bar.high())
                .low(bar.low())
                .close(bar.close())
                .volume(bar.volume())
                .source(req.source())
                .build();
            hourlyRepo.save(entity);
            inserted++;
        }

        log.info("ohlcv_hourly [{}]: {} eingefügt, {} übersprungen", req.ticker(), inserted, skipped);
        return new OhlcvHourlyBulkResponse(
            req.ticker(), inserted, skipped,
            inserted + " neue Kerzen gespeichert, " + skipped + " bereits vorhanden"
        );
    }


    // ── OhlcvFourHourly ───────────────────────────────────────────────────────

    public List<OhlcvFourHourlyResponse> getFourHourlyBars(String ticker) {
        return fourHourlyRepo.findByTickerOrderByTradeTimeAsc(ticker)
            .stream().map(this::toFourHourlyResponse).toList();
    }

    public List<OhlcvFourHourlyResponse> getFourHourlyBars(String ticker, LocalDateTime from, LocalDateTime to) {
        return fourHourlyRepo.findByTickerAndTradeTimeBetweenOrderByTradeTimeAsc(ticker, from, to)
            .stream().map(this::toFourHourlyResponse).toList();
    }

    public List<OhlcvFourHourlyResponse> getLatestFourHourlyBars(String ticker, int limit) {
        List<OhlcvFourHourly> bars = fourHourlyRepo.findLatestByTicker(ticker, limit);
        List<OhlcvFourHourly> sorted = new ArrayList<>(bars);
        sorted.sort((a, b) -> a.getTradeTime().compareTo(b.getTradeTime()));
        return sorted.stream().map(this::toFourHourlyResponse).toList();
    }

    @Transactional
    public OhlcvFourHourlyBulkResponse bulkInsertFourHourly(OhlcvFourHourlyBulkRequest req) {
        int inserted = 0;
        int skipped  = 0;

        for (OhlcvFourHourlyBarRequest bar : req.bars()) {
            if (fourHourlyRepo.existsByTickerAndTradeTime(req.ticker(), bar.tradeTime())) {
                skipped++;
                continue;
            }
            OhlcvFourHourly entity = OhlcvFourHourly.builder()
                .ticker(req.ticker())
                .tradeTime(bar.tradeTime())
                .open(bar.open())
                .high(bar.high())
                .low(bar.low())
                .close(bar.close())
                .volume(bar.volume())
                .source(req.source())
                .build();
            fourHourlyRepo.save(entity);
            inserted++;
        }

        log.info("ohlcv_4h [{}]: {} eingefügt, {} übersprungen", req.ticker(), inserted, skipped);
        return new OhlcvFourHourlyBulkResponse(
            req.ticker(), inserted, skipped,
            inserted + " neue Kerzen gespeichert, " + skipped + " bereits vorhanden"
        );
    }

    // ── FetchLog ──────────────────────────────────────────────────────────────

    @Transactional
    public FetchLogResponse logFetch(FetchLogRequest req) {
        FetchLog entry = FetchLog.builder()
            .ticker(req.ticker())
            .intervalType(req.intervalType())
            .source(req.source())
            .status(req.status())
            .barsFetched(req.barsFetched())
            .errorMsg(req.errorMsg())
            .build();
        return toFetchLogResponse(fetchLogRepo.save(entry));
    }

    public List<FetchLogResponse> getLogByTicker(String ticker) {
        return fetchLogRepo.findByTickerOrderByRunAtDesc(ticker)
            .stream().map(this::toFetchLogResponse).toList();
    }

    public List<FetchLogResponse> getRecentErrors(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return fetchLogRepo.findErrorsSince(since)
            .stream().map(this::toFetchLogResponse).toList();
    }

    // ── Coverage Summary ──────────────────────────────────────────────────────

    /** Übersicht über den gesamten Datenbestand – für Monitoring. */
    public CoverageSummaryResponse getCoverage() {
        List<TickerMeta> allMeta = metaRepo.findAll();

        long totalDaily      = dailyRepo.count();
        long totalHourly     = hourlyRepo.count();
        long totalFourHourly = fourHourlyRepo.count();

        List<TickerCoverageResponse> tickerCoverage = allMeta.stream().map(meta -> {
            long daily       = dailyRepo.countByTicker(meta.getTicker());
            long hourly      = hourlyRepo.countByTicker(meta.getTicker());
            long fourHourly  = fourHourlyRepo.countByTicker(meta.getTicker());
            LocalDate oldest = dailyRepo
                .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(
                    meta.getTicker(), LocalDate.of(2000, 1, 1), LocalDate.now())
                .stream().findFirst().map(OhlcvDaily::getTradeDate).orElse(null);
            LocalDate newest = dailyRepo
                .findLatestTradeDateByTicker(meta.getTicker()).orElse(null);

            String lastStatus = fetchLogRepo
                .findLastSuccess(meta.getTicker(), "daily")
                .map(f -> f.getStatus()).orElse("NEVER");

            return new TickerCoverageResponse(
                meta.getTicker(), meta.getCompanyName(),
                daily, hourly, fourHourly, oldest, newest, lastStatus
            );
        }).toList();

        // Gesamt-Extremwerte
        LocalDate oldestD = tickerCoverage.stream()
            .map(TickerCoverageResponse::oldestDaily)
            .filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
        LocalDate newestD = tickerCoverage.stream()
            .map(TickerCoverageResponse::newestDaily)
            .filter(d -> d != null).max(LocalDate::compareTo).orElse(null);

        return new CoverageSummaryResponse(
            allMeta.size(), totalDaily, totalHourly, totalFourHourly,
            oldestD, newestD, null, null,
            tickerCoverage
        );
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private TickerMeta findMeta(String ticker) {
        return metaRepo.findByTicker(ticker)
            .orElseThrow(() -> new ResourceNotFoundException(
                "TickerMeta für '" + ticker + "' nicht gefunden"));
    }

    private TickerMetaResponse toMetaResponse(TickerMeta m) {
        long daily  = dailyRepo.countByTicker(m.getTicker());
        long hourly = hourlyRepo.countByTicker(m.getTicker());
        LocalDate oldest = dailyRepo.findByTickerAndTradeDateBetweenOrderByTradeDateAsc(
            m.getTicker(), LocalDate.of(2000, 1, 1), LocalDate.now())
            .stream().findFirst().map(OhlcvDaily::getTradeDate).orElse(null);
        LocalDate newest = dailyRepo.findLatestTradeDateByTicker(m.getTicker()).orElse(null);

        return new TickerMetaResponse(
            m.getId(), m.getTicker(), m.getRawSymbol(), m.getSource(),
            m.getCompanyName(), m.getIsin(), m.getSector(), m.getCountry(),
            m.getLastRefreshed(), daily, hourly, oldest, newest,
            m.getCreatedAt(), m.getUpdatedAt()
        );
    }

    private OhlcvDailyResponse toDailyResponse(OhlcvDaily o) {
        return new OhlcvDailyResponse(
            o.getId(), o.getTicker(), o.getTradeDate(),
            o.getOpen(), o.getHigh(), o.getLow(), o.getClose(),
            o.getVolume(), o.getSource(), o.getFetchedAt()
        );
    }

    private OhlcvHourlyResponse toHourlyResponse(OhlcvHourly o) {
        return new OhlcvHourlyResponse(
            o.getId(), o.getTicker(), o.getTradeTime(),
            o.getOpen(), o.getHigh(), o.getLow(), o.getClose(),
            o.getVolume(), o.getSource(), o.getFetchedAt()
        );
    }

    private FetchLogResponse toFetchLogResponse(FetchLog f) {
        return new FetchLogResponse(
            f.getId(), f.getTicker(), f.getIntervalType(),
            f.getSource(), f.getStatus(), f.getBarsFetched(),
            f.getErrorMsg(), f.getRunAt()
        );
    }

    private OhlcvFourHourlyResponse toFourHourlyResponse(OhlcvFourHourly o) {
        return new OhlcvFourHourlyResponse(
            o.getId(), o.getTicker(), o.getTradeTime(),
            o.getOpen(), o.getHigh(), o.getLow(), o.getClose(),
            o.getVolume(), o.getSource(), o.getFetchedAt()
        );
    }
}

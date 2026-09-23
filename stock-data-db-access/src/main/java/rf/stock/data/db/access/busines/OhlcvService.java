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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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

    // ── Ticker-Universum (21.09.) ────────────────────────────────────────────

    /**
     * Alle Ticker, für die tatsächlich OHLCV-Daten vorliegen, mit Zeilenzahl je
     * Tabelle - Grundlage für die ML-Trainingsauswahl. Bewusst UNABHÄNGIG von
     * ticker_meta: ticker_meta wird nur vom history-fetcher gepflegt (beim
     * Abruf für die konfigurierten Listen), während Daten für einen Ticker auch
     * allein durch den Live-Write-back des agent-service-java entstehen können
     * (z.B. neu erstellte Listen, die der Fetcher noch nie gesehen hat). Ohne
     * diese Trennung würde das Training niemals über die dem Fetcher bekannten
     * Listen hinauskommen.
     */
    public List<TickerCoverage> getTickerCoverage() {
        Map<String, Integer> daily      = toCountMap(dailyRepo.countRowsByTicker());
        Map<String, Integer> hourly     = toCountMap(hourlyRepo.countRowsByTicker());
        Map<String, Integer> fourHourly = toCountMap(fourHourlyRepo.countRowsByTicker());

        Set<String> tickers = new TreeSet<>();
        tickers.addAll(daily.keySet());
        tickers.addAll(hourly.keySet());
        tickers.addAll(fourHourly.keySet());

        return tickers.stream()
            .map(t -> new TickerCoverage(
                t,
                daily.getOrDefault(t, 0),
                hourly.getOrDefault(t, 0),
                fourHourly.getOrDefault(t, 0)))
            .toList();
    }

    private static Map<String, Integer> toCountMap(List<Object[]> rows) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Long) row[1]).intValue());
        }
        return result;
    }

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
     * Bulk-Einfügen von Tageskerzen (Upsert, seit 09.09.).
     *
     * Vorher: bereits vorhandene (ticker + trade_date) wurden übersprungen,
     * nie aktualisiert - Begründung war "historische Schlusskurse ändern
     * sich nicht". Rolf möchte jetzt zwei zusätzliche Schreibwege auf
     * denselben Endpunkt zulaufen lassen: (1) agent-service-java schreibt
     * bei jeder Live-Analyse die letzten paar Tage zurück (siehe
     * DailyBarWriteBackService dort - der aktuelle, noch laufende
     * Handelstag ändert sich untertägig mehrfach, ein reines "skip wenn
     * vorhanden" würde das nie aktualisieren), (2) history-fetcher soll
     * künftig auch gelegentliche rückwirkende Kurskorrekturen (Splits,
     * Dividenden-Anpassungen) automatisch übernehmen, statt sie zu ignorieren.
     *
     * WICHTIG - Feldname "skipped" bewusst NICHT umbenannt, obwohl er
     * jetzt "aktualisiert" statt "übersprungen" bedeutet: history-fetcher
     * (Python, fetcher.py) liest result["skipped"] aus und benutzt
     * (inserted + skipped) > 0 für den SUCCESS/PARTIAL-Status. Mit
     * unveränderter Feld-Arithmetik (jede Kerze zählt weiterhin entweder als
     * inserted oder als skipped, nur die Aktion dahinter hat sich geändert)
     * bleibt diese Logik korrekt, ohne dass fetcher.py angepasst werden muss.
     */
    @Transactional
    public OhlcvDailyBulkResponse bulkInsertDaily(OhlcvDailyBulkRequest req) {
        int inserted = 0;
        int skipped  = 0; // semantisch jetzt: "bereits vorhanden, aktualisiert"

        for (OhlcvDailyBarRequest bar : req.bars()) {
            Optional<OhlcvDaily> existing = dailyRepo.findByTickerAndTradeDate(req.ticker(), bar.tradeDate());

            if (existing.isPresent()) {
                OhlcvDaily entity = existing.get();
                entity.setOpen(bar.open());
                entity.setHigh(bar.high());
                entity.setLow(bar.low());
                entity.setClose(bar.close());
                entity.setVolume(bar.volume());
                entity.setSource(req.source());
                dailyRepo.save(entity);
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

        log.info("ohlcv_daily [{}]: {} neu eingefügt, {} aktualisiert", req.ticker(), inserted, skipped);
        return new OhlcvDailyBulkResponse(
            req.ticker(), inserted, skipped,
            inserted + " neue Kerzen gespeichert, " + skipped + " aktualisiert"
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

    /**
     * Bulk-Einfügen von Stundenkerzen (Upsert, seit 20.09.).
     *
     * Vorher: bereits vorhandene (ticker + trade_time) wurden übersprungen.
     * Jetzt analog zu bulkInsertDaily: vorhandene Kerzen werden mit den neuen
     * Werten überschrieben, weil agent-service-java bei jeder Live-1h/4h-
     * Analyse die letzten Handelstage zurückschreibt (siehe
     * IntradayBarWriteBackService dort) - die aktuell laufende Stundenkerze
     * ändert sich untertägig mehrfach, ein reines "skip wenn vorhanden"
     * würde sie nie aktualisieren.
     *
     * Feldname "skipped" bleibt aus demselben Grund wie bei bulkInsertDaily
     * unverändert (history-fetcher wertet inserted + skipped aus) und
     * bedeutet jetzt "bereits vorhanden, aktualisiert".
     */
    @Transactional
    public OhlcvHourlyBulkResponse bulkInsertHourly(OhlcvHourlyBulkRequest req) {
        int inserted = 0;
        int skipped  = 0; // semantisch jetzt: "bereits vorhanden, aktualisiert"

        for (OhlcvHourlyBarRequest bar : req.bars()) {
            Optional<OhlcvHourly> existing = hourlyRepo.findByTickerAndTradeTime(req.ticker(), bar.tradeTime());

            if (existing.isPresent()) {
                OhlcvHourly entity = existing.get();
                entity.setOpen(bar.open());
                entity.setHigh(bar.high());
                entity.setLow(bar.low());
                entity.setClose(bar.close());
                entity.setVolume(bar.volume());
                entity.setSource(req.source());
                hourlyRepo.save(entity);
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

        log.info("ohlcv_hourly [{}]: {} neu eingefügt, {} aktualisiert", req.ticker(), inserted, skipped);
        return new OhlcvHourlyBulkResponse(
            req.ticker(), inserted, skipped,
            inserted + " neue Kerzen gespeichert, " + skipped + " aktualisiert"
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

    /**
     * Bulk-Einfügen von 4h-Kerzen (Upsert, seit 20.09.) - Begründung siehe
     * bulkInsertHourly. Vorhandene Kerzen werden überschrieben, damit der
     * zuletzt (noch unvollständig) aggregierte 4h-Block bei der nächsten
     * Analyse mit den endgültigen Werten aktualisiert wird.
     */
    @Transactional
    public OhlcvFourHourlyBulkResponse bulkInsertFourHourly(OhlcvFourHourlyBulkRequest req) {
        int inserted = 0;
        int skipped  = 0; // semantisch jetzt: "bereits vorhanden, aktualisiert"

        for (OhlcvFourHourlyBarRequest bar : req.bars()) {
            Optional<OhlcvFourHourly> existing = fourHourlyRepo.findByTickerAndTradeTime(req.ticker(), bar.tradeTime());

            if (existing.isPresent()) {
                OhlcvFourHourly entity = existing.get();
                entity.setOpen(bar.open());
                entity.setHigh(bar.high());
                entity.setLow(bar.low());
                entity.setClose(bar.close());
                entity.setVolume(bar.volume());
                entity.setSource(req.source());
                fourHourlyRepo.save(entity);
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

        log.info("ohlcv_4h [{}]: {} neu eingefügt, {} aktualisiert", req.ticker(), inserted, skipped);
        return new OhlcvFourHourlyBulkResponse(
            req.ticker(), inserted, skipped,
            inserted + " neue Kerzen gespeichert, " + skipped + " aktualisiert"
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

    /**
     * Übersicht über den gesamten Datenbestand – für Monitoring.
     *
     * Vorher: pro Ticker 5 sequenzielle Queries (count×3, oldest, newest,
     * lastStatus) – bei ~70-100 Tickern mehrere hundert Roundtrips PRO
     * AUFRUF, dazu lud "oldest" für jeden Ticker alle Zeilen ab dem Jahr
     * 2000 komplett als Entities, nur um die erste zu nehmen. Bei kaltem
     * MySQL-Cache (z.B. direkt nach einem Neustart) dauerte das spürbar
     * länger als der 10s-Timeout des aufrufenden history-fetcher.
     *
     * Jetzt: jeweils EIN gruppiertes Aggregat-Query für daily/hourly/
     * fourHourly + EIN Query für die FetchLog-Status, anschließend reines
     * In-Memory-Zusammenbauen pro Ticker über Map-Lookups (O(1) statt
     * weiterer Queries).
     */
    public CoverageSummaryResponse getCoverage() {
        List<TickerMeta> allMeta = metaRepo.findAll();

        long totalDaily      = dailyRepo.count();
        long totalHourly     = hourlyRepo.count();
        long totalFourHourly = fourHourlyRepo.count();

        // ticker -> [count, minDate, maxDate]
        Map<String, Object[]> dailyAgg = dailyRepo.aggregateByTicker().stream()
            .collect(Collectors.toMap(row -> (String) row[0], row -> row));

        // ticker -> count
        Map<String, Long> hourlyAgg = hourlyRepo.countGroupByTicker().stream()
            .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));
        Map<String, Long> fourHourlyAgg = fourHourlyRepo.countGroupByTicker().stream()
            .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));

        // ticker -> letzter SUCCESS-Status für "daily"
        Map<String, String> lastStatusByTicker = fetchLogRepo.findLastSuccessPerTicker("daily").stream()
            .collect(Collectors.toMap(FetchLog::getTicker, FetchLog::getStatus, (a, b) -> b));

        List<TickerCoverageResponse> tickerCoverage = allMeta.stream().map(meta -> {
            Object[] d = dailyAgg.get(meta.getTicker());
            long daily            = d != null ? (Long) d[1] : 0L;
            LocalDate oldest      = d != null ? (LocalDate) d[2] : null;
            LocalDate newest      = d != null ? (LocalDate) d[3] : null;
            long hourly           = hourlyAgg.getOrDefault(meta.getTicker(), 0L);
            long fourHourly       = fourHourlyAgg.getOrDefault(meta.getTicker(), 0L);
            String lastStatus     = lastStatusByTicker.getOrDefault(meta.getTicker(), "NEVER");

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

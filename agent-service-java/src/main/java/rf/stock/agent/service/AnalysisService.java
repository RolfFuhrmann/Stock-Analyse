package rf.stock.agent.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import rf.stock.agent.config.ServiceConfig;
import rf.stock.agent.indicator.BearishIndicator;
import rf.stock.agent.indicator.BullishIndicator;
import rf.stock.agent.model.IndicatorResult;
import rf.stock.agent.model.OhlcvBar;
import rf.stock.agent.model.StockResult;
import rf.stock.agent.model.TickerQuote;

/**
 * Zentrale Analyse-Logik des Agent Service.
 * Portiert aus main.py – Verhalten und Datenformat bewusst identisch gehalten,
 * damit der Angular-Client ohne Anpassung funktioniert.
 *
 * Routing:
 * interval=1d → SSE-Stream von Yahoo/TwelveData (Live-Daten)
 * interval=4h/1h → Kerzen aus DB-Access-Service (historisch, kein SSE)
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private static final Map<String, Integer> LOOKBACK_BY_INTERVAL = Map.of(
            "1d", 90,
            "4h", 180,
            "1h", 200);

    private static final Map<String, Integer> ML_LOOKBACK_BY_INTERVAL = Map.of(
            "1d", 300,
            "4h", 300,
            "1h", 300);

    private final ServiceConfig serviceConfig;
    private final DataServiceClient dataServiceClient;
    private final DbClient dbClient;
    private final MlClient mlClient;

    /** Session-Registry für Stop-Signale, analog zu _stop_events in Python. */
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    public AnalysisService(
            ServiceConfig serviceConfig,
            DataServiceClient dataServiceClient,
            DbClient dbClient,
            MlClient mlClient) {
        this.serviceConfig = serviceConfig;
        this.dataServiceClient = dataServiceClient;
        this.dbClient = dbClient;
        this.mlClient = mlClient;
    }

    public static int defaultLookback(String interval) {
        return LOOKBACK_BY_INTERVAL.getOrDefault(interval, 90);
    }

    /** Registriert eine neue Session und gibt das zugehörige Stop-Flag zurück. */
    public AtomicBoolean registerSession(String sessionId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        stopFlags.put(sessionId, flag);
        return flag;
    }

    /** Setzt das Stop-Signal für eine laufende Session. */
    public boolean stopSession(String sessionId) {
        AtomicBoolean flag = stopFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
            log.info("Stop-Signal gesetzt für session={}", sessionId);
            return true;
        }
        return false;
    }

    /**
     * Haupteinstieg: erstellt den Analyse-Stream für eine Ticker-Liste.
     */
    public Flux<StockResult> analyze(
            List<String> tickers,
            String source,
            String interval,
            int lookback,
            String sessionId,
            boolean includeMl) {
        AtomicBoolean stopFlag = stopFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));

        log.info("Analyse via '{}' [{}] für {} Ticker (session={}, ml={})",
                source, interval, tickers.size(), sessionId, includeMl);

        Flux<StockResult> resultFlux = interval.equals("4h") || interval.equals("1h")
                ? analyzeFromDb(tickers, source, interval, lookback, includeMl, stopFlag)
                : analyzeFromSse(tickers, source, lookback, includeMl, stopFlag);

        return resultFlux.doFinally(signal -> stopFlags.remove(sessionId));
    }

    // ── 4h/1h: Kerzen aus DB ─────────────────────────────────────────────────

    private Flux<StockResult> analyzeFromDb(
            List<String> tickers,
            String source,
            String interval,
            int lookback,
            boolean includeMl,
            AtomicBoolean stopFlag) {
        int n = LOOKBACK_BY_INTERVAL.getOrDefault(interval, 200);

        return Flux.fromIterable(tickers)
                .takeWhile(t -> !stopFlag.get())
                .concatMap(ticker -> dbClient.fetchQuote(ticker, interval, n)
                        .map(quote -> analyseQuote(quote, lookback, source, interval))
                        .flatMap(result -> enrichWithMl(result, interval, includeMl)));
    }

    // ── 1d: SSE-Stream von Yahoo/TwelveData ─────────────────────────────────

    private Flux<StockResult> analyzeFromSse(
            List<String> tickers,
            String source,
            int lookback,
            boolean includeMl,
            AtomicBoolean stopFlag) {
        String serviceUrl = source.equals("yahoo")
                ? serviceConfig.yahooUrl()
                : serviceConfig.twelvedataUrl();
        int outputsize = lookback + 40;

        return dataServiceClient.streamQuotes(serviceUrl, tickers, outputsize)
                .takeWhile(q -> !stopFlag.get())
                .map(quote -> analyseQuote(quote, lookback, source, "1d"))
                .concatMap(result -> enrichWithMl(result, "1d", includeMl));
    }

    // ── ML-Anreicherung ──────────────────────────────────────────────────────

    private Mono<StockResult> enrichWithMl(StockResult result, String interval, boolean includeMl) {
        if (!includeMl || result.error() != null) {
            return Mono.just(result);
        }

        int mlLookback = ML_LOOKBACK_BY_INTERVAL.getOrDefault(interval, 300);

        return mlClient.fetchSignal(result.ticker(), interval, mlLookback)
                .map(ml -> {
                    log.info("  {} [{}]: ML signal={} prob={}%",
                            result.ticker(), interval, ml.signal(), ml.reversalPct());
                    return withMlSignal(result, ml);
                })
                .defaultIfEmpty(result);
    }

    private StockResult withMlSignal(StockResult result, MlClient.MlSignal ml) {
        return StockResult.builder()
                .ticker(result.ticker())
                .name(result.name())
                .interval(result.interval())
                .currentPrice(result.currentPrice())
                .trendPct(result.trendPct())
                .trendDirection(result.trendDirection())
                .macdStochDirection(result.macdStochDirection())
                .elliottWave(result.elliottWave())
                .elliottWaveStage(result.elliottWaveStage())
                .stochastic(result.stochastic())
                .macdHistogram(result.macdHistogram())
                .criteriaMet(result.criteriaMet())
                .source(result.source())
                .candlePattern(result.candlePattern())
                .candleStrength(result.candleStrength())
                .reversalProb(ml.reversalProb())
                .reversalPct(ml.reversalPct())
                .mlSignal(ml.signal())
                .mlConfidence(ml.confidence())
                .mlAvailable(ml.modelAvailable())
                .error(result.error())
                .build();
    }

    // ── Regelbasierte Analyse ────────────────────────────────────────────────

    /**
     * Führt die regelbasierte Analyse durch (Elliott + MACD + Stochastik + Candle).
     * ML-Signal wird separat in enrichWithMl() ergänzt.
     *
     * Semantik der Indikatoren:
     * bull-Indikator erkennt Abwärtswelle + MACD<0 + Stoch<20 → Trend "bearish"
     * bear-Indikator erkennt Aufwärtswelle + MACD>0 + Stoch>80 → Trend "bullish"
     */
    StockResult analyseQuote(TickerQuote quote, int lookback, String source, String interval) {
        String ticker = quote.ticker() != null ? quote.ticker() : "?";
        String name = firstNonBlank(quote.longName(), quote.shortName(), quote.name());

        if (quote.hasError() || !quote.hasBars()) {
            return StockResult.error(ticker, name, interval, source,
                    quote.error() != null ? quote.error() : "Keine Kursdaten");
        }

        List<OhlcvBar> bars = quote.bars();

        if (bars.size() < 30) {
            return StockResult.error(ticker, name, interval, source, "Zu wenig Datenpunkte");
        }

        try {
            IndicatorResult bull = BullishIndicator.evaluate(bars, lookback);
            IndicatorResult bear = BearishIndicator.evaluate(bars, lookback);

            IndicatorResult result;
            String trendDirection;

            if (bear.criteriaMet() > bull.criteriaMet()) {
                result = bear;
                trendDirection = "bullish";
            } else if (bull.criteriaMet() > 0) {
                result = bull;
                trendDirection = "bearish";
            } else {
                result = bull;
                trendDirection = null;
            }

            // Unabhängig vom "gewonnenen" Indikator: reine Rohwert-Richtung von
            // MACD-Histogramm + Stochastik (siehe Javadoc in StockResult).
            // MACD-Histogramm gibt die Richtung vor. Ist die Stochastik neutral
            // (weder überkauft noch überverkauft), gilt einfach das MACD-Vorzeichen.
            // Nur wenn die Stochastik im Extrembereich der GEGENTEILIGEN Richtung
            // widerspricht (Divergenz – sollte praktisch nie vorkommen), bleibt das
            // Feld als Fallback leer.
            boolean macdPositive     = bear.macdOk();  // MACD-H > 0
            boolean macdNegative     = bull.macdOk();  // MACD-H < 0
            boolean stochOverbought  = bear.stochOk(); // Stochastik > 80
            boolean stochOversold    = bull.stochOk(); // Stochastik < 20

            String macdSign = macdPositive ? "bullish" : macdNegative ? "bearish" : null;

            String macdStochDirection;
            if (stochOverbought && macdNegative) {
                macdStochDirection = null; // Divergenz: Stoch überkauft, MACD-H negativ
            } else if (stochOversold && macdPositive) {
                macdStochDirection = null; // Divergenz: Stoch überverkauft, MACD-H positiv
            } else {
                macdStochDirection = macdSign;
            }

            double price = round2(bars.get(bars.size() - 1).close());
            int startIdx = Math.max(0, bars.size() - Math.min(lookback, bars.size()));
            double startPrice = bars.get(startIdx).close();
            double trendPct = round2((price - startPrice) / startPrice * 100);

            log.info("  {}: Elliott={} ({}) Stoch={} MACD={} [{}/3] dir={}",
                    ticker, result.elliottOk(), result.elliottStage(), result.stochOk(), result.macdOk(),
                    result.criteriaMet(), trendDirection);

            return StockResult.builder()
                    .ticker(ticker)
                    .name(name)
                    .interval(interval)
                    .currentPrice(price)
                    .trendPct(trendPct)
                    .trendDirection(trendDirection)
                    .macdStochDirection(macdStochDirection)
                    .elliottWave(result.elliottOk())
                    .elliottWaveStage(result.elliottStage())
                    .stochastic(result.stochOk())
                    .macdHistogram(result.macdOk())
                    .criteriaMet(result.criteriaMet())
                    .source(source)
                    .candlePattern(result.candle().pattern())
                    .candleStrength(result.candle().strength())
                    .mlSignal("none")
                    .mlConfidence("low")
                    .mlAvailable(false)
                    .build();

        } catch (Exception e) {
            log.error("{}: Analyse-Fehler – {}", ticker, e.getMessage(), e);
            return StockResult.error(ticker, name, interval, source, e.getMessage());
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank())
                return v;
        }
        return null;
    }
}

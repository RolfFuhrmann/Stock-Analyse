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
import rf.stock.agent.util.IntradayBarUtil;

/**
 * Zentrale Analyse-Logik des Agent Service.
 * Portiert aus main.py – Verhalten und Datenformat bewusst identisch gehalten,
 * damit der Angular-Client ohne Anpassung funktioniert.
 *
 * Routing (seit 20.09. alle Intervalle live):
 * interval=1d → SSE-Stream von Yahoo/TwelveData (Tageskerzen)
 * interval=4h/1h → SSE-Stream von Yahoo/TwelveData (Intraday-Kerzen, siehe
 * analyzeIntraday: Yahoo liefert nur 1h, 4h wird daraus berechnet;
 * TwelveData liefert 1h und 4h nativ)
 *
 * Write-back: bei jeder Live-Analyse werden die aktuellsten Kerzen
 * zusätzlich asynchron zurück in die DB geschrieben (1d: DailyBarWriteBackService,
 * 1h/4h: IntradayBarWriteBackService, jeweils dieselbe Logik: letzte 5
 * Handelstage überschreiben, fehlende Kerzen ergänzen) - hält aktiv genutzte
 * Ticker aktuell, ohne auf den history-fetcher-Cron warten zu müssen. Der
 * Fetcher bleibt parallel als Vollständigkeits-Sicherheitsnetz aktiv.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private static final Map<String, Integer> LOOKBACK_BY_INTERVAL = Map.of(
            "1d", 90,
            "4h", 180,
            "1h", 200);

    /**
     * Eigener, größerer Lookback nur für die Elliott-Wave-Erkennung (ta4j).
     * Grund: Bei nur 90 Bars (Standard-Lookback für 1d) sieht der
     * ElliottWaveAnalysisRunner den strukturell korrekten Wellenanfang oft
     * nicht mehr - er liegt regelmäßig deutlich außerhalb des Fensters (siehe
     * IFX-Praxisfall, 01./02.08.: ta4jexamples.ElliottWaveMacroCycleDemo fand
     * den korrekten Zyklus-Anker über 200-1000 Kalendertage Historie stabil
     * bei exakt demselben Datum). 230 Tage gewählt (Rolf, 03.08.) - liegt
     * innerhalb des stabilen Bereichs aus dem Praxistest. MACD und Stochastik
     * bleiben bewusst beim kleineren allgemeinen Lookback - die brauchen die
     * zusätzliche Historie nicht und sollen nicht unnötig auf älteren Daten
     * reagieren.
     * Werte für 4h/1h vorerst unverändert zum allgemeinen Lookback belassen -
     * noch nicht an echten Praxisfällen getestet, siehe Roadmap.
     */
    private static final Map<String, Integer> ELLIOTT_LOOKBACK_BY_INTERVAL = Map.of(
            "1d", 230,
            "4h", 180,
            "1h", 200);

    private static final Map<String, Integer> ML_LOOKBACK_BY_INTERVAL = Map.of(
            "1d", 300,
            "4h", 300,
            "1h", 300);

    /**
     * Obergrenze für outputsize bei Yahoo-Stundendaten (yahoo-service rechnet
     * daraus die abzufragende Periode in Tagen). Wert wie im history-fetcher
     * (data_client.fetch_4h_bars): dort wurde beobachtet, dass Yahoo bei zu
     * großem outputsize nicht mehr korrekte Stundendaten liefert (1440 →
     * Tageskerzen statt Stundenkerzen, 82 → korrekt). 600 liefert ca. 95
     * Kalendertage - für die 180 4h-Kerzen des Elliott-Lookbacks reicht das
     * bei Xetra-Werten (3 Blöcke/Tag), bei US-Werten (2 Blöcke/Tag) sind es
     * nur ca. 130 4h-Kerzen. Bei Bedarf hier anheben, nachdem gegen Yahoo
     * geprüft wurde, ab wann die Stundendaten abbrechen.
     */
    private static final int YAHOO_HOURLY_MAX_OUTPUTSIZE = 600;

    /** Zusätzliche Kerzen über den Lookback hinaus (Einschwingphase der Indikatoren). */
    private static final int OUTPUTSIZE_BUFFER = 40;

    private final ServiceConfig serviceConfig;
    private final DataServiceClient dataServiceClient;
    private final MlClient mlClient;
    private final DailyBarWriteBackService writeBackService;
    private final IntradayBarWriteBackService intradayWriteBackService;

    /** Session-Registry für Stop-Signale, analog zu _stop_events in Python. */
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    public AnalysisService(
            ServiceConfig serviceConfig,
            DataServiceClient dataServiceClient,
            MlClient mlClient,
            DailyBarWriteBackService writeBackService,
            IntradayBarWriteBackService intradayWriteBackService) {
        this.serviceConfig = serviceConfig;
        this.dataServiceClient = dataServiceClient;
        this.mlClient = mlClient;
        this.writeBackService = writeBackService;
        this.intradayWriteBackService = intradayWriteBackService;
    }

    public static int defaultLookback(String interval) {
        return LOOKBACK_BY_INTERVAL.getOrDefault(interval, 90);
    }

    public static int defaultElliottLookback(String interval) {
        return ELLIOTT_LOOKBACK_BY_INTERVAL.getOrDefault(interval, defaultLookback(interval));
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
                ? analyzeIntraday(tickers, source, interval, lookback, includeMl, stopFlag)
                : analyzeFromSse(tickers, source, lookback, includeMl, stopFlag);

        return resultFlux.doFinally(signal -> stopFlags.remove(sessionId));
    }

    // ── 4h/1h: Live-Abruf von Yahoo/TwelveData ───────────────────────────────

    /**
     * Intraday-Analyse (seit 20.09. live statt aus der DB).
     *
     * Abruf-Intervall je Quelle: Yahoo kann nur 1h - für die 4h-Ansicht wird
     * aus den 1h-Kerzen aggregiert. TwelveData liefert 1h und 4h nativ.
     * Die Zeitstempel werden vorab auf lokale Börsenzeit ohne Zone gekürzt
     * (IntradayBarUtil) - Yahoo liefert "…T09:00:00+02:00", mit Offset könnte
     * ElliottAnalysisUtil.parseBarDate sie nicht lesen, und es ist dasselbe
     * Format, das der history-fetcher in der DB ablegt.
     *
     * Write-back passiert mit den abgerufenen Kerzen in Abruf-Granularität
     * (bei Yahoo 1h - der Service leitet daraus zusätzlich die 4h-Kerzen für
     * die DB ab), die Analyse bekommt die Kerzen der gewählten Ansicht.
     */
    private Flux<StockResult> analyzeIntraday(
            List<String> tickers,
            String source,
            String interval,
            int lookback,
            boolean includeMl,
            AtomicBoolean stopFlag) {
        boolean yahoo = source.equals("yahoo");
        String fetchInterval = yahoo || interval.equals("1h") ? "1h" : "4h";
        String serviceUrl = yahoo ? serviceConfig.yahooUrl() : serviceConfig.twelvedataUrl();
        int elliottLookback = ELLIOTT_LOOKBACK_BY_INTERVAL.getOrDefault(interval, 200);
        int outputsize = intradayOutputsize(yahoo, interval, lookback, elliottLookback);

        return dataServiceClient.streamQuotes(serviceUrl, tickers, outputsize, fetchInterval)
                .takeWhile(q -> !stopFlag.get())
                .map(AnalysisService::normalizeIntradayQuote)
                .doOnNext(quote -> {
                    if (!quote.hasError() && quote.hasBars()) {
                        intradayWriteBackService.triggerAsync(quote.ticker(), source, fetchInterval, quote.bars());
                    }
                })
                .map(quote -> yahoo && interval.equals("4h") ? aggregateQuoteTo4h(quote) : quote)
                .map(quote -> analyseQuote(quote, lookback, elliottLookback, source, interval))
                .concatMap(result -> enrichWithMl(result, interval, includeMl));
    }

    /**
     * Anzahl abzurufender Kerzen (outputsize) für Intraday-Abrufe.
     * Yahoo/1h-Ansicht und TwelveData zählen in Kerzen der Ansicht; für die
     * Yahoo/4h-Ansicht wird die 4-fache Menge 1h-Kerzen benötigt, gedeckelt
     * auf YAHOO_HOURLY_MAX_OUTPUTSIZE (siehe dort).
     */
    private static int intradayOutputsize(boolean yahoo, String interval, int lookback, int elliottLookback) {
        int candles = Math.max(lookback, elliottLookback) + OUTPUTSIZE_BUFFER;
        if (yahoo && interval.equals("4h")) {
            return Math.min(candles * 4, YAHOO_HOURLY_MAX_OUTPUTSIZE);
        }
        return yahoo ? Math.min(candles, YAHOO_HOURLY_MAX_OUTPUTSIZE) : candles;
    }

    private static TickerQuote normalizeIntradayQuote(TickerQuote quote) {
        if (quote.hasError() || !quote.hasBars()) {
            return quote;
        }
        return withBars(quote, IntradayBarUtil.normalize(quote.bars()));
    }

    private static TickerQuote aggregateQuoteTo4h(TickerQuote quote) {
        if (quote.hasError() || !quote.hasBars()) {
            return quote;
        }
        return withBars(quote, IntradayBarUtil.aggregateTo4h(quote.bars()));
    }

    private static TickerQuote withBars(TickerQuote quote, List<OhlcvBar> bars) {
        return new TickerQuote(quote.ticker(), bars, quote.longName(), quote.shortName(),
                quote.name(), quote.currency(), quote.error());
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
        int elliottLookback = ELLIOTT_LOOKBACK_BY_INTERVAL.getOrDefault("1d", lookback);
        int outputsize = Math.max(lookback, elliottLookback) + 40;

        return dataServiceClient.streamQuotes(serviceUrl, tickers, outputsize)
                .takeWhile(q -> !stopFlag.get())
                .doOnNext(quote -> {
                    if (!quote.hasError() && quote.hasBars()) {
                        writeBackService.triggerAsync(quote.ticker(), source, quote.bars());
                    }
                })
                .map(quote -> analyseQuote(quote, lookback, elliottLookback, source, "1d"))
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
                .currency(result.currency())
                .interval(result.interval())
                .currentPrice(result.currentPrice())
                .trendPct(result.trendPct())
                .trendDirection(result.trendDirection())
                .macdStochDirection(result.macdStochDirection())
                .elliottWave(result.elliottWave())
                .elliottWaveStage(result.elliottWaveStage())
                .elliottChart(result.elliottChart())
                .stochastic(result.stochastic())
                .macdHistogram(result.macdHistogram())
                .criteriaMet(result.criteriaMet())
                .source(result.source())
                .candle(result.candle())
                .reversalProb(ml.reversalProb())
                .reversalPct(ml.reversalPct())
                .mlSignal(ml.signal())
                .mlConfidence(ml.confidence())
                .mlAvailable(ml.modelAvailable())
                .mlExplanation(ml.explanation())
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
    StockResult analyseQuote(TickerQuote quote, int lookback, int elliottLookback, String source, String interval) {
        String ticker = quote.ticker() != null ? quote.ticker() : "?";
        String name = firstNonBlank(quote.longName(), quote.shortName(), quote.name());
        String currency = quote.currency();

        if (quote.hasError() || !quote.hasBars()) {
            return StockResult.error(ticker, name, currency, interval, source,
                    quote.error() != null ? quote.error() : "Keine Kursdaten");
        }

        List<OhlcvBar> bars = quote.bars();

        if (bars.size() < 30) {
            return StockResult.error(ticker, name, currency, interval, source, "Zu wenig Datenpunkte");
        }

        try {
            IndicatorResult bull = BullishIndicator.evaluate(bars, elliottLookback);
            IndicatorResult bear = BearishIndicator.evaluate(bars, elliottLookback);

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
                    .currency(currency)
                    .interval(interval)
                    .currentPrice(price)
                    .trendPct(trendPct)
                    .trendDirection(trendDirection)
                    .macdStochDirection(macdStochDirection)
                    .elliottWave(result.elliottOk())
                    .elliottWaveStage(result.elliottStage())
                    .elliottChart(result.elliottChart())
                    .stochastic(result.stochOk())
                    .macdHistogram(result.macdOk())
                    .criteriaMet(result.criteriaMet())
                    .source(source)
                    .candle(result.candle())
                    .mlSignal("none")
                    .mlConfidence("low")
                    .mlAvailable(false)
                    .build();

        } catch (Exception e) {
            log.error("{}: Analyse-Fehler – {}", ticker, e.getMessage(), e);
            return StockResult.error(ticker, name, currency, interval, source, e.getMessage());
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

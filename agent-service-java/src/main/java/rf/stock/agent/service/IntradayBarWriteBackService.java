package rf.stock.agent.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import rf.stock.agent.model.OhlcvBar;
import rf.stock.agent.util.IntradayBarUtil;
import rf.stock.agent.util.TradingDayUtil;

/**
 * Schreibt bei jeder Live-1h/4h-Analyse (siehe AnalysisService.analyzeIntraday)
 * die abgerufenen Kerzen in die DB zurück (20.09.) - dieselbe Logik wie
 * DailyBarWriteBackService für Tageskerzen:
 *
 * Pro Ticker und Tabelle wird der neueste Zeitstempel in der DB ermittelt und
 * davon 5 Handelstage zurückgerechnet (Sicherheitsmarge für die noch laufende
 * Kerze und nachträgliche Korrekturen). Alles ab diesem Tag bis zur
 * aktuellsten abgerufenen Kerze wird geschrieben - vorhandene Kerzen werden
 * dabei überschrieben (Upsert im DB-Service), fehlende ergänzt. Das füllt
 * Lücken zwischen dem letzten DB-Eintrag und jetzt (falls ein Ticker länger
 * nicht abgerufen wurde) - Lücken, die weiter als 5 Handelstage vor dem
 * letzten DB-Eintrag liegen, bleiben unberührt (dafür ist der history-fetcher
 * da). Ticker ohne Vorwissen in der DB: alles Abgerufene wird geschrieben.
 *
 * Welche Tabellen gefüllt werden, hängt von Quelle und Abruf-Intervall ab:
 * - Yahoo kann nur 1h. Aus den 1h-Kerzen entstehen zwei Schreibvorgänge:
 *   die 1h-Kerzen selbst und die daraus berechneten 4h-Kerzen (Blockgrenzen
 *   wie im history-fetcher, siehe IntradayBarUtil.aggregateTo4h).
 * - TwelveData liefert 4h nativ: ein 1h-Abruf füllt nur ohlcv_hourly, ein
 *   4h-Abruf nur ohlcv_4h. Aus TwelveData-1h wird bewusst KEINE 4h-Kerze
 *   berechnet - die nativen 4h-Blöcke von TwelveData haben andere Grenzen
 *   (z.B. NYSE 09:30/13:30) als die 0/4/8/12/16-Uhr-Blöcke der Aggregation.
 *
 * Fire-and-forget wie beim 1d-Write-back: Fehler werden nur geloggt und
 * verzögern/beeinflussen die Analyse-Antwort nie. Die beiden Schreibvorgänge
 * bei Yahoo sind voneinander unabhängig (scheitert der eine, läuft der
 * andere trotzdem).
 */
@Service
public class IntradayBarWriteBackService {

    private static final Logger log = LoggerFactory.getLogger(IntradayBarWriteBackService.class);

    /** Sicherheitsmarge in Handelstagen vor dem letzten DB-Zeitstempel (wie beim 1d-Write-back). */
    static final int LOOKBACK_TRADING_DAYS = 5;

    private final DbClient dbClient;

    public IntradayBarWriteBackService(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    /**
     * Stößt den Write-back für einen Ticker an. Kehrt sofort zurück.
     *
     * @param source          "yahoo" oder "twelvedata"
     * @param fetchedInterval Granularität der übergebenen Kerzen: "1h" oder "4h"
     * @param bars            normalisierte Kerzen (IntradayBarUtil.normalize), aufsteigend sortiert
     */
    public void triggerAsync(String ticker, String source, String fetchedInterval, List<OhlcvBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }

        List<Mono<Void>> writes = new ArrayList<>();
        if ("1h".equals(fetchedInterval)) {
            writes.add(write(ticker, "1h", bars,
                    () -> dbClient.fetchLatestHourlyTime(ticker),
                    toWrite -> dbClient.writeBackHourlyBars(ticker, source, toWrite)));
            if ("yahoo".equals(source)) {
                List<OhlcvBar> fourHour = IntradayBarUtil.aggregateTo4h(bars);
                writes.add(write(ticker, "4h", fourHour,
                        () -> dbClient.fetchLatestFourHourlyTime(ticker),
                        toWrite -> dbClient.writeBackFourHourlyBars(ticker, source, toWrite)));
            }
        } else if ("4h".equals(fetchedInterval)) {
            writes.add(write(ticker, "4h", bars,
                    () -> dbClient.fetchLatestFourHourlyTime(ticker),
                    toWrite -> dbClient.writeBackFourHourlyBars(ticker, source, toWrite)));
        }

        for (Mono<Void> write : writes) {
            write.subscribe(
                    v -> {
                    },
                    e -> log.warn("Intraday-Write-back fehlgeschlagen für {}: {}", ticker, e.getMessage()));
        }
    }

    private Mono<Void> write(
            String ticker,
            String label,
            List<OhlcvBar> bars,
            Supplier<Mono<LocalDateTime>> latestDbTime,
            Function<List<OhlcvBar>, Mono<Void>> writer) {
        if (bars.isEmpty()) {
            return Mono.empty();
        }
        return latestDbTime.get()
                .map(IntradayBarWriteBackService::cutoffFor)
                // Kein vorhandener Zeitstempel (neuer Ticker) oder Lesefehler:
                // ohne Cutoff schreiben, also alles Abgerufene.
                .defaultIfEmpty(LocalDate.MIN)
                .flatMap(cutoff -> {
                    List<OhlcvBar> toWrite = filterFromCutoff(bars, cutoff);
                    if (toWrite.isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    log.debug("Intraday-Write-back [{}] {}: schreibe {} von {} Kerzen (Cutoff {})",
                            ticker, label, toWrite.size(), bars.size(), cutoff);
                    return writer.apply(toWrite);
                });
    }

    /** Ab diesem Tag (einschließlich) wird geschrieben: 5 Handelstage vor dem neuesten DB-Zeitstempel. */
    static LocalDate cutoffFor(LocalDateTime latestDbTime) {
        return TradingDayUtil.minusTradingDays(latestDbTime.toLocalDate(), LOOKBACK_TRADING_DAYS);
    }

    /** Behält alle Kerzen, deren Tag nicht vor dem Cutoff liegt. Zeitstempel müssen normalisiert sein. */
    static List<OhlcvBar> filterFromCutoff(List<OhlcvBar> bars, LocalDate cutoff) {
        return bars.stream()
                .filter(b -> !LocalDateTime.parse(b.date()).toLocalDate().isBefore(cutoff))
                .toList();
    }
}

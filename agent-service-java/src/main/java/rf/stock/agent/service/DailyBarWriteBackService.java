package rf.stock.agent.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import rf.stock.agent.indicator.ElliottAnalysisUtil;
import rf.stock.agent.model.OhlcvBar;
import rf.stock.agent.util.TradingDayUtil;

/**
 * Schreibt bei jeder Live-1d-Analyse (siehe AnalysisService.analyzeFromSse)
 * die aktuellsten Tageskerzen zurück in die DB (09.09., Rolfs Wunsch: DB
 * soll durch aktive Nutzung automatisch aktuell bleiben, statt sich allein
 * auf den täglichen history-fetcher-Cron zu verlassen).
 *
 * Umfang pro Ticker: letztes in der DB vorhandenes Datum ermitteln, davon
 * 5 Handelstage zurückrechnen (Sicherheitsmarge für evtl. verpasste Tage
 * oder nachträgliche Kurskorrekturen) - alles ab diesem Punkt bis zum
 * aktuellsten live abgerufenen Tag wird geschrieben. Das füllt auch größere
 * Lücken vollständig (nicht nur ein festes 5-Tage-Fenster), falls ein
 * Ticker mehrere Tage lang nicht abgerufen wurde. Ticker ganz neu in der
 * DB (kein Vorwissen) -> es wird alles geschrieben, was gerade live
 * abgerufen wurde.
 *
 * history-fetcher bleibt parallel als Sicherheitsnetz aktiv (Vollständigkeit
 * für Ticker, die niemand live abfragt) - dieser Service ersetzt ihn nicht,
 * sondern ergänzt ihn nur für aktiv genutzte Ticker.
 *
 * Fire-and-forget: läuft komplett asynchron im Hintergrund. Ein
 * fehlschlagender Write-back (z.B. db-service kurz nicht erreichbar) wird
 * nur geloggt und darf NIE die eigentliche Analyse-Antwort an den Client
 * verzögern oder beeinflussen.
 */
@Service
public class DailyBarWriteBackService {

    private static final Logger log = LoggerFactory.getLogger(DailyBarWriteBackService.class);

    /** Sicherheitsmarge in Handelstagen vor dem letzten DB-Datum (Rolfs Vorgabe, 09.09.). */
    private static final int LOOKBACK_TRADING_DAYS = 5;

    private final DbClient dbClient;

    public DailyBarWriteBackService(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    /**
     * Stößt den Write-back für einen Ticker an. Kehrt sofort zurück (der
     * eigentliche Schreibvorgang läuft asynchron im Hintergrund weiter) -
     * die Analyse/Antwort an den Client wartet NICHT darauf.
     */
    public void triggerAsync(String ticker, String source, List<OhlcvBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }

        dbClient.fetchLatestDailyDate(ticker)
                .map(latestDbDate -> TradingDayUtil.minusTradingDays(latestDbDate, LOOKBACK_TRADING_DAYS))
                // Kein vorhandenes Datum (neuer Ticker) oder fetchLatestDailyDate-Fehler:
                // ohne Cutoff schreiben, also alles Abgerufene.
                .defaultIfEmpty(LocalDate.MIN)
                .flatMap(cutoff -> {
                    List<OhlcvBar> toWrite = filterFromCutoff(bars, cutoff);
                    if (toWrite.isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    log.debug("Write-back [{}]: schreibe {} von {} abgerufenen Kerzen (Cutoff {})",
                            ticker, toWrite.size(), bars.size(), cutoff);
                    return dbClient.writeBackDailyBars(ticker, source, toWrite);
                })
                .subscribe(
                        v -> {
                        },
                        e -> log.warn("Write-back fehlgeschlagen für {}: {}", ticker, e.getMessage()));
    }

    private List<OhlcvBar> filterFromCutoff(List<OhlcvBar> bars, LocalDate cutoff) {
        return bars.stream()
                .filter(b -> {
                    LocalDate barDate = ElliottAnalysisUtil.parseBarDate(b.date())
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate();
                    return !barDate.isBefore(cutoff);
                })
                .toList();
    }
}

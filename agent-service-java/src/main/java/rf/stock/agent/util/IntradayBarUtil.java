package rf.stock.agent.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import rf.stock.agent.model.OhlcvBar;

/**
 * Hilfsfunktionen für Intraday-Kerzen (1h/4h), Stand 20.09.
 *
 * Zwei Aufgaben, beide bewusst 1:1 zur bestehenden Python-Logik im
 * history-fetcher (data_client.py: _parse_bars_hourly + _aggregate_1h_to_4h),
 * damit Kerzen, die der agent-service-java in die DB zurückschreibt, exakt
 * dieselben Zeitstempel und Blockgrenzen haben wie die des Fetchers -
 * sonst stünden zwei verschiedene Definitionen in derselben Tabelle und
 * der Upsert würde nie dieselbe Zeile treffen:
 *
 * 1. Zeitstempel normalisieren: nur die ersten 19 Zeichen ("yyyy-MM-ddTHH:mm:ss"),
 *    Zone/Offset wird verworfen. Yahoo liefert z.B. "2026-09-18T09:00:00+02:00",
 *    TwelveData "2026-09-18 09:00:00" - beides wird zur lokalen Börsenzeit ohne
 *    Zone. Das ist zugleich das Format, das ElliottAnalysisUtil.parseBarDate()
 *    verarbeiten kann (mit Offset oder Leerzeichen würde es scheitern).
 *
 * 2. 1h -> 4h aggregieren: jede Stunde wird dem nächstniedrigeren Blockstart
 *    (0/4/8/12/16/20 Uhr) zugeordnet, Blöcke mit weniger als 2 Kerzen werden
 *    verworfen (unvollständige Randblöcke).
 */
public final class IntradayBarUtil {

    /** Länge von "yyyy-MM-ddTHH:mm:ss". */
    private static final int TIMESTAMP_LENGTH = 19;

    /** Blöcke mit weniger 1h-Kerzen gelten als unvollständig (wie im history-fetcher). */
    private static final int MIN_BARS_PER_BLOCK = 2;

    private static final int BLOCK_HOURS = 4;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private IntradayBarUtil() {
    }

    /**
     * Kürzt einen Zeitstempel auf "yyyy-MM-ddTHH:mm:ss" (lokale Börsenzeit, ohne
     * Zone). Ein Leerzeichen als Datum/Zeit-Trenner (TwelveData) wird zu "T".
     * Kürzere Werte (reines Datum) bleiben unverändert.
     */
    public static String normalizeTimestamp(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length() < TIMESTAMP_LENGTH) {
            return raw;
        }
        String cut = raw.substring(0, TIMESTAMP_LENGTH);
        return cut.charAt(10) == ' ' ? cut.substring(0, 10) + 'T' + cut.substring(11) : cut;
    }

    /** Normalisiert alle Zeitstempel und sortiert aufsteigend nach Zeit. */
    public static List<OhlcvBar> normalize(List<OhlcvBar> bars) {
        return bars.stream()
                .map(b -> new OhlcvBar(normalizeTimestamp(b.date()),
                        b.open(), b.high(), b.low(), b.close(), b.volume()))
                .sorted(Comparator.comparing(OhlcvBar::date))
                .toList();
    }

    /**
     * Aggregiert 1h-Kerzen zu 4h-Kerzen. Erwartet bereits normalisierte
     * Zeitstempel (siehe {@link #normalize}); die Reihenfolge der Eingabe ist
     * egal. Open = erste, Close = letzte, High/Low = Extremwerte, Volume =
     * Summe der vorhandenen Werte (null, wenn keine Kerze ein Volumen hat).
     * Der Zeitstempel der Ergebnis-Kerze ist der Blockstart.
     */
    public static List<OhlcvBar> aggregateTo4h(List<OhlcvBar> bars1h) {
        TreeMap<LocalDateTime, List<OhlcvBar>> blocks = new TreeMap<>();

        for (OhlcvBar bar : bars1h) {
            LocalDateTime time = LocalDateTime.parse(bar.date());
            int blockHour = (time.getHour() / BLOCK_HOURS) * BLOCK_HOURS;
            LocalDateTime blockStart = time.toLocalDate().atTime(blockHour, 0);
            blocks.computeIfAbsent(blockStart, k -> new ArrayList<>()).add(bar);
        }

        List<OhlcvBar> result = new ArrayList<>();
        blocks.forEach((blockStart, group) -> {
            if (group.size() < MIN_BARS_PER_BLOCK) {
                return;
            }
            group.sort(Comparator.comparing(OhlcvBar::date));

            double high = group.stream().mapToDouble(OhlcvBar::high).max().orElseThrow();
            double low = group.stream().mapToDouble(OhlcvBar::low).min().orElseThrow();
            boolean hasVolume = group.stream().anyMatch(b -> b.volume() != null);
            Double volume = hasVolume
                    ? group.stream().filter(b -> b.volume() != null).mapToDouble(OhlcvBar::volume).sum()
                    : null;

            result.add(new OhlcvBar(
                    TIMESTAMP_FORMAT.format(blockStart),
                    group.getFirst().open(),
                    high,
                    low,
                    group.getLast().close(),
                    volume));
        });
        return result;
    }
}

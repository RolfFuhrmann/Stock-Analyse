package rf.stock.agent.model;

import java.util.List;

/**
 * Daten für die Elliott-Wave-Chart-Visualisierung im Frontend ("Option C",
 * siehe CLAUDE.md Abschnitt 3.2c). Wird befüllt, sobald ta4j irgendein
 * Szenario gefunden hat - unabhängig davon, ob elliott_wave=true ist, damit
 * auch Zwischenstände im Chart sichtbar sind. null, falls ta4j gar kein
 * Szenario liefert (z.B. zu wenig Datenpunkte).
 *
 * bars: die für die Elliott-Analyse tatsächlich verwendeten Kerzen
 * (elliottLookback-Fenster, nicht der allgemeine, meist kleinere lookback),
 * damit das Frontend den Chart unabhängig davon rendern kann.
 * swings: die vom gewählten Szenario erkannten Wellen-Beine (A/B/C bzw. 1-5).
 * target: Kursziel der aktuell laufenden Welle, null falls ta4j keins liefert.
 */
public record ElliottChartData(
    List<OhlcvBar> bars,
    List<ElliottSwingPoint> swings,
    ElliottTarget target
) {}

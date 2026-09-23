package rf.stock.agent.indicator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.elliott.ElliottAnalysisResult;
import org.ta4j.core.indicators.elliott.ElliottDegree;
import org.ta4j.core.indicators.elliott.ElliottLogicProfile;
import org.ta4j.core.indicators.elliott.ElliottPhase;
import org.ta4j.core.indicators.elliott.ElliottScenario;
import org.ta4j.core.indicators.elliott.ElliottScenarioSet;
import org.ta4j.core.indicators.elliott.ElliottSwing;
import org.ta4j.core.indicators.elliott.ElliottWaveAnalysisResult;
import org.ta4j.core.indicators.elliott.ElliottWaveAnalysisRunner;
import org.ta4j.core.num.Num;

import rf.stock.agent.model.ElliottChartData;
import rf.stock.agent.model.ElliottSwingPoint;
import rf.stock.agent.model.ElliottTarget;
import rf.stock.agent.model.OhlcvBar;

/**
 * Gemeinsame ta4j-Elliott-Wave-Hilfsmethoden für BullishIndicator und
 * BearishIndicator. Ausgelagert aus BearishIndicator (01.08.) - vorher per
 * direktem Querverweis (BearishIndicator.xyz) aus BullishIndicator heraus
 * genutzt, obwohl beide Klassen exakt dieselbe Kalibrierung (Degree-Wahl,
 * Runner-Konfiguration, Szenario-Auswahl, Stage-/Swing-Beschreibung)
 * benötigen - unabhängig davon, ob nach einem Impuls- oder Korrekturmuster
 * gesucht wird.
 */
public final class ElliottAnalysisUtil {

    private ElliottAnalysisUtil() {
    }

    static final double MIN_CONFIDENCE = 0.6;

    /**
     * Cross-Degree-Validierung: der Runner analysiert zusätzlich eine Stufe
     * höher/tiefer als den gewählten Degree und gleicht die Szenarien ab.
     * Konfiguration übernommen aus dem Praxistest mit dem ta4j-eigenen
     * ElliottWavePresetDemo (live-Modus), das mit dem HIERARCHICAL_SWING-Profil
     * den DIS-Strukturanker exakt auf den 27.03. gesetzt hat - unabhängig
     * bestätigt durch manuelle Wellenzählung. Siehe Roadmap in CLAUDE.md.
     *
     * Voraussetzung: ta4j-core >=0.22.7 (ElliottLogicProfile existiert erst ab
     * dieser Version - pom.xml wurde am 31.07. entsprechend angehoben).
     */
    private static final int HIGHER_DEGREES = 1;
    private static final int LOWER_DEGREES = 1;
    private static final double RUNNER_MIN_CONFIDENCE = 0.15;
    private static final int RUNNER_MAX_SCENARIOS = 5;

    record ElliottCheckResult(boolean genuine, String stage, ElliottChartData chart) {
        static final ElliottCheckResult NONE = new ElliottCheckResult(false, "", null);
    }

    /**
     * Wählt den zur tatsächlichen Bar-Anzahl passenden Elliott-Degree über
     * ta4js eigene Heuristik statt eines fest verdrahteten Werts. Grund:
     * INTERMEDIATE empfiehlt laut ta4j-Doku 180-400 Tagesbars Historie; unser
     * lookback (Default 90, im DIS-Fall real ~65 Bars) liegt deutlich darunter
     * -> ta4j würde ohnehin zu MINOR (60-180 Bars) raten.
     */
    static ElliottDegree selectDegree(int barCount) {
        List<ElliottDegree> recommended = ElliottDegree.getRecommendedDegrees(Duration.ofDays(1), barCount);
        return recommended.isEmpty() ? ElliottDegree.MINOR : recommended.get(0);
    }

    /**
     * Führt die Elliott-Wave-Analyse über den {@link ElliottWaveAnalysisRunner}
     * aus. Zentral für Bullish- und Bearish-Check, damit beide Richtungen
     * dieselbe Kalibrierung verwenden.
     */
    static Optional<ElliottAnalysisResult> analyze(BarSeries series, ElliottDegree degree) {
        ElliottWaveAnalysisRunner runner = ElliottWaveAnalysisRunner.builder()
                .degree(degree)
                .logicProfile(ElliottLogicProfile.HIERARCHICAL_SWING)
                .higherDegrees(HIGHER_DEGREES)
                .lowerDegrees(LOWER_DEGREES)
                .minConfidence(RUNNER_MIN_CONFIDENCE)
                .maxScenarios(RUNNER_MAX_SCENARIOS)
                .build();
        ElliottWaveAnalysisResult result = runner.analyze(series);
        return result.analysisFor(degree).map(ElliottWaveAnalysisResult.DegreeAnalysis::analysis);
    }

    static Optional<ElliottScenario> selectScenario(ElliottScenarioSet scenarioSet,
            Predicate<ElliottScenario> typeMatches) {
        Optional<ElliottScenario> base = scenarioSet.base();
        if (base.filter(typeMatches).isPresent()) {
            return base;
        }
        return scenarioSet.alternatives().stream()
                .filter(typeMatches)
                .max(Comparator.comparingDouble(scenario -> scenario.confidence().asPercentage()));
    }

    /**
     * Kompakte Darstellung der bereits VOLLENDETEN Wellen der aktuell
     * laufenden Welle vorangestellt, z.B. "A-B-" wenn C im Entstehen ist,
     * oder "1-2-" wenn Welle 3 im Entstehen ist. Die im-Entstehen-Welle
     * selbst taucht bewusst nicht auf - das Label zeigt nur, was bereits
     * abgeschlossen ist. "1-2-..." impliziert Impuls, "A-B-..." impliziert
     * Korrektur, daher keine zusätzliche Typ-Kennzeichnung nötig (Rolf-
     * Vorgabe, 01.08.). Leerstring, wenn noch keine Welle abgeschlossen ist
     * (WAVE1/CORRECTIVE_A) oder ta4j keine Phase liefert.
     * Seit 03.08. zusätzlich mit Kursziel für die Vollendung der laufenden
     * Welle, sofern vorhanden, z.B. "A-B- -> 50% 75,00" - siehe
     * {@link #describeTarget(ElliottScenario)}.
     */
    static String describeStage(ElliottScenario scenario) {
        ElliottPhase phase = scenario.currentPhase();
        if (phase == null) {
            return "";
        }
        String completed = switch (phase) {
            case WAVE1, CORRECTIVE_A -> "";
            case WAVE2 -> "1-";
            case WAVE3 -> "1-2-";
            case WAVE4 -> "1-2-3-";
            case WAVE5 -> "1-2-3-4-";
            case CORRECTIVE_B -> "A-";
            case CORRECTIVE_C -> "A-B-";
            default -> "";
        };
        if (completed.isEmpty()) {
            return completed;
        }
        return describeTarget(scenario).map(target -> completed + " -> " + target).orElse(completed);
    }

    /**
     * Kursziel für die Vollendung der aktuell laufenden Welle, z.B.
     * "50% 75,00" oder nur "75,00", falls sich kein sinnvolles Retracement
     * berechnen lässt.
     * <p>
     * Der Preis (primaryTarget()) kommt direkt von ta4j. <b>Die
     * Prozentzahl NICHT</b> - ta4j liefert dafür keine passende Ratio
     * (weder ElliottScenario noch ElliottProjectionIndicator geben eine
     * Fibonacci-Stufe zu einem Zielpreis zurück, nur nackte Preise;
     * ElliottRatioIndicator berechnet nur die Ratio des AKTUELLEN Kurses
     * zum letzten Swing, nicht die eines projizierten Ziels). Wir
     * berechnen die Prozentzahl daher selbst: Bewegung vom Start der
     * aktuell laufenden Welle bis zum Zielpreis, im Verhältnis zur
     * Amplitude der unmittelbar davor abgeschlossenen Welle. Einfache,
     * wellenunabhängige Näherung - klassische Elliott-Praxis misst je
     * nach Wellennummer teils gegen eine andere Referenzwelle (z.B. C
     * typischerweise gegen A, nicht gegen B). Bei Bedarf nachschärfen,
     * falls sich die Werte in der Praxis nicht mit sauberen Fibonacci-
     * Stufen (23,6/38,2/50/61,8/100/161,8%) decken (Rolf, 03.08.).
     */
    static Optional<String> describeTarget(ElliottScenario scenario) {
        Num target = scenario.primaryTarget();
        if (target == null) {
            return Optional.empty();
        }
        String priceStr = String.valueOf(BullishIndicator.round(target.doubleValue()));

        List<ElliottSwing> swings = scenario.swings();
        if (swings.size() < 2) {
            return Optional.of(priceStr);
        }
        ElliottSwing current = swings.get(swings.size() - 1);
        ElliottSwing reference = swings.get(swings.size() - 2);
        double referenceAmplitude = Math.abs(reference.toPrice().doubleValue() - reference.fromPrice().doubleValue());
        if (referenceAmplitude == 0) {
            return Optional.of(priceStr);
        }
        double movement = Math.abs(target.doubleValue() - current.fromPrice().doubleValue());
        long retracementPct = Math.round(movement / referenceAmplitude * 100.0);
        return Optional.of(retracementPct + "% " + priceStr);
    }

    static String describeSwings(List<OhlcvBar> data, ElliottScenario scenario) {
        List<ElliottSwing> swings = scenario.swings();
        if (swings.isEmpty()) {
            return "";
        }
        boolean impulse = scenario.type().isImpulse();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < swings.size(); i++) {
            ElliottSwing swing = swings.get(i);
            String label = impulse ? String.valueOf(i + 1) : String.valueOf((char) ('A' + i));
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(label).append(": ");
            if (swing.fromIndex() >= 0 && swing.fromIndex() < data.size()) {
                sb.append(data.get(swing.fromIndex()).date()).append(" (")
                        .append(BullishIndicator.round(swing.fromPrice().doubleValue())).append(")");
            } else {
                sb.append("?");
            }
            sb.append(" -> ");
            if (swing.toIndex() >= 0 && swing.toIndex() < data.size()) {
                sb.append(data.get(swing.toIndex()).date()).append(" (")
                        .append(BullishIndicator.round(swing.toPrice().doubleValue())).append(")");
            } else {
                sb.append("?");
            }
        }
        return sb.toString();
    }

    /**
     * Strukturiertes Gegenstück zu {@link #describeSwings(List, ElliottScenario)}
     * für die Chart-Visualisierung im Frontend ("Option C"). Enthält dieselben
     * Wellen-Beine, aber als Objekte statt als Log-String, zusätzlich die
     * für die Analyse verwendeten Bars und das Kursziel. null, falls das
     * Szenario keine Wellen-Beine liefert (sollte praktisch nicht vorkommen,
     * da selectScenario() bereits ein Szenario mit erkannten Wellen liefert).
     */
    static ElliottChartData buildChartData(List<OhlcvBar> data, ElliottScenario scenario) {
        List<ElliottSwing> swings = scenario.swings();
        if (swings.isEmpty()) {
            return null;
        }
        boolean impulse = scenario.type().isImpulse();
        List<ElliottSwingPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < swings.size(); i++) {
            ElliottSwing swing = swings.get(i);
            if (swing.fromIndex() < 0 || swing.fromIndex() >= data.size()
                    || swing.toIndex() < 0 || swing.toIndex() >= data.size()) {
                continue; // Wellen-Bein außerhalb des Bar-Fensters - im Chart nicht darstellbar
            }
            String label = impulse ? String.valueOf(i + 1) : String.valueOf((char) ('A' + i));
            points.add(new ElliottSwingPoint(
                    label,
                    data.get(swing.fromIndex()).date(),
                    BullishIndicator.round(swing.fromPrice().doubleValue()),
                    data.get(swing.toIndex()).date(),
                    BullishIndicator.round(swing.toPrice().doubleValue())));
        }
        return new ElliottChartData(data, points, buildTarget(scenario));
    }

    /** Strukturiertes Gegenstück zu {@link #describeTarget(ElliottScenario)}. */
    private static ElliottTarget buildTarget(ElliottScenario scenario) {
        Num target = scenario.primaryTarget();
        if (target == null) {
            return null;
        }
        double price = BullishIndicator.round(target.doubleValue());

        List<ElliottSwing> swings = scenario.swings();
        if (swings.size() < 2) {
            return new ElliottTarget(price, null);
        }
        ElliottSwing current = swings.get(swings.size() - 1);
        ElliottSwing reference = swings.get(swings.size() - 2);
        double referenceAmplitude = Math.abs(reference.toPrice().doubleValue() - reference.fromPrice().doubleValue());
        if (referenceAmplitude == 0) {
            return new ElliottTarget(price, null);
        }
        double movement = Math.abs(target.doubleValue() - current.fromPrice().doubleValue());
        long retracementPct = Math.round(movement / referenceAmplitude * 100.0);
        return new ElliottTarget(price, retracementPct);
    }

    /** public, damit candle.Ta4jBullishCandlePatterns dieselbe BarSeries-Konstruktion nutzt statt sie zu duplizieren. */
    public static BarSeries toBarSeries(List<OhlcvBar> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("agent-service-analysis").build();
        Instant previousEndTime = null;
        for (OhlcvBar bar : bars) {
            Instant endTime = parseBarDate(bar.date());
            Duration period = previousEndTime == null
                    ? Duration.ofDays(1)
                    : Duration.between(previousEndTime, endTime);
            if (period.isZero() || period.isNegative()) {
                period = Duration.ofDays(1);
            }
            series.barBuilder()
                    .timePeriod(period)
                    .endTime(endTime)
                    .openPrice(bar.open())
                    .highPrice(bar.high())
                    .lowPrice(bar.low())
                    .closePrice(bar.close())
                    .volume(bar.volume() == null ? 0d : bar.volume())
                    .add();
            previousEndTime = endTime;
        }
        return series;
    }

    static Instant parseBarDate(String date) {
        try {
            return Instant.parse(date);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(date).atZone(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e2) {
                return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        }
    }
}

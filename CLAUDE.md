# CLAUDE.md – Stock Platform Projektdokumentation

> Diese Datei am Anfang jeder Claude-Session hochladen.
> Claude hat kein Gedächtnis zwischen Sessions – diese Datei ist der vollständige Kontext.

---

## 1. Projektübersicht

**Name:** Stock Platform
**Zweck:** Analyse von Aktien anhand technischer Kriterien (Elliott Wave, Stochastik, MACD-Histogramm) kombiniert mit einem XGBoost-basierten ML-Modell zur Erkennung von Kursumkehrpunkten.
Nutzer wählen Ticker, Datenquelle und Zeitraum – der Agent analysiert und streamt die Ergebnisse in Echtzeit an den Angular-Client.

**Status:** In Entwicklung

---

## 2. Architektur

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Docker Network (stock-net)                    │
│                                                                      │
│  ┌──────────────────┐   SSE-Stream (result/done)                     │
│  │  Angular Client  │◄──────────────────────────────────────┐        │
│  │  (Port 4200/80)  │                                       │        │
│  └──────────────────┘                                       │        │
│                                                             │        │
│  ┌──────────────────┐   POST /analyze/stream                │        │
│  │  Agent Service   │───────────────────────────────────────┘        │
│  │  (Port 8010)     │                                                │
│  └──────┬───┬───┬───┘                                               │
│         │   │   └──────────────────────────────┐                    │
│   yahoo │   │ twelvedata                  ml   │                    │
│         ▼   ▼                                  ▼                    │
│  ┌──────────────┐  ┌──────────────────┐  ┌────────────────┐         │
│  │ VPN Gateway  │  │TwelveData Service│  │   ML Service   │         │
│  │ (Port 8011)  │  │  (Port 8012)     │  │  (Port 8015)   │         │
│  └──────┬───────┘  └──────────────────┘  └───────┬────────┘         │
│         │ network_mode: container:vpn             │                  │
│         ▼                                        │                  │
│  ┌──────────────┐      ┌───────────────────┐     │                  │
│  │ Yahoo Service│      │  DB Access Service│◄────┘                  │
│  │ (im VPN-Netz)│      │  (Port 8013)      │                        │
│  └──────────────┘      └────────┬──────────┘                        │
│                                 │                                    │
│                        ┌────────▼──────────┐                        │
│                        │  History Fetcher  │                        │
│                        │  (Port 8014)      │                        │
│                        └────────┬──────────┘                        │
│                                 │                                    │
│                        ┌────────▼──────────┐                        │
│                        │  MySQL Datenbank  │                        │
│                        │  (Port 3306)      │                        │
│                        └───────────────────┘                        │
└─────────────────────────────────────────────────────────────────────┘
```

**VPN-Routing:**
- `yahoo-service` hat `network_mode: "container:vpn"` – teilt Netzwerk-Namespace mit Gluetun
- Alle Yahoo-Anfragen gehen durch den WireGuard-Tunnel (Proton VPN, Standard: Niederlande)
- Der VPN-Container erhält den Alias `yahoo-service` im `stock-net` und leitet Port 8011 weiter
- `agent-service` spricht Yahoo über `http://yahoo-service:8011` (= VPN-Gateway)

**Kommunikation:**
- Client → Agent: `POST /analyze/stream` (JSON Body)
- Agent → Client: Server-Sent Events (SSE), Event-Typen: `result`, `done`
- Agent → ML: `POST /predict/{ticker}` (5s Timeout, non-blocking)
- History-Fetcher → DB-Service: REST `/api/ohlcv/...`
- ML-Service → DB-Service: REST `/api/ohlcv/daily/{ticker}/latest`

---

## 3. Services

### 3.1 Angular Client (`angular-client/`)

| Eigenschaft   | Wert                                        |
| ------------- | ------------------------------------------- |
| Framework     | Angular 21 (Standalone Components, Signals) |
| UI            | Angular Material 21                         |
| CSS           | Tailwind CSS 3                              |
| Build         | ng build → nginx:alpine                     |
| Port (Dev)    | 4200                                        |
| Port (Docker) | 80                                          |

**Komponentenstruktur:**

```
src/app/
├── components/
│   ├── filter-header/      # Sticky Header: Datenquelle, Ticker, Lookback, Buttons
│   ├── kpi-bar/            # KPI-Kacheln (Gesamt, 3/3, 2/3)
│   ├── results-table/      # Scrollbare Ergebnistabelle, Header eingefroren
│   ├── criteria-filter/    # Score-Filter (0–3 Kriterien)
│   ├── ticker-list-panel/  # Ticker-Listen aus DB anzeigen
│   └── ticker-list-editor/ # Listen anlegen/bearbeiten
├── models/
│   └── stock.models.ts     # StockResult (inkl. ML-Felder), FilterState, AnalysisSummary
├── services/
│   ├── analysis.service.ts   # SSE-Streaming
│   ├── ticker-list.service.ts # REST-Calls zum DB-Service
│   └── pdf-export.service.ts # PDF via Browser-Print (inkl. KI-Spalte)
└── app.component.ts          # Root: State (Signals) + Koordination
```

**StockResult (aktuelles Datenmodell):**

```typescript
export interface StockResult {
  ticker: string;
  name: string | null;
  current_price: number | null;
  trend_pct: number | null;
  trend_direction: 'bullish' | 'bearish' | null;
  elliott_wave: boolean;
  stochastic: boolean;
  macd_histogram: boolean;
  criteria_met: number;         // 0–3
  source: string;
  candle_pattern: string | null;
  candle_strength: number;
  // ML-Felder
  reversal_prob:  number | null; // 0.0–1.0
  reversal_pct:   number | null; // 0–100
  ml_signal:      'none' | 'weak' | 'moderate' | 'strong';
  ml_confidence:  'low' | 'medium' | 'high';
  ml_available:   boolean;
  error: string | null;
}
```

**Elliott-Wave-Spalte in `results-table_component.ts` (02.08.):** Die frühere
Farbunterscheidung (`elliott-confirmed` kräftig blau bei `elliott_wave=true`
vs. `elliott-progress` gedeckt grau bei reinem Zwischenstand) wurde entfernt -
Rolfs Wunsch nach einheitlichem Text (`#1a1f2e`, passend zur globalen
Textfarbe aus `styles.scss`). Grund: seit die Backend-Stage-Notation nur noch
vollendete Wellen kompakt zeigt (`"A-B-"`), fühlte sich die Farbunterscheidung
redundant an. `elliottWaveBadgeClass()` dadurch auf parameterlos vereinfacht.
Der `matTooltip` (bestätigt vs. Zwischenstand) blieb unverändert erhalten.

**Geplant, noch nicht begonnen ("Option C", siehe 3.2c):** `StockResult` soll
um ein `elliottChart`-Feld erweitert werden (Bars + Swing-Punkte + Zielpreis
des gewählten Szenarios), damit das Frontend selbst einen Wellen-Chart
rendern kann (Thumbnail in neuer Spalte, Klick → Modal). Kandidat für die
Chart-Bibliothek: `lightweight-charts` (TradingView).

### 3.2 Agent Service (`agent-service/`)

| Eigenschaft  | Wert                           |
| ------------ | ------------------------------ |
| Sprache      | Python 3.12                    |
| Framework    | FastAPI + sse-starlette        |
| Port         | 8010                           |
| Version      | 4.0.0                          |

**Endpunkte:**

| Method | Path              | Beschreibung                        |
| ------ | ----------------- | ----------------------------------- |
| GET    | `/health`         | Liveness-Check inkl. Service-URLs   |
| POST   | `/analyze/stream` | Startet SSE-Analyse-Stream          |
| POST   | `/analyze/stop`   | Bricht laufenden Stream ab          |

**Request Body (`POST /analyze/stream`):**

```json
{
  "tickers": ["ADS.DE", "AAPL"],
  "source": "yahoo",
  "lookback_days": 90,
  "session_id": "optional-uuid",
  "include_ml": true
}
```

**SSE Response Format:**

```
event: result
data: {"ticker":"ADS.DE","name":"adidas AG","current_price":164.55,"trend_pct":16.82,
       "trend_direction":"bullish","elliott_wave":true,"stochastic":false,
       "macd_histogram":true,"criteria_met":2,"source":"yahoo",
       "candle_pattern":null,"candle_strength":0,
       "reversal_prob":0.2794,"reversal_pct":27.9,
       "ml_signal":"none","ml_confidence":"low","ml_available":true,"error":null}

event: done
data: {"message": "Analyse abgeschlossen"}
```

### 3.2b Agent Service Java (`agent-service-java/`)

| Eigenschaft  | Wert                                        |
| ------------ | -------------------------------------------- |
| Sprache      | Java 25                                     |
| Framework    | Spring Boot 4.1 + WebFlux (reaktiv)         |
| Port         | 8016                                        |
| Container    | `stock_agent_java`                          |
| Status       | **Produktiv über docker-compose** (Stand 07/2026) |

Java/Spring-Boot-Port des Python `agent-service`. API-Vertrag (Request/Response-JSON)
ist identisch zum Python-Service. **TODO (Claude):** Klären und hier dokumentieren,
ob der Python-`agent-service` inzwischen abgeschaltet/entfernt wurde oder weiterhin
parallel läuft – Stand dieser Doku ist unklar, da das im Rahmen der Session nicht
final geklärt wurde.

**Elliott-Wave-Erkennung läuft seit 07/2026 über [ta4j](https://github.com/ta4j/ta4j)**,
nicht mehr über eine handgestrickte Implementierung. Läuft seit 31.07. über
`ElliottWaveAnalysisRunner` (0.22.4+) statt der nackten `ElliottWaveFacade`.
**`ta4j-core` ist seit 01.08. auf `0.22.7` angehoben und produktiv verifiziert**
(mehrere erfolgreiche DAX-/Dow-Jones-Komplettläufe seit 01.–03.08., inkl.
`ElliottLogicProfile.HIERARCHICAL_SWING`) – der frühere offene Punkt "Build nach
Upgrade nicht verifiziert" ist damit erledigt.

**Zentrale Hilfsklasse: `ElliottAnalysisSupport` → `ElliottAnalysisUtil`
(01.08., auf Rolfs Wunsch umbenannt).** Vorher direkte Querverweise
(`BearishIndicator.xyz` aus `BullishIndicator` heraus) - jetzt eigene,
package-private Klasse in `rf.stock.agent.indicator`, die beide Indicator-
Klassen nutzen: `selectDegree`, `analyze`, `selectScenario`, `describeStage`,
`describeTarget`, `describeSwings`, `toBarSeries`, `parseBarDate`,
`ElliottCheckResult`-Record, `MIN_CONFIDENCE`.

- `BullishIndicator.checkElliottABC()` – erkennt abgeschlossene Abwärtskorrekturen
  (Typ `CORRECTIVE`, Phase `CORRECTIVE_C`, Richtung bearish, Konfidenz ≥ `MIN_CONFIDENCE`,
  **seit 03.08. zusätzlich `scenarioSet.hasStrongConsensus()` erforderlich** - siehe unten)
- `BearishIndicator.checkElliottImpulseUp()` – erkennt vollständige 5-Wellen-Impulse
  aufwärts (Typ `IMPULSE`, Phase `WAVE5`, Richtung bullish, dieselben zusätzlichen
  Bedingungen)
- **`ElliottAnalysisUtil.describeStage()` seit 01.08. komplett umgestellt**: statt
  vollständiger deutscher Sätze ("A-B abgeschlossen, C im Entstehen") jetzt
  **kompakte Notation nur der bereits VOLLENDETEN Wellen**, z.B. `"A-B-"` wenn
  Korrektur-Welle C im Entstehen ist, `"1-2-"` wenn Impuls-Welle 3 im Entstehen
  ist. Die im-Entstehen-Welle selbst taucht bewusst nicht auf. `"1-2-..."`
  impliziert Impuls, `"A-B-..."` impliziert Korrektur - keine zusätzliche
  Typ-Kennzeichnung nötig (Rolf-Vorgabe). Leerstring bei `WAVE1`/`CORRECTIVE_A`
  (noch nichts vollendet).
- **`ElliottAnalysisUtil.describeTarget()` (neu, 03.08.)**: hängt an die
  kompakte Notation ein Kursziel an, z.B. `"A-B- -> 50% 75,00"`. **Der Preis
  kommt direkt von ta4j** (`scenario.primaryTarget()`). **Die Prozentzahl NICHT**
  - per `javap` verifiziert (`ElliottScenario`, `ElliottProjectionIndicator`,
  `ElliottRatioIndicator`): keine dieser Klassen liefert eine zu einem
  projizierten Ziel passende Fibonacci-Ratio, nur nackte Preise
  (`fibonacciTargets()` ist eine reine `List<Num>` ohne Label).
  `ElliottRatioIndicator` berechnet nur die Ratio des AKTUELLEN Kurses zum
  letzten Swing, nicht die eines Ziels. **Die Prozentzahl ist daher unsere
  eigene Näherung**: Bewegung vom Start der aktuell laufenden Welle bis zum
  Zielpreis, im Verhältnis zur Amplitude der unmittelbar davor abgeschlossenen
  Welle. Einmal an CAT validiert (berechnet 40%, Ziel lag nah am echten 38,2%-
  Fibonacci-Level) - vielversprechend, aber nur EIN Datenpunkt. **Bekannte
  Schwachstelle:** klassische Elliott-Praxis misst je nach Wellenposition
  gegen eine andere Referenzwelle (z.B. C typischerweise gegen A, nicht gegen
  B; Impuls-Wellen 3/5 oft als Extension statt Retracement) - unsere Formel
  nimmt immer nur die unmittelbar davorliegende Welle. Noch nicht an A/B/C und
  1-2-3-4-5-Positionen systematisch durchgetestet (Rolf sammelt weitere Fälle).
- **Eigener, größerer Lookback nur für Elliott (`AnalysisService.
  ELLIOTT_LOOKBACK_BY_INTERVAL`, 02./03.08.)**: `"1d"` → **230** (Tage/Bars,
  siehe Caveat unten), `"4h"`/`"1h"` vorerst unverändert zum allgemeinen
  Lookback. Grund: bei nur 90 Bars sieht der Runner den strukturell korrekten
  Wellenanfang oft nicht mehr (siehe Praxisfall IFX unten). MACD/Stochastik
  bleiben bewusst beim kleinen allgemeinen Lookback - brauchen die
  zusätzliche Historie nicht. **Caveat, noch nicht bereinigt:** Der Wert
  fließt sowohl als Kalendertage in `yahoo-service`s `period=f"{outputsize}d"`
  (yfinance) als auch als BAR-Anzahl in unsere interne Slice-Logik
  (`bars.subList(...)`) - zwei verschiedene Einheiten, derselbe Zahlenwert.
  Praktisch unschädlich (Slice-Grenze greift kaum, da weniger Bars als
  Kalendertage real ankommen), aber unsauber. `evaluate()` in beiden
  Indicator-Klassen nimmt seitdem nur noch `elliottLookback` entgegen - der
  alte allgemeine `lookback`-Parameter war dort ohnehin nie genutzt (MACD/
  Stochastik arbeiten immer auf der vollen `bars`-Liste) und wurde entfernt.
- **`scenarioSet.hasStrongConsensus()` als zusätzliches Gate (03.08.)**: `genuine`
  verlangt jetzt zusätzlich, dass sich die konkurrierenden Szenarien nicht
  deutlich widersprechen. Grund: `confidence` allein kann irreführend sein
  (siehe "Confidence vs. Probability" unten) - Konsens ist der günstigste
  verfügbare Proxy dafür, ohne die große Makro-Engine zu brauchen.
  `confidenceSpread()` wird zusätzlich fürs Debug-Log mitgeloggt (`Konsens=`,
  `Spread=`). **WICHTIG, noch offen:** Rolf hat am 03.08. angemerkt, dass er
  das evtl. **wieder rückgängig machen** möchte, nachdem er mehr Praxisfälle
  gesehen hat - noch nicht entschieden, nur im Hinterkopf zu behalten. Vor
  einem Revert: prüfen, wie viele bisher als `genuine=true` erkannte Fälle
  durch das Konsens-Gate neu herausgefiltert werden.
- **"Confidence" ist NICHT "Wahrscheinlichkeit" (Erkenntnis 03.08.)**: `confidence`
  (`ElliottScenario.confidence()`) ist ein reiner Struktur-Qualitätsscore für
  EIN Szenario für sich genommen (35% Fibonacci-Nähe, 20% Zeitproportionen,
  15% Alternation, 15% Kanal, 15% Vollständigkeit). Belegt an einem DAX-Beispiel
  (`ElliottWaveMacroCycleDemo`-JSON, ^GDAXI): das gewinnende Szenario
  (`totalScore=1.0`) hatte mit 41% die NIEDRIGSTE Confidence von 5 Kandidaten,
  während das Szenario mit der höchsten Confidence (64%) auf dem letzten Platz
  landete (`totalScore=0.844`). `totalScore` ist rechnerisch nur `probability`
  normiert auf den besten Kandidaten - und `probability`/`totalScore` existieren
  **ausschließlich in der großen `ElliottWaveMacroCycleDemo`-Engine**, nicht auf
  `ElliottScenarioSet`/`ElliottScenario` (per `javap` gegen `ElliottScenarioSet`
  verifiziert - kein `probability`-Feld, keine entsprechende Methode; einzige
  Ranking-Hilfe dort ist `byConfidenceDescending()`, was nahelegt, dass
  Confidence-Ranking der auf unserer einfacheren API-Ebene vorgesehene Weg ist).
- **Dieselbe ta4j-Berechnung läuft weiterhin zweimal pro Ticker** (einmal je
  Klasse, bekannter, nicht behobener Effizienz-Punkt).
- **OHLC/High-Low ist bereits Standard**, nicht konfigurierbar: `javap` auf
  `ElliottWaveFacade` und `SwingDetectors` bestätigt, dass keine der Methoden
  einen Preistyp-Parameter (z.B. `PriceType`) hat - die Doku beschreibt das
  ZigZag explizit als "OHLC-aware". **Praxisfall IFX (03.08.) bestätigt das als
  richtige Wahl**: Rolfs Charting-Tool (finanzen.net TradingDesk) macht seine
  automatische Wellenerkennung auf Open/Close-Basis, ta4j auf High/Low - das
  hatte zu scheinbaren Diskrepanzen geführt, war aber kein ta4j-Fehler, nur ein
  Vergleich unterschiedlicher Berechnungsbasen.
- ta4j bewertet kontinuierlich über Confidence-Scoring statt hartem Pass/Fail -
  ersetzt eine mehrwöchige, mehrfach nachkalibrierte Eigenentwicklung (Kaufman
  Efficiency Ratio, feste Fibonacci-Bänder, ZigZag-Bestätigung), die als
  Referenz/Fallback im Code dokumentiert, aber nicht mehr aktiv genutzt wird
  (siehe `BullishIndicator.detectUptrendPeakWithCorrection`, pausiert).
- **Grundsatzentscheidung:** Für gut erforschte, standardisierbare Probleme (wie
  Elliott-Wave-Erkennung) wird eine ausgereifte Bibliothek einer Eigenentwicklung
  vorgezogen, wenn eine mit vertretbarem Aufwand integrierbar ist.
- **Code-Stil-Konvention (07/2026):** Kommentare bewusst minimal halten,
  aussagekräftige Namensgebung statt erklärender Prosa bevorzugen (Rolf-Vorgabe).

**Die große `ta4jexamples.analysis.elliottwave.backtest.ElliottWaveMacroCycleDemo`-
Engine (Quellcode am 02.08. gesichtet, ~2600 Zeilen) - bewusst NICHT portiert:**
Erkennt Makro-Zyklus-Anker (den "richtigen" großen Wellenanfang) über einen
Vergleich von 5 `ElliottLogicProfile`-Hypothesen (H0-H4, alle auf `MINUTE`-Degree
gegen die volle Historie), die rohen Pivots (`rawSwings()`, nicht `scenarios()`)
zu Makro-Drawdowns kollabiert (`ElliottWaveMacroCycleDetector`,
55%-Drawdown-Schwelle über min. 120 Tage) und gegen ein Truth-Target-Register
bewertet. Profilnamen (`BTC_RELAXED_IMPULSE`/`BTC_RELAXED_CORRECTIVE`) und das
Holdout-Registry-Konzept deuten auf ein internes ta4j-Validierungs-Framework
gegen Bitcoin-Historie hin, nur zweitverwertet über `ElliottWavePresetDemo
live`. Portierung realistisch mehrere Tage Aufwand, deshalb zurückgestellt -
die einfachere `ELLIOTT_LOOKBACK`-Erhöhung (230 Tage) lieferte in Demo-Tests
bei IFX/GDAXI über einen Bereich von 200-1000 Tagen einen stabilen, identischen
Zyklus-Anker, was den großen Umbau vorerst unnötig macht. `ElliottWaveMacroCycleDetector`
selbst (der reine Anker-Erkennungs-Baustein, ~200 Zeilen) hängt NUR an
öffentlicher `ta4j-core`-API - falls der `ELLIOTT_LOOKBACK`-Ansatz an seine
Grenzen stößt, wäre das der pragmatischste Wiedereinstiegspunkt (als
Vorverarbeitungsschritt vor `ElliottAnalysisUtil.analyze()`, nicht als volle
Engine-Portierung).

**ta4j-API, verifiziert per `javap` gegen `ta4j-core-0.22.7.jar` sowie per
offizieller ta4j-Wiki-Doku:**
- `ElliottScenario.swings()` → `List<ElliottSwing>`; `ElliottSwing` ist ein
  Record mit `fromIndex()`/`toIndex()` (Kerzenindex) und `fromPrice()`/
  `toPrice()` (`Num`) pro Wellen-Bein - Basis für `describeSwings()` (loggt
  Datum+Kurs jeder Welle, damit sich Treffer direkt im Chart nachvollziehen
  lassen). `ElliottScenario` ist ein Record mit `primaryTarget()` (`Num`,
  EIN Zielpreis) und `fibonacciTargets()` (`List<Num>`, mehrere Kandidaten-
  Zielpreise) - **beide ohne zugehöriges Fibonacci-Ratio-Label** (03.08.,
  siehe `describeTarget()` oben).
- `ElliottScenarioSet` (03.08. per `javap` geprüft): `.base()`, `.alternatives()`,
  `.all()`, `.byPhase(...)`, `.byType(...)`, `.consensus()`, `.trendBias()`,
  `.confidenceSpread()`, `.hasStrongConsensus()`, `.invalidatedBy(...)`,
  `.validAt(...)`. **Kein `probability`/`totalScore`** - einzige eingebaute
  Sortierhilfe ist die statische `byConfidenceDescending()`.
- `ElliottProjectionIndicator.allTargets(int)`/`calculateTargets(swings, phase)`
  liefern `List<Num>` (reine Preise) - keine Ratio-Zuordnung. Die
  wellentyp-spezifische Logik dahinter (`calculateImpulseTargets`/
  `calculateCorrectiveTargets`) ist `private`, für uns nicht direkt nutzbar.
- `ElliottRatioIndicator.calculate(int)` liefert `ElliottRatio` (Typ + Wert,
  z.B. `RETRACEMENT 0.94`) - aber bezogen auf den AKTUELLEN Kurs relativ zum
  letzten Swing, nicht auf ein projiziertes Ziel. Für unseren Kursziel-
  Anwendungsfall nicht direkt verwendbar.
- `ElliottWaveFacade` hat **kein** `.facade(...)`, nur `.zigZag(...)` und
  `.fractal(...)` als Swing-Detektor-Konstruktoren, plus `.from(...)` für
  eigene Detektoren. Zweiter, mächtigerer Einstiegspunkt (seit 31.07. genutzt):
  `ElliottWaveAnalysisRunner.builder()...analyze(series)` – One-Shot-Pipeline
  mit Cross-Degree-Validierung und pluggable Swing-Detektoren/Confidence-Profilen,
  laut ta4j-Wiki der empfohlene Weg für genau unseren Anwendungsfall (mehrere
  Degrees gleichzeitig prüfen).
- `BaseBarSeriesBuilder` liegt in `org.ta4j.core`, **nicht** in
  `org.ta4j.core.builder` (Compile-Fehler-Ursache 07/2026, gefixt).
- ta4j-examples-Module (`ElliottWaveIndicatorSuiteDemo`, `ElliottWavePresetDemo`)
  sind nützlich zum Gegenprüfen eigener Ergebnisse gegen ta4js Referenz-Implementierung
  (`mvn -pl ta4j-examples exec:java -Dexec.mainClass=... -Dexec.args="..."`).
  `ElliottWaveMultiDegreeAnalysisDemo` dagegen hat **keine CLI-Unterstützung für
  Live-Datenquellen** (läuft nur gegen ein fest einprogrammiertes ossifiziertes
  BTC-Testset) – für Ticker-spezifische Tests ungeeignet, `ElliottWavePresetDemo`
  im `live`-Modus nutzen (z.B. `live YahooFinance IFX.DE PT1D 230`). Bei
  `PT1D`/`PT24H` routet das automatisch in `ElliottWaveMacroCycleDemo.
  runLivePreset(...)` (siehe oben) - der `degree`-Parameter wird dabei
  ignoriert, die Engine wählt selbst über die 5 Hypothesen.
- **Ta4j selbst empfiehlt laut Wiki explizit, KEINE ta4j-eigenen Chart-Renderer
  in Produktivcode zu übernehmen**: *"Ta4j includes charting helpers, but
  you're not locked in - serialize to JSON and use any visualization stack you
  prefer."* Bestätigt die Entscheidung für Option C bei der geplanten Frontend-
  Visualisierung (siehe Roadmap).

**Remote-Debugging (VS Code, JDWP):** `JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`
als Env-Var in `docker-compose.yml` beim `agent-service-java`-Service, Port
`5005:5005` gemappt, `launch.json` mit `"type": "java", "request": "attach",
"hostName": "localhost", "port": 5005"` (**ohne** `projectName` - eine falsche
Angabe verhindert das Evaluieren von Ausdrücken in der Debug-Konsole, auch wenn
Breakpoints trotzdem greifen). Kein Rebuild nötig, nur `docker compose up -d`.

**Praxisfälle, die zu den obigen Änderungen führten (07/2026):**
- **BA:** `BullishIndicator` akzeptierte ein Szenario mit A-B-C, bei dem Welle A
  tatsächlich STIEG (kein Abwärts-Zickzack) - Richtungsprüfung
  (`scenario.isBearish()`) fehlte ursprünglich, jetzt ergänzt.
- **DIS (mehrstufige Untersuchung, 30.–31.07.):** Erkannte A-Welle (`ElliottDegree.
  INTERMEDIATE`, kein Compressor) war eine kleine, verschachtelte Teilbewegung
  (18.06.) innerhalb einer visuell viel größeren, von Rolf manuell nachgezählten
  Bewegung (Hoch 07.05. → Tief 10.06.). Eigene Zigzag-Simulation der echten OHLCV-
  Daten (20.04.–21.07.) bestätigte: bei ~5-6% Schwellenwert ergibt sich exakt diese
  größere Struktur. Testlauf mit ta4js `ElliottWaveIndicatorSuiteDemo` gegen 62 Bars
  zeigte das gleiche Problem eine Ebene tiefer (Auto-Degree `MINUTE`, nur die
  letzten ~20 Bars ausgewertet). Der Durchbruch kam über `ElliottWavePresetDemo`
  im `live`-Modus (276 Bars, ~1 Jahr): Mit `ElliottLogicProfile.HIERARCHICAL_SWING`
  setzte ta4j den Strukturanker exakt auf den 27.03.2026 – **unabhängig durch
  Rolfs manuelle Wellenzählung bestätigt** (27.03. = Ende einer großen ABC-
  Korrektur). Ergebnis: Was wie eine abgeschlossene, isolierte ABC-Korrektur
  aussah (07.05.–23.07.), ist strukturell die **Welle 2** einer größeren
  bullishen Impulsbewegung, deren Welle 1 von 27.03. (Tief) bis 07.05. (Hoch)
  läuft. Invalidierung ≤92,19 (nahe 27.03.-Anker), Ziel 103,78 für Welle 3.
  Führte zur Umstellung von `ElliottWaveFacade` + `ElliottSwingCompressor` (30.07.,
  Zwischenlösung) auf `ElliottWaveAnalysisRunner` mit `HIERARCHICAL_SWING`-Profil
  und Cross-Degree-Validierung (31.07., aktueller Stand). **Noch offen:** Build
  nach `ta4j-core`-Upgrade auf 0.22.7 und erneuter Testlauf gegen den DIS-Fall
  im Produktivcode (`agent-service-java`, nicht nur ta4j-examples-Demo).
- **AMZN [1H]:** `parseBarDate()` scheiterte an Zeitstempeln ohne Zone
  (`"2026-05-19T12:30:00"`, Format bei 1H/4H-Daten von TwelveData) - Fix über
  `LocalDateTime`-Zwischenschritt vor dem `LocalDate`-Fallback.
- **IFX (01.–03.08.):** Gleiches Grundmuster wie DIS - `checkElliottABC` fand
  bei 90 Bars nur einen kleinen lokalen Pivot (74,02) als Wellenstart A, statt
  des strukturell korrekten Hochs bei 88,46/88,83 (02./22.06.). `ElliottWavePresetDemo
  live`-Test (400 Kalendertage) zeigte den wahren Rahmen: eine seit 23.03.2026
  laufende Welle 2 (Korrektur) innerhalb eines viel größeren bullishen Impulses
  (Welle 1 von 36€ auf 88€), mit Fibonacci-Score 99,2%. Stabilitätstest über
  200/400/1000/1825 Kalendertage: der Zyklus-Anker (23.03.) blieb über den
  gesamten Bereich 200-1000 Tage identisch stabil, kippte erst bei den vollen
  1825 Tagen auf einen noch größeren, älteren Zyklus (Juli 2022) - legitime
  Elliott-Fraktalität, kein Fehler. Führte zur `ELLIOTT_LOOKBACK`-Einführung
  (230 Tage, siehe oben) statt einer vollen Portierung der `MacroCycleDemo`-
  Engine. Nebenbefund: Rolfs Charting-Tool (finanzen.net) rechnet auf Open/
  Close-Basis, ta4j auf High/Low - erklärte frühere scheinbare Diskrepanzen,
  kein ta4j-Fehler (siehe oben).
- **GDAXI (03.08.):** Lieferte den Beweis für "Confidence ≠ Wahrscheinlichkeit"
  (siehe oben) - Base Case gewann mit der niedrigsten Confidence (41%) aller 5
  Szenarien, das Szenario mit der höchsten Confidence (64%) landete auf dem
  letzten Platz nach `totalScore`.

**Migration Java 21 → 25 / Spring Boot 3.4 → 4.1 (07/2026):**
- Grund: Spring Boot 3.4 und 3.5 sind beide EOL (Stand 07/2026), daher direkter
  Sprung auf 4.1 (aktuell unterstützte Linie, Support bis 07/2027) statt Zwischenschritt
- Lombok braucht seit JDK 25 einen expliziten `annotationProcessorPath` im
  `maven-compiler-plugin` (implizite Classpath-Erkennung reicht nicht mehr)
- Spring Boot 4 nutzt standardmäßig Jackson 3 (`tools.jackson.*`) statt Jackson 2
  (`com.fasterxml.jackson.*`) – `com.fasterxml.jackson.databind.ObjectMapper` wird
  nicht mehr automatisch als Bean bereitgestellt. Fix: offizielles
  Kompatibilitätsmodul `org.springframework.boot:spring-boot-jackson2` (Stop-Gap,
  wird in künftiger Spring-Boot-Version entfernt – echte Migration auf Jackson 3
  ist ein offener Punkt für später)
- Docker-Images auf `eclipse-temurin:25-*-alpine` umgestellt,
  `--enable-native-access=ALL-UNNAMED` im Entrypoint (JEP 472, JDK 24+ warnt sonst
  bei Nettys nativer Bibliothek)
- JDK 21 lokal (Mac, Homebrew-Cask `temurin@21`) und alle zugehörigen
  `JAVA_HOME`-Referenzen vollständig entfernt

### 3.2c Bekannte offene Punkte in agent-service-java

- **Uptrend-Peak-Erkennung** (`BullishIndicator.detectUptrendPeakWithCorrection`):
  strukturelle Alternative zur reinen Peak-Erkennung über das Fenster-Maximum,
  implementiert aber pausiert zugunsten der ta4j-Migration. Nicht in `evaluate()`
  verdrahtet.
- **ta4j-Szenario wird doppelt berechnet** (einmal in `BullishIndicator`, einmal in
  `BearishIndicator`) – Zusammenlegung möglich, aber noch nicht umgesetzt.
- **`hasStrongConsensus()`-Gate evtl. wieder rückgängig zu machen** (Stand 03.08.,
  noch nicht entschieden) - Rolf will erst mehr Praxisfälle sammeln, bevor er
  entscheidet, ob das Gate zu streng filtert. **Vor einem Revert prüfen:** wie
  viele bisher als `genuine=true` erkannte Fälle fallen dadurch neu raus?
- **`RUNNER_MIN_CONFIDENCE = 0.15`, `MIN_CONFIDENCE = 0.6`, `HIGHER_DEGREES`/
  `LOWER_DEGREES = 1`, `ELLIOTT_LOOKBACK_BY_INTERVAL["1d"] = 230`** sind vom
  Praxistest mit dem ta4j-eigenen `ElliottWavePresetDemo` übernommen, aber noch
  nicht systematisch an vielen Tickern nachkalibriert.
- **`describeTarget()`-Retracement-Prozentzahl nur an einem Fall (CAT) validiert**
  - noch systematisch für A/B/C und 1-2-3-4-5-Positionen durchzutesten, siehe
  bekannte Schwachstelle oben (Referenzwelle evtl. wellenpositionsabhängig
  falsch gewählt, insb. bei C und bei Impuls-Extensions).
- **Elliott-Lookback/Outputsize-Einheiten-Verwechslung** (Kalendertage vs.
  Bar-Anzahl, siehe oben) - unschädlich, aber unsauber, bei Gelegenheit trennen.
- **Praxisbeobachtung, noch nicht untersucht (Rolf, 03.08.):** Bei einigen
  Tickern wird nach einem längeren Abwärtstrend eine sehr kleinteilige
  Aufwärtsbewegung als Impuls ausgegeben - wirkt fragwürdig, noch nicht
  eingegrenzt, auf welche Fälle/Muster das zutrifft.
- **Frontend-Elliott-Wave-Chart-Visualisierung ("Option C", geplant, noch NICHT
  begonnen)**: Rolf möchte in einer neuen Spalte rechts von "Elliott Wave" ein
  Thumbnail sehen (Kerzen + eingezeichnete Swing-Punkte des gewählten
  Szenarios), Klick öffnet größer in einem Modal - um visuell nachvollziehen zu
  können, wie ta4j die Wellenzählung setzt. Entscheidung bereits getroffen:
  strukturierte Daten (Bars + Swing-Punkte + Zielpreis) ans Angular-Frontend
  schicken, dort mit einer JS-Chart-Bibliothek rendern (Kandidat: TradingViews
  `lightweight-charts`) - NICHT serverseitig JFreeChart-Bilder erzeugen
  (Headless-Swing-Komplexität im Alpine-Docker-Container) und NICHT die
  `ta4jexamples.charting`-Klassen wiederverwenden (Beispielcode-Kopplung, siehe
  MacroCycleDemo-Engine oben). Offene Design-Fragen vor Implementierung:
  (1) wie viele Bars pro Ticker/Request mitschicken (voller
  `elliottLookback`-Bereich vs. nur ab `scenario.startIndex()` - Trade-off
  gegen SSE-Payload-Größe bei großen Multi-Ticker-Läufen); (2) Bestätigung
  `lightweight-charts` als Bibliothek. **Rolf ist ab 03.08. für mehrere Tage
  unterwegs, unklar wann er weitermacht - das ist der nächste anstehende
  Schritt, sobald es weitergeht.**
- **Klären: Python-`agent-service` (Port 8010) noch aktiv oder durch
  `agent-service-java` ersetzt?**
- **`stock-data-db-access`: README/Doku für Java-25-Stand ergänzen**



| Eigenschaft   | Wert                                              |
| ------------- | ------------------------------------------------- |
| Sprache       | Python 3.12                                       |
| Framework     | FastAPI + sse-starlette                           |
| Port (intern) | 8011 (erreichbar nur über VPN-Gateway-Alias)      |
| Netzwerk      | `network_mode: "container:vpn"` – kein stock-net  |
| TLS-Tarnung   | `curl_cffi` mit `impersonate="chrome"`            |

**Anti-Blocking-Strategie:**
- Läuft isoliert im Netzwerk-Namespace des `vpn`-Containers
- Externe IP = anonyme Proton VPN IP (Niederlande)
- `curl_cffi` imitiert Chrome-TLS-Fingerabdruck
- Zufälliger Delay zwischen Tickern: 2.5–4.5 Sekunden
- Automatische Retry-Logik bei Rate-Limit: 5s, 10s, 20s

### 3.4 TwelveData Service (`twelvedata-service/`)

| Eigenschaft   | Wert                                |
| ------------- | ----------------------------------- |
| Sprache       | Python 3.12                         |
| Framework     | FastAPI + sse-starlette             |
| Port          | 8012                                |
| Version       | 2.0.0                               |
| Konfiguration | `twelvedata-service/.env` (API-Key) |

**Wichtig – interval-Parameter:**
- Ab v2.0.0 akzeptiert der Service einen optionalen `interval`-Parameter im Request
- Default: `"1day"` (rückwärtskompatibel)
- Für Stundendaten: `"1h"`
- **Ohne `interval="1day"` liefert TwelveData Intraday-Daten → leeres `bars`-Array**

**Rate-Limit:** Free Plan max 8 Requests/Minute → fixer Delay von 7.5s zwischen Tickern im Stream. Der History-Fetcher wartet zusätzlich 8s nach jedem einzelnen Request.

### 3.5 VPN Gateway (`vpn` / Gluetun)

- Image: `qmcgaw/gluetun`
- Provider: Proton VPN (WireGuard, Free-Server, Niederlande)
- Konfiguration: Root `.env` (WireGuard-Keys, Länder, Subnetz)
- Alias `yahoo-service` im `stock-net` → Port 8011 wird weitergeleitet
- IP-Wechsel: `docker exec vpn kill -HUP 1`

### 3.6 DB Access Service (`stock-data-db-access/`)

| Eigenschaft  | Wert                              |
| ------------ | --------------------------------- |
| Sprache      | Java 25                           |
| Framework    | Spring Boot 4.1                   |
| Port         | 8013                              |
| Datenbank    | MySQL 9.7                         |
| Migrations   | Flyway (V1–V4)                    |

**Datenbanktabellen:**

| Tabelle         | Zweck                                          |
| --------------- | ---------------------------------------------- |
| `ticker_lists`  | Listen (DAX40, DOW30, INDIZES, INTERNATIONALE RTF'S) |
| `ticker_symbols`| Einzelne Ticker pro Liste (raw_symbol)         |
| `ticker_meta`   | Normalisierte API-Symbole + Stammdaten         |
| `ohlcv_daily`   | Tageskerzen (5 Jahre, ~1.250 pro Ticker)       |
| `ohlcv_hourly`  | Stundenkerzen (12 Monate, ~5.000 pro Ticker)   |
| `fetch_log`     | Protokoll aller Datenabrufe                    |

**Wichtige Endpunkte (OHLCV):**

| Method | Path                              | Beschreibung                     |
| ------ | --------------------------------- | -------------------------------- |
| GET    | `/api/ohlcv/meta`                 | Alle Ticker-Metadaten            |
| GET    | `/api/ohlcv/daily/{ticker}/latest?n=90` | Neueste N Tageskerzen      |
| POST   | `/api/ohlcv/daily/bulk`           | Bulk-Insert Tageskerzen          |
| POST   | `/api/ohlcv/hourly/bulk`          | Bulk-Insert Stundenkerzen        |
| POST   | `/api/ohlcv/fetch-log`            | Abruf-Protokoll schreiben        |
| GET    | `/api/ohlcv/coverage`             | Datenbestand-Übersicht           |

### 3.7 History Fetcher (`history-fetcher/`)

| Eigenschaft  | Wert                              |
| ------------ | --------------------------------- |
| Sprache      | Python 3.12                       |
| Framework    | FastAPI + APScheduler             |
| Port         | 8014                              |
| Version      | 1.0.0 (fix: TwelveData interval)  |

**Verhalten:**
- Beim ersten Start: prüft ob Daten vorhanden → startet Erstbefüllung automatisch (AUTO_INITIAL_RUN=true)
- Erstbefüllung: 5 Jahre Tagesdaten + Stundendaten für alle 4 Listen
- Täglicher Update-Lauf: 20:00 Uhr (nur neue Kerzen seit letztem Abruf)
- Idempotent: bereits vorhandene Kerzen werden übersprungen

**Endpunkte:**

| Method | Path              | Beschreibung                         |
| ------ | ----------------- | ------------------------------------ |
| GET    | `/health`         | Status + Scheduler-Info              |
| GET    | `/status`         | Letzter Lauf + nächster geplanter    |
| POST   | `/fetch/initial`  | Erstbefüllung manuell starten        |
| POST   | `/fetch/update`   | Update-Lauf manuell starten          |
| GET    | `/coverage`       | Proxy → DB-Service Coverage          |

**Kritische Konfiguration:**
```
LIST_CODES = ["DAX40", "DOW30", "INDIZES", "INTERNATIONALE RTF'S"]
twelvedata_delay_sec = 8.0   # nach JEDEM TwelveData-Request (Rate-Limit)
ticker_delay_sec     = 0.5   # zwischen Yahoo-Tickern
```

**TwelveData-Fix (v2):** `interval="1day"` wird explizit übergeben. Ohne diesen Parameter liefert TwelveData Intraday-Daten → leeres bars-Array → 0 Kerzen in der DB.

### 3.8 ML Service (`ml-service/`)

| Eigenschaft  | Wert                              |
| ------------ | --------------------------------- |
| Sprache      | Python 3.12                       |
| Framework    | FastAPI + APScheduler             |
| Port         | 8015                              |
| Modell       | XGBoost (xgb_reversal.joblib)     |
| Features     | 38 technische Indikatoren         |

**Was das Modell tut:**
- Lernt aus 5 Jahren OHLCV-History aller Ticker
- Label: Steigt der Kurs in den nächsten 5 Tagen um mehr als 3%? (ja=1 / nein=0)
- Zeitreihen-Split 80/20 (kein zufälliges Shufflen → kein Data-Leakage)
- Klassen-Gewichtung: Umkehrpunkte sind selten → pos_weight automatisch berechnet

**Top-Features (aus Trainings-Ergebnis):**
1. `vol_20d` – Volatilität 20 Tage (11.8%) – dominiert deutlich
2. `vol_10d` – Volatilität 10 Tage (6.7%)
3. `dist_52w_high` – Abstand 52-Wochen-Hoch (3.7%)
4. `dist_sma50` – Abstand SMA 50 (3.0%)
5. `lower_wick` – Unterer Kerzendocht (2.7%)

**Backtesting-Ergebnis (initiales Training):**
- Precision: 0.384 | Recall: 0.367 | ROC-AUC: 0.698
- ROC-AUC 0.698 = solides Signal (0.5 = Zufall, 1.0 = perfekt)

**Konfiguration (Stellschrauben):**
```
forecast_horizon       = 5     # Tage in die Zukunft
reversal_threshold_pct = 3.0   # Mindest-Kursänderung % für "Umkehr"
```
Nach Änderung: `curl -X POST http://localhost:8015/model/train`

**Signal-Schwellen:**
- 0–39%: kein Signal
- 40–54%: schwach
- 55–74%: mittel
- 75–100%: stark 🔥

**Endpunkte:**

| Method | Path                   | Beschreibung                         |
| ------ | ---------------------- | ------------------------------------ |
| GET    | `/health`              | Status + model_ready                 |
| GET    | `/model/status`        | Metriken + Feature-Importance        |
| POST   | `/model/train`         | Training manuell starten             |
| POST   | `/predict/{ticker}`    | Vorhersage für einen Ticker          |
| POST   | `/predict/batch`       | Vorhersage für mehrere Ticker        |

**Modell-Persistenz:** Docker-Volume `ml_models:/app/models` – überlebt Container-Neustarts.
**Retraining:** Automatisch jeden Sonntag 02:00 Uhr.

---

## 4. Docker & Ports

| Service            | Container               | Port  |
| ------------------ | ----------------------- | ----- |
| VPN Gateway        | `vpn`                   | 8011  |
| Agent Service      | `stock_agent`           | 8010  |
| Agent Service Java | `stock_agent_java`      | 8016  |
| Yahoo Service      | `stock_yahoo`           | –     |
| TwelveData Service | `stock_twelvedata`      | 8012  |
| DB Access Service  | `stock_db_access`       | 8013  |
| History Fetcher    | `stock_history_fetcher` | 8014  |
| ML Service         | `stock_ml_service`      | 8015  |
| MySQL              | `stock_data_db`         | 3306  |
| Angular Client     | `stock_client`          | 4200  |

**Vollständiger Start:**
```bash
docker compose up -d --build
```

**Einzelnen Service neu bauen:**
```bash
docker compose up -d --build <service-name>
# z.B.:
docker compose up -d --build agent-service
docker compose up -d --build ml-service
```

**ML-Training nach Datenbankbefüllung:**
```bash
curl -X POST http://localhost:8015/model/train
curl http://localhost:8015/model/status
```

**History-Fetcher manuell starten:**
```bash
curl -X POST http://localhost:8014/fetch/initial
docker logs -f stock_history_fetcher
```

**Swagger Docs:**
- Agent:      http://localhost:8010/docs
- TwelveData: http://localhost:8012/docs
- DB-Service: http://localhost:8013/swagger-ui.html
- History:    http://localhost:8014/docs
- ML-Service: http://localhost:8015/docs

---

## 5. Coding-Konventionen

- **Sprache im Code:** Englisch (Variablen, Methoden, Interfaces)
- **Kommentare:** Deutsch (erklären das _Warum_, nicht das _Was_)
- **Architektur:** Smart/Dumb Components – `AppComponent` hält State, Kindkomponenten nur Inputs/Outputs
- **State-Management:** Angular Signals (`signal`, `computed`) – kein RxJS Subject/BehaviorSubject für UI-State
- **Kein `any`** – strikte TypeScript-Typisierung durchgehend
- **Single Responsibility** – eine Komponente = eine Aufgabe
- **Clean Code:** Lesbarkeit vor Cleverness, keine verschachtelten Einzeiler

---

## 6. Roadmap

> Diese Sektion bitte nach jeder Session aktualisieren.

- [x] Elliott Wave + MACD + Stochastik Indikatoren
- [x] Bullische und bearische Umkehrerkennung
- [x] Candlestick Pattern Erkennung (5 bullische + bearische Muster)
- [x] `trend_direction` zeigt aktuellen Markttrend (nicht Umkehrerwartung)
- [x] Angular-Client: Ticker-Listen aus DB laden und bearbeiten
- [x] DB Access Service (Spring Boot / MySQL) mit Flyway-Migrationen
- [x] History Fetcher: 5 Jahre Tagesdaten + Stundendaten für alle Listen
- [x] ML Service: XGBoost Umkehrwahrscheinlichkeit (ROC-AUC 0.698)
- [x] Agent Service v4: ML-Signal in SSE-Stream integriert
- [x] Angular Client: KI-Signal-Spalte (farbkodiert, sortierbar, PDF-Export)
- [x] `agent-service-java`: Java/Spring-Boot-Port, produktiv über docker-compose
- [x] `agent-service-java`: Elliott-Wave-Erkennung auf ta4j (`ElliottWaveFacade`)
      umgestellt statt Eigenentwicklung (Confidence-Scoring statt Pass/Fail)
- [x] `agent-service-java` + `stock-data-db-access`: Migration Java 21→25,
      Spring Boot 3.4→4.1 (Jackson-2-Kompatibilitätsmodul, Lombok-Fix, JDK 21
      lokal vollständig entfernt)
- [x] `elliottStage`-Zwischenstand (z.B. "A-B abgeschlossen, C im Entstehen")
      durch bis ins Angular-Frontend, Elliott-Wave-Spalte zeigt bestätigt vs.
      im Entstehen visuell unterschiedlich an
- [x] Richtungsprüfung in `checkElliottABC` ergänzt (Praxisfall BA: A-B-C mit
      steigender Welle A wurde fälschlich akzeptiert)
- [x] Alternativ-Szenario-Fallback (`selectScenario`/`alternativeScenarios()`),
      `ElliottDegree.INTERMEDIATE` statt `MINOR` (Praxisfall DIS, Zwischenstand
      30.07. - inzwischen durch Runner-Umstellung abgelöst, siehe unten)
- [x] Remote-Debugging-Setup (VS Code + JDWP) für `agent-service-java` dokumentiert
- [x] DIS-Kalibrierungsfall bis zur Ursache durchdrungen: dynamischer Degree
      (`ElliottDegree.getRecommendedDegrees`) + `ElliottWaveAnalysisRunner` mit
      `HIERARCHICAL_SWING`-Profil und Cross-Degree-Validierung (+-1 Grad) statt
      `ElliottWaveFacade` + handkalibriertem `ElliottSwingCompressor`. Durch
      Rolfs manuelle Wellenzählung unabhängig bestätigt (Strukturanker 27.03.)
- [x] `pom.xml` von `agent-service-java` auf `ta4j-core` 0.22.7 angehoben,
      Build + mehrere DAX-/Dow-Jones-Komplettläufe erfolgreich verifiziert
- [x] Geteilte Elliott-Hilfsmethoden aus `BearishIndicator` in eigene Klasse
      `ElliottAnalysisUtil` ausgelagert, von beiden Indicator-Klassen genutzt
- [x] `elliottStage`-Ausgabe auf kompakte Nur-vollendete-Wellen-Notation
      umgestellt ("A-B-"/"1-2-" statt Volltext-Sätze)
- [x] Kursziel + eigene Retracement-Näherung in `elliottStage` ergänzt
      (`describeTarget()`, z.B. "A-B- -> 50% 75,00") - Preis von ta4j, Prozent
      selbst berechnet, einmal an CAT validiert
- [x] Eigener, größerer Lookback nur für Elliott-Berechnung eingeführt
      (`ELLIOTT_LOOKBACK_BY_INTERVAL["1d"]=230`) statt vollem Makro-Engine-Port
      - IFX-Praxistest bestätigte stabilen Zyklus-Anker über 200-1000 Tage
- [x] `scenarioSet.hasStrongConsensus()` als zusätzliches Gate für `genuine`
      ergänzt, `confidenceSpread()` mitgeloggt (Stand 03.08. - evtl. wieder
      rückgängig zu machen, siehe 3.2c, noch nicht entschieden)
- [x] Elliott-Wave-Badge-Farblogik im Frontend entfernt (einheitliches Schwarz
      statt blau/grau)
- [ ] ta4j-Doppelberechnung (Bullish/Bearish) zusammenlegen
- [ ] `RUNNER_MIN_CONFIDENCE`/`MIN_CONFIDENCE`/`HIGHER_DEGREES`/`LOWER_DEGREES`/
      `ELLIOTT_LOOKBACK` an mehreren Tickern (nicht nur DIS/IFX) nachkalibrieren
- [ ] `describeTarget()`-Retracement-Prozentzahl an mehr Fällen (A/B/C, 1-2-3-4-5)
      validieren - Referenzwelle evtl. wellenpositionsabhängig anzupassen
- [ ] `hasStrongConsensus()`-Gate: entscheiden ob beibehalten oder Revert (03.08.)
- [ ] Elliott-Lookback/Outputsize-Einheiten sauber trennen (Kalendertage vs.
      Bar-Anzahl aktuell vermischt)
- [ ] Praxisbeobachtung untersuchen: kleinteilige Aufwärtsbewegung nach langem
      Abwärtstrend wird manchmal als Impuls erkannt (Rolf, 03.08., noch nicht
      eingegrenzt)
- [ ] **Nächster großer Schritt ("Option C"): Elliott-Wave-Chart-Thumbnail +
      Modal im Angular-Frontend** - Backend liefert Bars+Swings+Ziel als JSON,
      Frontend rendert mit `lightweight-charts` (Kandidat). Design-Fragen noch
      offen (Bar-Menge im Payload, Bibliotheks-Bestätigung). **Rolf ist ab
      03.08. mehrere Tage unterwegs - das ist der Wiedereinstiegspunkt.**
- [ ] Klären: Python-`agent-service` (Port 8010) noch aktiv oder durch
      `agent-service-java` ersetzt?
- [ ] `stock-data-db-access`: README/Doku für Java-25-Stand ergänzen

**Zuletzt geändert:** 2026-08-03
**Zuletzt bearbeitet von Claude:** Nach dem `ta4j-core`-0.22.7-Upgrade (Build
verifiziert) intensive Praxistest-Runde mit Rolf über mehrere Sessions (01.-
03.08.): DAX-/Dow-Jones-Komplettläufe zeigten zunächst zwei mutmaßliche
Datenfehler (falsche Kurse, falscher Stage-Text bei ENR/HOT/CON), die sich
nach ausführlicher Fehlersuche (DB-Daten korrekt, `agent-service-java`-Code
korrekt, rohe Yahoo-API korrekt) letztlich als konsistenter, reproduzierbarer
`yfinance`/`curl_cffi`-Bug entpuppten (nicht in unserem Code lokalisierbar,
Update auf `yfinance 1.5.2` half nicht) - ungelöst, siehe `yahoo-service`.
Danach IFX-Praxisfall bearbeitet: gleiches Strukturproblem wie DIS, gelöst über
neuen separaten `ELLIOTT_LOOKBACK` (230 Tage) statt der viel aufwändigeren
`ElliottWaveMacroCycleDemo`-Engine (~2600 Zeilen Beispielcode gesichtet, bewusst
nicht portiert). Nebenbei: `ElliottAnalysisSupport` in `ElliottAnalysisUtil`
umbenannt und aus `BearishIndicator` in eigene Klasse ausgelagert;
`describeStage()` auf kompakte "A-B-"/"1-2-"-Notation umgestellt statt
Volltext; `describeTarget()` (Kursziel + eigene Retracement-Näherung) neu
ergänzt; Frontend-Farblogik der Elliott-Wave-Spalte entfernt. Am 03.08.
zusätzlich `hasStrongConsensus()`-Gate ergänzt (Status: evtl. vorläufig,
Rolf noch unentschieden) und die "Confidence ist nicht Wahrscheinlichkeit"-
Erkenntnis anhand eines GDAXI-Beispiels dokumentiert. Nächster geplanter
Schritt ("Option C": Elliott-Chart-Thumbnail/Modal im Frontend) ist besprochen
und entschieden, aber noch nicht begonnen - Rolf ist ab 03.08. für mehrere
Tage unterwegs, Fortsetzung zeitlich offen.


---

## 7. Wichtige Hinweise für Claude

- **Bestehende API-Verträge nicht brechen** – das SSE-Format (`event: result / done`) ist fix
- **Angular Material + Tailwind** – beide im Einsatz; `preflight: false` in Tailwind um Konflikte zu vermeiden
- **Prebuilt Material Theme** – `indigo-pink.css` eingebunden via `angular.json styles`
- **Kein `ngModule`** – ausschließlich Standalone Components
- **PDF-Export** – immer Browser-Print, kein jsPDF oder Server-seitiges PDF
- **VPN-Routing** – `yahoo-service` hat `network_mode: "container:vpn"`, keinen eigenen `stock-net`-Anschluss. Erreichbar über Alias `yahoo-service` am VPN-Gateway (Port 8011). Nie direkten Port für `yahoo-service` in `docker-compose.yml` eintragen.
- **Secrets** – `.env`-Dateien niemals committen. Immer die `.env.example`-Vorlage aktuell halten wenn neue Variablen hinzukommen.
- **Plattform** – `--platform=linux/arm64` in allen Dockerfiles (Apple Silicon). Bei x86-Änderungen immer erwähnen.
- **TwelveData interval** – immer `interval="1day"` für Tagesdaten und `interval="1h"` für Stundendaten übergeben. Ohne diesen Parameter liefert TwelveData Intraday-Daten.
- **ML-Signal ist non-blocking** – Timeout 5s. Bei ML-Service-Ausfall läuft die Analyse normal weiter (`ml_available: false`).
- **Java-Services (`agent-service-java`, `stock-data-db-access`)** laufen seit 07/2026 auf **Java 25 + Spring Boot 4.1**. JDK 21 ist lokal nicht mehr installiert – bei neuen Java-Services/Dependencies immer von Java 25 als Baseline ausgehen.
- **Bibliothek vor Eigenentwicklung:** Für gut erforschte, standardisierbare Probleme (z.B. Elliott-Wave-Erkennung) erst prüfen, ob eine ausgereifte Bibliothek existiert (siehe ta4j-Entscheidung, Abschnitt 3.2b), bevor eine Eigenentwicklung vertieft wird.
- Bei Unklarheiten zuerst fragen, dann implementieren

---

## 8. Analyse-Workflow & Systemdesign-Entscheidungen

### Zweistufiger Analyse-Workflow

```
Stufe 1 – Automatisches Screening (agent-service)
  ├── Elliott Wave + MACD + Stochastik → trend_direction (bullish / bearish)
  ├── Score 0–3 → Priorisierung (3/3 = stärkstes Signal)
  ├── Candlestick Pattern → erster Hinweis auf mögliche Umkehr
  └── KI-Signal (XGBoost) → Umkehrwahrscheinlichkeit 0–100%

Stufe 2 – Manuelle Qualitätsprüfung (nur bei Score 2/3 oder 3/3)
  ├── Fibonacci-Retracements einzeichnen
  ├── Cluster-Analyse (GDs, frühere Tiefs/Hochs, Volumenknotenpunkte)
  └── Endgültige Handelsentscheidung
```

### Semantik der Indikatoren (kritisch!)

Die Indikator-Dateien sind als **Umkehrsignal-Detektoren** konzipiert:

| Datei                           | Erkennt diese Marktbedingung       | `trend_direction` in main.py |
| ------------------------------- | ---------------------------------- | ---------------------------- |
| `bullish_reversal_indicator.py` | Abwärtswelle + MACD<0 + Stoch<20  | `"bearish"`                  |
| `bearish_reversal_indicator.py` | Aufwärtswelle + MACD>0 + Stoch>80 | `"bullish"`                  |

**Regel:** `trend_direction` zeigt den **aktuellen Markttrend**, nicht die erwartete Umkehrrichtung. Diese Invertierung ist in `main.py` (`analyse_quote`) explizit kommentiert und darf **nicht** geändert werden.

**In `agent-service-java` gilt dieselbe Semantik**, nur mechanisch anders umgesetzt:
`BullishIndicator` (→ `trend_direction: "bearish"`) erkennt über ta4j eine
abgeschlossene Abwärtskorrektur (Phase `CORRECTIVE_C`); `BearishIndicator`
(→ `trend_direction: "bullish"`) erkennt einen vollständigen 5-Wellen-Aufwärtsimpuls
(Phase `WAVE5`). Die Datei-Namen bleiben bewusst semantisch invertiert, wie im
Python-Original – siehe Abschnitt 3.2b.

### ML-Signal Interpretation

| Kombination                          | Bedeutung                                            |
| ------------------------------------ | ---------------------------------------------------- |
| `bearish` + ML-Signal stark (>75%)   | Abwärtstrend + KI sieht Umkehrchance → Fibonacci!   |
| `bullish` + ML-Signal keins (<40%)   | Laufender Aufwärtstrend, keine Wende erwartet        |
| `bullish` + ML-Signal mittel/stark   | Widerspruch → besonders genau prüfen                 |

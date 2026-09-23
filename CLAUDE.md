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
│   ├── ticker-list-editor/ # Listen anlegen/bearbeiten
│   ├── elliott-chart-thumbnail/ # Mini-Chart (Kerzen+Swings) in der Ergebnistabelle, öffnet Modal
│   └── elliott-chart-modal/     # Großer Elliott-Wave-Chart (lightweight-charts), Wellen-Labels + Zielpreis-Linie
├── models/
│   └── stock.models.ts     # StockResult (inkl. ML- + Elliott-Chart-Feldern), FilterState, AnalysisSummary
├── services/
│   ├── analysis.service.ts   # SSE-Streaming (hält per WakeLockService den Rechner wach)
│   ├── wake-lock.service.ts  # Screen Wake Lock während einer Analyse
│   ├── ticker-list.service.ts # REST-Calls zum DB-Service
│   └── pdf-export.service.ts # PDF via Browser-Print (inkl. KI-Spalte, Name-Spalte, Chart-Spalte als statisches SVG)
├── shared/
│   ├── currency.util.ts       # Währungssymbol: bevorzugt StockResult.currency, Fallback auf Ticker-Suffix-Heuristik
│   └── elliott-chart.util.ts  # Bars/Swings → lightweight-charts-Datenformate (von Thumbnail + Modal genutzt)
└── app.component.ts          # Root: State (Signals) + Koordination
```

**StockResult (aktuelles Datenmodell):**

```typescript
export interface StockResult {
  ticker: string;
  name: string | null;
  /** ISO-4217-Code (z.B. "USD", "EUR"), direkt vom Daten-Service übernommen (siehe 3.3/3.4). null falls nicht geliefert. */
  currency: string | null;
  current_price: number | null;
  trend_pct: number | null;
  trend_direction: 'bullish' | 'bearish' | null;
  elliott_wave: boolean;
  /**
   * Bars/Swings/Zielpreis für die Chart-Visualisierung ("Option C", 19.08.
   * umgesetzt) - null, falls ta4j kein Szenario findet, unabhängig von
   * elliott_wave (auch Zwischenstände werden angezeigt).
   */
  elliott_chart: ElliottChartData | null;
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

**Elliott-Wave-Badge-Farblogik entfernt (02.08., seit 20.08. auch im PDF-Export
konsistent):** Die frühere Farbunterscheidung (`elliott-confirmed` kräftig blau
bei `elliott_wave=true` vs. `elliott-progress` gedeckt grau bei reinem
Zwischenstand) wurde im Frontend entfernt - Rolfs Wunsch nach einheitlichem
Text (`#1a1f2e`, passend zur globalen Textfarbe aus `styles.scss`). Grund: seit
die Backend-Stage-Notation nur noch vollendete Wellen kompakt zeigt (`"A-B-"`),
fühlte sich die Farbunterscheidung redundant an. `elliottWaveBadgeClass()`
dadurch auf parameterlos vereinfacht. Der `matTooltip` (bestätigt vs.
Zwischenstand) blieb unverändert erhalten. **PDF-Export hatte diese
Vereinheitlichung zunächst nicht nachvollzogen** (zeigte noch grün/grau nach
`elliott_wave`) - am 20.08. beim vollständigen PDF-Konsistenz-Check gegen den
Screen gefunden und behoben (`.badge-elliott`, einheitlich).

**Elliott-Wave-Chart-Visualisierung ("Option C", umgesetzt 19.08.):** Neue
Spalte "Chart" in der Ergebnistabelle (`elliott-chart-thumbnail.component.ts`)
- kleine, achsenlose `lightweight-charts`-Instanz (Kerzen + blaue Zickzack-
Linie über den erkannten Swings), Klick öffnet `elliott-chart-modal.component.ts`
mit vollständigem Chart (Achsen, beschriftete Wellen-Marker A/B/C bzw. 1-5,
gestrichelte Kursziel-Linie). Bibliothek: `lightweight-charts` v4.2 (damit
entschieden - Option-C-Design-Fragen aus 3.2c sind erledigt). Backend liefert
dafür `elliott_chart` (Bars des Elliott-Lookback-Fensters + Swing-Punkte +
Kursziel) direkt im `StockResult`-JSON (`ElliottAnalysisUtil.buildChartData()`,
siehe 3.2b). TradingView-Attributions-Logo im Thumbnail via
`attributionLogo: false` ausgeblendet (zu klein für ein 92×32px-Thumbnail),
im großen Modal-Chart bewusst sichtbar gelassen (Lizenz-Attribution).
**Von Rolf im Praxistest bestätigt:** hilft sehr dabei, die von ta4j
gefundenen Wellen tatsächlich nachzuvollziehen. **Noch offen:** bei sehr
langen Ticker-Listen (DAX/Dow-Komplettläufe) noch nicht geprüft, ob viele
einzelne Thumbnail-Chart-Instanzen performant bleiben - Alternative bei
Bedarf: Sparkline-SVG statt echter Chart-Instanzen.

**Währungsanzeige (19./20.08.):** `currencySymbol()` (jetzt in
`shared/currency.util.ts`, von Tabelle UND PDF-Export genutzt) bevorzugt
`StockResult.currency` vom Daten-Service (ISO-4217, z.B. "EUR"/"USD") und
fällt nur noch auf die alte Ticker-Suffix-Heuristik (`.DE` → €, sonst $)
zurück, falls ein Daten-Service keine Währung liefert. Grund: frei
konfigurierbare Abruflisten können Werte aus verschiedenen Währungsräumen
mischen, die reine Suffix-Heuristik lag dabei potenziell falsch (siehe 3.3/3.4).

**Namens-Fallback für `twelvedata-service` (20.08.):** Da `twelvedata-service`
keinen Firmennamen liefert (siehe 3.4), baut `ticker-list-panel.component.ts`
beim Laden einer Liste eine `resolved-ticker → displayName`-Map aus den in der
Abrufliste gepflegten `TickerSymbol.displayName`-Werten. `app.component.ts`
wendet sie über `withFallbackName()` an, sobald ein SSE-Ergebnis ohne
"echten" Namen zurückkommt (kein Name ODER Name === Ticker) - überschreibt
nie einen bereits vorhandenen echten Namen vom Daten-Service (z.B. Yahoo
`longName`). Rein clientseitig, kein neues Feld im Backend-API-Vertrag nötig.

**PDF-Export-Konsistenz-Check (19./20.08.):** Vollständiger Abgleich
`pdf-export.service.ts` gegen `results-table.component.ts` ergab drei weitere
Abweichungen, alle behoben: (1) Preis nutzte im PDF immer `$` statt wie am
Screen `currencySymbol()` - jetzt vereinheitlicht (siehe oben); (2)
Spaltenüberschrift "Umkehrformation" widersprach sowohl dem Screen als auch
der PDF-eigenen Fußzeile, die schon "Candlestick Pattern" sagte - vereinheitlicht;
(3) eigene **Name-Spalte fehlte im PDF komplett** (nur Ticker/"Basiswert" war
vorhanden) - ergänzt, an derselben Position wie am Screen (direkt nach
"Basiswert"). Chart-Spalte im PDF ist reines SVG (`buildChartSvg()`), keine
eingebettete `lightweight-charts`-Instanz - Grund: das Druckfenster ist ein
separates `document.write()`-Dokument, in dem eine asynchron ladende
JS-Chart-Bibliothek nicht zuverlässig vor `window.print()` fertig würde.

**`lookbackDays`-Feld schreibgeschützt, Default 230 (23.08.):** War zuvor 90
und editierbar - beides irreführend. `lookbackDays` gated die Elliott-Wave-
Erkennung nicht (siehe 3.2b, `Math.max(lookback, elliottLookback)`), sondern
nur das Fenster der Trend%-Spalte. `filter-header.component.ts` setzt das
Feld daher jetzt `disabled` (Tooltip erklärt warum), `INTERVAL_LOOKBACK` in
`stock.models.ts` ist auf `ELLIOTT_LOOKBACK_BY_INTERVAL` im Backend
abgestimmt (`1d`→230, `4h`→180, `1h`→200).

**Bundle-Größe / Docker-Build (22./23.08.):** `lightweight-charts` wird in
`elliott-chart-thumbnail.component.ts` und `elliott-chart-modal.component.ts`
per `await import('lightweight-charts')` statt statischem Import geladen -
landet dadurch in einem separaten, lazy geladenen Chunk statt im initialen
Bundle (das `angular.json`-Budget von 1.00 MB musste dafür nicht angehoben
werden). Das `angular-client`-Dockerfile nutzt außerdem `npm install` statt
`npm ci` (Grund: `package-lock.json` kann in Claudes Sandbox mangels
Netzwerkzugriff nicht aktuell gehalten werden, `npm ci` bricht bei jeder
Abweichung hart ab - siehe 7 für den Trade-off).

**Ruhezustand während der Analyse (20.09.):** Schläft der Mac, friert Docker
Desktop alle Container ein - die laufende Analyse bricht ab (Software kann das
nicht umgehen). `WakeLockService` (Screen Wake Lock API) hält den Rechner
deshalb wach, solange `AnalysisService.streamAnalysis()` läuft (Freigabe im
Teardown: complete, error und Stop-Button). Grenzen: Der Browser gibt die
Sperre frei, sobald der Tab nicht sichtbar ist (anderer Tab, minimiert,
verdeckt) - beim Zurückkehren wird sie neu angefordert; Deckel zuklappen
schläft trotzdem; nur https/localhost. Ohne API-Unterstützung läuft die
Analyse wie bisher ohne Sperre.

**Einstellungen / VPN (21.09.):** Zahnrad im Header (`filter-header`) öffnet
ein Off-Canvas-Panel von rechts (`components/settings-panel`, immer im DOM, damit
ein laufender IP-Wechsel bei geschlossenem Panel weiterläuft). Es zeigt Status,
IP, Standort (ISO-Code → deutscher Name per `Intl.DisplayNames`) und Anbieter
(`VpnService` → `GET /vpn/info`, lädt beim Öffnen und per Aktualisieren-Button)
und darunter den Button "IP-Adresse ändern" (`POST /vpn/rotate`; gesperrt während
einer Analyse und während des Wechsels; ESC/Klick auf den Hintergrund schließt).

**Modell-Info zeigt Kalibrierungsstatus (21.09.):** `MlModelInfoComponent` zeigt
zusätzlich, ob das geladene Modell kalibriert ist (`calibrated`); bei `false`
(Modell vor dem 21.09. trainiert) erscheint ein Hinweis, dass ein neues
Training nötig ist, damit der Modellwert eine belastbare Größenordnung hat.

**KI-Signal erklären (21.09.):** Klick auf den KI-Wert in der Ergebnistabelle öffnet
`MlExplanationModalComponent`: Ausgangswert des Modells, die 8 wichtigsten Merkmale als
Balken (rechts = erhöht den Wert, links = senkt ihn, jeweils mit aktuellem Wert und
Prozentpunkten), Rest zusammengefasst, Endwert; dazu ein Einordnungssatz (typischer Wert
und tatsächlicher Anstiegsanteil je Zeitrahmen). Einstellungen → "KI-Modell"
(`MlModelInfoComponent` über `MlService` → `GET /ml/info`): Lernziel, Trainingsstand,
Metriken, Zusammensetzung von Training/Test je Zeitrahmen (Warnhinweis, wenn ein Zeitrahmen
nicht getestet wurde), Kalibrierung und wichtigste Merkmale. Der Spaltentooltip nennt
jetzt "5 Kerzen" statt "5 Tage".

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
  (Typ `CORRECTIVE`, Phase `CORRECTIVE_C`, Richtung bearish, Konfidenz ≥ `MIN_CONFIDENCE`.
  **`scenarioSet.hasStrongConsensus()`-Zusatzbedingung (03.–19.08. testweise aktiv)
  am 19.08. final wieder entfernt** - siehe unten)
- `BearishIndicator.checkElliottImpulseUp()` – erkennt vollständige 5-Wellen-Impulse
  aufwärts (Typ `IMPULSE`, Phase `WAVE5`, Richtung bullish, dieselbe Bedingung)
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
- **`scenarioSet.hasStrongConsensus()`-Gate: eingeführt 03.08., am 19.08. final
  wieder entfernt.** War kurzzeitig eine zusätzliche Bedingung für `genuine`
  (verlangte, dass sich die konkurrierenden Szenarien nicht deutlich
  widersprechen; `confidenceSpread()` zusätzlich fürs Debug-Log mitgeloggt).
  Grund für die Einführung: `confidence` allein kann irreführend sein (siehe
  "Confidence vs. Probability" unten). **Rolf hat nach mehr Praxisfällen
  entschieden, das Gate wieder zu entfernen** - hat sich in der Praxis nicht
  bewährt, zu viele echte Treffer wurden herausgefiltert. Endgültige
  Entscheidung, kein erneuter Versuch geplant. Beide Aufrufstellen
  (`BullishIndicator.checkElliottABC()`, `BearishIndicator.checkElliottImpulseUp()`)
  sowie die zugehörigen Konsens-/Spread-Logausgaben wieder entfernt.
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
  prefer."* Bestätigt die Entscheidung für Option C bei der Frontend-
  Visualisierung (umgesetzt, siehe 3.1).

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

**Live-Abruf + Write-back in die DB (1d seit 09.09., 1h/4h seit 20.09.):**
Alle Intervalle werden live von Yahoo/TwelveData abgerufen (4h/1h lasen
vorher nur aus der DB, `DbClient.fetchQuote()` bleibt als ungenutzter
DB-Fallback erhalten). Die abgerufenen Kerzen werden zusätzlich asynchron
(fire-and-forget, Fehler nur geloggt) in die DB zurückgeschrieben:
`DailyBarWriteBackService` (1d) und `IntradayBarWriteBackService` (1h/4h)
mit **derselben Regel**: neuesten Zeitstempel des Tickers in der jeweiligen
Tabelle ermitteln → 5 Handelstage zurückrechnen (`TradingDayUtil`, nur
Mo-Fr, ohne Feiertage) → alles Abgerufene ab dort schreiben. Der DB-Service
macht daraus einen Upsert (vorhandene Kerzen überschrieben, fehlende
ergänzt), Ticker ohne DB-Daten: alles schreiben. Der Write-back schließt die Lücke zwischen
letztem DB-Eintrag und jetzt (bei 4h/1h im Live-Test bestätigt: BA 4h,
7 neu + 11 aktualisiert). Ältere Lücken (mehr als 5 Handelstage vor dem
letzten DB-Eintrag) bleiben unberührt - die füllt nur der history-fetcher,
der parallel als Sicherheitsnetz aktiv bleibt.

| Quelle     | Ansicht | Abruf                    | Geschrieben in                              |
| ---------- | ------- | ------------------------ | ------------------------------------------- |
| Yahoo      | 1d      | 1d                       | `ohlcv_daily`                               |
| Yahoo      | 1h / 4h | 1h (kein natives 4h)     | `ohlcv_hourly` + aus 1h berechnet `ohlcv_4h` |
| TwelveData | 1d      | `1day`                   | `ohlcv_daily`                               |
| TwelveData | 1h      | `1h`                     | `ohlcv_hourly`                              |
| TwelveData | 4h      | `4h` (nativ)             | `ohlcv_4h`                                  |

Aus TwelveData-1h werden bewusst **keine** 4h-Kerzen berechnet (native
TwelveData-4h-Blöcke haben andere Grenzen, z.B. NYSE 09:30/13:30).
`IntradayBarUtil` (Paket `util`) bildet die Python-Logik des history-fetchers
1:1 nach (`_parse_bars_hourly` + `_aggregate_1h_to_4h`, mit identischem
Input gegen die Python-Referenz geprüft): Zeitstempel auf die ersten 19
Zeichen kürzen (lokale Börsenzeit ohne Zone, Leerzeichen → `T`), 4h-Blockstart
= Stunde auf 0/4/8/12/16/20 abgerundet, Blöcke mit < 2 Kerzen verworfen.
Die Normalisierung ist zugleich Voraussetzung für die Analyse:
`ElliottAnalysisUtil.parseBarDate()` kann Yahoo-Zeitstempel mit Offset
(`...+02:00`) nicht lesen. `outputsize` bei Yahoo ist auf 600 gedeckelt
(`YAHOO_HOURLY_MAX_OUTPUTSIZE`, Wert aus dem history-fetcher) - reicht für
180 4h-Kerzen bei Xetra-Werten (3 Blöcke/Tag), bei US-Werten (2/Tag) nur für
ca. 130. TwelveData-Free-Plan: der Live-Abruf verbraucht ab jetzt auch bei
4h/1h Credits (1 pro Ticker, 8 Requests/Minute).

**VPN-Steuerung (21.09.):** `VpnController`/`VpnService` vermitteln zwischen
Client und Gluetun (der Browser darf nicht direkt an Gluetun: CORS, Auth):
- `GET /vpn/info` → `{status, ip, city, region, country, organization, error}`
  (Status von Gluetun, IP/Standort von yahoo-service `/ip`). Die Gluetun-Antwort
  wird als Text gelesen und selbst geparst (WebClient dekodiert nur bei passendem
  Content-Type); ist der Status nicht abrufbar, steht der Grund im Feld `error`
  und als WARNING im Log.
- `POST /vpn/rotate` → `{oldIp, newIp, changed, attempts, error}`: Tunnel per
  `PUT /v1/vpn/status` stoppen (3 s Pause) und starten, dann alle 3 s die
  Ausgangs-IP abfragen (max. 90 s). Bleibt die IP gleich, bis zu 3 Versuche.
  Fehler stehen im Feld `error` (HTTP 200), nicht als HTTP-Fehler. Parallele
  Wechsel werden abgewiesen. Der Ablauf läuft per `cache()` unabhängig vom
  HTTP-Aufrufer zu Ende (Tab schließen darf das VPN nicht gestoppt
  zurücklassen); nach einem Fehler wird best effort `running` nachgeschoben.
- Konfiguration: `services.vpn-control-url` (Env `VPN_CONTROL_URL`, Default
  `http://vpn:8000`).
- Server-seitig gibt es keine Sperre gegen laufende Analysen - der Client sperrt
  den Button während einer Analyse (Yahoo-Abrufe würden sonst abbrechen).

**ML-Erklärung (21.09.):** `MlClient` übernimmt das Feld `explanation` des ml-service
(Modell `MlExplanation`, dieselben snake_case-Namen) und `AnalysisService.withMlSignal`
setzt es als `ml_explanation` ins `StockResult`; `null`, wenn der ml-service keine
liefert (ältere Modelle/Fehler). Neuer Endpunkt `GET /ml/info` (`MlController`) reicht
`/model/info` des ml-service durch, bei Ausfall `{model_ready:false, error}`.

### 3.2c Bekannte offene Punkte in agent-service-java

- **Uptrend-Peak-Erkennung** (`BullishIndicator.detectUptrendPeakWithCorrection`):
  strukturelle Alternative zur reinen Peak-Erkennung über das Fenster-Maximum,
  implementiert aber pausiert zugunsten der ta4j-Migration. Nicht in `evaluate()`
  verdrahtet.
- **ta4j-Szenario wird doppelt berechnet** (einmal in `BullishIndicator`, einmal in
  `BearishIndicator`) – Zusammenlegung möglich, aber noch nicht umgesetzt.
- **`hasStrongConsensus()`-Gate: erledigt (19.08.)** - final wieder entfernt,
  siehe 3.2b. Kein offener Punkt mehr.
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
- **Frontend-Elliott-Wave-Chart-Visualisierung ("Option C"): umgesetzt (19.08.),
  siehe 3.1.** Kein offener Punkt mehr, außer der unten genannten Performance-
  Frage bei sehr großen Listen.
- **Bullische UND bearische Candle-Pattern-Erkennung auf ta4j umgestellt
  (23./24.08.), AUSSER jeweils Abandoned Baby:**
  `BullishCandlePatterns.java` (Hammer, Morning Star, Bullish Engulfing,
  Piercing Line) und `BearishCandlePatterns.java` (Shooting Star, Bearish
  Engulfing, Dark Cloud Cover) nutzen jetzt ta4js Bausteine statt der alten
  `CandleUtils`-Eigenentwicklung. **Abandoned Baby bleibt in beiden Klassen
  unverändert Eigenentwicklung** - dafür gibt es keinen ta4j-Indikator
  (Priorität/Strength 5, weiterhin zuerst geprüft).
  **Wichtige Korrektur (23.08., nach Prüfung des echten ta4j-0.24.1-JARs -
  Rolf hatte es als `.jar` hochgeladen, javap fehlte im Sandbox-Container,
  daher eigener Konstantenpool-Parser gebaut):** Eine zunächst angenommene
  Rule-Injection in ta4js Candle-Indikator-Konstruktoren
  (`new HammerIndicator(series, downTrendRule)`) **existiert in 0.24.1
  NICHT** - alle Hammer-/MorningStar-/Piercing-/ShootingStar-/DarkCloud-
  Klassen binden ihre Trendprüfung weiterhin fest an einen internen,
  nicht austauschbaren ADX-basierten `DownTrendIndicator`/`UpTrendIndicator`
  (byte-identisch zu 0.22.7). Deshalb: Geometrie mit ta4js eigenen,
  wiederverwendbaren Bausteinen (`RealBodyIndicator`, `Bar`/`Num`)
  nachgebaut - identische Formeln/Default-Schwellwerte wie in ta4js
  Originalklassen, aber ohne deren eingebaute Trendprüfung. Jede einzelne
  dabei verwendete Methode/Konstruktor-Signatur wurde gegen den echten
  0.24.1-Bytecode verifiziert (nicht nur gegen die vendorte 0.22.7-Quelle
  geraten). Bullish/Bearish Engulfing haben in ta4j ohnehin **keine**
  eingebaute Trendprüfung - dort wird ta4js `BullishEngulfingIndicator`/
  `BearishEngulfingIndicator` unverändert für die reine Geometrie
  übernommen.
  **ta4j-Version auf 0.24.1 angehoben** (`pom.xml`, vorher 0.22.7).
  **GD200→GD50→GD20-Kaskade (24.08., Rolfs Wunsch) statt einer festen
  GD20-Prüfung:** jedes Muster wird zuerst gegen GD200 auf Trend geprüft,
  dann GD50, dann GD20 - der erste GD, der bestätigt, gewinnt (`gdPeriod`
  im Ergebnis). Bullish: Schlusskurs UNTER dem GD (Downtrend). Bearish:
  Schlusskurs ÜBER dem GD (Uptrend). Gemeinsame Kaskaden-/Chart-Logik in
  neuer Klasse `CandleGdCascade.java` ausgelagert (von beiden
  Pattern-Klassen genutzt, `boolean downtrend`-Parameter steuert die
  Vergleichsrichtung), um sie nicht doppelt zu pflegen.
  **Chart-Visualisierung im Frontend (24.08.):** neue Modelle
  `CandleChartData`/`CandleGdPoint` (Bars + GD-Linien-Werte + Datum der
  Muster-Kerze(n)), durchgereicht bis `StockResult.candle_chart`/
  `candle_gd_period`. Neue Angular-Komponenten
  `candle-pattern-chart-thumbnail`/`candle-pattern-chart-modal` (Struktur an
  die Elliott-Chart-Komponenten angelehnt) - neue Spalte "Muster-Chart" in
  der Ergebnistabelle, Klick öffnet Modal mit Kerzen + GD-Linie + markierter
  Muster-Kerze, Titel z.B. "Hammer unterhalb GD200" bzw. "Shooting Star
  oberhalb GD200" (Richtung wird aus dem Musternamen abgeleitet,
  `BEARISH_PATTERNS`-Set in `results-table.component.ts` - **beim ersten
  Entwurf war das Modal fest auf "unterhalb" verdrahtet, was für bearische
  Muster falsch war; noch in derselben Session korrigiert**). Candlestick-
  Pattern-Badge zeigt zusätzlich "(GD200)" - in Tabelle UND PDF (PDF nur als
  Text, keine Chart-Grafik dort - bewusst ausgelassen, siehe unten).
  **Tests:** `BullishCandlePatternsTest.java`/`BearishCandlePatternsTest.java`
  - Downtrend-/Uptrend-Präfixe auf ≥18-19 Kerzen verlängert
  (`decliningRun()`/`risingRun()`-Hilfsmethoden), da die SMA-Kaskade mehr
  Historie braucht als die alte, kürzere `hasDowntrendBefore()`/
  `hasUptrendBefore()`-Prüfung. Der `bar()`-Test-Helper musste von einem
  konstanten Fixdatum ("2025-01-01" für jede Kerze) auf fortlaufend
  eindeutige Daten umgestellt werden - `ElliottAnalysisUtil.toBarSeries()`
  (jetzt auch von beiden Pattern-Klassen genutzt, dafür **public** gemacht -
  ein erster Deploy-Versuch schlug fehl, weil nur die Methode, nicht aber
  die umgebende Klasse public war) braucht pro Kerze eine strikt
  aufsteigende `endTime`, was die alte `CandleUtils`-Logik nie interessiert
  hat. Piercing-Line- und Shooting-Star-/Dark-Cloud-Cover-/Bearish-
  Engulfing-Tests waren im ursprünglichen Test-Suite z.T. gar nicht bzw.
  ohne GD-Kompatibilität abgedeckt - ergänzt. Abandoned-Baby-Tests (beide
  Klassen) unverändert, kein GD-Bezug.
  **Piercing-/Dark-Cloud-Variantenwahl:** `PiercingLineIndicator` statt
  `PiercingIndicator`, `DarkCloudCoverIndicator` statt `DarkCloudIndicator`
  (jeweils neuer, @since 0.22.3, explizit konfigurierbare Gap-/Penetrations-
  Schwellen statt fest verdrahtet). ta4j liefert kein eingebautes
  Scoring/Priorisierung (nur `true`/`false` je Bar) - die "erstes Match
  gewinnt"-Kaskade mit Stärke-Ranking (`CandlePatternResult`) bleibt daher
  weiterhin selbst gebaut.
  **Noch offen:** GD-Kalibrierung (Periodenlänge, evtl. Steigungskriterium
  statt reinem Preisvergleich) an echten Marktdaten nicht validiert - Rolfs
  eigene Einschätzung dazu: "Die Praxis wird zeigen ob es tatsächlich
  funktioniert" (24.08.). PDF-Chart-Grafik für Candle-Patterns fehlt
  (analog zum Elliott-Chart-SVG wäre das ein eigener SVG-Renderer, aus
  Zeitgründen ausgelassen).
- **Build-Verifikation: erledigt (22.08.).** `mvn compile` (agent-service-java)
  und `npm install && ng build` (angular-client) von Rolf lokal erfolgreich
  durchlaufen. Kein offener Punkt mehr.
- **`angular-client`-Dockerfile: `npm ci` → `npm install` (23.08.).** Claude
  kann `package-lock.json` in seiner Sandbox nicht aktuell halten (kein
  Netzwerkzugriff dort), daher weicht sie nach jeder neuen npm-Dependency in
  `package.json` ab - `npm ci` bricht bei jeder Abweichung hart ab
  (`EUSAGE`-Fehler beim Docker-Build), `npm install` gleicht sie stattdessen
  automatisch ab. Trade-off: etwas weniger strikt reproduzierbar als
  `npm ci`. Alternative, falls das wichtiger wird als der Komfort: Rolf
  schickt nach einem lokalen `npm install` die aktualisierte
  `package-lock.json` zurück, dann kann bei `npm ci` geblieben werden.
- **`lightweight-charts` per dynamischem Import statt statisch importiert
  (23.08.):** In `elliott-chart-thumbnail.component.ts` und
  `elliott-chart-modal.component.ts` jetzt `await import('lightweight-charts')`
  statt `import ... from 'lightweight-charts'` (nur noch `import type` für
  Typen, keine Laufzeit-Bundle-Auswirkung). Grund: die Bibliothek landete
  sonst im initialen Bundle und riss das `angular.json`-Budget (1.05 MB statt
  1.00 MB); durch den dynamischen Import wird sie in einen separaten, erst
  bei tatsächlichem Chart-Rendering lazy geladenen Chunk ausgelagert - Budget
  konnte auf den ursprünglichen 1.00-MB-Wert zurückgesetzt werden. Nebenbei
  zwei vorbestehende `NG8011`-Content-Projection-Warnungen in
  `filter-header.component.ts` behoben (Icon+Text je in `<ng-container>`
  gewrappt, unabhängig von den Chart-Änderungen).
- **`lookbackDays`-Default im Client auf 230 synchronisiert und
  nicht-editierbar gemacht (23.08.):** War zuvor 90 (Altwert), obwohl
  `ELLIOTT_LOOKBACK_BY_INTERVAL["1d"]` im Backend längst 230 ist -
  **Erkenntnis dabei: der Client-Wert gated die Elliott-Wave-Erkennung
  ohnehin nicht** (`AnalysisService.analyzeFromSse()` bildet die tatsächlich
  abgerufene Bar-Anzahl über `Math.max(lookback, elliottLookback) + 40`, der
  Backend-eigene `elliottLookback` gewinnt also immer). Der Client-Wert
  steuerte bisher nur das Fenster der Trend%-Spalte
  (`AnalysisService.analyseQuote()`, `trendPct`). Um die Verwirrung zu
  vermeiden, die ein editierbares, aber wirkungsloses Feld erzeugt hätte, ist
  `lookbackDays` in `filter-header.component.ts` jetzt schreibgeschützt
  (`disabled`, mit erklärendem Tooltip) und wird nur noch programmatisch
  synchron zum jeweiligen Intervall gesetzt (`INTERVAL_LOOKBACK` in
  `stock.models.ts`: `1d`→230, `4h`→180, `1h`→200 - deckungsgleich mit
  `ELLIOTT_LOOKBACK_BY_INTERVAL` im Backend).
- **Thumbnail-Chart-Performance bei sehr langen Ticker-Listen (DAX/Dow) noch
  nicht geprüft:** jede Tabellenzeile mit `elliott_chart`-Daten bekommt eine
  eigene echte `lightweight-charts`-Instanz als Thumbnail - bei Komplettläufen
  eventueller Performance-Engpass, Alternative bei Bedarf: Sparkline-SVG.
- **Zeitstempel-Konvention der Intraday-Tabellen (20.09., per DB-Stichprobe
  bestätigt):** `ohlcv_hourly`/`ohlcv_4h` enthalten **lokale Börsenzeit ohne
  Zone**, nicht UTC (ADS.DE: letzte Stundenkerze 17:00, 4h-Blöcke
  08:00/12:00/16:00; AAPL: 09:30/14:30/15:30, 4h-Blöcke 09:30/13:30 nativ von
  TwelveData). Die falschen "UTC"-Kommentare in den Entities `OhlcvHourly`/
  `OhlcvFourHourly` und in den Docstrings von `data_client.py` (history-fetcher)
  sind am 20.09. korrigiert. In den Flyway-Migrationen `V4`/`V5` stehen sie
  weiterhin (Dateien NICHT ändern - Checksumme).
  **Kleiner Fehler im history-fetcher:** `fetcher.py` (Update-Lauf) berechnet
  die fehlenden Stunden/4h-Blöcke mit `datetime.utcnow()` minus dem letzten
  DB-Zeitstempel - der ist aber lokale Börsenzeit. Ergebnis: Xetra ca. 2 h zu
  wenig, NYSE ca. 4 h zu viel Lücke; wegen Puffer und Upsert harmlos, bei
  Gelegenheit auf die lokale Zeit der Börse umstellen.
  Der Write-back des agent-service-java folgt dem tatsächlichen Verhalten.
- **Unfertige Kerzen in der DB (20.09., Stichprobe):** Vor dem Upsert-Umbau
  blieben untertägig abgerufene, noch laufende Kerzen dauerhaft stehen (ADS.DE
  15:00-Stundenkerze mit Volumen 4.430, abgerufen 15:44; AAPL 09:30-Kerze
  mit 93.445 Volumen, abgerufen 15:49) - dadurch auch die daraus
  aggregierten 4h-Blöcke unvollständig. Werden durch den Upsert beim nächsten
  Write-back bzw. `/fetch/update` innerhalb der 5-Tage-Marge korrigiert.
  `fetched_at` bleibt beim Upsert unverändert (Erst-Schreibzeit).
- **Yahoo-Stundendaten-Tiefe:** `YAHOO_HOURLY_MAX_OUTPUTSIZE = 600` ist aus dem
  history-fetcher übernommen, nicht selbst gegen Yahoo ausgetestet.
- **Klären: Python-`agent-service` (Port 8010) noch aktiv oder durch
  `agent-service-java` ersetzt?**
- **`stock-data-db-access`: README/Doku für Java-25-Stand ergänzen**



### 3.3 Yahoo Service (`yahoo-service/`)

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

**Name + Währung im `TickerQuote` (19./20.08. ergänzt):** `longName` und
`currency` (ISO-4217) werden aus `Ticker.history_metadata` gelesen - das steht
nach dem ohnehin schon aufgerufenen `.history()` bereits im `yfinance`-Objekt,
**kein zusätzlicher Yahoo-Request** und damit kein zusätzliches Rate-Limit-
Risiko (das war zuvor der Grund, warum `TickerQuote` überhaupt nie einen
Namen mitschickte - `agent-service-java`s Fallback-Kette `longName` →
`shortName` → `name` war immer schon korrekt, lief nur ins Leere). `.get()`
statt Attributzugriff, da einzelne Felder je nach Instrument (z.B. Indizes)
fehlen können.

**Ausgangs-IP `GET /ip` (21.09.):** liefert `{ip, city, region, country,
organization}` der IP, unter der der Container im Internet erscheint (= VPN-IP,
Anbieter nacheinander: `ipinfo.io/json`, `ipwho.is`, `ipapi.co/json`, zuletzt
`api.ipify.org` nur mit IP - IPs von Gratis-VPN-Servern werden von einzelnen
IP-Datenbanken abgelehnt, HTTP 429; Fehlschläge stehen als WARNING im Log). `ip: null`,
wenn keine Verbindung besteht (Tunnel im Aufbau/gestoppt). Genutzt vom
agent-service-java für die VPN-Anzeige und das Warten auf den Tunnel nach dem
IP-Wechsel.

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

**Währung im `TickerQuote` (20.08. ergänzt), kein Firmenname:** `currency`
kommt kostenlos aus dem `meta`-Objekt, das die `time_series`-API ohnehin
mitliefert (kein zusätzlicher Request). **Einen Firmennamen liefert dieser
Endpunkt NICHT** - dafür bräuchte es einen separaten `/quote`-Aufruf pro
Ticker, der das ohnehin knappe Free-Plan-Kontingent verdoppeln würde (bewusst
NICHT eingebaut). Namens-Lücke wird stattdessen rein clientseitig über die
`displayName`-Werte der Abrufliste geschlossen (siehe 3.1, "Namens-Fallback
für `twelvedata-service`").

**Log-Hygiene API-Key (20.09.):** httpx loggt auf INFO jede Request-URL
inklusive `apikey=...`. `main.py` setzt den `httpx`-Logger daher auf WARNING
und schwärzt `apikey=` in Fehlermeldungen (`_redact`), da diese auch als
`error` im SSE-Event bis zum Client durchgereicht werden. Der Key stand vorher
im Klartext in den Logs (und im Chat) - nach diesem Leak den Key bei TwelveData neu erzeugen.

### 3.5 VPN Gateway (`vpn` / Gluetun)

- Image: `qmcgaw/gluetun`
- Provider: Proton VPN (WireGuard, Free-Server, Niederlande)
- Konfiguration: Root `.env` (WireGuard-Keys, Länder, Subnetz)
- Alias `yahoo-service` im `stock-net` → Port 8011 wird weitergeleitet
- IP-Wechsel: per Button im Client (Zahnrad → Einstellungen, siehe unten) oder
  manuell über den Steuerungs-Server / `docker exec vpn kill -HUP 1`
- **Steuerungs-Server (Port 8000, seit 21.09. in Gebrauch):** Aktuelle Gluetun-
  Versionen sperren alle Routen, solange keine Auth-Konfiguration existiert
  (Antwort "Unauthorized"). `gluetun-auth/config.toml` (Host, neben der
  docker-compose.yml) wird nach `/gluetun/auth/config.toml:ro` gemountet und gibt
  ohne Login nur diese Routen frei: `GET /v1/publicip/ip`, `GET /v1/vpn/status`,
  `PUT /v1/vpn/status` (`auth = "none"`, Port 8000 ist NICHT veröffentlicht,
  nur im Docker-Netz `stock-net` als `http://vpn:8000` erreichbar). **Niemals
  `/v1/vpn/settings` freigeben** - die Route liefert die WireGuard-Schlüssel.
  Nach Änderung an der Datei: `docker compose up -d --force-recreate vpn yahoo-service`
  (yahoo-service läuft im Netzwerk des vpn-Containers und muss mit neu erstellt werden).
- **IP-Wechsel-Mechanik (getestet 21.09.):** `PUT /v1/vpn/status` mit `stopped`, dann
  `running` verbindet zu einem (zufällig) gewählten Server des gefilterten Pools -
  Test: Server 185.107.56.49 → 89.38.99.72, Ausgangs-IP 185.107.56.50 → 89.38.99.79.
  Pool bei `FREE_ONLY=on`, `SERVER_COUNTRIES=Netherlands` ist klein, derselbe
  Server kann wiederkommen (daher bis zu 3 Versuche). **Gluetuns eigene
  Route `/v1/publicip/ip` bleibt nach einem Neustart leer** ("all fetchers failed"
  direkt nach dem Tunnelaufbau) - IP und Standort werden deshalb über
  `yahoo-service GET /ip` ermittelt. Ein Neustart des Tunnels über die API, der
  nie mit `running` abgeschlossen wird, lässt das VPN dauerhaft gestoppt
  (Killswitch, kein Yahoo-Abruf mehr) - `docker compose up -d --force-recreate
  vpn yahoo-service` holt es zurück.

- **Server-Pool (Stand 21.09., Quelle: gluetun-servers-Repo, Liste vom 06.08.2026):**
  Der Steuerungs-Server hat KEINE Route zum Auslesen der verfügbaren Länder/Server
  (nur `GET /v1/vpn/settings`, das die WireGuard-Schlüssel enthält - nicht
  freigeben) und Länder lassen sich zur Laufzeit nicht ändern (Doku kennt nur GET
  auf settings). Die Serverliste steckt in Gluetun selbst
  (`docker run --rm qmcgaw/gluetun format-servers -protonvpn`) bzw. im Repo
  `qdm12/gluetun-servers` (`pkg/servers/protonvpn.json`, Felder u.a. `free`, `ips`).
  Freie WireGuard-Server: 50 in 10 Ländern - USA 21, Kanada 7, Niederlande 4,
  Singapur 4, Japan 3, Norwegen 3, Rumänien 3, Schweiz 3, Mexiko 1, Polen 1.
  Die Niederlande-Liste enthält die zwei bisher beobachteten Endpunkte
  (185.107.56.49 = NL-FREE#125, 89.38.99.72 = NL-FREE#129) sowie
  149.34.244.174 und 185.132.134.35. Endpunkt-IP ≠ Ausgangs-IP (Beispiel:
  Endpunkt 89.38.99.72 → Ausgangs-IP 89.38.99.79); für Yahoo zählt die
  Ausgangs-IP, sie ist erst nach dem Verbinden bekannt (`yahoo-service /ip`).
  Länderwechsel nur über `SERVER_COUNTRIES` in der `.env` +
  `docker compose up -d --force-recreate vpn yahoo-service`.

### 3.6 DB Access Service (`stock-data-db-access/`)

| Eigenschaft  | Wert                              |
| ------------ | --------------------------------- |
| Sprache      | Java 25                           |
| Framework    | Spring Boot 4.1                   |
| Port         | 8013                              |
| Datenbank    | MySQL 9.7                         |
| Migrations   | Flyway (V1–V5)                    |

**Datenbanktabellen:**

| Tabelle         | Zweck                                          |
| --------------- | ---------------------------------------------- |
| `ticker_lists`  | Listen (DAX40, DOW30, INDIZES, INTERNATIONALE RTF'S) |
| `ticker_symbols`| Einzelne Ticker pro Liste (raw_symbol)         |
| `ticker_meta`   | Normalisierte API-Symbole + Stammdaten         |
| `ohlcv_daily`   | Tageskerzen (5 Jahre, ~1.250 pro Ticker)       |
| `ohlcv_hourly`  | Stundenkerzen (12 Monate, ~5.000 pro Ticker)   |
| `ohlcv_4h`      | 4h-Kerzen (Yahoo: aus 1h berechnet, TwelveData: nativ) |
| `fetch_log`     | Protokoll aller Datenabrufe                    |

**Wichtige Endpunkte (OHLCV):**

| Method | Path                              | Beschreibung                     |
| ------ | --------------------------------- | -------------------------------- |
| GET    | `/api/ohlcv/meta`                 | Alle Ticker-Metadaten            |
| GET    | `/api/ohlcv/daily/{ticker}/latest?n=90` | Neueste N Tageskerzen      |
| POST   | `/api/ohlcv/daily/bulk`           | Bulk-Upsert Tageskerzen (seit 09.09.) |
| POST   | `/api/ohlcv/hourly/bulk`          | Bulk-Upsert Stundenkerzen (seit 20.09.) |
| POST   | `/api/ohlcv/4h/bulk`              | Bulk-Upsert 4h-Kerzen (seit 20.09.) |
| GET    | `/api/ohlcv/tickers`              | Ticker mit tatsächlichen Daten + Zeilenzahl je Tabelle (seit 21.09., Basis der ML-Trainingsauswahl) |
| POST   | `/api/ohlcv/fetch-log`            | Abruf-Protokoll schreiben        |
| GET    | `/api/ohlcv/coverage`             | Datenbestand-Übersicht           |

### 3.7 History Fetcher (`history-fetcher/`)

| Eigenschaft  | Wert                              |
| ------------ | --------------------------------- |
| Sprache      | Python 3.12                       |
| Framework    | FastAPI + APScheduler             |
| Port         | 8014                              |
| Version      | 1.0.0 (fix: TwelveData interval)  |

**Verhalten (seit 20.09.: nur manuell):**
- **Läuft NICHT automatisch.** Standard `AUTO_RUN_ENABLED=false` (Env, `config.py`
  `auto_run_enabled`): kein täglicher Cron-Lauf, kein Catch-up beim Container-
  Start, kein Scheduler. Der Fetcher ist ein Reparaturwerkzeug: Aktuell
  gehalten werden aktiv genutzte Ticker durch den Write-back des
  agent-service-java (1d, 1h, 4h). Die frühere Variable `AUTO_INITIAL_RUN`
  wird nicht mehr gelesen (kann in der docker-compose.yml entfallen, ebenso
  `DAILY_UPDATE_HOUR/-MINUTE`).
- Manuell: `POST /fetch/update` holt fehlende Kerzen nach (pro Ticker ab dem
  letzten DB-Zeitstempel), `POST /fetch/initial` ruft alles neu ab
  (5 Jahre Tagesdaten + Stunden-/4h-Kerzen für alle 4 Listen) und
  **überschreibt vorhandene Kerzen** im abgerufenen Fenster (Upsert; nichts
  wird gelöscht). Läuft schon ein Abruf, antwortet der Fetcher mit "busy".
- **Listen dynamisch (seit 21.09.):** `_get_all_tickers()` holt die Codes ALLER
  angelegten Listen über `GET /api/lists` (vorher fest codiert:
  `DAX40, DOW30, INDIZES, INTERNATIONALE RTF'S` in `fetcher.py` - eine neue
  Liste brauchte bis dahin eine Codeänderung hier, um überhaupt eine
  Erstbefüllung zu bekommen).
- Optional: `AUTO_RUN_ENABLED=true` schaltet den täglichen Lauf (20:00,
  `misfire_grace_hours=12` gegen Host-Schlaf) und den Catch-up bei jedem
  Container-Start wieder ein.
- `GET /status` zeigt `auto_run_enabled`, `running` und den letzten Lauf.

**Endpunkte:**

| Method | Path              | Beschreibung                         |
| ------ | ----------------- | ------------------------------------ |
| GET    | `/health`         | Status + Scheduler-Info              |
| GET    | `/status`         | Letzter Lauf + nächster geplanter    |
| POST   | `/fetch/initial`  | Alles neu abrufen (überschreibt)     |
| POST   | `/fetch/update`   | Fehlende Kerzen nachholen (Reparatur)|
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

**Warum das KI-Signal bisher nur bei DAX40/DOW30 erschien (Befund + Fix, 21.09.):**
Zwei unabhängige Ursachen, beide behoben:
1. **Trainingsauswahl war an ticker_meta gekoppelt.** `ml-service` holte seine
   Trainings-Ticker über `GET /api/ohlcv/meta` (`ticker_meta`-Tabelle). Diese
   Tabelle pflegt AUSSCHLIESSLICH der history-fetcher beim Abruf für seine
   eigenen Listen - der Live-Write-back des agent-service-java (der für JEDEN
   analysierten Ticker Kerzen schreibt) hat `ticker_meta` nie berührt. Ein
   Ticker aus einer neuen, vom Fetcher nie gesehenen Liste bekam dadurch nie
   OHLCV-Daten für 210+ Zeilen und tauchte im Training nie auf - Vorhersagen
   selbst brauchen aber kein Training FÜR den Ticker (ein XGBoost-Modell ist
   nicht pro Ticker parametrisiert), sie scheiterten nur an fehlenden Daten.
   **Fix:** neuer Endpunkt `GET /api/ohlcv/tickers` in stock-data-db-access
   (zählt direkt in den OHLCV-Tabellen, unabhängig von ticker_meta/Listen);
   `ml-service` nutzt ihn jetzt für die Trainingsauswahl.
2. **history-fetcher kannte nur 4 fest eingetragene Listen-Codes**
   (`DAX40, DOW30, INDIZES, INTERNATIONALE RTF'S` in `fetcher.py`) - eine neu
   angelegte Liste bekam nie eine vollständige Backfüllung über
   `/fetch/initial`, sondern musste sich rein über den Live-Write-back
   (Cutoff 5 Handelstage) langsam aufbauen. **Fix:** `_get_all_tickers()` holt
   die Codes jetzt dynamisch über `GET /api/lists` (neue Funktion
   `get_all_list_codes()` in `db_client.py`) - jede vorhandene Liste,
   einschließlich neu erstellter, wird von `/fetch/initial`/`/fetch/update`
   erfasst. Der Fetcher bleibt weiterhin nur manuell startbar (siehe oben);
   für eine neue Liste empfiehlt sich einmal `POST /fetch/initial`, statt auf
   die schrittweise Live-Befüllung zu warten.

Nach diesen beiden Fixes braucht eine neue Liste zwei Dinge, damit sie ein
KI-Signal bekommt: genug OHLCV-Zeilen in der DB (per `/fetch/initial` sofort,
sonst nach ein paar Live-Analysen) und ein Training NACH Punkt 1 (damit der
Ticker in `feature_importance`/Diagnose auftaucht - für die reine Vorhersage
reicht schon das bestehende Modell, sobald genug Zeilen da sind).

**Was das Modell tut (am Code geprüft, 21.09.):**
- Ein gemeinsames Modell für alle drei Zeitrahmen: Trainingsdaten aus 1d, 4h und 1h
  aller Ticker werden zusammengeführt, `interval_code` (0/1/2) ist eines der 38 Features.
- Label: 1, wenn der **höchste Schlusskurs der nächsten 5 Kerzen** mindestens 3 % über
  dem aktuellen Schlusskurs liegt (nicht: Kurs nach 5 Tagen). "5" zählt in Kerzen des
  jeweiligen Zeitrahmens (4h: 20 Stunden, 1h: 5 Stunden), die Schwelle ist überall 3 %.
  Der angezeigte Wert ist also die Einschätzung "Anstieg > 3 % in 5 Kerzen", unabhängig
  von Trend oder Wellenlage - "Umkehr" ist nur der Name.
- Features (38): Renditen 1/3/5/10/20, Volatilität 5/10/20, MACD (4), Stochastik (3),
  RSI (3), Bollinger (2), Volumen (3), Abstand zu GD 20/50/200 + zwei GD-Kreuzungen,
  Kerzenform (4), Abstand zu 252-Kerzen-Hoch/-Tief, ROC 10/20, Anteil Kerzen über
  GD 20/50, Zeitrahmen. Der im Docstring von `engineer.py` erwähnte Elliott-Score wird
  NICHT verwendet. Alle Zeitfenster zählen in Kerzen (bei 4h/1h nicht in Tagen).
- Zeitreihen-Split 80/20 (siehe Einschränkungen unten), XGBoost mit
  `scale_pos_weight = negativ/positiv`, 400 Bäume mit Early Stopping.

**Split, Early Stopping und Kalibrierung (überarbeitet 21.09., behebt die
zuvor dokumentierten Einschränkungen):**
1. **Chronologischer Split PRO Ticker und Zeitrahmen** (vorher: ein einziger
   80/20-Schnitt über die aneinandergehängte Verarbeitungsreihenfolge aller
   Ticker/Intervalle - die Testmenge bestand dadurch je nach Dict-Reihenfolge
   nur aus einem einzelnen Zeitrahmen, im Sandbox-Test ausschließlich 1h).
   Jetzt: jeder Ticker/Zeitrahmen wird für sich 70/10/20 in train/val/test
   geteilt (älterer Teil zuerst, kein Shufflen), erst danach werden alle
   train-, val- bzw. test-Teile zusammengeführt. So enthält jede der drei
   Mengen anteilig jeden Ticker und jeden Zeitrahmen.
2. **Early Stopping auf der Validierungsmenge**, nicht mehr auf der Testmenge
   - die berichteten Testmetriken sind dadurch nicht mehr durch die
   Modellgröße selbst beeinflusst.
3. **Isotonische Kalibrierung auf der Validierungsmenge**
   (`sklearn.isotonic.IsotonicRegression`): bildet den rohen, durch
   `scale_pos_weight` verzerrten Modellwert monoton auf die tatsächlich
   beobachtete Trefferquote ab. Sandbox-Test mit rein zufälligen (nicht
   vorhersagbaren) Kursdaten: der kalibrierte Modellwert pendelte sich nahe
   der tatsächlichen Grundrate ein (≈27 %), statt wie vorher systematisch
   angehoben zu sein (≈54 %) - das Modell "weiß", dass es nichts weiß, und
   der Wert zeigt das jetzt auch an. `/model/info` → `calibrated: true/false`
   zeigt an, ob ein Modell diese Kalibrierung hat; ältere, vor dem 21.09.
   trainierte Modelle liefern weiterhin unkalibrierte Werte, bis neu trainiert
   wird (Kalibrator-Datei `calibrator.joblib` fehlt dann einfach, Fallback ist
   die Identität - kein Fehler).
4. **Erklärung (`explanation`) rechnet jetzt mit denselben kalibrierten
   Werten**: Basiswert und Modellwert werden je einzeln kalibriert, die
   Verschiebung der einzelnen Merkmale wird proportional zur kalibrierten
   Gesamtverschiebung verteilt - "Basiswert + Beiträge = angezeigter
   Modellwert" gilt dadurch weiterhin exakt (im Sandbox-Test geprüft,
   Abweichung nur Rundung, ≤0,1 Pp).
5. **Noch nicht umgesetzt (bewusst zurückgestellt):** getrennte Modelle pro
   Zeitrahmen statt eines gemeinsamen Modells mit `interval_code` als Feature.
   Ob sich das lohnt, zeigt sich erst mit echten (nicht synthetischen)
   Trainingsdaten über `breakdown`/`calibration` in `/model/info` - bei
   deutlich unterschiedlicher Kalibrierungsgüte je Zeitrahmen wäre das der
   nächste Schritt.

Nach dem Deployment dieser Änderung ist ein `POST /model/train` nötig, damit
das gespeicherte Modell die Kalibrierungsdatei bekommt - vorher meldet
`/model/info` `calibrated: false` und der Client zeigt einen entsprechenden
Hinweis.

**Rohstoff-/Devisenpaare (`XAU/USD` u.ä.) über TwelveData (23.09.):** Zwei
gegensätzliche Zwänge - Twelve Data verlangt für Rohstoffe/Devisen zwingend
einen Schrägstrich im Symbol (`XAU/USD`, ein Symbol ohne Schrägstrich wie
`XAUUSD` kennt die API nicht), unsere eigene stock-data-db-access-API lehnt
einen Schrägstrich im URL-Pfad dagegen ab (Tomcat, siehe unten). **Lösung:**
Solche Ticker werden intern mit Bindestrich geführt - **in der Ticker-Liste
also `XAU-USD` eintragen, Quelle TwelveData** - und `twelvedata-service`
übersetzt beim Abruf über `SYMBOL_ALIASES` in main.py auf das
Twelve-Data-Symbol mit Schrägstrich. Der Ticker in der Antwort (und damit in
der DB) bleibt die Bindestrich-Schreibweise. Hinterlegt: Gold (`XAU-USD`),
Silber (`XAG-USD`), Platin (`XPT-USD`), Palladium (`XPD-USD`) - bei weiteren
Paaren die Map in `SYMBOL_ALIASES` ergänzen. Bewusst keine generische
Bindestrich→Schrägstrich-Umwandlung, weil das Ticker mit echtem Bindestrich
(z.B. Aktien-Gattungen wie `BRK-B`) verfälschen würde.

**Bug gefunden beim ersten Live-Training nach der Umstellung (22.09.): Index-
Ticker scheiterten mit HTTP 400.** Sobald das Training wirklich alle Ticker mit
Daten zieht (siehe oben), sind auch die INDIZES-Ticker (`^DJI`, `^GDAXI`,
`^GSPC`, `^NDX`) dabei. `db_client.py` (ml-service UND history-fetcher) baute
die GET-URLs bisher per f-String mit dem rohen Ticker im Pfad
(`f"{BASE}/api/ohlcv/daily/{ticker}"`) - Tomcat lehnt ein `^` im Pfad schon auf
Verbindungsebene ab (bevor Spring überhaupt routet), daher der 400 ohne
aussagekräftigen Body. **Fix:** `urllib.parse.quote(ticker, safe="")` an jeder
Stelle, an der ein Ticker in einen URL-Pfad eingesetzt wird - betrifft alle
GET-`/latest`- und GET-ohne-Zeitraum-Aufrufe in beiden Services.
agent-service-java war nicht betroffen: `WebClient.uri(template, ticker)` mit
Template-Variable kodiert automatisch. Die im Trainings-Log sichtbaren
"Zu wenig Samples"-Meldungen für andere Ticker (z.B. `QBTS`, `G24.DE`,
`OM3L.DE`) sind dagegen kein Bug, sondern bedeuten schlicht: dieser Ticker hat
noch nicht genug Kurshistorie in der DB - je nach Ticker per `/fetch/initial`
oder weiteren Live-Analysen behebbar.

**Erklärung je Vorhersage (`explanation`, seit 21.09.):** XGBoost `pred_contribs`
(nur Bäume bis `best_iteration`, sonst passt die Summe nicht zu `predict_proba`)
liefert je Merkmal einen Beitrag; er wird proportional in Prozentpunkte umgerechnet,
sodass `base_pct + Σ effect_pp + other_pp = prob_pct` gilt (getestet, Abweichung nur
Rundung). Die 8 stärksten Merkmale stehen einzeln (`factors`: technischer Name,
deutsches Label aus `features/labels.py`, Rohwert, formatierter Wert, Einfluss), der
Rest als `other_pp`. Dazu Einordnung `typical_score_pct` (Ø Modellwert im Training für
diesen Zeitrahmen) und `actual_rate_pct` (tatsächlicher Anstiegsanteil) - beides erst
bei Modellen, die nach dem 21.09. trainiert wurden. Neue Diagnosefelder in
`model_meta.json`: `scale_pos_weight`, `breakdown` (je Zeitrahmen Samples, Anstiegsanteil,
Ø Modellwert, Testmetriken), `calibration` (10 Bins Modellwert vs. Trefferquote),
`feature_importance_all`.

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
      testweise ergänzt (03.08.) und **nach Praxiserfahrung final wieder
      entfernt (19.08.)** - hat sich nicht bewährt, siehe 3.2b
- [x] Elliott-Wave-Badge-Farblogik im Frontend entfernt (einheitliches Schwarz
      statt blau/grau) - seit 20.08. auch im PDF-Export konsistent
- [x] **"Option C": Elliott-Wave-Chart-Thumbnail + Modal im Angular-Frontend
      (19.08.)** - Backend liefert `elliott_chart` (Bars+Swings+Ziel) im
      `StockResult`-JSON, Frontend rendert mit `lightweight-charts` v4.2
      (Thumbnail in eigener Spalte, Klick → Modal mit beschrifteten
      Wellen-Markern + Kursziel-Linie). Von Rolf im Praxistest bestätigt.
- [x] PDF-Export-Konsistenz-Check gegen Screen (19./20.08.): Chart-Spalte
      ergänzt (statisches SVG), Name-Spalte ergänzt (fehlte komplett),
      Währungssymbol korrigiert, Spaltenüberschrift "Candlestick Pattern"
      vereinheitlicht
- [x] Echte Währung statt Ticker-Suffix-Heuristik (19./20.08.): `yahoo-service`
      und `twelvedata-service` liefern jetzt beide `currency` (ISO-4217, ohne
      zusätzlichen API-Call), durchgereicht bis in Tabelle + PDF
      (`shared/currency.util.ts`)
- [x] `yahoo-service` liefert jetzt `longName` (aus `history_metadata`, kein
      zusätzlicher Request) - behebt fehlende Firmennamen bei Yahoo-Quotes
- [x] Namens-Fallback für `twelvedata-service` über `displayName` der
      Abrufliste (20.08., clientseitig, siehe 3.1) - TwelveData liefert selbst
      keinen Firmennamen
- [x] Build-Verifikation: `mvn compile` + `npm install && ng build` lokal von
      Rolf erfolgreich durchlaufen (22.08.)
- [x] `angular-client`-Dockerfile: `npm ci` → `npm install` (23.08.) - behebt
      `EUSAGE`-Docker-Build-Abbruch durch veraltete `package-lock.json`
- [x] `lightweight-charts` per dynamischem Import statt statisch importiert
      (23.08.) - Bundle-Budget-Warnung behoben, ohne das Budget selbst
      anzuheben; zwei vorbestehende `NG8011`-Warnungen in
      `filter-header.component.ts` nebenbei behoben
- [x] `lookbackDays`-Default im Client auf 230 synchronisiert und
      nicht-editierbar gemacht (23.08.) - Erkenntnis dabei: der Wert gated
      die Elliott-Wave-Erkennung ohnehin nicht (Backend-`Math.max`-Logik)
- [ ] ta4j-Doppelberechnung (Bullish/Bearish) zusammenlegen
- [ ] `RUNNER_MIN_CONFIDENCE`/`MIN_CONFIDENCE`/`HIGHER_DEGREES`/`LOWER_DEGREES`/
      `ELLIOTT_LOOKBACK` an mehreren Tickern (nicht nur DIS/IFX) nachkalibrieren
- [ ] `describeTarget()`-Retracement-Prozentzahl an mehr Fällen (A/B/C, 1-2-3-4-5)
      validieren - Referenzwelle evtl. wellenpositionsabhängig anzupassen
- [ ] Elliott-Lookback/Outputsize-Einheiten sauber trennen (Kalendertage vs.
      Bar-Anzahl aktuell vermischt)
- [ ] Praxisbeobachtung untersuchen: kleinteilige Aufwärtsbewegung nach langem
      Abwärtstrend wird manchmal als Impuls erkannt (Rolf, 03.08., noch nicht
      eingegrenzt)
- [ ] **Praxistest der Elliott-Wellen-Erkennung läuft (Rolf, ab 19.08.)** -
      prüft anhand des neuen Charts, ob die von ta4j gefundenen Wellen
      plausibel sind
- [x] Bullische UND bearische Candle-Pattern-Erkennung auf ta4j umgestellt
      (23./24.08., außer jeweils Abandoned Baby) - Geometrie aus ta4js
      RealBodyIndicator/Bar/Num nachgebaut (KEINE Rule-Injection, siehe
      Korrektur in 3.2c), GD200→GD50→GD20-Kaskade als Trend-Regel
      (`CandleGdCascade.java`), plus Chart-Visualisierung im Frontend
      ("Muster-Chart"-Spalte). Von Rolf lokal als `mvn compile`-fähig
      bestätigt (24.08., nach einem Fix: `ElliottAnalysisUtil` musste
      `public` sein, nicht nur `toBarSeries()`)
- [ ] GD-Kalibrierung (Periodenlänge, evtl. Steigungskriterium statt reinem
      Preisvergleich) an echten Marktdaten noch nicht validiert - Rolfs
      eigene Einschätzung: "Die Praxis wird zeigen ob es tatsächlich
      funktioniert" (24.08.)
- [ ] PDF-Chart-Grafik für Candle-Patterns fehlt (nur Text "(GD200)" im
      Badge, kein SVG wie beim Elliott-Chart)
- [ ] Thumbnail-Chart-Performance bei sehr langen Ticker-Listen (DAX/Dow) noch
      nicht geprüft
- [ ] Klären: Python-`agent-service` (Port 8010) noch aktiv oder durch
      `agent-service-java` ersetzt?
- [ ] `stock-data-db-access`: README/Doku für Java-25-Stand ergänzen

**Zuletzt geändert:** 2026-08-24
**Zuletzt bearbeitet von Claude:** Aufbauend auf dem 19./20.08.-Stand (siehe
Roadmap oben für Details zu Option C, Currency-/Name-Rollout) folgten drei
weitere Sessions rund um die lokale Build-Verifikation. Rolf hat `mvn compile`
und `npm install && ng build` erfolgreich lokal ausgeführt (22.08.) - beide
grün, nur eine Bundle-Budget-Warnung (`lightweight-charts` ließ das initiale
Bundle auf 1.05 MB wachsen). Statt das Budget nur hochzusetzen, wurde
`lightweight-charts` in beiden Chart-Komponenten auf dynamischen Import
umgestellt (Code-Splitting in einen lazy-geladenen Chunk) - Budget konnte
wieder auf 1.00 MB zurück. Danach schlug `docker compose up --build` mit
`npm ci`/`EUSAGE` fehl, da `package-lock.json` seit dem Hinzufügen von
`lightweight-charts` strukturell nicht mehr synchron zu `package.json`
gehalten werden kann (Claudes Sandbox hat keinen Netzwerkzugriff für
`npm install`) - Dockerfile daher auf `npm install` umgestellt. Danach fiel
auf, dass der Client-Default `lookbackDays=90` nicht mehr zum
Backend-`ELLIOTT_LOOKBACK_BY_INTERVAL["1d"]=230` passte; bei der Analyse
stellte sich heraus, dass dieser Client-Wert die Elliott-Wave-Erkennung
ohnehin nie beeinflusst hat (nur die Trend%-Spalte) - Default trotzdem auf
230 synchronisiert und das Feld zur Vermeidung von Verwirrung
schreibgeschützt gemacht.

Ab 23.08. dann die Candle-Pattern-Umstellung: Rolf griff die zuvor
zurückgestellte Idee wieder auf. Claude baute zunächst die Kerzen-Geometrie
von Hand mit ta4js Rule-losen 0.22.7-Bausteinen nach (RealBodyIndicator +
eigener Gd20DownTrendIndicator) - Rolf verwies auf ein Rule-Injection-Beispiel
(`new HammerIndicator(series, downTrendRule)`), woraufhin Claude komplett
darauf umbaute, ohne es verifizieren zu können (kein 0.24.1-Quellcode
verfügbar). **Rolf lud daraufhin die echte `ta4j-core-0.24.1.jar` hoch** -
die Prüfung (eigener Konstantenpool-Parser gebaut, da `javap` im
Sandbox-Container fehlte) widerlegte die Rule-Injection-Annahme eindeutig:
alle betroffenen ta4j-Klassen binden ihre Trendprüfung weiterhin fest an
ADX/UpTrend, byte-identisch zu 0.22.7. Claude baute daraufhin auf den
ursprünglichen, jetzt gegen den echten Bytecode verifizierten Ansatz zurück.
Danach zwei direkte Iterationen auf Rolfs Anfrage: (1) die feste GD20-Prüfung
durch eine GD200→GD50→GD20-Kaskade ersetzt, inkl. neuer Chart-Visualisierung
im Client ("Muster-Chart"-Spalte, z.B. "Hammer unterhalb GD200"); (2) dieselbe
Umstellung für `BearishCandlePatterns` übertragen (Shooting Star, Bearish
Engulfing, Dark Cloud Cover), inkl. eines dabei entdeckten und noch in
derselben Session behobenen Richtungs-Bugs (Modal/Tooltip zeigten fest
"unterhalb GD", was für bearische Muster falsch ist - jetzt musterabhängig
"unterhalb"/"oberhalb"). Ein erster Docker-Build schlug fehl
(`ElliottAnalysisUtil is not public` - nur die genutzte Methode, nicht die
Klasse selbst war public gemacht worden), von Rolf gemeldet und sofort
behoben. **`docker compose up -d --build agent-service-java` läuft seitdem
bei Rolf durch.** GD-Kalibrierung an echten Marktdaten steht noch aus.


---

## 7. Wichtige Hinweise für Claude

- **Bestehende API-Verträge nicht brechen** – das SSE-Format (`event: result / done`) ist fix
- **Angular Material + Tailwind** – beide im Einsatz; `preflight: false` in Tailwind um Konflikte zu vermeiden
- **Prebuilt Material Theme** – `indigo-pink.css` eingebunden via `angular.json styles`
- **Kein `ngModule`** – ausschließlich Standalone Components
- **PDF-Export** – immer Browser-Print, kein jsPDF oder Server-seitiges PDF. Chart-Spalte im PDF ist reines SVG (`buildChartSvg()` in `pdf-export.service.ts`), NICHT `lightweight-charts` einbetten - Druckfenster ist ein separates `document.write()`-Dokument, in dem eine asynchron ladende JS-Chart-Bibliothek nicht zuverlässig vor `window.print()` fertig würde.
- **Preis-Währung** – immer `StockResult.currency` (ISO-4217 vom Daten-Service) nutzen, nie den Ticker raten (`shared/currency.util.ts`). Abruflisten können Werte aus verschiedenen Währungsräumen mischen.
- **Bei neuen Feldern in `StockResult`/`TickerQuote`**: Änderung betrifft potenziell bis zu vier Repos (`yahoo-service`/`twelvedata-service` → `agent-service-java` → `angular-client`) - alle Konstruktor-/Builder-Aufrufstellen prüfen, nicht nur den Haupt-Erfolgspfad (siehe Currency-/Name-Rollout 19./20.08. als Beispiel: `DbClient.java` hatte z.B. direkte `TickerQuote`-Konstruktoraufrufe, die leicht übersehen werden).
- **Neue npm-Dependencies in `angular-client`**: Claude kann `package-lock.json` in seiner Sandbox nicht aktuell halten (kein Netzwerkzugriff dort). Deshalb nutzt das Dockerfile bewusst `npm install` statt `npm ci` (23.08.) - sonst bricht der Docker-Build mit `EUSAGE` ab, sobald `package.json` und `package-lock.json` auseinanderlaufen. Nicht versehentlich zurück auf `npm ci` wechseln, ohne dass Rolf eine lokal aktualisierte `package-lock.json` beisteuert.
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

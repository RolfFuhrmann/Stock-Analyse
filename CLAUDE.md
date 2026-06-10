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
| Sprache      | Java 21                           |
| Framework    | Spring Boot 3                     |
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

**Zuletzt geändert:** 2026-06-10
**Zuletzt bearbeitet von Claude:** ML-Pipeline vollständig integriert. History-Fetcher (TwelveData interval-Fix + alle 4 Listen). ML-Service (XGBoost, 38 Features, wöchentliches Retraining). Agent Service v4.0.0 (ML-Signal via `_fetch_ml_signal()`). Angular Client (KI-Signal Spalte + PDF-Export). Dokumentation komplett aktualisiert.

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

### ML-Signal Interpretation

| Kombination                          | Bedeutung                                            |
| ------------------------------------ | ---------------------------------------------------- |
| `bearish` + ML-Signal stark (>75%)   | Abwärtstrend + KI sieht Umkehrchance → Fibonacci!   |
| `bullish` + ML-Signal keins (<40%)   | Laufender Aufwärtstrend, keine Wende erwartet        |
| `bullish` + ML-Signal mittel/stark   | Widerspruch → besonders genau prüfen                 |

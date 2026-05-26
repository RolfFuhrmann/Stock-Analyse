# CLAUDE.md – Stock Platform Projektdokumentation

> Diese Datei am Anfang jeder Claude-Session hochladen.
> Claude hat kein Gedächtnis zwischen Sessions – diese Datei ist der vollständige Kontext.

---

## 1. Projektübersicht

**Name:** Stock Platform
**Zweck:** Analyse von Aktien anhand technischer Kriterien (Elliott Wave, Stochastik, MACD-Histogramm).
Nutzer wählen Ticker, Datenquelle und Zeitraum – der Agent analysiert und streamt die Ergebnisse in Echtzeit an den Angular-Client.

**Status:** In Entwicklung

---

## 2. Architektur

```
┌──────────────────────────────────────────────────────────┐
│                   Docker Network (stock-net)              │
│                                                          │
│  ┌──────────────────┐   SSE-Stream                       │
│  │  Angular Client  │◄─────────────────────────────┐     │
│  │  (Port 4200/80)  │                              │     │
│  └──────────────────┘                              │     │
│                                                    │     │
│  ┌──────────────────┐   POST /analyze/stream        │     │
│  │  Agent Service   │────────────────────────────→─┘     │
│  │  (Port 8010)     │                                    │
│  └──────┬──────┬────┘                                    │
│         │      │                                         │
│    yahoo│      │twelvedata                               │
│         ▼      ▼                                         │
│  ┌────────────────────┐   ┌──────────────────────┐       │
│  │  VPN Gateway       │   │  TwelveData Service  │       │
│  │  (Gluetun/Port8011)│   │  (Port 8012)         │       │
│  │  alias:yahoo-svc   │   │  .env: API-Key       │       │
│  └────────┬───────────┘   └──────────────────────┘       │
│           │  network_mode: container:vpn                  │
│           ▼                                              │
│  ┌──────────────────┐                                    │
│  │  Yahoo Service   │  (kein eigener Port/Netzwerk)      │
│  │  Läuft im VPN-   │                                    │
│  │  Container-Netz  │                                    │
│  └──────────────────┘                                    │
└──────────────────────────────────────────────────────────┘
```

**VPN-Routing:**

- `yahoo-service` hat `network_mode: "container:vpn"` – teilt Netzwerk-Namespace mit Gluetun
- Alle Yahoo-Anfragen gehen durch den WireGuard-Tunnel (Proton VPN, Standard: Niederlande)
- Der VPN-Container erhält den Alias `yahoo-service` im `stock-net` und leitet Port 8011 weiter
- `agent-service` spricht Yahoo über `http://yahoo-service:8011` (= VPN-Gateway)

**Kommunikation:**

- Client → Agent: `POST /analyze/stream` (JSON Body)
- Agent → Client: Server-Sent Events (SSE), Event-Typen: `result`, `done`

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
│   └── empty-state/        # Platzhalter ohne Daten
├── models/
│   └── stock.models.ts     # StockResult, FilterState, AnalysisSummary, DataSource
├── services/
│   ├── analysis.service.ts # SSE-Streaming
│   └── pdf-export.service.ts # PDF via Browser-Print
└── app.component.ts        # Root: State (Signals) + Koordination
```

**Vordefinierte Ticker-Presets:**

- DAX 40: 32 Ticker (ADS, AIR, ALV, … ZAL)
- Dow Jones: 30 Ticker (AAPL, AMGN, … AMZN – WBA entfernt)

**Features:**

- SSE-Streaming mit Echtzeit-Fortschrittsanzeige
- Stop-Button bricht laufenden Datenabruf ab (bereits geladene Ergebnisse bleiben erhalten)
- PDF-Export via Browser-Druckdialog (kein jsPDF)
- PDF-Button aktiviert sich erst wenn alle Daten vorliegen (`loading === false && results.length > 0`)

### 3.2 Agent Service (`agent-service/`)

| Eigenschaft  | Wert                       |
| ------------ | -------------------------- |
| Sprache      | Python 3.12                |
| Framework    | FastAPI + sse-starlette    |
| Port         | 8010                       |
| Datenquellen | Yahoo Finance, Twelve Data |

**Endpunkte:**

| Method | Path              | Beschreibung               |
| ------ | ----------------- | -------------------------- |
| POST   | `/analyze/stream` | Startet SSE-Analyse-Stream |

**Request Body (`POST /analyze/stream`):**

```json
{
  "tickers": ["AAPL", "MSFT"],
  "source": "yahoo",
  "lookback_days": 90
}
```

**SSE Response Format:**

```
event: result
data: {"ticker":"AAPL","current_price":182.5,"trend_pct":3.2,"elliott_wave":true,"stochastic":false,"macd_histogram":true,"criteria_met":2,"source":"yahoo","error":null}

event: result
data: {...}

event: done
data: {}
```

**Lokale Tests (ohne Docker):**

```bash
cd agent-service
pytest tests/ -v --tb=short
# Mit Coverage-Report:
pytest tests/ --cov=candle_patterns --cov=trend_indicators --cov-report=term-missing
```

Die Testdateien enthalten `sys.path.insert(0, ...)` – kein `pip install -e .` nötig.

### 3.3 Yahoo Service (`yahoo-service/`)

| Eigenschaft   | Wert                                             |
| ------------- | ------------------------------------------------ |
| Sprache       | Python 3.12                                      |
| Framework     | FastAPI + sse-starlette                          |
| Port (intern) | 8011 (erreichbar nur über VPN-Gateway-Alias)     |
| Netzwerk      | `network_mode: "container:vpn"` – kein stock-net |
| TLS-Tarnung   | `curl_cffi` mit `impersonate="chrome"`           |

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
| Konfiguration | `twelvedata-service/.env` (API-Key) |

**Rate-Limit:** Free Plan max 8 Requests/Minute → fixer Delay von 7.5s zwischen Tickern.

### 3.5 VPN Gateway (`vpn` / Gluetun)

- Image: `qmcgaw/gluetun`
- Provider: Proton VPN (WireGuard, Free-Server, Niederlande)
- Konfiguration: Root `.env` (WireGuard-Keys, Länder, Subnetz)
- Alias `yahoo-service` im `stock-net` → Port 8011 wird weitergeleitet
- IP-Wechsel: `docker exec vpn kill -HUP 1`

---

## 4. Datenmodelle (TypeScript)

```typescript
export type DataSource = 'yahoo' | 'twelvedata';

export interface StockResult {
  ticker: string;
  current_price: number | null;
  trend_pct: number | null;
  elliott_wave: boolean;
  stochastic: boolean;
  macd_histogram: boolean;
  criteria_met: number; // 0, 1, 2 oder 3
  source: string;
  error: string | null;
}

export interface FilterState {
  source: DataSource;
  tickers: string[];
  lookbackDays: number;
}

export interface AnalysisSummary {
  total: number;
  count3of3: number;
  count2of3: number;
  source: DataSource;
}
```

---

## 5. Docker & Lokale Ausführung

### Vollständiger Start (mit VPN)

Voraussetzung: Root `.env` mit WireGuard-Zugangsdaten und `twelvedata-service/.env` mit API-Key.

```bash
docker compose up -d --build
```

### Einzelner Client-Start

```bash
cd angular-client
docker build -t stock-client .
docker run -p 4200:80 --name stock-client stock-client
```

### Nur Client aus docker-compose (ohne Backend)

```bash
docker compose up --build --no-deps angular-client
```

### Lokale Agent-Tests (ohne Docker)

```bash
cd agent-service
pytest tests/ -v --tb=short
# Mit Coverage-Report (Ziel: ≥ 80%):
pytest tests/ --cov=candle_patterns --cov=trend_indicators --cov-report=term-missing
```

### Hinweis Plattform

Alle Dockerfiles enthalten `--platform=linux/arm64` (Apple Silicon / Mac M-Series).
Für x86-Server/Linux-VM die `--platform`-Angabe entfernen.

---

## 6. Coding-Konventionen

- **Sprache im Code:** Englisch (Variablen, Methoden, Interfaces)
- **Kommentare:** Deutsch (erklären das _Warum_, nicht das _Was_)
- **Architektur:** Smart/Dumb Components – `AppComponent` hält State, Kindkomponenten nur Inputs/Outputs
- **State-Management:** Angular Signals (`signal`, `computed`) – kein RxJS Subject/BehaviorSubject für UI-State
- **Kein `any`** – strikte TypeScript-Typisierung durchgehend
- **Single Responsibility** – eine Komponente = eine Aufgabe
- **Clean Code:** Lesbarkeit vor Cleverness, keine verschachtelten Einzeiler

---

## 7. Offene Punkte / Roadmap

> Diese Sektion bitte nach jeder Session aktualisieren.

- [ ] ~~`TODO: Erstelle einen DB-Access Service für MySQL97 die eigenständig im Docker läuft. Der Service soll alle Verwaltungsfunktionen zum anlegen, ändern und löschen für Listen (DAX, DOW Jones etc.) mit Tickersymbole abdecken. Dazu gehört auch das Datenmodell mit den entsprechenden Enititäten. Es soll auch berücksichtigt werden, das die Datenbeschaffung die Tickersybole anders aufbereitet werden müssen. Ich denke die speicherung des Börsenplatzes (XETRA, USA) sollte dem genüge tun.`~~

- [x] ~~`TODO: trend_indicators.py lockern. MACD unter der 0-Linie. Slow-Stochastik unter der 20-Linie.`~~ ✅ `macd_ok` prüft nur noch `is_negative`. `stoch_ok` prüft nur noch `k < 20`. `histogram_shrinking` und `k_rising` werden weiterhin berechnet, sind aber kein Pflichtkriterium.
- [x] ~~`TODO: Für candle_patterns.py jedes Pattern in eigenen Bereich kapseln. Gemeinsam genutzter Code in Utility auslagern.`~~ ✅ Jedes Muster in eigener Funktion (`_detect_abandoned_baby`, `_detect_morning_star`, `_detect_engulfing`, `_detect_piercing`, `_detect_hammer`). Hilfsfunktionen in `_CandleUtils` ausgelagert.
- [x] ~~`TODO: Prüfung für Bullish Abandoned Baby und Morning Star 4 Kerzen. Kerze 0 bis 1 definiert den Abwärtskontext. Kerze 2 definiert den Doji/Stern. Kerze 3 die Aufwärtsbewegung.`~~ ✅ Beide Muster nutzen `df.iloc[-4:]`. i0/i1 = Abwärtskontext (beide bearish, i1 schließt unter i0). i2 = Doji+Gap / Stern. i3 = bullische Umkehrkerze.
- [x] ~~`TODO: Prüfung für Piercing Line, Bullish Engulfing und Hammer 3 Kerzen. Kerze 0 bis 1 definiert den Abwärtskontext. Kerze 2 ist zur Erkennung des Musters.`~~ ✅ Alle drei Muster nutzen `df.iloc[-3:]`. i0/i1 = Abwärtskontext. i2 = Mustererkennung.
- [x] ~~`TODO: Im agent-service den indicators.py aufsplitten, so das der Bereich für die Trenderkennung (EW, Stochastik und MACD) gekapselt wird und der Bereich für die Kerzenformationen ebenfalls gekapselt wird.`~~ ✅ Aufgeteilt in `trend_indicators.py` (Elliott, MACD, Stochastik, evaluate_stock) und `candle_patterns.py`. `indicators.py` bleibt als Kompatibilitäts-Shim – kein Breaking Change in main.py.
- [x] ~~`TODO: Das Testsetup mit in den Service integriert wird, so das die Tests manuell ausgeführt werden können. Die Testabdeckung soll mindestens 80% betragen.`~~ ✅ `tests/test_candle_patterns.py` + `tests/test_trend_indicators.py`, 50 Tests, Coverage 95%. Ausführen: `cd agent-service && pytest tests/ -v --cov=candle_patterns --cov=trend_indicators --cov-report=term-missing`
- [x] ~~Yahoo-Abruf über VPN absichern.~~ ✅ `yahoo-service` läuft mit `network_mode: "container:vpn"` hinter Gluetun (WireGuard, Proton VPN). VPN-Konfiguration in Root `.env`.
- [x] ~~Projekt für GitHub vorbereiten.~~ ✅ Root `.gitignore` + `.env.example` erstellt. `twelvedata-service/.env.example` ergänzt. Echter API-Key aus `.env` entfernt (Platzhalter). README Schnellstart korrigiert.

**Zuletzt geändert:** 2026-05-24
**Zuletzt bearbeitet von Claude:** trend_indicators.py gelockert (MACD nur is_negative, Stochastik nur k<20). candle_patterns.py refactored (\_CandleUtils + je eine Funktion pro Muster). Alle Muster auf einheitliche i0/i1-Kontext-Struktur umgestellt (4 Kerzen: Abandoned Baby, Morning Star; 3 Kerzen: Engulfing, Piercing, Hammer). 27 Tests, alle grün.

---

## 8. Wichtige Hinweise für Claude

- **Bestehende API-Verträge nicht brechen** – das SSE-Format (`event: result / done`) ist fix
- **Angular Material + Tailwind** – beide im Einsatz; `preflight: false` in Tailwind um Konflikte zu vermeiden
- **Prebuilt Material Theme** – `indigo-pink.css` eingebunden via `angular.json styles`
- **Kein `ngModule`** – ausschließlich Standalone Components
- **PDF-Export** – immer Browser-Print, kein jsPDF oder Server-seitiges PDF
- **VPN-Routing** – `yahoo-service` hat `network_mode: "container:vpn"`, keinen eigenen `stock-net`-Anschluss. Erreichbar über Alias `yahoo-service` am VPN-Gateway (Port 8011). Nie direkten Port für `yahoo-service` in `docker-compose.yml` eintragen.
- **Secrets** – `.env`-Dateien niemals committen. Immer die `.env.example`-Vorlage aktuell halten wenn neue Variablen hinzukommen.
- **Plattform** – `--platform=linux/arm64` in allen Dockerfiles (Apple Silicon). Bei x86-Änderungen immer erwähnen.
- Bei Unklarheiten zuerst fragen, dann implementieren

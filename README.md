# Stock Analysis Platform (English Version)

Microservice architecture with SSE push per ticker, live 1d/4h/1h analysis with
automatic database write‑back, XGBoost‑based AI reversal signal (with a
per‑prediction explanation), and in‑app VPN control for the Yahoo data feed.

- Angular client (:4200) sends `POST /analyze/stream` to the Agent Service
  (:8010 Python, or :8016 Java — same API contract).
- Agent Service subscribes to SSE `/quotes/stream` from the Yahoo Service
  (:8011) [via VPN] or TwelveData Service (:8012), for **all three**
  timeframes (1d, 4h, 1h) — 4h/1h used to be read only from the database,
  they are now fetched live just like 1d.
- After each rule‑based analysis, the Agent Service requests the AI signal
  (including an explanation of the contributing factors) from the ML Service
  (:8015).
- Every live fetch is also written back into MySQL asynchronously (fire‑and‑
  forget) by the Agent Service, so actively used tickers stay fresh without
  waiting for the History Fetcher's daily run.
- Historical price data can additionally be (re)populated on demand by the
  History Fetcher (:8014) — it no longer runs automatically; see
  [Historical Data & Repairs](#historical-data--repairs).

---

## Data Flow

1. Angular sends the ticker list + source (`yahoo` for Xetra | `twelvedata`
   for US stocks) and the timeframe (1d / 4h / 1h) to the Agent.
2. Agent subscribes to the SSE stream from the selected data service, at the
   granularity the timeframe needs (Yahoo has no native 4h, so the Agent
   fetches 1h from Yahoo and aggregates 4h candles itself; TwelveData
   delivers 4h natively).
3. Data service streams data per ticker immediately after retrieval (with
   delay, to stay within rate limits).
4. Agent performs rule‑based analysis (Elliott Wave · MACD · Stochastic ·
   Candlestick Patterns), asynchronously writes the fetched candles back to
   the database, and in parallel requests the AI reversal signal (with its
   explanation) from the ML Service.
5. Angular displays each ticker immediately after processing.

---

## VPN Gateway & Anti‑Blocking (Yahoo Finance)

Because Yahoo Finance uses aggressive anti‑scraping mechanisms, the
`yahoo-service` runs isolated behind a **Gluetun VPN Gateway**.

- **Network masking:** The Yahoo container uses the VPN network tunnel
  (`network_mode: "container:vpn"`) and has an anonymous IP address (default:
  Netherlands, ProtonVPN free tier).
- **TLS/Browser fingerprinting:** The Python code uses `curl_cffi` to
  perfectly imitate the cryptographic TLS fingerprint of a real Mac Chrome
  browser.
- **Home network protection:** Your private router IP remains invisible to
  Yahoo. All other services run without VPN at full speed.
- **In‑app IP rotation:** The Angular client has a settings panel (gear icon
  in the header) that shows the VPN's current status/IP/location and lets
  you request a new IP with one click — see
  [VPN Control & IP Rotation](#vpn-control--ip-rotation-for-blocking-events).

---

## Quick Start

1. **Set up root environment variables** (VPN credentials):

   Open `.env` and insert the WireGuard credentials from the Proton VPN
   `*.conf` file (`WIREGUARD_PRIVATE_KEY`, `WIREGUARD_ADDRESSES`,
   `SERVER_COUNTRIES`, `FREE_ONLY`).

2. **Set up TwelveData API key** (for US stocks):

   Open `twelvedata-service/.env` and insert the API key from
   https://twelvedata.com.

3. **Set up database credentials:**

   Add the following variables to the root `.env` (see `.env.example`):

   ```
   MYSQL_ROOT_PASSWORD=changeme
   MYSQL_USER=stockuser
   MYSQL_PASSWORD=changeme
   ```

4. **Enable the Gluetun control server** (needed for in‑app VPN status/IP
   rotation — see [VPN Control & IP Rotation](#vpn-control--ip-rotation-for-blocking-events)
   for the `config.toml` this requires).

5. **Start the platform (Docker Desktop must be running)**

   ```bash
   docker compose up -d --build
   ```

6. **Open the Web UI:**
   Visit `http://localhost:4200` in your browser. Keep the tab visible while
   an analysis runs — a Screen Wake Lock keeps the machine from sleeping
   during that time, but only while the tab is in the foreground and not
   past a closed laptop lid.

---

## Historical Data & Repairs

The History Fetcher **no longer runs automatically** (`AUTO_RUN_ENABLED`
defaults to `false`): no daily cron job, no catch‑up on container start.
Actively used tickers are kept current by the Agent's own live write‑back
(see [Live Fetch & Database Write‑Back](#live-fetch--database-write-back)).
The Fetcher is now a repair/backfill tool you run manually — useful right
after creating a new ticker list, or to top up a large historical window
(5 years of daily data + hourly/4h data) faster than live write‑back would.

The Fetcher discovers **all** ticker lists dynamically via
`GET /api/lists` — a newly created list is picked up automatically, no code
change needed.

**Full re‑fetch for all lists (overwrites existing candles, deletes nothing):**

```bash
curl -X POST http://localhost:8014/fetch/initial
```

**Fill in missing candles only (repair gaps):**

```bash
curl -X POST http://localhost:8014/fetch/update
```

**Monitor progress:**

```bash
docker logs -f stock_history_fetcher
```

**Check status (also shows whether automatic mode is enabled):**

```bash
curl http://localhost:8014/status
curl http://localhost:8014/coverage
```

To re‑enable the daily automatic run and startup catch‑up, set
`AUTO_RUN_ENABLED=true` for the `history-fetcher` service.

---

## Live Fetch & Database Write‑Back

All three timeframes (1d, 4h, 1h) are now fetched **live** from Yahoo or
TwelveData for every analysis — 4h/1h used to be read only from the
database. Every fetch is also written back into MySQL asynchronously
(fire‑and‑forget; a failure here is only logged and never affects the
analysis):

| Source     | View    | Fetched as              | Written to                                    |
| ---------- | ------- | ------------------------ | ---------------------------------------------- |
| Yahoo      | 1d      | 1d                        | `ohlcv_daily`                                  |
| Yahoo      | 1h / 4h | 1h (Yahoo has no native 4h) | `ohlcv_hourly` + `ohlcv_4h` (aggregated from 1h) |
| TwelveData | 1d      | 1day                      | `ohlcv_daily`                                  |
| TwelveData | 1h      | 1h                        | `ohlcv_hourly`                                 |
| TwelveData | 4h      | 4h (native)               | `ohlcv_4h`                                     |

Write rule (same for all three tables): find the ticker's latest timestamp
in the database, go back 5 trading days from there, and write everything
fetched from that point onward. Existing candles are overwritten (upsert in
the DB service), missing ones are added — this closes the gap between the
last DB entry and now. Older gaps (more than 5 trading days before the last
DB entry) are only filled by the History Fetcher. A brand‑new ticker with no
DB data yet: everything fetched is written.

Intraday timestamps are local exchange time without a timezone
(`yyyy-MM-ddTHH:mm:ss`), matching the History Fetcher exactly. 4h blocks
start at 0/4/8/12/16/20 (local exchange time); blocks with fewer than 2
1h‑candles are dropped.

---

## VPN Control & IP Rotation for Blocking Events

If Yahoo blocks the current VPN IP (`YFRateLimitError`), you can request a
fresh Proton server either from the Angular client or manually.

### 🖱️ From the Angular client (recommended)

Open the settings panel (gear icon in the header) to see the current VPN
status, IP address, and location, and press **"Change IP address"**. The
Agent Service stops and restarts the tunnel via Gluetun's control server and
waits for a new exit IP; this can take up to a minute and, on a small free
server pool, may occasionally land on the same server (it retries a few
times automatically). The button is disabled while an analysis is running,
since rotating would interrupt in‑flight fetches.

This requires Gluetun's **control server** (port 8000, not published to the
host) to be reachable from the Agent container and to have an auth
configuration that allows the relevant routes. Create
`gluetun-auth/config.toml` next to `docker-compose.yml`:

```toml
[[roles]]
name = "vpn-rotate"
routes = ["GET /v1/publicip/ip", "GET /v1/vpn/status", "PUT /v1/vpn/status"]
auth = "none"
```

and mount it into the `vpn` service:

```yaml
    volumes:
      - ./gluetun-auth/config.toml:/gluetun/auth/config.toml:ro
```

**Never add `/v1/vpn/settings` to the allowed routes** — that endpoint
returns your WireGuard private key. After adding the file, recreate the VPN
and Yahoo containers (Yahoo shares the VPN's network namespace):

```bash
docker compose up -d --force-recreate vpn yahoo-service
```

### 🔧 Manually, without the UI

```bash
# Stop and restart the tunnel (Gluetun picks a new server from the pool)
curl -X PUT http://localhost:8000/v1/vpn/status -d '{"status":"stopped"}'
sleep 3
curl -X PUT http://localhost:8000/v1/vpn/status -d '{"status":"running"}'

# Or, equivalently, recreate the containers outright:
docker compose up -d --force-recreate vpn yahoo-service
```

### 🔍 Check IP address and location of the Yahoo service

```bash
curl http://localhost:8011/ip
```

This queries several IP‑info providers in turn (a shared free‑VPN IP is
sometimes rejected by one of them) and is what the Agent's VPN panel uses
internally — more reliable right after a restart than Gluetun's own
`/v1/publicip/ip`, which can briefly return nothing while the tunnel is
still coming up.

### 📋 Show VPN logs

```bash
docker logs vpn
```

---

## Train the ML Model

After the initial database population, train the AI model:

```bash
# Start training (runs in background, a few minutes depending on data volume)
curl -X POST http://localhost:8015/model/train

# Status + backtesting metrics
curl http://localhost:8015/model/status

# Client-friendly info: training data breakdown, calibration curve,
# feature importance with German labels
curl http://localhost:8015/model/info

# Follow logs
docker logs -f stock_ml_service
```

The model is automatically retrained every Sunday at 02:00 (this scheduler
is independent from the History Fetcher's automatic mode).

The model is trained on **every ticker that has enough historical data in
the database**, discovered via `GET /api/ohlcv/tickers` — not limited to any
particular list, and not dependent on the History Fetcher ever having seen
the ticker. Training data for each ticker/timeframe is split
chronologically into train/validation/test (oldest → newest, split
independently per ticker so every timeframe and ticker is represented in
each part); early stopping uses the validation set, and an isotonic
calibration step (also fit on validation) maps the raw, class‑weight‑
inflated model score onto a value that approximates the actually observed
hit rate. `GET /model/info` reports whether the currently loaded model has
this calibration (`calibrated: true/false` — `false` only for a model
trained before this change; retraining fixes it).

**ML signal in the result:**

- `reversal_pct`: model's score for an upward move of more than the
  configured threshold (default 3%) within the next 5 candles of the
  selected timeframe (0–100%) — a model score, not a guaranteed probability
- `ml_signal`: `none` | `weak` | `moderate` | `strong`
- `ml_explanation`: which features push the score up or down for this
  specific prediction (base value, the 8 strongest features with their
  current value and effect in percentage points, remaining features
  summarized) — click the AI badge in the results table to see it
- Displayed in the Angular table as a color‑coded, clickable badge in the AI
  signal column; the settings panel's "AI Model" section shows the global
  training summary, metrics, per‑timeframe breakdown, and calibration curve

---

## Services & Ports

| Service            | Port | Description                                          |
| ------------------ | ---- | ----------------------------------------------------- |
| Agent Service      | 8010 | AI agent · SSE proxy · Elliott/MACD/Stochastic · ML   |
| Agent Service Java | 8016 | Java/Spring Boot port of Agent Service (same API contract, ta4j‑based Elliott Wave detection, live 1d/4h/1h fetch + DB write‑back, VPN control) |
| VPN Gateway        | 8011 | Gluetun VPN · forwards port 8011 to Yahoo; control server on 8000 (not published) |
| Yahoo Service      | –    | Yahoo Finance · runs inside VPN network · `/ip` reports current exit IP/location |
| TwelveData Service | 8012 | Twelve Data API · SSE · delay per ticker (Free plan) · symbol aliases for commodities/forex pairs (see [Ticker Formats](#ticker-formats)) |
| DB Access Service  | 8013 | MySQL · Ticker lists · OHLCV data · REST API          |
| History Fetcher    | 8014 | Manual historical backfill/repair (see [Historical Data & Repairs](#historical-data--repairs)) |
| ML Service         | 8015 | XGBoost · calibrated reversal score with explanation · weekly retraining |
| Angular Client     | 4200 | Web UI · real‑time results · AI signal column · VPN/AI‑model settings panel |

### Swagger Docs

- Agent: http://localhost:8010/docs
- Agent (Java): http://localhost:8016/actuator (Spring Boot Actuator, no Swagger UI configured yet)
- Yahoo: http://localhost:8011/docs (via VPN gateway)
- TwelveData: http://localhost:8012/docs
- DB Access: http://localhost:8013/swagger-ui.html
- History: http://localhost:8014/docs
- ML Service: http://localhost:8015/docs

---

## Ticker Formats

- **Yahoo Finance:** Standard Yahoo format for XETRA (e.g., `ADS.DE`) and
  futures (e.g., `GC=F` for gold, `SI=F` for silver)
- **Twelve Data:** US tickers (e.g., `AAPL`, `MSFT`, `JPM`)
- **Index tickers** (e.g., `^GDAXI`, `^DJI`) work with either source; the
  `^` is now correctly URL‑encoded end‑to‑end (History Fetcher, ML Service).
- **Commodities/forex pairs via Twelve Data** (e.g., gold, silver) must be
  entered with a **hyphen**, not a slash — Twelve Data itself requires a
  slash in the symbol (`XAU/USD`), but a slash cannot appear in our own
  internal REST paths. Enter the ticker as `XAU-USD` (source Twelve Data);
  `twelvedata-service` translates it to `XAU/USD` only for the outbound API
  call. Currently mapped: `XAU-USD` (gold), `XAG-USD` (silver — note: Twelve
  Data's `time_series` endpoint returned 404 for this in testing, i.e. no
  historical data available regardless of symbol; `SI=F` via Yahoo is a
  working alternative), `XPT-USD` (platinum), `XPD-USD` (palladium).

Comma‑separated in the Angular client:
`AAPL, MSFT, JPM, ADS.DE`
or load predefined lists.

---

## DB Access Service

The `stock-data-db-access` service is a **Spring Boot 4.1 / Java 25**
microservice that manages ticker lists and historical price data in a MySQL
database. It is the single source of truth for all ticker symbols and OHLCV
data.

### Database Schema (Flyway V1–V5)

| Table            | Description                                                    |
| ---------------- | ---------------------------------------------------------------- |
| `ticker_lists`   | Lists with code, name, source, and ticker format                 |
| `ticker_symbols` | Individual tickers per list (raw_symbol)                         |
| `ticker_meta`    | Normalized API symbols (e.g., ADS → ADS.DE), ISIN — populated only by the History Fetcher for the lists it has fetched, **not** the source of truth for ML training (see below) |
| `ohlcv_daily`    | Daily OHLCV candles (5 years, unique per ticker+date)             |
| `ohlcv_hourly`   | Hourly OHLCV candles (12 months, local exchange time)             |
| `ohlcv_4h`       | 4‑hour OHLCV candles (Yahoo: aggregated from hourly; Twelve Data: native) |
| `fetch_log`      | Log of all data fetches (SUCCESS/ERROR/PARTIAL)                   |

### Important REST Endpoints

| Method | Path                                  | Description                                       |
| ------ | -------------------------------------- | --------------------------------------------------- |
| GET    | `/api/lists`                           | All ticker lists                                    |
| GET    | `/api/lists/code/{code}/raw-symbols`   | Raw symbols of a list                               |
| GET    | `/api/ohlcv/daily/{ticker}/latest?n=`  | Latest N daily candles                              |
| GET    | `/api/ohlcv/4h/{ticker}/latest?n=`     | Latest N 4‑hour candles                             |
| GET    | `/api/ohlcv/hourly/{ticker}/latest?n=` | Latest N hourly candles                             |
| POST   | `/api/ohlcv/daily/bulk`                | Bulk **upsert** daily candles (existing candles are overwritten, not just skipped) |
| POST   | `/api/ohlcv/4h/bulk`                   | Bulk upsert 4‑hour candles                          |
| POST   | `/api/ohlcv/hourly/bulk`               | Bulk upsert hourly candles                          |
| GET    | `/api/ohlcv/coverage`                  | Data coverage overview                              |
| GET    | `/api/ohlcv/tickers`                   | Every ticker that actually has OHLCV data, with row counts per table — independent of `ticker_meta`/lists; this is what the ML Service uses to decide what to train on, so a ticker gets included as soon as it has enough data, however it got there (History Fetcher **or** live write‑back from a fresh analysis) |

---

## Publish Image (Docker Hub)

### Build and push a single image

```bash
docker build \
  --platform linux/arm64 \
  -t <dockerhub-user>/stock-data-db-access:latest \
  ./stock-data-db-access

docker push <dockerhub-user>/stock-data-db-access:latest
```

### Build all services at once

```bash
DOCKER_USER=<dockerhub-user>

for SERVICE in agent-service agent-service-java yahoo-service twelvedata-service angular-client \
               stock-data-db-access history-fetcher ml-service; do
  docker build --platform linux/arm64 -t $DOCKER_USER/$SERVICE:latest ./$SERVICE
  docker push $DOCKER_USER/$SERVICE:latest
done
```

> **Note:** The `vpn` container uses the public `qmcgaw/gluetun` image and
> does not need to be pushed.

---

## Project Structure

```
docker-compose.yml              # Multi-container setup incl. Gluetun VPN
gluetun-auth/config.toml        # Gluetun control-server auth (VPN panel, IP rotation)
.env                            # Secrets (do not commit!)
.env.example                    # Template for .env

agent-service/
  main.py                       # SSE proxy + analysis + ML integration
  bullish_reversal_indicator.py # Elliott + MACD + Stochastic (bullish)
  bearish_reversal_indicator.py # Elliott + MACD + Stochastic (bearish)
  candle_patterns.py            # 5 candlestick patterns
  Dockerfile / requirements.txt

agent-service-java/
  src/main/java/rf/stock/agent/
    indicator/                  # BullishIndicator / BearishIndicator (ta4j-based
                                 # Elliott Wave via ElliottWaveFacade, MACD, Stochastic)
    candle/                     # Candlestick pattern detection
    util/                       # IntradayBarUtil (1h→4h aggregation), TradingDayUtil
    service/                    # AnalysisService, DataServiceClient, DbClient,
                                 # DailyBarWriteBackService, IntradayBarWriteBackService,
                                 # VpnService, MlClient
    controller/                 # AgentController (SSE proxy), VpnController, MlController
    model/                      # OhlcvBar, StockResult, MlExplanation, VpnInfo, ...
  Dockerfile / pom.xml          # Java 25, Spring Boot 4.1

yahoo-service/
  main.py                       # Yahoo Finance + SSE + curl_cffi + /ip (exit IP/location)
  Dockerfile / requirements.txt

twelvedata-service/
  main.py                       # Twelve Data API + SSE + interval parameter +
                                 # SYMBOL_ALIASES (commodities/forex) + API-key log redaction
  Dockerfile / requirements.txt / .env

stock-data-db-access/
  src/main/java/...             # Spring Boot application
  src/main/resources/db/migration/  # Flyway V1–V5
  Dockerfile / pom.xml

history-fetcher/
  app/
    main.py                     # FastAPI (manual-only by default, see config.auto_run_enabled)
    fetcher.py                  # Initial fill + update, ticker lists discovered dynamically
    data_client.py              # SSE client for Yahoo/TwelveData
    db_client.py                # REST client for DB service
    config.py                   # Configuration (delays, days, times, auto_run_enabled)
  Dockerfile / requirements.txt

ml-service/
  app/
    main.py                     # FastAPI + APScheduler + training trigger + /model/info
    features/
      engineer.py                # Compute 38 technical features
      labels.py                  # German display labels for each feature
    model/
      trainer.py                 # XGBoost training, chronological train/val/test split,
                                  # isotonic calibration, backtesting diagnostics
      predictor.py                # Prediction + per-prediction explanation (pred_contribs)
    api/db_client.py             # Load OHLCV data from DB (ticker universe via /api/ohlcv/tickers)
    config.py                    # Forecast horizon, thresholds, split fractions, retraining
  Dockerfile / requirements.txt
  models/                        # Persisted model + scaler + calibrator (Docker volume)

angular-client/
  src/app/
    components/                  # filter-header, kpi-bar, results-table,
                                  # settings-panel, ml-explanation-modal, ml-model-info, ...
    models/stock.models.ts       # StockResult incl. ML fields (ml_explanation, ...), VPN/ML-info types
    services/                    # analysis (+ wake lock), ticker-list, pdf-export, vpn, ml
  Dockerfile / angular.json
```

---

_Not financial advice. Technical analysis is for informational purposes only._

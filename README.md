# Stock Analysis Platform (English Version)

Microservice architecture with SSE push per ticker and XGBoost‑based AI reversal signal.

- Angular client (:4200) sends `POST /analyze/stream` to the Agent Service (:8010).
- Agent Service subscribes to SSE `/quotes/stream` from the Yahoo Service (:8011) [via VPN] or TwelveData Service (:8012).
- After each rule‑based analysis, the Agent Service requests the AI signal from the ML Service (:8015).
- Historical price data is stored daily in MySQL by the History Fetcher (:8014).

---

## Data Flow

1. Angular sends the ticker list + source (`yahoo` for Xetra | `twelvedata` for US stocks) to the Agent.
2. Agent subscribes to the SSE stream from the selected data service.
3. Data service streams data per ticker immediately after retrieval (with delay).
4. Agent performs rule‑based analysis (Elliott Wave · MACD · Stochastic · Candlestick Patterns) and requests the AI reversal signal from the ML Service in parallel.
5. Angular displays each ticker immediately after processing.

---

## VPN Gateway & Anti‑Blocking (Yahoo Finance)

Because Yahoo Finance uses aggressive anti‑scraping mechanisms, the `yahoo-service` runs isolated behind a **Gluetun VPN Gateway**.

- **Network masking:** The Yahoo container uses the VPN network tunnel (`network_mode: "container:vpn"`) and has an anonymous IP address (default: Netherlands).
- **TLS/Browser fingerprinting:** The Python code uses `curl_cffi` to perfectly imitate the cryptographic TLS fingerprint of a real Mac Chrome browser.
- **Home network protection:** Your private router IP remains invisible to Yahoo. All other services run without VPN at full speed.

---

## Quick Start

1. **Set up root environment variables** (VPN credentials):

   Open `.env` and insert the WireGuard credentials from the Proton VPN `*.conf` file (`WIREGUARD_PRIVATE_KEY`, `WIREGUARD_ADDRESSES`).

2. **Set up TwelveData API key** (for US stocks):

   Open `twelvedata-service/.env` and insert the API key from https://twelvedata.com.

3. **Set up database credentials:**

   Add the following variables to the root `.env` (see `.env.example`):

   ```
   MYSQL_ROOT_PASSWORD=changeme
   MYSQL_USER=stockuser
   MYSQL_PASSWORD=changeme
   ```

4. **Start the platform (Docker Desktop must be running)**

   ```bash
   docker compose up -d --build
   ```

5. **Open the Web UI:**
   Visit `http://localhost:4200` in your browser.

---

## First Start – Database Population

On the first start, the History Fetcher automatically fills the database with historical price data (5 years of daily data + hourly data for all ticker lists). Depending on the TwelveData rate limit, this takes about 15–30 minutes.

**Monitor progress:**
```bash
docker logs -f stock_history_fetcher
```

**Check status:**
```bash
curl http://localhost:8014/coverage
```

**Restart manually (if needed):**
```bash
curl -X POST http://localhost:8014/fetch/initial
```

---

## Train the ML Model

After the initial database population, train the AI model:

```bash
# Start training (runs in background, ~3–5 minutes on M3)
curl -X POST http://localhost:8015/model/train

# Status + backtesting metrics
curl http://localhost:8015/model/status

# Follow logs
docker logs -f stock_ml_service
```

The model is automatically retrained every Sunday at 02:00.

**ML signal in the result:**
- `reversal_pct`: Probability of an upward reversal in the next 5 days (0–100%)
- `ml_signal`: `none` | `weak` | `moderate` | `strong`
- Displayed in the Angular table as a color‑coded badge in the AI signal column

---

## VPN Control & IP Rotation for Blocking Events

If Yahoo blocks the current VPN IP (`YFRateLimitError`), Gluetun can immediately switch to a fresh, unblocked Proton server.

### 🔄 Disconnect and reconnect (request a fresh IP)
```bash
docker exec vpn kill -HUP 1
```

### 🔍 Check IP address and location of the Yahoo service
```bash
docker run --rm --network=container:vpn alpine sh -c "wget -qO- https://ipinfo.io"
```

### 📋 Show VPN logs
```bash
docker logs vpn
```

---

## Services & Ports

| Service                  | Port  | Description                                           |
| ------------------------ | ----- | ----------------------------------------------------- |
| Agent Service            | 8010  | AI agent · SSE proxy · Elliott/MACD/Stochastic · ML  |
| VPN Gateway              | 8011  | Gluetun VPN · Forwards port 8011 to Yahoo            |
| Yahoo Service            | –     | Yahoo Finance · Runs inside VPN network              |
| TwelveData Service       | 8012  | Twelve Data API · SSE · 8s delay per ticker (Free)   |
| DB Access Service        | 8013  | MySQL · Ticker lists · OHLCV data · REST API         |
| History Fetcher          | 8014  | Historical data population · Daily updates           |
| ML Service               | 8015  | XGBoost · Reversal probability · Weekly retraining   |
| Angular Client           | 4200  | Web UI · Real‑time results · AI signal column        |

### Swagger Docs

- Agent:       http://localhost:8010/docs  
- Yahoo:       http://localhost:8011/docs (via VPN gateway)  
- TwelveData:  http://localhost:8012/docs  
- DB Access:   http://localhost:8013/swagger-ui.html  
- History:     http://localhost:8014/docs  
- ML Service:  http://localhost:8015/docs  

---

## Ticker Formats

- **Yahoo Finance:** Standard Yahoo format for XETRA (e.g., `ADS.DE`)
- **Twelve Data:** US tickers (e.g., `AAPL`, `MSFT`, `JPM`)

Comma‑separated in the Angular client:  
`AAPL, MSFT, JPM, ADS.DE`  
or load predefined lists.

---

## DB Access Service

The `stock-data-db-access` service is a **Spring Boot 3 / Java 21** microservice that manages ticker lists and historical price data in a MySQL 9.7 database. It is the single source of truth for all ticker symbols and OHLCV data.

### Database Schema (Flyway V1–V4)

| Table              | Description                                           |
| ------------------ | ----------------------------------------------------- |
| `ticker_lists`     | Lists with code, name, source, and ticker format      |
| `ticker_symbols`   | Individual tickers per list (raw_symbol)              |
| `ticker_meta`      | Normalized API symbols (e.g., ADS → ADS.DE), ISIN     |
| `ohlcv_daily`      | Daily OHLCV candles (5 years, unique per ticker+date) |
| `ohlcv_hourly`     | Hourly OHLCV candles (12 months)                      |
| `fetch_log`        | Log of all data fetches (SUCCESS/ERROR/PARTIAL)       |

### Important REST Endpoints

| Method | Path                                   | Description                          |
| ------ | -------------------------------------- | ------------------------------------- |
| GET    | `/api/lists`                           | All ticker lists                      |
| GET    | `/api/lists/code/{code}/raw-symbols`   | Raw symbols of a list                 |
| GET    | `/api/ohlcv/daily/{ticker}/latest?n=` | Latest N daily candles                |
| POST   | `/api/ohlcv/daily/bulk`                | Bulk insert daily candles (idempotent) |
| POST   | `/api/ohlcv/hourly/bulk`               | Bulk insert hourly candles            |
| GET    | `/api/ohlcv/coverage`                  | Data coverage overview                |

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

for SERVICE in agent-service yahoo-service twelvedata-service angular-client \
               stock-data-db-access history-fetcher ml-service; do
  docker build --platform linux/arm64 -t $DOCKER_USER/$SERVICE:latest ./$SERVICE
  docker push $DOCKER_USER/$SERVICE:latest
done
```

> **Note:** The `vpn` container uses the public `qmcgaw/gluetun` image and does not need to be pushed.

---

## Project Structure

```
docker-compose.yml              # Multi-container setup incl. Gluetun VPN
.env                            # Secrets (do not commit!)
.env.example                    # Template for .env

agent-service/
  main.py                       # SSE proxy + analysis + ML integration (v4.0.0)
  bullish_reversal_indicator.py # Elliott + MACD + Stochastic (bullish)
  bearish_reversal_indicator.py # Elliott + MACD + Stochastic (bearish)
  candle_patterns.py            # 5 candlestick patterns
  Dockerfile / requirements.txt

yahoo-service/
  main.py                       # Yahoo Finance + SSE + curl_cffi
  Dockerfile / requirements.txt

twelvedata-service/
  main.py                       # Twelve Data API + SSE + interval parameter (v2.0.0)
  Dockerfile / requirements.txt / .env

stock-data-db-access/
  src/main/java/...             # Spring Boot application
  src/main/resources/db/migration/  # Flyway V1–V4
  Dockerfile / pom.xml

history-fetcher/
  app/
    main.py                     # FastAPI + APScheduler
    fetcher.py                  # Initial fill + daily update
    data_client.py              # SSE client for Yahoo/TwelveData
    db_client.py                # REST client for DB service
    config.py                   # Configuration (delays, days, times)
  Dockerfile / requirements.txt

ml-service/
  app/
    main.py                     # FastAPI + APScheduler + training trigger
    features/engineer.py        # Compute 38 technical features
    model/trainer.py            # XGBoost training + backtesting
    model/predictor.py          # Prediction for individual tickers
    api/db_client.py            # Load OHLCV data from DB
    config.py                   # Forecast horizon, thresholds, retraining
  Dockerfile / requirements.txt
  models/                       # Persisted model state (Docker volume)

angular-client/
  src/app/
    components/                 # filter-header, kpi-bar, results-table, ...
    models/stock.models.ts      # StockResult incl. ML fields
    services/                   # analysis, ticker-list, pdf-export
  Dockerfile / angular.json
```

---

*Not financial advice. Technical analysis is for informational purposes only.*

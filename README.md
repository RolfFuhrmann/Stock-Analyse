# Stock Analysis Platform

Microservice architecture with SSE push per ticker:

- Angular Client (:4200) sends `POST /analyze/stream` to the Agent Service (:8010).
- Agent Service subscribes to SSE `/quotes/stream` from the Yahoo Service (:8011) [VIA VPN].
- Alternatively, the Agent subscribes to the stream from the TwelveData Service (:8012) without VPN.

**Data Flow:**

1. Angular sends the ticker list + source (`yahoo` for Xetra stocks | `twelvedata` for US stocks) to the Agent.
2. Agent subscribes to the SSE stream from the chosen Data Service.
3. Data Service pushes data per ticker immediately after retrieval (with delay).
4. Agent analyzes data (Elliott Wave: A-B-C downward · MACD: below 0 · Stochastic: below 20) looking for reversal signals in the downtrend and pushes the results to Angular.
5. Angular displays each ticker immediately as soon as it is processed.

---

## VPN Gateway & Anti-Blocking (Yahoo Finance)

Since Yahoo Finance uses aggressive anti-scraping mechanisms, the `yahoo-service` runs isolated behind a **Gluetun VPN Gateway**.

- **Network Masking:** The Yahoo container uses the VPN's network tunnel (`network_mode: "container:vpn"`) and has an anonymous IP address on the internet (default: Netherlands).
- **TLS/Browser Fingerprinting:** The Python code utilizes `curl_cffi` to perfectly mimic the cryptographic TLS fingerprint of a real Mac Chrome browser.
- **Home Network Protection:** Your private router IP remains invisible to Yahoo and is never blocked. All other services run without a VPN at full speed.

---

## Quick Start

1. **Set up Root Environment Variables** (VPN Credentials):

   Open `.env` and enter your WireGuard credentials from the `*.conf` file downloaded from Proton VPN (`WIREGUARD_PRIVATE_KEY`, `WIREGUARD_ADDRESSES`).

2. **Set up TwelveData API Key** (for the optional Twelve Data mode):

   Open `twelvedata-service/.env` and enter your API key from https://twelvedata.com.

3. **Set up Database Credentials:**

   Add the following variables to your root `.env` (see `.env.example`):

   ```
   MYSQL_ROOT_PASSWORD=changeme
   MYSQL_USER=stockuser
   MYSQL_PASSWORD=changeme
   ```

4. **Start the Platform: Docker Desktop must be running**

```bash
  docker compose up -d --build
```

5. **Open Web UI:**
   Open `http://localhost:4200` in your browser.

---

## VPN Control & IP Rotation on Blocks

If Yahoo blocks the current VPN IP despite using `curl_cffi` (`YFRateLimitError`), you can force Gluetun to instantly switch to a fresh, unblocked Proton server on the fly.

### 🔄 Command to Disconnect and Reconnect (Request Fresh IP)

Run this command in your terminal to immediately drop the existing VPN connection and automatically obtain a new IP address from the Netherlands:
`docker exec vpn kill -HUP 1`

### 🔍 Check IP Address and Location of the Yahoo Service

To verify which public IP address and location the tunneled Yahoo service is actually using on the internet:
`docker run --rm --network=container:vpn alpine sh -c "wget -qO- https://ipinfo.io"`

### 📋 View VPN Logs

`docker logs vpn`

---

## Services & Ports

- **Agent Service (Port 8010):** AI Agent · SSE Proxy · Elliott/MACD/Stochastic
- **VPN Gateway (Port 8011):** Gluetun VPN · Forwards Port 8011 to Yahoo
- **Yahoo Service (No Port):** Yahoo Finance · Runs tunneled inside the VPN network
- **TwelveData Svc (Port 8012):** Twelve Data API · SSE · Delay 7.5s/Ticker
- **DB Access Service (Port 8013):** MySQL · Ticker List Management · REST API
- **Angular Client (Port 4200):** Web UI · Radio Buttons · Live Results

### Swagger Docs

- Agent: http://localhost:8010/docs
- Yahoo: http://localhost:8011/docs (provided via the VPN gateway)
- TwelveData: http://localhost:8012/docs
- DB Access: http://localhost:8013/swagger-ui.html

---

## Ticker Formats

- **Yahoo Finance:** Standard Yahoo format for XETRA (e.g., `ADS.DE` etc.)
- **Twelve Data:** US tickers (Free plan) (e.g., `AAPL`, `MSFT`, `JPM`)

Enter them comma-separated in the Angular client: `AAPL, MSFT, JPM, ADS.DE` or load predefined lists.

---

## DB Access Service

The `stock-data-db-access` service is a **Spring Boot 4 / Java 25** microservice that manages ticker lists (e.g., DAX 40, Dow Jones) in a MySQL 9 database. It is the single source of truth for all ticker symbols used across the platform.

### Responsibilities

- Create, update, and delete named ticker lists (e.g., `DAX40`, `DOW30`)
- Add, update, and remove individual ticker symbols per list
- Normalize raw symbols to Yahoo Finance format automatically based on the exchange (e.g., `ADS` + `XETRA` → `ADS.DE`)
- Expose a dedicated endpoint that returns ready-to-use Yahoo symbols for the Agent Service

### Data Model

Each **ticker list** has a unique code (e.g., `DAX40`) and a set of **ticker symbols**. Every symbol stores:

| Field          | Description                                                 | Example  |
| -------------- | ----------------------------------------------------------- | -------- |
| `raw_symbol`   | Symbol as entered by the user                               | `ADS`    |
| `yahoo_symbol` | Normalized symbol for Yahoo Finance (auto-calculated)       | `ADS.DE` |
| `exchange`     | Exchange enum: `XETRA`, `NYSE`, `NASDAQ`, `LSE`, `EURONEXT` | `XETRA`  |
| `display_name` | Human-readable company name                                 | `Adidas` |

### Key REST Endpoints

| Method   | Path                                   | Description                                      |
| -------- | -------------------------------------- | ------------------------------------------------ |
| `GET`    | `/api/lists`                           | Get all ticker lists                             |
| `GET`    | `/api/lists/{id}`                      | Get a list with all its symbols                  |
| `GET`    | `/api/lists/code/{code}`               | Get a list by code (e.g., `DAX40`)               |
| `GET`    | `/api/lists/code/{code}/yahoo-symbols` | Get Yahoo-normalized symbols — for Agent Service |
| `POST`   | `/api/lists`                           | Create a new list                                |
| `PUT`    | `/api/lists/{id}`                      | Update a list                                    |
| `DELETE` | `/api/lists/{id}`                      | Delete a list and all its symbols                |
| `POST`   | `/api/lists/{id}/symbols`              | Add a symbol to a list                           |
| `PUT`    | `/api/lists/{id}/symbols/{symId}`      | Update a symbol                                  |
| `DELETE` | `/api/lists/{id}/symbols/{symId}`      | Remove a symbol from a list                      |

### Schema Migration

Database schema is managed by **Flyway**. Migration scripts are located in `stock-data-db-access/src/main/resources/db/migration/`. On startup, Flyway applies any pending migrations automatically. The initial migration (`V1__init_schema.sql`) seeds both DAX 40 and Dow Jones ticker lists.

### Running Tests

```bash
cd stock-data-db-access
mvn test
```

---

## Publishing a Service Image to Docker Hub

To manually build and push a service image (e.g., after changes to `stock-data-db-access`), follow these steps.

### Prerequisites

- Docker Desktop is running
- You are logged in: `docker login`

### Build & Push

**1. Build the image locally:**

```bash
# Replace <your-dockerhub-username> and <service-dir> as needed.
# Example for stock-data-db-access:

docker build \
  --platform linux/arm64 \
  -t <your-dockerhub-username>/stock-data-db-access:latest \
  ./stock-data-db-access
```

**2. Optionally tag with a version:**

```bash
docker tag \
  <your-dockerhub-username>/stock-data-db-access:latest \
  <your-dockerhub-username>/stock-data-db-access:1.0.0
```

**3. Push to Docker Hub:**

```bash
docker push <your-dockerhub-username>/stock-data-db-access:latest

# Push the version tag as well if you created one:
docker push <your-dockerhub-username>/stock-data-db-access:1.0.0
```

**4. Use the remote image in `docker-compose.yml`** (instead of building locally):

```yaml
db-service:
  image: <your-dockerhub-username>/stock-data-db-access:latest
  # build:               ← comment this out when using a pre-built image
  #   context: ./stock-data-db-access
```

### Push All Services at Once

To rebuild and push every service in one go:

```bash
DOCKER_USER=<your-dockerhub-username>

for SERVICE in agent-service yahoo-service twelvedata-service angular-client stock-data-db-access; do
  docker build --platform linux/arm64 -t $DOCKER_USER/$SERVICE:latest ./$SERVICE
  docker push $DOCKER_USER/$SERVICE:latest
done
```

> **Note:** The `vpn` container uses the public `qmcgaw/gluetun` image and does not need to be pushed.

---

## Project Structure

- **docker-compose.yml** - Multi-container setup including Gluetun VPN routing
- **agent-service/**
  - `main.py` - SSE Proxy + analysis logic
  - `indicators.py` - Elliott Wave · MACD · Slow Stochastic
  - `Dockerfile` & `requirements.txt`
- **yahoo-service/**
  - `main.py` - Yahoo Finance + SSE + curl_cffi integration
  - `Dockerfile` & `requirements.txt` (Contains yfinance and curl_cffi)
- **twelvedata-service/**
  - `main.py` - Twelve Data API + SSE + Delay
  - `Dockerfile`, `requirements.txt` & `.env`
- **angular-client/**
  - `src/app/app.component.ts` - UI · Radio Buttons · Live Table
  - `src/app/analysis.service.ts` - SSE Client
  - `Dockerfile` & `angular.json`
- **stock-data-db-access/**
  - `src/main/java/rf/stock/data/db/access/` - Spring Boot application
  - `src/main/resources/db/migration/` - Flyway SQL migrations
  - `Dockerfile` & `pom.xml`

---

_Not a financial advisor. Technical analysis is for informational purposes only._

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

3. **Start the Platform: Docker Desktop must be running**

```bash
  docker compose up -d --build
```

4. **Open Web UI:**
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
- **Angular Client (Port 4200):** Web UI · Radio Buttons · Live Results

### Swagger Docs

- Agent: http://localhost:8010/docs
- Yahoo: http://localhost:8011/docs (provided via the VPN gateway)
- TwelveData: http://localhost:8012/docs

---

## Ticker Formats

- **Yahoo Finance:** Standard Yahoo format for XETRA (e.g., `ADS.DE` etc.)
- **Twelve Data:** US tickers (Free plan) (e.g., `AAPL`, `MSFT`, `JPM`)

Enter them comma-separated in the Angular client: `AAPL, MSFT, JPM, ADS.DE` or load predefined lists.

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

---

_Not a financial advisor. Technical analysis is for informational purposes only._

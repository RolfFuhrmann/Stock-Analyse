# Stock Analyse Platform

Microservice-Architektur mit SSE-Push pro Ticker:

- Angular Client (:4200) sendet POST /analyze/stream an den Agent Service (:8010).
- Agent Service abonniert SSE /quotes/stream vom Yahoo Service (:8011) [ÜBER VPN].
- Alternativ abonniert der Agent den Stream vom TwelveData Service (:8012) ohne VPN.

**Datenfluss:**

1. Angular sendet Ticker-Liste + Quelle (yahoo - für Xetra-Werte|twelvedata für US-Werte) an Agent.
2. Agent abonniert SSE vom gewählten Data Service.
3. Data Service pusht pro Ticker sofort nach Abruf (mit Delay).
4. Agent analysiert (Elliott Wave: A-B-C abwärts· MACD: unter 0 · Stochastik: unter 20) nach Umkehrsignale im Abwertstrend und pusht Ergebnis an Angular.
5. Angular zeigt jeden Ticker sofort an sobald er verarbeitet ist.

---

## VPN-Gateway & Anti-Blocking (Yahoo Finance)

Da Yahoo Finance aggressive Anti-Scraping-Mechanismen nutzt, läuft der `yahoo-service` isoliert hinter einem **Gluetun VPN-Gateway**.

- **Netzwerktarnung:** Der Yahoo-Container nutzt den Netzwerktunnel des VPNs (`network_mode: "container:vpn"`) und besitzt im Internet eine anonyme IP-Adresse (Standard: Niederlande).
- **TLS/Browser-Fingerprinting:** Der Python-Code verwendet `curl_cffi`, um den kryptografischen TLS-Fingerabdruck eines echten Mac-Chrome-Browsers perfekt zu imitieren.
- **Schutz der Heimleitung:** Die private Router-IP bleibt für Yahoo unsichtbar und wird niemals blockiert. Alle anderen Services laufen ohne VPN mit voller Geschwindigkeit.

---

## Schnellstart

1. **Root-Umgebungsvariablen anlegen** (VPN-Zugangsdaten):

   Öffne `.env` und trage deine WireGuard-Zugangsdaten aus der von Proton VPN heruntergeladenen `*.conf`-Datei ein (`WIREGUARD_PRIVATE_KEY`, `WIREGUARD_ADDRESSES`).

2. **TwelveData API-Key anlegen** (für den optionalen Twelve Data Modus):

   Öffne `twelvedata-service/.env` und trage deinen API-Key von https://twelvedata.com ein.

3. **Plattform starten: Voraussetzung ist ein laufender Docker Desktop**

   ```bash
   docker compose up -d --build
   ```

4. **Web-UI öffnen:**
   Öffne im Browser die Adresse `http://localhost:4200`

---

## VPN-Steuerung & IP-Wechsel bei Sperren

Falls Yahoo die aktuelle VPN-IP trotz `curl_cffi` blockiert (`YFRateLimitError`), kannst du Gluetun im laufenden Betrieb zwingen, sofort auf einen frischen, unblockierten Proton-Server zu wechseln.

### 🔄 Befehl zum Kappen und Neuverbinden (Frische IP anfordern)

Führe diesen Befehl im Terminal aus, um die bestehende VPN-Verbindung sofort zu trennen und vollautomatisch eine neue IP-Adresse aus den Niederlande zu beziehen:
`docker exec vpn kill -HUP 1`

### 🔍 IP-Adresse und Standort des Yahoo-Services prüfen

Um zu kontrollieren, welche öffentliche IP-Adresse und welchen Standort der getunnelte Yahoo-Dienst aktuell tatsächlich im Internet verwendet:
`docker run --rm --network=container:vpn alpine sh -c "wget -qO- https://ipinfo.io"`

### 📋 VPN-Protokolle (Logs) einsehen

`docker logs vpn`

---

## Services & Ports

- **Agent Service (Port 8010):** KI-Agent · SSE-Proxy · Elliott/MACD/Stochastik
- **VPN Gateway (Port 8011):** Gluetun VPN · Leitet Port 8011 an Yahoo weiter
- **Yahoo Service (Kein Port):** Yahoo Finance · Läuft getunnelt im VPN-Netzwerk
- **TwelveData Svc (Port 8012):** Twelve Data API · SSE · Delay 7.5s/Ticker
- **Angular Client (Port 4200):** Web-UI · Radio-Buttons · Live-Ergebnisse

### Swagger Docs

- Agent: http://localhost:8010/docs
- Yahoo: http://localhost:8011/docs (bereitgestellt über das VPN-Gateway)
- TwelveData: http://localhost:8012/docs

---

## Ticker-Formate

- **Yahoo Finance:** Standard Yahoo-Format für XETRA (z. B. `ADS.DE` etc.)
- **Twelve Data:** US-Ticker (Free-Tarif) (z. B. `AAPL`, `MSFT`, `JPM`)

Im Angular Client kommasepariert eingeben: `AAPL, MSFT, JPM, ADS.DE` oder Vodefinierte Listen abrufen.

---

## Projektstruktur

- **docker-compose.yml** - Multi-Container Setup inkl. Gluetun VPN-Routing
- **agent-service/**
  - `main.py` - SSE-Proxy + Analyse-Logik
  - `indicators.py` - Elliott Wave · MACD · Slow Stochastik
  - `Dockerfile` & `requirements.txt`
- **yahoo-service/**
  - `main.py` - Yahoo Finance + SSE + curl_cffi Integration
  - `Dockerfile` & `requirements.txt` (Enthält yfinance und curl_cffi)
- **twelvedata-service/**
  - `main.py` - Twelve Data API + SSE + Delay
  - `Dockerfile`, `requirements.txt` & `.env`
- **angular-client/**
  - `src/app/app.component.ts` - UI · Radio-Buttons · Live-Tabelle
  - `src/app/analysis.service.ts` - SSE-Client
  - `Dockerfile` & `angular.json`

---

_Kein Anlageberater. Technische Analyse nur zur Information._

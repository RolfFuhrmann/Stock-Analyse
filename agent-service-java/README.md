# agent-service-java

Java/Spring-Boot-Port des Python `agent-service`. Läuft **parallel** zum bestehenden
Python-Service auf einem eigenen Port – keine Einbindung in `docker-compose.yml` bisher.

## Port & Unterschiede

|                | Python `agent-service` | `agent-service-java`              |
| -------------- | ---------------------- | --------------------------------- |
| Port           | 8010                   | **8016**                          |
| Framework      | FastAPI + asyncio      | Spring Boot 3 + WebFlux (reaktiv) |
| Container-Name | `stock_agent`          | noch nicht in docker-compose      |

API-Vertrag (Request/Response-JSON) ist **identisch** zum Python-Service, der
Angular-Client kann ohne Anpassung gegen `agent-service-java` zeigen, sobald
die `AGENT_SERVICE_URL` in `analysis.service.ts` umgestellt wird.

## Struktur

```
src/main/java/rf/stock/agent/
├── AgentServiceApplication.java     Hauptklasse
├── config/
│   ├── ServiceConfig.java           URLs für Yahoo/TwelveData/ML/DB
│   └── WebConfig.java               CORS + WebClient-Bean
├── controller/
│   └── AgentController.java         /health, /analyze/stream, /analyze/stop
├── service/
│   ├── AnalysisService.java         Kernlogik: Live-Abruf für 1d, 4h und 1h
│   ├── DataServiceClient.java       SSE-Client für Yahoo/TwelveData (mit interval)
│   ├── DbClient.java                REST-Client zur DB (Lesen + Write-back)
│   ├── DailyBarWriteBackService.java     Write-back 1d-Kerzen in die DB
│   ├── IntradayBarWriteBackService.java  Write-back 1h/4h-Kerzen in die DB
│   ├── VpnService.java              VPN-Info und IP-Wechsel über Gluetun
│   └── MlClient.java                REST-Client für ML-Service
├── util/
│   ├── IntradayBarUtil.java         Zeitstempel normalisieren + 1h → 4h aggregieren
│   └── TradingDayUtil.java          Handelstage-Arithmetik (Write-back-Cutoff)
├── indicator/
│   ├── BullishIndicator.java        Elliott A-B-C + MACD<0 + Stoch<20
│   └── BearishIndicator.java        Elliott 1-2-3 + MACD>0 + Stoch>80
├── candle/
│   ├── CandleUtils.java             Kerzen-Hilfsfunktionen (negative Indizes)
│   ├── BullishCandlePatterns.java   5 Muster: Abandoned Baby, Morning Star,
│   │                                Engulfing, Piercing Line, Hammer
│   └── BearishCandlePatterns.java   4 Muster: Abandoned Baby, Dark Cloud Cover,
│                                    Engulfing, Shooting Star
└── model/                           Records für Request/Response/Domain-Objekte
```

## VPN-Steuerung

`GET /vpn/info` (Status, IP, Standort) und `POST /vpn/rotate` (IP wechseln)
sprechen mit dem Steuerungs-Server von Gluetun (`VPN_CONTROL_URL`, Default
`http://vpn:8000`). Gluetun braucht dafür eine `config.toml`, die
`GET /v1/vpn/status` und `PUT /v1/vpn/status` freigibt (Details in der
CLAUDE.md, Abschnitt 3.5). Ausgangs-IP und Standort kommen von
`yahoo-service GET /ip`.

## Live-Abruf und Write-back (1d, 4h, 1h)

Alle Intervalle werden live von Yahoo bzw. TwelveData abgerufen. Die
abgerufenen Kerzen werden zusätzlich asynchron (fire-and-forget) in die DB
zurückgeschrieben - ein Fehler dabei wird nur geloggt und beeinflusst die
Analyse nie.

| Quelle     | Ansicht | Abruf          | Geschrieben in                           |
| ---------- | ------- | -------------- | ---------------------------------------- |
| Yahoo      | 1d      | 1d             | `ohlcv_daily`                            |
| Yahoo      | 1h / 4h | 1h (Yahoo kann kein 4h) | `ohlcv_hourly` + aus 1h berechnet `ohlcv_4h` |
| TwelveData | 1d      | 1day           | `ohlcv_daily`                            |
| TwelveData | 1h      | 1h             | `ohlcv_hourly`                           |
| TwelveData | 4h      | 4h (nativ)     | `ohlcv_4h`                               |

Schreib-Regel (für alle Tabellen gleich): den neuesten Zeitstempel des
Tickers in der DB ermitteln, davon 5 Handelstage zurückrechnen, ab dort alles
Abgerufene schreiben. Vorhandene Kerzen werden überschrieben (Upsert im
DB-Service), fehlende ergänzt - das schließt die Lücke zwischen letztem
DB-Eintrag und jetzt. Ältere Lücken (mehr als 5 Handelstage vor dem letzten
DB-Eintrag) füllt nur der history-fetcher. Neuer Ticker ohne DB-Daten: alles
schreiben.

Intraday-Zeitstempel sind lokale Börsenzeit ohne Zone (`yyyy-MM-ddTHH:mm:ss`),
exakt wie im history-fetcher. Die 4h-Blöcke starten bei 0/4/8/12/16/20 Uhr
(lokale Börsenzeit), Blöcke mit weniger als 2 Kerzen werden verworfen.

## Build & Run lokal

```bash
cd agent-service-java
mvn spring-boot:run
```

Health-Check: `curl http://localhost:8016/health`

## Build & Run via Docker

```bash
docker build -t agent-service-java .
docker run -p 8016:8016\
  -e YAHOO_SERVICE_URL=http://host.docker.internal:8011 \
  -e TWELVEDATA_SERVICE_URL=http://host.docker.internal:8012 \
  -e ML_SERVICE_URL=http://host.docker.internal:8015 \
  -e DB_SERVICE_URL=http://host.docker.internal:8013 \
  agent-service-java
```

## Bekannte Unterschiede zum Python-Service (bewusst vereinfacht)

- **Ticker-Name-Lookup über yfinance** (`_get_ticker_name` in Python) wurde
  **nicht** portiert. Yahoo/TwelveData liefern den Namen bereits im
  SSE-Quote-Payload (`longName`/`shortName`) – Java nutzt nur diesen Wert.
  Falls ein Service den Namen nicht mitliefert, bleibt `name` null
  (Angular zeigt dann nur den Ticker an, kein Blocker).
- **Candlestick-Logik 1:1 portiert** – inklusive der "lockeren" (relaxed)
  Toleranzen im Bearish Abandoned Baby, die im Python-Original ebenfalls
  keinen strikten Trend-Check mehr hatten. Das ist also der richtige
  Ansatzpunkt, wenn du die Candle-Muster jetzt überarbeiten willst.

## Nächste Schritte (sobald der Service getestet ist)

1. In `docker-compose.yml` als `agent-service-java` (Port 8016) ergänzen
2. Im Angular Client testweise auf Port 8016 umstellen
3. Nach erfolgreicher Migration: Python-`agent-service` deaktivieren

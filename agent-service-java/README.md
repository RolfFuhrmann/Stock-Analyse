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
│   ├── AnalysisService.java         Kernlogik: Routing 1d (SSE) vs 4h/1h (DB)
│   ├── DataServiceClient.java       SSE-Client für Yahoo/TwelveData
│   ├── DbClient.java                REST-Client für 4h/1h-Kerzen aus der DB
│   └── MlClient.java                REST-Client für ML-Service
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

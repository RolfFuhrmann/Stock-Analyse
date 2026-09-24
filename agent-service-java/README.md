# agent-service-java

Java/Spring-Boot-Port des ursprünglichen Python `agent-service`. Läuft als
eigener Service (Port **8016**) im docker-compose-Stack, API-Vertrag
(Request/Response-JSON) ist **identisch** zum Python-Service.

## Port & Unterschiede

|                | Python `agent-service` | `agent-service-java`     |
| -------------- | ----------------------- | ------------------------ |
| Port           | 8010                    | **8016**                 |
| Framework      | FastAPI + asyncio       | Spring Boot 4.1 + WebFlux (reaktiv) |
| Java-Version   | –                       | 25                        |
| Container-Name | `stock_agent`           | `stock_agent_java`        |

## Struktur

```
src/main/java/rf/stock/agent/
├── AgentServiceApplication.java     Hauptklasse
├── config/
│   ├── ServiceConfig.java           URLs für Yahoo/TwelveData/ML/DB/VPN
│   └── WebConfig.java               CORS + WebClient-Bean
├── controller/
│   ├── AgentController.java         /health, /analyze/stream, /analyze/stop
│   ├── VpnController.java           /vpn/info, /vpn/rotate
│   └── MlController.java            /ml/info (reicht ml-service /model/info durch)
├── service/
│   ├── AnalysisService.java         Kernlogik: Live-Abruf für 1d, 4h und 1h
│   ├── DataServiceClient.java       SSE-Client für Yahoo/TwelveData (mit interval)
│   ├── DbClient.java                REST-Client zur DB (Lesen + Write-back)
│   ├── DailyBarWriteBackService.java     Write-back 1d-Kerzen in die DB
│   ├── IntradayBarWriteBackService.java  Write-back 1h/4h-Kerzen in die DB
│   ├── VpnService.java              VPN-Info und IP-Wechsel über Gluetun
│   └── MlClient.java                REST-Client für ML-Service (inkl. Erklärung je Vorhersage)
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
                                      (u.a. StockResult, MlExplanation, VpnInfo, VpnRotateResult)
```

## Live-Abruf und Write-back (1d, 4h, 1h)

Alle Intervalle werden live von Yahoo bzw. TwelveData abgerufen. Die
abgerufenen Kerzen werden zusätzlich asynchron (fire-and-forget) in die DB
zurückgeschrieben - ein Fehler dabei wird nur geloggt und beeinflusst die
Analyse nie.

| Quelle     | Ansicht | Abruf                   | Geschrieben in                               |
| ---------- | ------- | ------------------------ | --------------------------------------------- |
| Yahoo      | 1d      | 1d                        | `ohlcv_daily`                                 |
| Yahoo      | 1h / 4h | 1h (Yahoo kann kein 4h)   | `ohlcv_hourly` + aus 1h berechnet `ohlcv_4h`   |
| TwelveData | 1d      | 1day                      | `ohlcv_daily`                                 |
| TwelveData | 1h      | 1h                        | `ohlcv_hourly`                                |
| TwelveData | 4h      | 4h (nativ)                | `ohlcv_4h`                                    |

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

## ML-Integration

`MlClient` ruft `POST /predict/{ticker}` im ml-service auf und reicht das
Ergebnis unverändert als `ml_signal`/`reversal_pct`/`ml_explanation` im
`StockResult` an den Client durch. `ml_explanation` enthält die Merkmale, die
den Modellwert am stärksten beeinflussen (Basiswert, Top-8-Merkmale mit
Einfluss in Prozentpunkten, Rest zusammengefasst) - Grundlage für den
"Warum dieser Wert?"-Dialog im Client. `GET /ml/info` reicht zusätzlich
`GET /model/info` des ml-service durch (Trainingsstand, Metriken,
Merkmals-Wichtigkeit, Kalibrierungsstatus) für die Einstellungen im Client.

## VPN-Steuerung

`GET /vpn/info` (Status, IP, Standort) und `POST /vpn/rotate` (IP wechseln)
sprechen mit dem Steuerungs-Server von Gluetun (`VPN_CONTROL_URL`, Default
`http://vpn:8000`). Gluetun braucht dafür eine `config.toml`, die
`GET /v1/vpn/status` und `PUT /v1/vpn/status` freigibt (Details in der
CLAUDE.md, Abschnitt 3.5). Ausgangs-IP und Standort kommen von
`yahoo-service GET /ip` (mehrere IP-Datenbanken nacheinander als Fallback).
Der Client sperrt den Button während einer laufenden Analyse; ein Wechsel
läuft serverseitig unabhängig vom aufrufenden HTTP-Request zu Ende, auch wenn
der Browser-Tab währenddessen geschlossen wird.

## Build & Run lokal

```bash
cd agent-service-java
mvn spring-boot:run
```

Health-Check: `curl http://localhost:8016/health`

## Build & Run via Docker

```bash
docker build -t agent-service-java .
docker run -p 8016:8016 \
  -e YAHOO_SERVICE_URL=http://host.docker.internal:8011 \
  -e TWELVEDATA_SERVICE_URL=http://host.docker.internal:8012 \
  -e ML_SERVICE_URL=http://host.docker.internal:8015 \
  -e DB_SERVICE_URL=http://host.docker.internal:8013 \
  -e VPN_CONTROL_URL=http://host.docker.internal:8000 \
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

## Bekannte offene Punkte

Siehe CLAUDE.md, Abschnitt 3.2c, für die vollständige, laufend aktualisierte
Liste (u.a. Zeitstempel-Konvention der Intraday-Tabellen, Yahoo-Stundendaten-
Tiefe, Trennung Erklärung/Kalibrierung im ML-Modell).

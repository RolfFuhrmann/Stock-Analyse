-- V4__add_ohlcv_tables.sql
-- Historische Kursdaten für ML-Training und schnellere Analyse-Läufe.
-- Drei neue Tabellen: ticker_meta, ohlcv_daily, ohlcv_hourly, fetch_log.

-- ── ticker_meta ──────────────────────────────────────────────────────────────
-- Stammdaten pro Ticker: normalisiertes Yahoo-Symbol, Name, ISIN, Sektor.
-- Verbindet die raw_symbols aus ticker_symbols mit den API-Symbolen.

CREATE TABLE IF NOT EXISTS ticker_meta (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    -- Normalisiertes API-Symbol (z.B. ADS.DE, AAPL) – eindeutig
    ticker           VARCHAR(30)  NOT NULL UNIQUE,
    -- Roh-Symbol wie in ticker_symbols gespeichert (z.B. ADS)
    raw_symbol       VARCHAR(20)  NOT NULL,
    -- Datenquelle: yahoo | twelvedata
    source           VARCHAR(20)  NOT NULL DEFAULT 'yahoo',
    company_name     VARCHAR(200),
    isin             VARCHAR(12),
    sector           VARCHAR(100),
    country          VARCHAR(50),
    -- Letzter erfolgreicher Metadaten-Abruf
    last_refreshed   DATETIME,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_meta_raw_symbol (raw_symbol),
    INDEX idx_meta_source     (source)
) COMMENT = 'Stammdaten pro Ticker – verbindet raw_symbol mit normalisierten API-Symbolen';


-- ── ohlcv_daily ──────────────────────────────────────────────────────────────
-- Tageskerzen (Open/High/Low/Close/Volume) für die historische Analyse.
-- Ziel: 5 Jahre pro Ticker (~1.250 Kerzen). Speicherbedarf: ~25 MB für 70 Ticker.

CREATE TABLE IF NOT EXISTS ohlcv_daily (
    id           BIGINT         AUTO_INCREMENT PRIMARY KEY,
    -- Normalisiertes API-Symbol (Fremdschlüssel-Logik via Applikation, kein FK wegen Bulk-Performance)
    ticker       VARCHAR(30)    NOT NULL,
    trade_date   DATE           NOT NULL,
    open         DECIMAL(12, 4) NOT NULL,
    high         DECIMAL(12, 4) NOT NULL,
    low          DECIMAL(12, 4) NOT NULL,
    close        DECIMAL(12, 4) NOT NULL,
    volume       BIGINT,
    -- Datenquelle des Abrufs: yahoo | twelvedata
    source       VARCHAR(20)    NOT NULL,
    -- Zeitpunkt des Datenbankeintrags
    fetched_at   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Pro Ticker darf es pro Tag nur eine Kerze geben
    UNIQUE KEY uq_ohlcv_daily_ticker_date (ticker, trade_date),
    INDEX idx_ohlcv_daily_ticker      (ticker),
    INDEX idx_ohlcv_daily_trade_date  (trade_date),
    -- Kombinierter Index für typische ML-Abfragen: WHERE ticker = ? ORDER BY trade_date
    INDEX idx_ohlcv_daily_ticker_date (ticker, trade_date)
) COMMENT = 'Tageskerzen – Basis für Elliott Wave, MACD, Stochastik und ML-Training';


-- ── ohlcv_hourly ─────────────────────────────────────────────────────────────
-- Stundenkerzen für feinere Einstiegssignale (optional, 6–12 Monate).
-- Speicherbedarf: ~34 MB für 70 Ticker / 12 Monate.

CREATE TABLE IF NOT EXISTS ohlcv_hourly (
    id           BIGINT         AUTO_INCREMENT PRIMARY KEY,
    ticker       VARCHAR(30)    NOT NULL,
    -- Zeitpunkt der Kerze (UTC) – Stunden-Granularität
    trade_time   DATETIME       NOT NULL,
    open         DECIMAL(12, 4) NOT NULL,
    high         DECIMAL(12, 4) NOT NULL,
    low          DECIMAL(12, 4) NOT NULL,
    close        DECIMAL(12, 4) NOT NULL,
    volume       BIGINT,
    source       VARCHAR(20)    NOT NULL,
    fetched_at   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_ohlcv_hourly_ticker_time (ticker, trade_time),
    INDEX idx_ohlcv_hourly_ticker      (ticker),
    INDEX idx_ohlcv_hourly_trade_time  (trade_time),
    INDEX idx_ohlcv_hourly_ticker_time (ticker, trade_time)
) COMMENT = 'Stundenkerzen – für feinere Einstiegspunkte und späteres ML-Feintuning';


-- ── fetch_log ─────────────────────────────────────────────────────────────────
-- Protokolliert jeden Datenabruf: Erstbefüllung und tägliche Updates.
-- Basis für Monitoring und Fehlersuche im history-fetcher-Service.

CREATE TABLE IF NOT EXISTS fetch_log (
    id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
    ticker        VARCHAR(30)   NOT NULL,
    -- Intervall des Abrufs: daily | hourly
    interval_type VARCHAR(10)   NOT NULL,
    source        VARCHAR(20)   NOT NULL,
    -- Ergebnis: SUCCESS | ERROR | PARTIAL
    status        VARCHAR(10)   NOT NULL,
    -- Anzahl neu geschriebener Zeilen (0 bei Fehler)
    bars_fetched  INT           NOT NULL DEFAULT 0,
    -- Fehlermeldung (NULL bei Erfolg)
    error_msg     VARCHAR(500),
    run_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_fetch_log_ticker  (ticker),
    INDEX idx_fetch_log_run_at  (run_at),
    INDEX idx_fetch_log_status  (status)
) COMMENT = 'Protokoll aller Datenabrufe – für Monitoring und Fehlerdiagnose';

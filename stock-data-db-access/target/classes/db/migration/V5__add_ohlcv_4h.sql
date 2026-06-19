-- V5__add_ohlcv_4h.sql
-- 4-Stunden-Kerzen für kürzere Analyse-Zeitrahmen.
-- Yahoo-Ticker: durch Aggregation aus ohlcv_hourly befüllt (4 × 1h → 1 × 4h).
-- TwelveData-Ticker: direkt via interval="4h" abgerufen.

CREATE TABLE IF NOT EXISTS ohlcv_4h (
    id          BIGINT          AUTO_INCREMENT PRIMARY KEY,
    -- Normalisiertes API-Symbol (z.B. ADS.DE, AAPL)
    ticker      VARCHAR(30)     NOT NULL,
    -- Kerzenbeginn in UTC (z.B. 08:00, 12:00, 16:00 für Xetra-Handelstage)
    trade_time  DATETIME        NOT NULL,
    open        DECIMAL(12,4)   NOT NULL,
    high        DECIMAL(12,4)   NOT NULL,
    low         DECIMAL(12,4)   NOT NULL,
    close       DECIMAL(12,4)   NOT NULL,
    volume      BIGINT,
    -- Datenquelle: yahoo (aggregiert) | twelvedata (nativ)
    source      VARCHAR(20)     NOT NULL DEFAULT 'yahoo',
    fetched_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_ohlcv_4h_ticker_time (ticker, trade_time),
    INDEX idx_ohlcv_4h_ticker     (ticker),
    INDEX idx_ohlcv_4h_trade_time (trade_time)
) COMMENT = '4h-OHLCV-Kerzen – Yahoo via Aggregation aus 1h, TwelveData nativ';

-- V1__init_schema.sql
-- Initiales Schema für die Stock-Platform Listen-Verwaltung

CREATE TABLE IF NOT EXISTS ticker_lists (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100)  NOT NULL,
    -- Eindeutiger technischer Schlüssel, z.B. "DAX40", "DOW30"
    code       VARCHAR(50)   NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ticker_symbols (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    list_id      BIGINT        NOT NULL,
    -- Roher Ticker so wie er vom Nutzer eingegeben wird (z.B. "ADS", "AAPL")
    raw_symbol   VARCHAR(20)   NOT NULL,
    -- Normalisierter Ticker für die jeweilige Datenquelle (z.B. "ADS.DE" für XETRA/Yahoo)
    yahoo_symbol VARCHAR(20)   NOT NULL,
    -- Börsenplatz als Enum-String: XETRA, NYSE, NASDAQ, LSE, EURONEXT
    exchange     VARCHAR(20)   NOT NULL,
    display_name VARCHAR(100),
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_ticker_list FOREIGN KEY (list_id) REFERENCES ticker_lists(id) ON DELETE CASCADE,
    -- Pro Liste darf ein raw_symbol nur einmal vorkommen
    CONSTRAINT uq_list_symbol UNIQUE (list_id, raw_symbol)
);

-- Index für schnelle Abfragen nach Liste
CREATE INDEX idx_ticker_symbols_list_id ON ticker_symbols(list_id);

-- Seed-Daten: DAX 40
INSERT INTO ticker_lists (name, code, description) VALUES
    ('DAX 40',    'DAX40', 'Deutscher Aktienindex – 40 größte Unternehmen'),
    ('Dow Jones', 'DOW30', 'Dow Jones Industrial Average – 30 US-Unternehmen');

-- DAX 40 Ticker (list_id = 1, Börsenplatz XETRA → yahoo_symbol bekommt .DE-Suffix)
INSERT INTO ticker_symbols (list_id, raw_symbol, yahoo_symbol, exchange, display_name) VALUES
    (1, 'ADS',  'ADS.DE',  'XETRA', 'Adidas'),
    (1, 'AIR',  'AIR.DE',  'XETRA', 'Airbus'),
    (1, 'ALV',  'ALV.DE',  'XETRA', 'Allianz'),
    (1, 'BAS',  'BAS.DE',  'XETRA', 'BASF'),
    (1, 'BAYN', 'BAYN.DE', 'XETRA', 'Bayer'),
    (1, 'BEI',  'BEI.DE',  'XETRA', 'Beiersdorf'),
    (1, 'BMW',  'BMW.DE',  'XETRA', 'BMW'),
    (1, 'BNR',  'BNR.DE',  'XETRA', 'Brenntag'),
    (1, 'CON',  'CON.DE',  'XETRA', 'Continental'),
    (1, 'DTE',  'DTE.DE',  'XETRA', 'Deutsche Telekom'),
    (1, 'EOAN', 'EOAN.DE', 'XETRA', 'E.ON'),
    (1, 'FRE',  'FRE.DE',  'XETRA', 'Fresenius'),
    (1, 'HEI',  'HEI.DE',  'XETRA', 'Heidelberg Materials'),
    (1, 'HEN3', 'HEN3.DE', 'XETRA', 'Henkel'),
    (1, 'IFX',  'IFX.DE',  'XETRA', 'Infineon'),
    (1, 'MBG',  'MBG.DE',  'XETRA', 'Mercedes-Benz'),
    (1, 'MRK',  'MRK.DE',  'XETRA', 'Merck'),
    (1, 'MTX',  'MTX.DE',  'XETRA', 'MTU Aero Engines'),
    (1, 'MUV2', 'MUV2.DE', 'XETRA', 'Munich Re'),
    (1, 'P911', 'P911.DE', 'XETRA', 'Porsche AG'),
    (1, 'PAH3', 'PAH3.DE', 'XETRA', 'Porsche SE'),
    (1, 'RHM',  'RHM.DE',  'XETRA', 'Rheinmetall'),
    (1, 'RWE',  'RWE.DE',  'XETRA', 'RWE'),
    (1, 'SAP',  'SAP.DE',  'XETRA', 'SAP'),
    (1, 'SHL',  'SHL.DE',  'XETRA', 'Siemens Healthineers'),
    (1, 'SIE',  'SIE.DE',  'XETRA', 'Siemens'),
    (1, 'SY1',  'SY1.DE',  'XETRA', 'Symrise'),
    (1, 'VOW3', 'VOW3.DE', 'XETRA', 'Volkswagen'),
    (1, 'VNA',  'VNA.DE',  'XETRA', 'Vonovia'),
    (1, 'ZAL',  'ZAL.DE',  'XETRA', 'Zalando'),
    (1, 'DB1',  'DB1.DE',  'XETRA', 'Deutsche Börse'),
    (1, 'DBK',  'DBK.DE',  'XETRA', 'Deutsche Bank'),
    (1, 'DHL',  'DHL.DE',  'XETRA', 'DHL Group'),
    (1, 'DTG',  'DTG.DE',  'XETRA', 'Daimler Truck'),
    (1, 'ENR',  'ENR.DE',  'XETRA', 'Siemens Energy'),
    (1, 'FNTN', 'FNTN.DE', 'XETRA', 'Freenet'),
    (1, 'HNR1', 'HNR1.DE', 'XETRA', 'Hannover Rück'),
    (1, 'LIN',  'LIN',     'NYSE',  'Linde (US-Listing)'),
    (1, 'QIAGEN','QGEN',   'NYSE',  'Qiagen (US-Listing)'),
    (1, 'COK',  'COK.DE',  'XETRA', 'Covestro');

-- Dow Jones Ticker (list_id = 2, Börsenplatz NYSE/NASDAQ → yahoo_symbol = raw_symbol)
INSERT INTO ticker_symbols (list_id, raw_symbol, yahoo_symbol, exchange, display_name) VALUES
    (2, 'AAPL',  'AAPL',  'NASDAQ', 'Apple'),
    (2, 'AMGN',  'AMGN',  'NASDAQ', 'Amgen'),
    (2, 'AXP',   'AXP',   'NYSE',   'American Express'),
    (2, 'BA',    'BA',    'NYSE',   'Boeing'),
    (2, 'CAT',   'CAT',   'NYSE',   'Caterpillar'),
    (2, 'CRM',   'CRM',   'NYSE',   'Salesforce'),
    (2, 'CSCO',  'CSCO',  'NASDAQ', 'Cisco'),
    (2, 'CVX',   'CVX',   'NYSE',   'Chevron'),
    (2, 'DIS',   'DIS',   'NYSE',   'Walt Disney'),
    (2, 'DOW',   'DOW',   'NYSE',   'Dow Inc.'),
    (2, 'GS',    'GS',    'NYSE',   'Goldman Sachs'),
    (2, 'HD',    'HD',    'NYSE',   'Home Depot'),
    (2, 'HON',   'HON',   'NASDAQ', 'Honeywell'),
    (2, 'IBM',   'IBM',   'NYSE',   'IBM'),
    (2, 'INTC',  'INTC',  'NASDAQ', 'Intel'),
    (2, 'JNJ',   'JNJ',   'NYSE',   'Johnson & Johnson'),
    (2, 'JPM',   'JPM',   'NYSE',   'JPMorgan Chase'),
    (2, 'KO',    'KO',    'NYSE',   'Coca-Cola'),
    (2, 'MCD',   'MCD',   'NYSE',   'McDonald''s'),
    (2, 'MMM',   '3M',   'NYSE',   '3M'),-- MMM ist der offizielle Ticker
    (2, 'MRK',   'MRK',   'NYSE',   'Merck & Co.'),
    (2, 'MSFT',  'MSFT',  'NASDAQ', 'Microsoft'),
    (2, 'NKE',   'NKE',   'NYSE',   'Nike'),
    (2, 'PG',    'PG',    'NYSE',   'Procter & Gamble'),
    (2, 'TRV',   'TRV',   'NYSE',   'Travelers'),
    (2, 'UNH',   'UNH',   'NYSE',   'UnitedHealth'),
    (2, 'V',     'V',     'NYSE',   'Visa'),
    (2, 'VZ',    'VZ',    'NYSE',   'Verizon'),
    (2, 'WMT',   'WMT',   'NYSE',   'Walmart'),
    (2, 'AMZN',  'AMZN',  'NASDAQ', 'Amazon');

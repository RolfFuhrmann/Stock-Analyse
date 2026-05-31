-- V3__add_ticker_format.sql
-- Ticker-Format pro Liste: RAW (unverändert), XETRA (.DE), CUSTOM (frei wählbar)

ALTER TABLE ticker_lists
    ADD COLUMN ticker_format VARCHAR(10) NOT NULL DEFAULT 'RAW'
        COMMENT 'RAW = unverändert | XETRA = .DE anhängen | CUSTOM = custom_suffix',
    ADD COLUMN custom_suffix VARCHAR(10) NULL
        COMMENT 'Nur relevant wenn ticker_format = CUSTOM, z.B. .PA oder .L';

-- Bestehende Listen sinnvoll migrieren
UPDATE ticker_lists SET ticker_format = 'XETRA' WHERE code = 'DAX40';
UPDATE ticker_lists SET ticker_format = 'RAW'   WHERE code = 'DOW30';

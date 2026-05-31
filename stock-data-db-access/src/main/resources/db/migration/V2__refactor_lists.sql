-- V2__refactor_lists.sql
-- 1. yahoo_symbol aus ticker_symbols entfernen (wird im Client berechnet)
-- 2. source-Spalte zu ticker_lists hinzufügen (Yahoo Finance / TwelveData)

ALTER TABLE ticker_symbols
    DROP COLUMN yahoo_symbol;

ALTER TABLE ticker_lists
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'yahoo'
        COMMENT 'Datenquelle: yahoo | twelvedata';

-- Bestehende Listen mit sinnvollen Defaults befüllen
UPDATE ticker_lists SET source = 'yahoo'      WHERE code = 'DAX40';
UPDATE ticker_lists SET source = 'twelvedata' WHERE code = 'DOW30';

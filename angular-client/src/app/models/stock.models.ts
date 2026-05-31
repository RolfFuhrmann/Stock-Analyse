export type DataSource   = 'yahoo' | 'twelvedata';
export type TickerFormat = 'RAW' | 'XETRA' | 'CUSTOM';

export interface StockResult {
  ticker: string;
  current_price: number | null;
  trend_pct: number | null;
  elliott_wave: boolean;
  stochastic: boolean;
  macd_histogram: boolean;
  criteria_met: number;
  source: string;
  candle_pattern: string | null;
  candle_strength: number;
  error: string | null;
}

export interface FilterState {
  source: DataSource;
  tickers: string[];
  lookbackDays: number;
}

export interface AnalysisSummary {
  total: number;
  count3of3: number;
  count2of3: number;
  source: DataSource;
}

export interface CriteriaFilter {
  elliott:    boolean;
  stochastic: boolean;
  macd:       boolean;
  minScore:   2 | 3 | null;
}

export const EMPTY_FILTER: CriteriaFilter = {
  elliott: false, stochastic: false, macd: false, minScore: null,
};

// ── Ticker-Listen ─────────────────────────────────────────

export interface TickerSymbol {
  id: number;
  rawSymbol: string;
  displayName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TickerList {
  id: number;
  name: string;
  code: string;
  description: string | null;
  source: DataSource;
  tickerFormat: TickerFormat;
  customSuffix: string | null;
  symbolCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TickerListDetail extends TickerList {
  symbols: TickerSymbol[];
}

export interface TickerListRequest {
  name: string;
  code: string;
  description: string | null;
  source: DataSource;
  tickerFormat: TickerFormat;
  customSuffix: string | null;
}

export interface TickerSymbolRequest {
  rawSymbol: string;
  displayName: string | null;
}

/**
 * Business-Logik: Ticker für Yahoo Finance normalisieren.
 *   RAW    → unverändert  (Indizes: ^GDAXI, US: AAPL)
 *   XETRA  → .DE anhängen (ADS → ADS.DE)
 *   CUSTOM → customSuffix anhängen (z.B. HSBA → HSBA.L)
 *
 * Bereits enthaltene Punkte werden nie doppelt gesuffixed.
 */
export function toYahooSymbol(
  rawSymbol: string,
  tickerFormat: TickerFormat,
  customSuffix: string | null = null
): string {
  if (rawSymbol.includes('.') || rawSymbol.startsWith('^')) return rawSymbol;
  switch (tickerFormat) {
    case 'XETRA':  return rawSymbol + '.DE';
    case 'CUSTOM': return customSuffix ? rawSymbol + customSuffix : rawSymbol;
    default:       return rawSymbol; // RAW
  }
}

/** Label-Text für den Format-Badge im Panel. */
export function formatBadgeLabel(format: TickerFormat, customSuffix: string | null): string {
  switch (format) {
    case 'XETRA':  return 'XETRA (.DE)';
    case 'CUSTOM': return `CUSTOM (${customSuffix ?? '?'})`;
    default:       return 'RAW';
  }
}

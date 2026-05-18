// ─────────────────────────────────────────────
// Domain Models – Stock Analyse Platform
// ─────────────────────────────────────────────

export type DataSource = 'yahoo' | 'twelvedata';

export interface StockResult {
  ticker: string;
  current_price: number | null;
  trend_pct: number | null;
  elliott_wave: boolean;
  stochastic: boolean;
  macd_histogram: boolean;
  criteria_met: number;
  source: string;
  candle_pattern: string | null;  // z.B. "Bullish Engulfing", null wenn kein Muster
  candle_strength: number;        // 0–5, höher = stärkeres Signal
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

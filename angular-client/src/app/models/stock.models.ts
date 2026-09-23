export type DataSource   = 'yahoo' | 'twelvedata';
export type TickerFormat = 'RAW' | 'XETRA' | 'CUSTOM';
export type Interval     = '1d' | '4h' | '1h';

export const INTERVAL_LABELS: Record<Interval, string> = {
  '1d': 'Daily',
  '4h': '4 Stunden',
  '1h': '1 Stunde',
};

export const INTERVAL_LOOKBACK: Record<Interval, number> = {
  '1d': 230, // synchron zu ELLIOTT_LOOKBACK_BY_INTERVAL["1d"] in agent-service-java (siehe filter-header.component.ts)
  '4h': 180,
  '1h': 200,
};

export interface StockResult {
  ticker: string;
  name: string | null;
  /** ISO-4217-Code (z.B. "USD", "EUR"), null falls der Daten-Service keine Währung liefert. */
  currency: string | null;
  interval: '1d' | '4h' | '1h';
  current_price: number | null;
  trend_pct: number | null;
  /** Erkannte Richtung der Umkehr: "bullish", "bearish" oder null */
  trend_direction: 'bullish' | 'bearish' | null;
  /**
   * Eigenständige Richtung, rein aus MACD-Histogramm + Stochastik abgeleitet
   * (unabhängig davon, welcher Indikator für elliott_wave/trend_direction
   * "gewonnen" hat):
   *   "bullish" – MACD-Histogramm > 0 UND Stochastik > 80
   *   "bearish" – MACD-Histogramm < 0 UND Stochastik < 20
   *   null      – keines von beidem eindeutig erfüllt
   */
  macd_stoch_direction: 'bullish' | 'bearish' | null;
  elliott_wave: boolean;
  /**
   * Menschenlesbarer Zwischenstand der aktuellen ta4j-Wellenanalyse
   * (z.B. "A-B abgeschlossen, C im Entstehen"), unabhängig davon ob
   * elliott_wave true/false ist. Leerstring, wenn kein Szenario gefunden
   * wurde (z.B. zu wenig Datenpunkte).
   */
  elliott_wave_stage: string;
  /**
   * Bars/Swings/Zielpreis der ta4j-Elliott-Analyse für die Chart-
   * Visualisierung (Thumbnail + Modal). null, wenn ta4j kein Szenario
   * gefunden hat (z.B. zu wenig Datenpunkte) - unabhängig von elliott_wave.
   */
  elliott_chart: ElliottChartData | null;
  stochastic: boolean;
  macd_histogram: boolean;
  criteria_met: number;
  source: string;
  /**
   * Allgemeingültiges Candlestick-Pattern-Ergebnis (07.09., ersetzt die
   * vorherigen Einzelfelder candle_pattern/candle_strength/candle_gd_period).
   * pattern ist null wenn kein Muster erkannt wurde - das Objekt selbst ist
   * nie null.
   */
  candle: CandlePattern;
  // ── ML-Signal ──────────────────────────────────────────
  reversal_prob:  number | null;
  reversal_pct:   number | null;
  ml_signal:      'none' | 'weak' | 'moderate' | 'strong';
  ml_confidence:  'low' | 'medium' | 'high';
  ml_available:   boolean;
  /** Erklärung des Modellwerts (welche Merkmale ihn bestimmen) - null, wenn nicht geliefert */
  ml_explanation?: MlExplanation | null;
  // ───────────────────────────────────────────────────────
  error: string | null;
}

// ── ML-Erklärung ─────────────────────────────────────────────

/** Ein Merkmal und sein Einfluss auf den ML-Modellwert. */
export interface MlFactor {
  /** technischer Name, z.B. "vol_20d" */
  feature:    string;
  /** deutsche Bezeichnung */
  label:      string;
  value:      number;
  /** aufbereiteter Wert für die Anzeige, z.B. "4,1 %" */
  value_text: string;
  /** Einfluss in Prozentpunkten: positiv schiebt den Wert nach oben, negativ nach unten */
  effect_pp:  number;
}

/** Zusammensetzung eines Modellwerts: base_pct + Σ effect_pp + other_pp = prob_pct. */
export interface MlExplanation {
  interval:          '1d' | '4h' | '1h';
  base_pct:          number;
  prob_pct:          number;
  factors:           MlFactor[];
  other_pp:          number;
  other_count:       number;
  /** typischer Modellwert für diesen Zeitrahmen im Training (null bei älteren Modellen) */
  typical_score_pct: number | null;
  /** Anteil tatsächlicher Anstiege für diesen Zeitrahmen im Training (null bei älteren Modellen) */
  actual_rate_pct:   number | null;
}

/** Kennzahlen je Zeitrahmen aus dem Training (nur bei Modellen ab 21.09.). */
export interface MlIntervalBreakdown {
  train_samples:     number;
  test_samples:      number;
  positive_rate_pct: number | null;
  mean_score_pct:    number | null;
  test_roc_auc?:     number;
  test_precision?:   number;
  test_recall?:      number;
}

/** Ein Wahrscheinlichkeits-Bin der Kalibrierung (Testmenge). */
export interface MlCalibrationBin {
  bin_from:      number;
  bin_to:        number;
  samples:       number;
  predicted_pct: number;
  actual_pct:    number;
}

/** Antwort von GET /ml/info im agent-service-java (durchgereicht vom ml-service). */
export interface MlModelInfo {
  model_ready:              boolean;
  error?:                   string;
  training_active?:         boolean;
  trained_at?:              string | null;
  next_retrain?:            string | null;
  total_samples?:           number;
  train_samples?:           number;
  val_samples?:             number;
  test_samples?:            number;
  /** false = Modell vor dem 21.09. trainiert, Modellwert ist unkalibriert */
  calibrated?:              boolean;
  tickers_count?:           number;
  positive_rate_pct?:       number;
  forecast_horizon?:        number;
  reversal_threshold_pct?:  number;
  intervals_trained?:       string[];
  scale_pos_weight?:        number | null;
  metrics?:                 { precision?: number; recall?: number; roc_auc?: number };
  thresholds?:              { weak: number; moderate: number; strong: number };
  feature_importance?:      { feature: string; label: string; importance: number }[];
  breakdown?:               Record<string, MlIntervalBreakdown> | null;
  calibration?:             MlCalibrationBin[] | null;
}

/**
 * Allgemeingültiges Candlestick-Pattern-Ergebnis, für alle Muster nutzbar
 * (07.09.). Aktuell befüllt candle_dates/confirmed nur Hammer - bei den
 * übrigen Mustern (Morning Star, Bullish/Bearish Engulfing, Piercing Line,
 * Abandoned Baby) ist candle_dates null und confirmed immer false.
 */
export interface CandlePattern {
  /** z.B. "Hammer", "Morning Star" oder null (kein Muster). */
  pattern: string | null;
  /** 0–5, 0 = kein Muster. */
  strength: number;
  /** Welcher GD (20/50/200) den Trend bestätigt hat. null bei ADX-basierten Mustern (aktuell nur Hammer), Abandoned Baby und fehlendem Muster. */
  gd_period: number | null;
  /**
   * Datum/Daten der am Muster beteiligten Kerze(n). Bei confirmed=true
   * enthält die Liste BEIDE Daten (Musterkerze + Bestätigungskerze).
   */
  candle_dates: string[] | null;
  /**
   * true, wenn nicht die Musterkerze selbst, sondern eine nachfolgende
   * Kerze das Signal auslöst (z.B. Hammer + Bestätigung durch höheren
   * Schlusskurs am Folgetag).
   */
  confirmed: boolean;
}

// ── Elliott-Wave-Chart ("Option C") ──────────────────────

export interface ElliottChartBar {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number | null;
}

export interface ElliottSwingPoint {
  label: string;
  from_date: string;
  from_price: number;
  to_date: string;
  to_price: number;
}

export interface ElliottTarget {
  price: number;
  retracement_pct: number | null;
}

export interface ElliottChartData {
  bars: ElliottChartBar[];
  swings: ElliottSwingPoint[];
  target: ElliottTarget | null;
}

export interface FilterState {
  source: DataSource;
  interval: Interval;
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

// ── VPN (Einstellungen) ──────────────────────────────────────

/** Antwort von GET /vpn/info im agent-service-java. */
export interface VpnInfo {
  /** Tunnel-Zustand laut Gluetun ("running", "stopped", ...) oder null */
  status:       string | null;
  ip:           string | null;
  city:         string | null;
  region:       string | null;
  /** ISO-Ländercode, z.B. "NL" */
  country:      string | null;
  organization: string | null;
  /** Hinweis, wenn Teile der Daten nicht ermittelt werden konnten */
  error:        string | null;
}

/** Antwort von POST /vpn/rotate. Fehler stehen im Feld "error" (kein HTTP-Fehler). */
export interface VpnRotateResult {
  oldIp:    string | null;
  newIp:    string | null;
  changed:  boolean;
  attempts: number;
  error:    string | null;
}

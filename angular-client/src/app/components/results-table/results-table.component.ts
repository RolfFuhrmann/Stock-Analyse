import { Component, input, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';
import { StockResult } from '../../models/stock.models';

type SortColumn = 'ticker' | 'name' | 'price' | 'trend' | 'direction' | 'elliott' | 'stochastic' | 'macd' | 'score' | 'candle' | 'ml';
type SortDir = 'asc' | 'desc' | null;

/**
 * ResultsTableComponent
 *
 * Zeigt alle analysierten Ticker in einer vollständig sortierbaren Tabelle.
 * Jede Spalte ist per Klick auf- oder absteigend sortierbar (3-Klick-Rotation).
 * Die Sortierung läuft über ein computed() Signal und ist SSE-Streaming-kompatibel.
 *
 * Farbliche Kennzeichnung:
 *   - Grüner Hintergrund: alle 3 Kriterien erfüllt
 *   - Gelber Hintergrund: 2 von 3 Kriterien erfüllt
 */
@Component({
  selector: 'app-results-table',
  standalone: true,
  imports: [CommonModule, MatTableModule, MatChipsModule, MatTooltipModule, MatIconModule],
  template: `
    <div class="table-wrapper">
      <div class="table-container">
        <table mat-table [dataSource]="sortedResults()" class="results-table mat-elevation-z1">

        <!-- Ticker -->
        <ng-container matColumnDef="ticker">
          <th mat-header-cell *matHeaderCellDef class="sortable-header" (click)="sortBy('ticker')">
            Basiswert <mat-icon class="sort-icon">{{ sortIcon('ticker') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row">
            <span class="ticker-name">{{ row.ticker }}</span>
            @if (row.error) {
              <div class="ticker-error">{{ row.error }}</div>
            }
          </td>
        </ng-container>

        <!-- Name -->
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef class="col-left sortable-header" (click)="sortBy('name')">
            Name <mat-icon class="sort-icon">{{ sortIcon('name') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-name">
            {{ row.name ?? '–' }}
          </td>
        </ng-container>

        <!-- Kurs -->
        <ng-container matColumnDef="price">
          <th mat-header-cell *matHeaderCellDef class="col-right sortable-header" (click)="sortBy('price')">
            Kurs <mat-icon class="sort-icon">{{ sortIcon('price') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-right numeric">
            {{ row.current_price != null ? currencySymbol(row.ticker) + ' ' + row.current_price.toFixed(2) : '–' }}
          </td>
        </ng-container>

        <!-- Trend -->
        <ng-container matColumnDef="trend">
          <th mat-header-cell *matHeaderCellDef class="col-right sortable-header" (click)="sortBy('trend')">
            Trend <mat-icon class="sort-icon">{{ sortIcon('trend') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-right numeric"
              [class.positive]="(row.trend_pct ?? 0) >= 0"
              [class.negative]="(row.trend_pct ?? 0) < 0">
            {{ row.trend_pct != null
               ? ((row.trend_pct >= 0 ? '+' : '') + row.trend_pct.toFixed(1) + '%')
               : '–' }}
          </td>
        </ng-container>

        <!-- Richtung -->
        <ng-container matColumnDef="direction">
          <th mat-header-cell *matHeaderCellDef class="col-center sortable-header" (click)="sortBy('direction')">
            Richtung <mat-icon class="sort-icon">{{ sortIcon('direction') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-center">
            @if (row.trend_direction === 'bullish') {
              <span class="direction-bullish">▲ Bullish</span>
            } @else if (row.trend_direction === 'bearish') {
              <span class="direction-bearish">▼ Bearish</span>
            } @else {
              <span class="direction-none">–</span>
            }
          </td>
        </ng-container>

        <!-- Elliott Wave -->
        <ng-container matColumnDef="elliott">
          <th mat-header-cell *matHeaderCellDef class="col-center sortable-header" (click)="sortBy('elliott')">
            Elliott Wave <mat-icon class="sort-icon">{{ sortIcon('elliott') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-center">
            <span [class]="badgeClass(row.elliott_wave)">{{ row.elliott_wave ? 'True' : 'False' }}</span>
          </td>
        </ng-container>

        <!-- Stochastik -->
        <ng-container matColumnDef="stochastic">
          <th mat-header-cell *matHeaderCellDef class="col-center sortable-header" (click)="sortBy('stochastic')">
            Stochastik <mat-icon class="sort-icon">{{ sortIcon('stochastic') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-center">
            <span [class]="badgeClass(row.stochastic)">{{ row.stochastic ? 'True' : 'False' }}</span>
          </td>
        </ng-container>

        <!-- MACD -->
        <ng-container matColumnDef="macd">
          <th mat-header-cell *matHeaderCellDef class="col-center sortable-header" (click)="sortBy('macd')">
            MACD-Histogramm <mat-icon class="sort-icon">{{ sortIcon('macd') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-center">
            <span [class]="badgeClass(row.macd_histogram)">{{ row.macd_histogram ? 'True' : 'False' }}</span>
          </td>
        </ng-container>

        <!-- Score -->
        <ng-container matColumnDef="score">
          <th mat-header-cell *matHeaderCellDef class="col-center sortable-header" (click)="sortBy('score')">
            Score <mat-icon class="sort-icon">{{ sortIcon('score') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-center">
            <span [class]="'score score-' + row.criteria_met">{{ row.criteria_met }}/3</span>
          </td>
        </ng-container>

        <!-- Umkehrformation -->
        <ng-container matColumnDef="candle">
          <th mat-header-cell *matHeaderCellDef class="col-candle sortable-header" (click)="sortBy('candle')">
            Candlestick Pattern <mat-icon class="sort-icon">{{ sortIcon('candle') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-candle">
            @if (row.candle_pattern) {
              <span
                [class]="'candle-badge candle-s' + row.candle_strength"
                [matTooltip]="candleTooltip(row.candle_strength)"
              >{{ row.candle_pattern }}</span>
            } @else {
              <span class="candle-none">–</span>
            }
          </td>
        </ng-container>

        <!-- ML Umkehrwahrscheinlichkeit -->
        <ng-container matColumnDef="ml">
          <th mat-header-cell *matHeaderCellDef class="col-center sortable-header" (click)="sortBy('ml')"
              matTooltip="KI-Umkehrwahrscheinlichkeit (XGBoost) für die nächsten 5 Tage">
            KI-Signal <mat-icon class="sort-icon">{{ sortIcon('ml') }}</mat-icon>
          </th>
          <td mat-cell *matCellDef="let row" class="col-center">
            @if (!row.ml_available) {
              <span class="ml-unavailable" matTooltip="ML-Service nicht verfügbar">–</span>
            } @else if (row.reversal_pct != null) {
              <span
                [class]="'ml-badge ml-' + row.ml_signal"
                [matTooltip]="mlTooltip(row)"
              >
                {{ row.reversal_pct.toFixed(0) }}%
                @if (row.ml_signal !== 'none') {
                  <span class="ml-signal-label">{{ mlSignalLabel(row.ml_signal) }}</span>
                }
              </span>
            } @else {
              <span class="ml-unavailable">–</span>
            }
          </td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="displayedColumns; sticky: true"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns"
            [class.row-all3]="row.criteria_met === 3"
            [class.row-2of3]="row.criteria_met === 2"
            class="result-row">
        </tr>

      </table>
    </div><!-- /table-container -->
    </div><!-- /table-wrapper -->

    <!-- Legende -->
    <div class="legend">
      <span class="badge-true">True</span> Kriterium erfüllt &nbsp;·&nbsp;
      <span class="badge-false">False</span> Nicht erfüllt &nbsp;·&nbsp;
      <span class="score score-3">3/3</span> Stärkstes Signal &nbsp;·&nbsp;
      <span class="score score-2">2/3</span> Mittleres Signal &nbsp;·&nbsp;
      <span class="ml-badge ml-strong">75% 🔥</span> KI: Starkes Umkehrsignal &nbsp;·&nbsp;
      <span class="ml-badge ml-moderate">55% ↑</span> KI: Mittleres Signal
    </div>
  `,
  styles: [`
    .table-wrapper {
      height: calc(100vh - 220px);
      min-height: 300px;
      overflow: hidden;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.08);
    }

    .table-container {
      height: 100%;
      overflow-y: auto;
      overflow-x: auto;
    }

    .results-table {
      width: 100%;
      font-size: 13px;
    }

    th.mat-mdc-header-cell {
      position: sticky;
      top: 0;
      z-index: 10;
      background: #f9fafb !important;
      border-bottom: 2px solid #e5e7eb !important;
    }

    /* Sortierbare Header */
    .sortable-header {
      cursor: pointer;
      user-select: none;
      white-space: nowrap;
    }
    .sortable-header:hover { background: #f0f4f8 !important; }

    .sort-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
      vertical-align: middle;
      margin-left: 2px;
      color: #9ca3af;
    }

    tr.row-all3 { background: #f0fdf4; }
    tr.row-2of3 { background: #fefce8; }

    .result-row { animation: fadeIn 0.3s ease; }
    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(5px); }
      to   { opacity: 1; transform: translateY(0); }
    }

    .col-right  { text-align: right !important; }
    .col-center { text-align: center !important; }
    .numeric    { font-variant-numeric: tabular-nums; }

    .ticker-name  { font-family: 'SF Mono', Monaco, monospace; font-weight: 600; }
    .ticker-error { font-size: 11px; color: #ef4444; margin-top: 2px; }

    .positive { color: #1a7f37; }
    .negative { color: #b91c1c; }

    .badge-true, .badge-false {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 500;
    }
    .badge-true  { background: #dcfce7; color: #166534; }
    .badge-false { background: #f3f4f6; color: #6b7280; }

    .score {
      display: inline-block;
      padding: 3px 9px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
    }
    .score-3 { background: #dcfce7; color: #166534; }
    .score-2 { background: #fef9c3; color: #854d0e; }
    .score-1, .score-0 { background: #f3f4f6; color: #9ca3af; }

    /* Umkehrformation */
    .col-candle { text-align: left !important; min-width: 170px; }

    .candle-badge {
      display: inline-block;
      padding: 3px 9px;
      border-radius: 20px;
      font-size: 11px;
      font-weight: 500;
      white-space: nowrap;
      cursor: default;
    }
    .candle-s5 { background: #fce7f3; color: #9d174d; border: 1px solid #fbcfe8; }
    .candle-s4 { background: #ede9fe; color: #5b21b6; border: 1px solid #ddd6fe; }
    .candle-s3 { background: #dbeafe; color: #1e40af; border: 1px solid #bfdbfe; }
    .candle-s2 { background: #dcfce7; color: #166534; border: 1px solid #bbf7d0; }
    .candle-s1 { background: #f3f4f6; color: #374151; border: 1px solid #e5e7eb; }
    .candle-none { color: #d1d5db; font-size: 13px; }

    .direction-bullish {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
      background: #dcfce7;
      color: #166534;
    }
    .direction-bearish {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
      background: #fee2e2;
      color: #991b1b;
    }
    .direction-none { color: #d1d5db; font-size: 13px; }

    .col-name {
      font-size: 12px;
      color: #6b7280;
      max-width: 200px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* ML-Signal Badge */
    .ml-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 9px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
      cursor: default;
      white-space: nowrap;
    }
    .ml-none     { background: #f3f4f6; color: #9ca3af; }
    .ml-weak     { background: #fef9c3; color: #854d0e; border: 1px solid #fde68a; }
    .ml-moderate { background: #ffedd5; color: #9a3412; border: 1px solid #fed7aa; }
    .ml-strong   { background: #fee2e2; color: #991b1b; border: 1px solid #fecaca; }
    .ml-signal-label { font-size: 11px; }
    .ml-unavailable  { color: #d1d5db; font-size: 13px; }

    .legend {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
      font-size: 12px;
      color: #6b7280;
      padding-top: 12px;
      margin-top: 4px;
      border-top: 1px solid #e5e7eb;
    }
  `],
})
export class ResultsTableComponent {
  readonly results = input.required<StockResult[]>();

  readonly displayedColumns = [
    'ticker', 'name', 'price', 'trend', 'direction',
    'elliott', 'stochastic', 'macd', 'score', 'candle', 'ml'
  ];

  // ── Sortier-State ────────────────────────────────────────
  readonly sortColumn = signal<SortColumn | null>(null);
  readonly sortDir    = signal<SortDir>(null);

  /** Sortierte Ergebnisliste – reaktiv auf Sortier-State und eingehende Streaming-Daten */
  readonly sortedResults = computed(() => {
    const col  = this.sortColumn();
    const dir  = this.sortDir();
    const data = [...this.results()];

    if (!col || !dir) return data;

    return data.sort((a, b) => {
      const f = dir === 'asc' ? 1 : -1;
      switch (col) {
        case 'ticker':     return f * a.ticker.localeCompare(b.ticker);
        case 'name':       return f * ((a.name ?? '').localeCompare(b.name ?? ''));
        case 'price':      return f * ((a.current_price ?? -Infinity) - (b.current_price ?? -Infinity));
        case 'trend':      return f * ((a.trend_pct ?? -Infinity) - (b.trend_pct ?? -Infinity));
        case 'elliott':    return f * (Number(a.elliott_wave) - Number(b.elliott_wave));
        case 'stochastic': return f * (Number(a.stochastic) - Number(b.stochastic));
        case 'macd':       return f * (Number(a.macd_histogram) - Number(b.macd_histogram));
        case 'score':      return f * (a.criteria_met - b.criteria_met);
        case 'direction':  return f * ((a.trend_direction ?? '').localeCompare(b.trend_direction ?? ''));
        case 'candle':     return f * ((a.candle_strength ?? 0) - (b.candle_strength ?? 0));
        case 'ml':         return f * ((a.reversal_pct ?? -1) - (b.reversal_pct ?? -1));
        default:           return 0;
      }
    });
  });

  /** Rotiert Sortierrichtung: null → asc → desc → null */
  sortBy(col: SortColumn): void {
    if (this.sortColumn() !== col) {
      this.sortColumn.set(col);
      this.sortDir.set('asc');
    } else {
      const next: SortDir = this.sortDir() === 'asc' ? 'desc' : this.sortDir() === 'desc' ? null : 'asc';
      this.sortDir.set(next);
      if (!next) this.sortColumn.set(null);
    }
  }

  /** Icon-Name für den aktuellen Sortierstatus einer Spalte */
  sortIcon(col: SortColumn): string {
    if (this.sortColumn() !== col || !this.sortDir()) return 'unfold_more';
    return this.sortDir() === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  /** Währungssymbol aus Ticker ableiten – XETRA (.DE) → €, sonst $ */
  currencySymbol(ticker: string): string {
    return ticker.toUpperCase().endsWith('.DE') ? '€' : '$';
  }

  badgeClass(value: boolean): string {
    return value ? 'badge-true' : 'badge-false';
  }

  mlSignalLabel(signal: string): string {
    const labels: Record<string, string> = {
      strong:   '🔥',
      moderate: '↑',
      weak:     '~',
    };
    return labels[signal] ?? '';
  }

  mlTooltip(row: any): string {
    const conf: Record<string, string> = { high: 'Hoch', medium: 'Mittel', low: 'Niedrig' };
    const sig: Record<string, string>  = {
      strong:   'Starkes Signal',
      moderate: 'Mittleres Signal',
      weak:     'Schwaches Signal',
      none:     'Kein Signal',
    };
    return `KI-Umkehrsignal: ${sig[row.ml_signal] ?? '–'} | Konfidenz: ${conf[row.ml_confidence] ?? '–'} | Wahrscheinlichkeit: ${row.reversal_pct?.toFixed(1) ?? '–'}%`;
  }

  candleTooltip(strength: number): string {
    const labels: Record<number, string> = {
      5: 'Strength 5/5 – Bullish Abandoned Baby (very rare, very strong)',
      4: 'Strength 4/5 – Morning Star (3-candle reversal)',
      3: 'Strength 3/5 – Bullish Engulfing (2-candle reversal)',
      2: 'Strength 2/5 – Piercing Line (2-candle reversal)',
      1: 'Strength 1/5 – Hammer (single candle pattern)',
    };
    return labels[strength] ?? '';
  }
}

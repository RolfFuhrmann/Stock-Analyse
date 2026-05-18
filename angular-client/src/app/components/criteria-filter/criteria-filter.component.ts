import { Component, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CriteriaFilter } from '../../models/stock.models';

/**
 * CriteriaFilterComponent
 *
 * Filter-Buttons für die drei Analyse-Kriterien (Elliott, Stochastik, MACD)
 * sowie einen Score-Filter (≥2/3 oder 3/3).
 *
 * Kein eigener State – kommuniziert ausschließlich über Inputs/Outputs
 * nach dem Smart/Dumb-Prinzip der Anwendung.
 */
@Component({
  selector: 'app-criteria-filter',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="filter-bar">

      <span class="filter-label">Filter:</span>

      <!-- Kriterien-Toggles -->
      <div class="toggle-group">
        <button class="filter-btn" [class.active-green]="filter().elliott"
          (click)="toggleCriterion('elliott')" matTooltip="Nur Ticker mit Elliott Wave">
          <span class="dot"></span> Elliott Wave
        </button>
        <button class="filter-btn" [class.active-green]="filter().stochastic"
          (click)="toggleCriterion('stochastic')" matTooltip="Nur Ticker mit Stochastik-Signal">
          <span class="dot"></span> Stochastik
        </button>
        <button class="filter-btn" [class.active-green]="filter().macd"
          (click)="toggleCriterion('macd')" matTooltip="Nur Ticker mit MACD-Signal">
          <span class="dot"></span> MACD
        </button>
      </div>

      <div class="divider"></div>

      <!-- Score-Filter -->
      <div class="toggle-group">
        <button class="filter-btn" [class.active-green]="filter().minScore === 3"
          (click)="setScore(3)" matTooltip="Nur Ticker mit allen 3 Kriterien">
          <span class="score-badge s3">3/3</span>
        </button>
        <button class="filter-btn" [class.active-amber]="filter().minScore === 2"
          (click)="setScore(2)" matTooltip="Ticker mit mindestens 2 Kriterien">
          <span class="score-badge s2">≥2/3</span>
        </button>
      </div>

      <!-- Treffer-Zähler & Reset -->
      @if (isActive()) {
        <span class="filter-count">{{ visibleCount() }} von {{ totalCount() }}</span>
        <button mat-stroked-button class="reset-btn" (click)="onReset()"
          matTooltip="Alle Filter zurücksetzen">
          <mat-icon>filter_alt_off</mat-icon> Filter zurücksetzen
        </button>
      }

    </div>
  `,
  styles: [`
    .filter-bar {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
      padding: 10px 0 14px;
    }
    .filter-label {
      font-size: 11px;
      font-weight: 600;
      color: #6b7280;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .toggle-group { display: flex; gap: 6px; }
    .filter-btn {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border: 1px solid #e5e7eb;
      border-radius: 20px;
      background: #fff;
      font-size: 12px;
      font-weight: 500;
      color: #374151;
      cursor: pointer;
      transition: all 0.15s ease;
      font-family: inherit;
    }
    .filter-btn:hover { border-color: #d1d5db; background: #f9fafb; }
    .active-green { border-color: #166534 !important; background: #dcfce7 !important; color: #166534 !important; }
    .active-amber { border-color: #854d0e !important; background: #fef9c3 !important; color: #854d0e !important; }
    .dot { width: 7px; height: 7px; border-radius: 50%; background: #86efac; flex-shrink: 0; }
    .score-badge {
      display: inline-block;
      padding: 1px 7px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 700;
    }
    .s3 { background: #dcfce7; color: #166534; }
    .s2 { background: #fef9c3; color: #854d0e; }
    .divider { width: 1px; height: 24px; background: #e5e7eb; }
    .filter-count { font-size: 12px; color: #6b7280; margin-left: 4px; }
    .reset-btn {
      font-size: 12px !important;
      padding: 0 10px !important;
      height: 30px;
      border-radius: 20px !important;
      color: #6b7280 !important;
      border-color: #d1d5db !important;
    }
    .reset-btn mat-icon { font-size: 15px; width: 15px; height: 15px; }
  `],
})
export class CriteriaFilterComponent {
  readonly filter       = input.required<CriteriaFilter>();
  readonly totalCount   = input<number>(0);
  readonly visibleCount = input<number>(0);

  readonly filterChange = output<CriteriaFilter>();
  readonly reset        = output<void>();

  isActive(): boolean {
    const f = this.filter();
    return f.elliott || f.stochastic || f.macd || f.minScore !== null;
  }

  toggleCriterion(key: 'elliott' | 'stochastic' | 'macd'): void {
    this.filterChange.emit({ ...this.filter(), [key]: !this.filter()[key] });
  }

  setScore(score: 2 | 3): void {
    const current = this.filter().minScore;
    this.filterChange.emit({ ...this.filter(), minScore: current === score ? null : score });
  }

  onReset(): void {
    this.reset.emit();
  }
}

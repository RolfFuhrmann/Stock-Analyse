import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { AnalysisSummary } from '../../models/stock.models';

/**
 * KpiBarComponent
 *
 * Zeigt eine Übersichtszeile mit den wichtigsten Kennzahlen
 * der abgeschlossenen Analyse: Anzahl analysierter Ticker,
 * Treffer mit allen 3 Kriterien, Treffer mit 2 von 3.
 */
@Component({
  selector: 'app-kpi-bar',
  standalone: true,
  imports: [CommonModule, MatCardModule],
  template: `
    <div class="kpi-bar">

      <mat-card class="kpi-card">
        <div class="kpi-val">{{ summary().total }}</div>
        <div class="kpi-label">Analysiert</div>
      </mat-card>

      <mat-card class="kpi-card kpi-green">
        <div class="kpi-val">{{ summary().count3of3 }}</div>
        <div class="kpi-label">Alle 3 Kriterien</div>
      </mat-card>

      <mat-card class="kpi-card kpi-amber">
        <div class="kpi-val">{{ summary().count2of3 }}</div>
        <div class="kpi-label">2 von 3</div>
      </mat-card>

      <mat-card class="kpi-card">
        <div class="kpi-val">{{ sourceLabel() }}</div>
        <div class="kpi-label">Datenquelle</div>
      </mat-card>

    </div>
  `,
  styles: [`
    .kpi-bar {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 20px;
    }
    .kpi-card {
      padding: 12px 20px;
      min-width: 120px;
    }
    .kpi-val {
      font-size: 26px;
      font-weight: 700;
      line-height: 1;
    }
    .kpi-label {
      font-size: 11px;
      color: #6b7280;
      margin-top: 4px;
    }
    .kpi-green .kpi-val { color: #1a7f37; }
    .kpi-amber .kpi-val { color: #9a6700; }
  `],
})
export class KpiBarComponent {
  readonly summary = input.required<AnalysisSummary>();

  sourceLabel(): string {
    return this.summary().source === 'yahoo' ? 'Yahoo Finance' : 'Twelve Data';
  }
}

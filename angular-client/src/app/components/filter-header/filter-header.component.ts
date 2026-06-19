import {
  Component, computed, input, OnChanges, output, signal, SimpleChanges
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataSource, FilterState, Interval, INTERVAL_LABELS, INTERVAL_LOOKBACK } from '../../models/stock.models';

@Component({
  selector: 'app-filter-header',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatButtonModule, MatButtonToggleModule,
    MatFormFieldModule, MatInputModule,
    MatIconModule, MatTooltipModule, MatProgressBarModule,
  ],
  template: `
    <header class="filter-header">
      <div class="header-inner">

        <!-- Logo / Home-Button -->
        <button class="brand-btn" (click)="onHome()"
                matTooltip="Zurück zur Startseite">
          <span class="brand-icon">◎</span>
          <div>
            <div class="brand-name">Stock Analyse</div>
            <div class="brand-sub">Elliott · MACD · Stochastik</div>
          </div>
        </button>

        <div class="divider"></div>

        <!-- Datenquelle -->
        <div class="control-group">
          <label class="control-label">Datenquelle</label>
          <mat-button-toggle-group [value]="source()"
                                   (change)="onSourceChange($event.value)"
                                   [disabled]="loading()">
            <mat-button-toggle value="yahoo">Yahoo Finance</mat-button-toggle>
            <mat-button-toggle value="twelvedata">Twelve Data</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        <div class="divider"></div>

        <!-- Analyse-Zeitrahmen -->
        <div class="control-group">
          <label class="control-label">Zeitrahmen</label>
          <mat-button-toggle-group [value]="interval()"
                                   (change)="onIntervalChange($event.value)"
                                   [disabled]="loading()">
            <mat-button-toggle value="1d">1D</mat-button-toggle>
            <mat-button-toggle value="4h">4H</mat-button-toggle>
            <mat-button-toggle value="1h">1H</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        <div class="divider"></div>

        <!-- Ticker Eingabe -->
        <div class="control-group ticker-group">
          <label class="control-label">
            Basiswerte
            <span class="control-hint">{{ tickerCount() }} Ticker</span>
          </label>
          <mat-form-field appearance="outline" class="ticker-field">
            <textarea matInput
                      [ngModel]="tickerInput()"
                      (ngModelChange)="onTickerInput($event)"
                      [disabled]="loading()"
                      placeholder="AAPL, MSFT – oder Liste links auswählen"
                      rows="1">
            </textarea>
          </mat-form-field>
        </div>

        <div class="divider"></div>

        <!-- Lookback -->
        <div class="control-group">
          <label class="control-label">Lookback (Tage)</label>
          <mat-form-field appearance="outline" class="lookback-field">
            <input matInput type="number"
                   [ngModel]="lookbackDays()"
                   (ngModelChange)="lookbackDays.set(+$event)"
                   min="30" max="365" [disabled]="loading()" />
          </mat-form-field>
        </div>

        <div class="divider"></div>

        <!-- Aktions-Buttons -->
        <div class="control-group actions-group">
          <button mat-flat-button color="primary" class="btn-analyze"
                  (click)="onAnalyze()"
                  [disabled]="loading() || tickerCount() === 0"
                  matTooltip="Analyse für alle Ticker starten">
            @if (loading()) {
              <mat-icon class="spin-icon">sync</mat-icon> Analyse läuft…
            } @else {
              <mat-icon>analytics</mat-icon> Analyse starten
            }
          </button>

          @if (loading()) {
            <button mat-stroked-button color="warn" class="btn-stop"
                    (click)="onStop()">
              <mat-icon>stop_circle</mat-icon> Stoppen
            </button>
          }

          <button mat-stroked-button color="accent" class="btn-pdf"
                  (click)="onExportPdf()"
                  [disabled]="!pdfReady()"
                  matTooltip="{{ pdfReady() ? 'Als PDF speichern' : 'Warte auf vollständige Ergebnisse' }}">
            <mat-icon>picture_as_pdf</mat-icon> Als PDF speichern
          </button>
        </div>
      </div>

      @if (loading()) {
        <mat-progress-bar mode="determinate" [value]="progressPct()" class="progress-bar" />
      }

      @if (error()) {
        <div class="error-banner">
          <mat-icon>error_outline</mat-icon> {{ error() }}
        </div>
      }
    </header>
  `,
  styles: [`
    .filter-header {
      position: sticky; top: 0; z-index: 100;
      background: #fff; border-bottom: 1px solid #e5e7eb;
      box-shadow: 0 1px 4px rgba(0,0,0,0.06);
    }
    .header-inner {
      display: flex; align-items: center;
      padding: 12px 24px; flex-wrap: wrap; gap: 12px;
    }

    /* Logo als Button */
    .brand-btn {
      display: flex; align-items: center; gap: 10px;
      background: none; border: none; cursor: pointer;
      padding: 6px 10px; border-radius: 8px; flex-shrink: 0;
      transition: background 0.15s;
    }
    .brand-btn:hover { background: #f0fdf4; }
    .brand-icon { font-size: 22px; color: #1d9e75; }
    .brand-name { font-size: 14px; font-weight: 600; white-space: nowrap; text-align: left; }
    .brand-sub  { font-size: 10px; color: #6b7280; white-space: nowrap; }

    .divider { width: 1px; height: 36px; background: #e5e7eb; flex-shrink: 0; }
    .control-group { display: flex; flex-direction: column; gap: 4px; }
    .control-label {
      font-size: 10px; font-weight: 600; color: #6b7280;
      text-transform: uppercase; letter-spacing: 0.05em;
      display: flex; align-items: center; gap: 6px;
    }
    .control-hint { font-weight: 400; font-size: 10px; color: #9ca3af; text-transform: none; }
    .ticker-group { flex: 1; min-width: 280px; }
    .ticker-field { width: 100%; }
    .ticker-field .mat-mdc-form-field-subscript-wrapper { display: none; }
    .lookback-field { width: 100px; }
    .lookback-field .mat-mdc-form-field-subscript-wrapper { display: none; }
    .actions-group { flex-direction: row; align-items: flex-end; gap: 8px; }
    .btn-analyze, .btn-stop, .btn-pdf {
      display: flex; align-items: center; gap: 6px; white-space: nowrap;
    }
    .spin-icon { animation: spin 0.8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .progress-bar { height: 3px; }
    .error-banner {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 24px; background: #fee2e2; color: #991b1b;
      font-size: 12px; border-top: 1px solid #fca5a5;
    }
  `],
})
export class FilterHeaderComponent implements OnChanges {

  readonly loading     = input<boolean>(false);
  readonly error       = input<string | null>(null);
  readonly progressPct = input<number>(0);
  readonly pdfReady    = input<boolean>(false);
  /** Ticker + Datenquelle aus dem TickerListPanel */
  readonly externalTickers = input<string[]>([]);
  readonly externalSource  = input<DataSource | null>(null);

  readonly analyze   = output<FilterState>();
  readonly exportPdf = output<void>();
  readonly stop      = output<void>();
  readonly home      = output<void>();

  readonly source       = signal<DataSource>('yahoo');
  readonly interval     = signal<Interval>('1d');
  readonly tickerInput  = signal('AAPL, MSFT, JPM');
  readonly lookbackDays = signal(90);

  readonly intervalLabels = INTERVAL_LABELS;

  readonly tickerList = computed(() =>
    this.tickerInput().split(',').map((t) => t.trim()).filter((t) => t.length > 0)
  );
  readonly tickerCount = computed(() => this.tickerList().length);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['externalTickers']) {
      const t = this.externalTickers();
      if (t.length > 0) this.tickerInput.set(t.join(', '));
    }
    // Datenquelle automatisch auf die der Liste setzen
    if (changes['externalSource'] && this.externalSource()) {
      this.source.set(this.externalSource()!);
    }
  }

  onSourceChange(value: DataSource): void   { this.source.set(value); }
  onIntervalChange(value: Interval): void   {
    this.interval.set(value);
    this.lookbackDays.set(INTERVAL_LOOKBACK[value]);
  }
  onTickerInput(val: string): void          { this.tickerInput.set(val); }
  onHome(): void                          { this.home.emit(); }

  onAnalyze(): void {
    if (this.tickerCount() === 0 || this.loading()) return;
    this.analyze.emit({
      source: this.source(), interval: this.interval(),
      tickers: this.tickerList(), lookbackDays: this.lookbackDays(),
    });
  }

  onStop():      void { this.stop.emit(); }
  onExportPdf(): void { this.exportPdf.emit(); }
}

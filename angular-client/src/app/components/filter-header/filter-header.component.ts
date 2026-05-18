import { CommonModule } from '@angular/common';
import { Component, computed, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

// Angular Material
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataSource, FilterState } from '../../models/stock.models';

/** Vordefinierte Ticker-Sets */
const PRESETS: Record<string, string> = {
  dax: 'ADS.DE, AIR.DE, ALV.DE, BAS.DE, BAYN.DE, BEI.DE, BMW.DE, BNR.DE, CBK.DE, CON.DE, DB1.DE, DBK.DE, DHL.DE, DTE.DE, DTG.DE, ENR.DE, EOAN.DE, FRE.DE, G1A.DE, G24.DE, HEI,HEN3.DE, HNR1.DE, IFX.DE, MBG.DE, MRK.DE, MTX.DE, MUV2.DE, P911.DE, PAH3.DE, QIA.DE, RHM.DE, RWE.DE, SAP.DE, SHL.DE, SIE.DE, SY1.DE,, VNA.DE, VOW3.DE, ZAL.DE',
  // WBA entfernt, AMZN hinzugefügt
  dow: 'AAPL, AMGN, AXP, BA, CAT, CRM, CSCO, CVX, DIS, GS, HD, HON, IBM, JNJ, JPM, KO, MCD, MMM, MRK, MSFT, NKE, PG, SHW, TRV, UNH, V, VZ, AMZN, WMT, DOW',
};

/**
 * FilterHeaderComponent
 *
 * Zeigt alle Auswahlkriterien in einer horizontalen Kopfleiste:
 * - Datenquelle (Yahoo / TwelveData)
 * - Ticker-Eingabe mit DAX- und DOW-Preset
 * - Lookback-Zeitraum
 * - Analyse-Button und optionaler PDF-Export-Button
 *
 * Kommuniziert ausschließlich über Inputs/Outputs nach oben –
 * kein direkter Service-Aufruf (Single Responsibility).
 */
@Component({
  selector: 'app-filter-header',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatChipsModule,
  ],
  template: `
    <header class="filter-header">
      <div class="header-inner">
        <!-- Brand -->
        <div class="brand">
          <span class="brand-icon">◎</span>
          <div>
            <div class="brand-name">Stock Analyse</div>
            <div class="brand-sub">Elliott · MACD · Stochastik</div>
          </div>
        </div>

        <div class="divider"></div>

        <!-- Datenquelle -->
        <div class="control-group">
          <label class="control-label">Datenquelle</label>
          <mat-button-toggle-group [value]="source()" (change)="onSourceChange($event.value)" [disabled]="loading()">
            <mat-button-toggle value="yahoo">Yahoo Finance</mat-button-toggle>
            <mat-button-toggle value="twelvedata">Twelve Data</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        <div class="divider"></div>

        <!-- Ticker Eingabe -->
        <div class="control-group ticker-group">
          <label class="control-label">
            Basiswerte
            <span class="control-hint">{{ tickerCount() }} Ticker</span>
          </label>
          <div class="ticker-controls">
            <div class="preset-btns">
              <button
                mat-stroked-button
                [class.active-preset]="activePreset() === 'dax'"
                [disabled]="loading()"
                (click)="applyPreset('dax')"
              >
                DAX 40
              </button>
              <button
                mat-stroked-button
                [class.active-preset]="activePreset() === 'dow'"
                [disabled]="loading()"
                (click)="applyPreset('dow')"
              >
                Dow Jones
              </button>
            </div>
            <mat-form-field appearance="outline" class="ticker-field">
              <textarea
                matInput
                [ngModel]="tickerInput()"
                (ngModelChange)="onTickerInput($event)"
                [disabled]="loading()"
                placeholder="AAPL, MSFT, JPM"
                rows="1"
              ></textarea>
            </mat-form-field>
          </div>
        </div>

        <div class="divider"></div>

        <!-- Lookback -->
        <div class="control-group">
          <label class="control-label">Lookback (Tage)</label>
          <mat-form-field appearance="outline" class="lookback-field">
            <input
              matInput
              type="number"
              [ngModel]="lookbackDays()"
              (ngModelChange)="lookbackDays.set(+$event)"
              min="30"
              max="365"
              [disabled]="loading()"
            />
          </mat-form-field>
        </div>

        <div class="divider"></div>

        <!-- Aktions-Buttons -->
        <div class="control-group actions-group">
          <button
            mat-flat-button
            color="primary"
            class="btn-analyze"
            (click)="onAnalyze()"
            [disabled]="loading() || tickerCount() === 0"
            matTooltip="Analyse für alle Ticker starten"
          >
            @if (loading()) {
              <mat-icon class="spin-icon">sync</mat-icon>
              Analyse läuft…
            } @else {
              <mat-icon>analytics</mat-icon>
              Analyse starten
            }
          </button>

          <!-- Stop-Button – nur während laufender Analyse sichtbar -->
          @if (loading()) {
            <button mat-stroked-button color="warn" class="btn-stop" (click)="onStop()" matTooltip="Datenabruf abbrechen">
              <mat-icon>stop_circle</mat-icon>
              Stoppen
            </button>
          }

          <button
            mat-stroked-button
            color="accent"
            class="btn-pdf"
            (click)="onExportPdf()"
            [disabled]="!pdfReady()"
            matTooltip="{{ pdfReady() ? 'Ergebnis als PDF speichern' : 'Warte auf vollständige Ergebnisse' }}"
          >
            <mat-icon>picture_as_pdf</mat-icon>
            Als PDF speichern
          </button>
        </div>
      </div>

      <!-- Progress Bar (läuft beim Streaming) -->
      @if (loading()) {
        <mat-progress-bar mode="determinate" [value]="progressPct()" class="progress-bar"></mat-progress-bar>
      }

      <!-- Fehleranzeige -->
      @if (error()) {
        <div class="error-banner">
          <mat-icon>error_outline</mat-icon>
          {{ error() }}
        </div>
      }
    </header>
  `,
  styles: [
    `
      .filter-header {
        position: sticky;
        top: 0;
        z-index: 100;
        background: #fff;
        border-bottom: 1px solid #e5e7eb;
        box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
      }

      .header-inner {
        display: flex;
        align-items: center;
        gap: 0;
        padding: 12px 24px;
        flex-wrap: wrap;
        gap: 12px;
      }

      /* Brand */
      .brand {
        display: flex;
        align-items: center;
        gap: 10px;
        flex-shrink: 0;
      }
      .brand-icon {
        font-size: 22px;
        color: #1d9e75;
      }
      .brand-name {
        font-size: 14px;
        font-weight: 600;
        white-space: nowrap;
      }
      .brand-sub {
        font-size: 10px;
        color: #6b7280;
        white-space: nowrap;
      }

      .divider {
        width: 1px;
        height: 36px;
        background: #e5e7eb;
        flex-shrink: 0;
      }

      /* Control Groups */
      .control-group {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .control-label {
        font-size: 10px;
        font-weight: 600;
        color: #6b7280;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .control-hint {
        font-weight: 400;
        font-size: 10px;
        color: #9ca3af;
        text-transform: none;
        letter-spacing: 0;
      }

      /* Ticker */
      .ticker-group {
        flex: 1;
        min-width: 280px;
      }
      .ticker-controls {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .preset-btns {
        display: flex;
        gap: 6px;
      }
      .ticker-field {
        width: 100%;
      }
      .ticker-field .mat-mdc-form-field-subscript-wrapper {
        display: none;
      }

      .active-preset {
        border-color: #1d9e75 !important;
        color: #1d9e75 !important;
        background: #f0fdf4 !important;
      }

      /* Lookback */
      .lookback-field {
        width: 100px;
      }
      .lookback-field .mat-mdc-form-field-subscript-wrapper {
        display: none;
      }

      /* Actions */
      .actions-group {
        flex-direction: row;
        align-items: flex-end;
        gap: 8px;
      }

      .btn-analyze {
        display: flex;
        align-items: center;
        gap: 6px;
        white-space: nowrap;
      }
      .btn-stop {
        display: flex;
        align-items: center;
        gap: 6px;
        white-space: nowrap;
      }

      .btn-pdf {
        display: flex;
        align-items: center;
        gap: 6px;
        white-space: nowrap;
      }

      .spin-icon {
        animation: spin 0.8s linear infinite;
      }
      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }

      /* Progress & Error */
      .progress-bar {
        height: 3px;
      }

      .error-banner {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 24px;
        background: #fee2e2;
        color: #991b1b;
        font-size: 12px;
        border-top: 1px solid #fca5a5;
      }
    `,
  ],
})
export class FilterHeaderComponent implements OnInit {
  // ── Inputs ─────────────────────────────────────────────
  readonly loading = input<boolean>(false);
  readonly error = input<string | null>(null);
  readonly progressPct = input<number>(0);
  readonly pdfReady = input<boolean>(false);

  // ── Outputs ────────────────────────────────────────────
  readonly analyze = output<FilterState>();
  readonly exportPdf = output<void>();
  readonly stop = output<void>();

  // ── Interner State ─────────────────────────────────────
  readonly source = signal<DataSource>('yahoo');
  readonly tickerInput = signal('AAPL, MSFT, JPM');
  readonly lookbackDays = signal(90);
  readonly activePreset = signal<string | null>(null);

  readonly tickerList = computed(() =>
    this.tickerInput()
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0),
  );
  readonly tickerCount = computed(() => this.tickerList().length);

  ngOnInit(): void {}

  onSourceChange(value: DataSource): void {
    this.source.set(value);
  }

  applyPreset(key: string): void {
    this.tickerInput.set(PRESETS[key]);
    this.activePreset.set(key);
  }

  onTickerInput(val: string): void {
    this.tickerInput.set(val);
    this.activePreset.set(null);
  }

  onAnalyze(): void {
    if (this.tickerCount() === 0 || this.loading()) return;
    this.analyze.emit({
      source: this.source(),
      tickers: this.tickerList(),
      lookbackDays: this.lookbackDays(),
    });
  }

  onStop(): void {
    this.stop.emit();
  }

  onExportPdf(): void {
    this.exportPdf.emit();
  }
}

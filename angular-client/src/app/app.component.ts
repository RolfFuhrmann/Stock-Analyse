import { Component, computed, signal, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';

import { FilterHeaderComponent } from './components/filter-header/filter-header.component';
import { KpiBarComponent } from './components/kpi-bar/kpi-bar.component';
import { ResultsTableComponent } from './components/results-table/results-table.component';
import { EmptyStateComponent } from './components/empty-state/empty-state.component';
import { CriteriaFilterComponent } from './components/criteria-filter/criteria-filter.component';

import { AnalysisService } from './services/analysis.service';
import { PdfExportService } from './services/pdf-export.service';
import { FilterState, StockResult, AnalysisSummary, CriteriaFilter, EMPTY_FILTER } from './models/stock.models';

/**
 * AppComponent – Root-Komponente
 *
 * Verantwortlichkeit: State-Management und Koordination der Kindkomponenten.
 * Kein UI-Code, keine Business-Logik – diese liegt in Services und Komponenten.
 *
 * Struktur:
 *   <app-filter-header>  → sticky Header mit allen Auswahlkriterien
 *   <main>
 *     <app-empty-state>  → Platzhalter ohne Ergebnisse
 *     <app-kpi-bar>      → KPI-Übersicht nach Analyse
 *     <app-results-table>→ Ergebnistabelle (Streaming-fähig)
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    FilterHeaderComponent,
    KpiBarComponent,
    ResultsTableComponent,
    EmptyStateComponent,
    CriteriaFilterComponent,
  ],
  template: `
    <!-- Sticky Header mit Filtern und Aktionen -->
    <app-filter-header
      [loading]="loading()"
      [error]="error()"
      [progressPct]="progressPct()"
      [pdfReady]="pdfReady()"
      (analyze)="onAnalyze($event)"
      (exportPdf)="onExportPdf()"
      (stop)="onStop()"
    />

    <!-- Hauptbereich -->
    <main class="main-content">

      @if (results().length === 0 && !loading()) {
        <app-empty-state />
      }

      @if (results().length > 0) {
        <app-kpi-bar [summary]="summary()" />
        <app-criteria-filter
          [filter]="criteriaFilter()"
          [totalCount]="results().length"
          [visibleCount]="filteredResults().length"
          (filterChange)="criteriaFilter.set($event)"
          (reset)="criteriaFilter.set(emptyFilter)"
        />
        <app-results-table [results]="filteredResults()" />
      }

    </main>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      min-height: 100vh;
    }
    .main-content {
      flex: 1;
      padding: 28px 32px;
      background: #f9fafb;
      overflow-x: auto;
    }
  `],
})
export class AppComponent implements OnDestroy {

  // ── State ───────────────────────────────────────────────
  readonly loading        = signal(false);
  readonly error          = signal<string | null>(null);
  readonly results        = signal<StockResult[]>([]);
  readonly totalTickers   = signal(0);
  readonly criteriaFilter = signal<CriteriaFilter>(EMPTY_FILTER);

  /** Konstante für Template-Zugriff beim Reset */
  readonly emptyFilter = EMPTY_FILTER;

  /** Aktive Streaming-Subscription – wird bei Stop/Neustart abgemeldet */
  private _subscription: Subscription | null = null;

  // ── Computed ────────────────────────────────────────────

  readonly progressPct = computed(() =>
    this.totalTickers() > 0
      ? Math.round((this.results().length / this.totalTickers()) * 100)
      : 0
  );

  readonly pdfReady = computed(
    () => !this.loading() && this.results().length > 0
  );

  readonly summary = computed<AnalysisSummary>(() => ({
    total:     this.results().length,
    count3of3: this.results().filter((r) => r.criteria_met === 3).length,
    count2of3: this.results().filter((r) => r.criteria_met === 2).length,
    source:    this._lastSource,
  }));

  /** Gefilterte Ergebnisliste – reagiert reaktiv auf Kriterien-Filter */
  readonly filteredResults = computed(() => {
    const f = this.criteriaFilter();
    return this.results().filter((r) => {
      if (f.elliott    && !r.elliott_wave)   return false;
      if (f.stochastic && !r.stochastic)     return false;
      if (f.macd       && !r.macd_histogram) return false;
      if (f.minScore !== null && r.criteria_met < f.minScore) return false;
      return true;
    });
  });

  private _lastSource: 'yahoo' | 'twelvedata' = 'yahoo';

  constructor(
    private readonly analysisService: AnalysisService,
    private readonly pdfExportService: PdfExportService,
  ) {}

  // ── Event Handler ───────────────────────────────────────

  onAnalyze(filter: FilterState): void {
    // Vorherigen Stream sauber beenden bevor ein neuer startet
    this._cancelStream();

    this._lastSource = filter.source;
    this.loading.set(true);
    this.error.set(null);
    this.results.set([]);
    this.totalTickers.set(filter.tickers.length);
    this.criteriaFilter.set(EMPTY_FILTER);

    this._subscription = this.analysisService
      .streamAnalysis(filter.tickers, filter.source, filter.lookbackDays)
      .subscribe({
        next:     (result) => this.results.update((prev) => [...prev, result]),
        error:    (err)    => {
          this.error.set(`Fehler: ${err.message ?? 'Agent nicht erreichbar'}`);
          this.loading.set(false);
        },
        complete: ()       => this.loading.set(false),
      });
  }

  /** Bricht den laufenden Datenabruf ab – stoppt auch den Data-Service im Backend */
  onStop(): void {
    this.analysisService.stopStream();
    this._subscription?.unsubscribe();
    this._subscription = null;
    this.loading.set(false);
  }

  onExportPdf(): void {
    if (!this.pdfReady()) return;
    this.pdfExportService.exportResults(this.results(), this.summary());
  }

  ngOnDestroy(): void {
    this._cancelStream();
  }

  // ── Private Helpers ─────────────────────────────────────

  private _cancelStream(): void {
    this._subscription?.unsubscribe();
    this._subscription = null;
  }
}

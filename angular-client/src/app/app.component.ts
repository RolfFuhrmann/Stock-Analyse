import { Component, computed, signal, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClientModule } from '@angular/common/http';
import { Subscription } from 'rxjs';

import { FilterHeaderComponent } from './components/filter-header/filter-header.component';
import { KpiBarComponent } from './components/kpi-bar/kpi-bar.component';
import { ResultsTableComponent } from './components/results-table/results-table.component';
import { EmptyStateComponent } from './components/empty-state/empty-state.component';
import { CriteriaFilterComponent } from './components/criteria-filter/criteria-filter.component';
import { TickerListPanelComponent, ListSelection } from './components/ticker-list-panel/ticker-list-panel.component';
import { TickerListEditorComponent } from './components/ticker-list-editor/ticker-list-editor.component';

import { AnalysisService } from './services/analysis.service';
import { PdfExportService } from './services/pdf-export.service';
import {
  DataSource, FilterState, StockResult, AnalysisSummary,
  CriteriaFilter, EMPTY_FILTER, TickerList
} from './models/stock.models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, HttpClientModule,
    FilterHeaderComponent, KpiBarComponent,
    ResultsTableComponent, EmptyStateComponent, CriteriaFilterComponent,
    TickerListPanelComponent, TickerListEditorComponent,
  ],
  template: `
    <!-- Sticky Header -->
    <app-filter-header
      [loading]="loading()"
      [error]="error()"
      [progressPct]="progressPct()"
      [pdfReady]="pdfReady()"
      [externalTickers]="selectedTickers()"
      [externalSource]="selectedSource()"
      (analyze)="onAnalyze($event)"
      (exportPdf)="onExportPdf()"
      (stop)="onStop()"
      (home)="onHome()"
    />

    <!-- Listen-Panel: nur auf Startseite sichtbar -->
    @if (showPanel()) {
      <section class="list-panel-section"
               [class.editor-open]="listToEdit() || editorIsNew()">
        <app-ticker-list-panel
          #listPanel
          (listSelected)="onListSelected($event)"
          (editList)="onEditList($event)"
          (newList)="onNewList()"
        />
        @if (listToEdit() || editorIsNew()) {
          <app-ticker-list-editor
            [listToEdit]="listToEdit()"
            [isNew]="editorIsNew()"
            (saved)="onEditorSaved()"
            (deleted)="onEditorDeleted()"
            (closed)="onEditorClosed()"
          />
        }
      </section>
    }

    <!-- Hauptbereich -->
    <main class="main-content">
      @if (results().length === 0 && !loading() && hasAnalyzed()) {
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
    :host { display: flex; flex-direction: column; min-height: 100vh; }

    .list-panel-section {
      display: grid;
      grid-template-columns: 300px;
      gap: 16px;
      padding: 16px 32px;
      background: #f9fafb;
      border-bottom: 1px solid #e5e7eb;
      animation: slideDown 0.2s ease;
    }
    .list-panel-section.editor-open {
      grid-template-columns: 300px 1fr;
    }
    @keyframes slideDown {
      from { opacity: 0; transform: translateY(-8px); }
      to   { opacity: 1; transform: translateY(0); }
    }

    .main-content {
      flex: 1; padding: 28px 32px;
      background: #f9fafb; overflow-x: auto;
    }
  `],
})
export class AppComponent implements OnDestroy {

  @ViewChild('listPanel') private listPanel?: TickerListPanelComponent;

  // ── Analyse-State ────────────────────────────────────────
  readonly loading        = signal(false);
  readonly error          = signal<string | null>(null);
  readonly results        = signal<StockResult[]>([]);
  readonly totalTickers   = signal(0);
  readonly criteriaFilter = signal<CriteriaFilter>(EMPTY_FILTER);
  readonly emptyFilter    = EMPTY_FILTER;
  /** Wird true sobald die erste Analyse gestartet wurde – steuert Empty-State */
  readonly hasAnalyzed    = signal(false);

  private _subscription: Subscription | null = null;

  // ── Panel-State ──────────────────────────────────────────
  /** Panel sichtbar solange keine Analyse läuft und keine Ergebnisse vorhanden */
  readonly showPanel       = computed(() => !this.loading() && this.results().length === 0);
  readonly selectedTickers = signal<string[]>([]);
  readonly selectedSource  = signal<DataSource | null>(null);
  readonly listToEdit      = signal<TickerList | null>(null);
  readonly editorIsNew     = signal(false);

  // ── Computed ─────────────────────────────────────────────
  readonly progressPct = computed(() =>
    this.totalTickers() > 0
      ? Math.round((this.results().length / this.totalTickers()) * 100)
      : 0
  );
  readonly pdfReady = computed(() => !this.loading() && this.results().length > 0);

  readonly summary = computed<AnalysisSummary>(() => ({
    total:     this.results().length,
    count3of3: this.results().filter((r) => r.criteria_met === 3).length,
    count2of3: this.results().filter((r) => r.criteria_met === 2).length,
    source:    this._lastSource,
  }));

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

  private _lastSource: DataSource = 'yahoo';

  constructor(
    private readonly analysisService: AnalysisService,
    private readonly pdfExportService: PdfExportService,
  ) {}

  // ── Home ─────────────────────────────────────────────────
  onHome(): void {
    this._cancelStream();
    this.loading.set(false);
    this.error.set(null);
    this.results.set([]);
    this.hasAnalyzed.set(false);
    this.totalTickers.set(0);
    this.criteriaFilter.set(EMPTY_FILTER);
    this.selectedTickers.set([]);
    this.selectedSource.set(null);
    this.listToEdit.set(null);
    this.editorIsNew.set(false);
  }

  // ── Panel Events ─────────────────────────────────────────
  onListSelected(selection: ListSelection): void {
    this.selectedTickers.set(selection.tickers);
    this.selectedSource.set(selection.source);
  }

  onEditList(list: TickerList): void {
    this.editorIsNew.set(false);
    this.listToEdit.set(list);
  }

  onNewList(): void {
    this.listToEdit.set(null);
    this.editorIsNew.set(false);
    setTimeout(() => this.editorIsNew.set(true), 0);
  }

  onEditorSaved(): void {
    this.listPanel?.loadLists();
  }

  onEditorDeleted(): void {
    this.listToEdit.set(null);
    this.listPanel?.loadLists();
  }

  onEditorClosed(): void {
    this.listToEdit.set(null);
    this.editorIsNew.set(false);
  }

  // ── Analyse Events ───────────────────────────────────────
  onAnalyze(filter: FilterState): void {
    this._cancelStream();
    this._lastSource = filter.source;
    this.hasAnalyzed.set(true);
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
        complete: () => this.loading.set(false),
      });
  }

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

  ngOnDestroy(): void { this._cancelStream(); }

  private _cancelStream(): void {
    this._subscription?.unsubscribe();
    this._subscription = null;
  }
}

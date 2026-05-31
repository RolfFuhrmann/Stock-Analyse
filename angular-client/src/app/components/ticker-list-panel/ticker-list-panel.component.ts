import { Component, inject, output, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { TickerListService } from '../../services/ticker-list.service';
import {
  DataSource, TickerList, TickerListDetail,
  toYahooSymbol, formatBadgeLabel
} from '../../models/stock.models';

export interface ListSelection {
  tickers: string[];
  source: DataSource;
}

@Component({
  selector: 'app-ticker-list-panel',
  standalone: true,
  imports: [
    CommonModule, MatCardModule, MatButtonModule,
    MatIconModule, MatTooltipModule, MatProgressSpinnerModule,
  ],
  template: `
    <mat-card class="panel-card">
      <div class="panel-header">
        <div class="panel-title">
          <mat-icon class="panel-title-icon">playlist_play</mat-icon>
          Abruflisten
        </div>
        <button mat-icon-button matTooltip="Neu laden"
                (click)="loadLists()" [disabled]="loading()">
          <mat-icon [class.spin]="loading()">refresh</mat-icon>
        </button>
      </div>

      @if (loading()) {
        <div class="panel-loading">
          <mat-spinner diameter="28" />
          <span>Listen werden geladen…</span>
        </div>
      }

      @if (errorMsg()) {
        <div class="panel-error">
          <mat-icon>error_outline</mat-icon> {{ errorMsg() }}
        </div>
      }

      @if (!loading() && lists().length > 0) {
        <div class="list-grid">
          @for (list of lists(); track list.id) {
            <div class="list-item"
                 [class.selected]="selectedListId() === list.id"
                 (click)="onSelectList(list)"
                 matTooltip="Klicken um Ticker zu übernehmen">
              <div class="list-item-main">
                <div class="list-name">{{ list.name }}</div>
                <div class="list-meta">
                  <span class="list-code">{{ list.code }}</span>
                  <span class="list-count">{{ list.symbolCount }} Ticker</span>
                  <span class="badge source-badge"
                        [class.source-yahoo]="list.source === 'yahoo'"
                        [class.source-twelve]="list.source === 'twelvedata'">
                    {{ list.source === 'yahoo' ? 'Yahoo' : 'Twelve Data' }}
                  </span>
                  <span class="badge format-badge"
                        [class.format-raw]="list.tickerFormat === 'RAW'"
                        [class.format-xetra]="list.tickerFormat === 'XETRA'"
                        [class.format-custom]="list.tickerFormat === 'CUSTOM'"
                        [matTooltip]="formatTooltip(list)">
                    {{ formatLabel(list) }}
                  </span>
                </div>
                @if (list.description) {
                  <div class="list-desc">{{ list.description }}</div>
                }
              </div>
              <button mat-icon-button class="edit-btn"
                      matTooltip="Liste bearbeiten"
                      (click)="onEditList($event, list)">
                <mat-icon>edit</mat-icon>
              </button>
            </div>
          }
        </div>
      }

      @if (!loading() && !errorMsg() && lists().length === 0) {
        <div class="panel-empty">
          <mat-icon>format_list_bulleted</mat-icon>
          <span>Noch keine Listen vorhanden</span>
        </div>
      }

      <div class="panel-footer">
        <button mat-stroked-button class="new-list-btn"
                (click)="onNewList()">
          <mat-icon>add</mat-icon> Neue Abrufliste
        </button>
      </div>
    </mat-card>
  `,
  styles: [`
    .panel-card {
      display: flex; flex-direction: column;
      padding: 0; overflow: hidden;
    }
    .panel-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 14px 16px 10px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0;
    }
    .panel-title {
      display: flex; align-items: center; gap: 8px;
      font-size: 13px; font-weight: 600; color: #374151;
    }
    .panel-title-icon { font-size: 18px; width: 18px; height: 18px; color: #1d9e75; }
    .spin { animation: spin 0.8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .panel-loading {
      display: flex; align-items: center; gap: 10px;
      padding: 20px 16px; font-size: 13px; color: #6b7280;
    }
    .panel-error {
      display: flex; align-items: center; gap: 8px;
      padding: 12px 16px; background: #fee2e2; color: #991b1b;
      font-size: 12px; margin: 8px; border-radius: 6px;
    }
    .panel-empty {
      display: flex; flex-direction: column; align-items: center;
      gap: 8px; padding: 28px 16px; color: #9ca3af; font-size: 13px;
    }
    .panel-empty mat-icon { font-size: 32px; width: 32px; height: 32px; opacity: 0.4; }
    .list-grid {
      flex: 1; overflow-y: auto; padding: 8px;
      display: flex; flex-direction: column; gap: 6px;
    }
    .list-item {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 12px; border: 1px solid #e5e7eb; border-radius: 8px;
      cursor: pointer; transition: all 0.15s ease; background: #fff;
    }
    .list-item:hover  { border-color: #1d9e75; background: #f0fdf4; }
    .list-item.selected {
      border-color: #1d9e75; background: #ecfdf5;
      box-shadow: 0 0 0 2px #bbf7d0;
    }
    .list-item-main { flex: 1; min-width: 0; }
    .list-name {
      font-size: 13px; font-weight: 600; color: #111827;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .list-meta {
      display: flex; align-items: center; gap: 5px;
      margin-top: 3px; flex-wrap: wrap;
    }
    .list-code {
      font-size: 10px; font-family: 'SF Mono', Monaco, monospace;
      background: #f3f4f6; color: #6b7280; padding: 1px 6px; border-radius: 4px;
    }
    .list-count { font-size: 11px; color: #9ca3af; }

    /* Badges */
    .badge {
      font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: 500;
    }
    .source-yahoo   { background: #fef9c3; color: #854d0e; }
    .source-twelve  { background: #dbeafe; color: #1e40af; }
    .format-raw     { background: #f3f4f6; color: #6b7280; }
    .format-xetra   { background: #dcfce7; color: #166534; }
    .format-custom  { background: #ede9fe; color: #5b21b6; }

    .list-desc {
      font-size: 11px; color: #9ca3af; margin-top: 2px;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .edit-btn {
      flex-shrink: 0; opacity: 0; transition: opacity 0.15s; color: #6b7280 !important;
    }
    .list-item:hover .edit-btn,
    .list-item.selected .edit-btn { opacity: 1; }
    .panel-footer { padding: 10px 12px; border-top: 1px solid #e5e7eb; flex-shrink: 0; }
    .new-list-btn {
      width: 100%; font-size: 13px !important;
      color: #1d9e75 !important; border-color: #1d9e75 !important;
    }
  `],
})
export class TickerListPanelComponent implements OnInit {
  private readonly tickerListService = inject(TickerListService);

  readonly listSelected = output<ListSelection>();
  readonly editList     = output<TickerList>();
  readonly newList      = output<void>();

  readonly lists          = signal<TickerList[]>([]);
  readonly loading        = signal(false);
  readonly errorMsg       = signal<string | null>(null);
  readonly selectedListId = signal<number | null>(null);

  ngOnInit(): void { this.loadLists(); }

  loadLists(): void {
    this.loading.set(true);
    this.errorMsg.set(null);
    this.tickerListService.getLists().subscribe({
      next:  (lists) => { this.lists.set(lists); this.loading.set(false); },
      error: ()      => {
        this.errorMsg.set('DB-Service nicht erreichbar (Port 8013)');
        this.loading.set(false);
      },
    });
  }

  onSelectList(list: TickerList): void {
    this.selectedListId.set(list.id);
    this.tickerListService.getListDetail(list.id).subscribe({
      next: (detail: TickerListDetail) => {
        const tickers = detail.symbols.map((s) =>
          toYahooSymbol(s.rawSymbol, detail.tickerFormat, detail.customSuffix)
        );
        this.listSelected.emit({ tickers, source: list.source });
      },
      error: () => this.errorMsg.set('Symbole konnten nicht geladen werden'),
    });
  }

  onEditList(event: MouseEvent, list: TickerList): void {
    event.stopPropagation();
    this.editList.emit(list);
  }

  onNewList(): void {
    this.selectedListId.set(null);
    this.newList.emit();
  }

  formatLabel(list: TickerList): string {
    return formatBadgeLabel(list.tickerFormat, list.customSuffix);
  }

  formatTooltip(list: TickerList): string {
    switch (list.tickerFormat) {
      case 'XETRA':  return 'Ticker werden mit .DE ergänzt (z.B. ADS → ADS.DE)';
      case 'CUSTOM': return `Ticker werden mit ${list.customSuffix ?? '?'} ergänzt`;
      default:       return 'Ticker werden unverändert übergeben';
    }
  }
}

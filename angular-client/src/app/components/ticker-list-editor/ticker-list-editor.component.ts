import {
  Component, inject, input, output, signal, OnChanges, SimpleChanges
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatRadioModule } from '@angular/material/radio';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';

import { TickerListService } from '../../services/ticker-list.service';
import {
  DataSource, TickerFormat, TickerList, TickerListDetail, TickerSymbol,
  TickerListRequest, TickerSymbolRequest,
} from '../../models/stock.models';

@Component({
  selector: 'app-ticker-list-editor',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule, MatButtonToggleModule, MatRadioModule,
    MatIconModule, MatFormFieldModule, MatInputModule,
    MatTooltipModule, MatProgressSpinnerModule, MatDividerModule,
  ],
  template: `
    <mat-card class="editor-card">

      @if (!mode()) {
        <div class="editor-empty">
          <mat-icon>edit_note</mat-icon>
          <span>Liste auswählen oder neue anlegen</span>
        </div>
      }

      @if (mode()) {
        <div class="editor-header">
          <div class="editor-title">
            <mat-icon class="editor-title-icon">
              {{ mode() === 'new' ? 'add_circle' : 'edit' }}
            </mat-icon>
            {{ mode() === 'new' ? 'Neue Liste' : 'Liste bearbeiten' }}
          </div>
          <button mat-icon-button (click)="onClose()"><mat-icon>close</mat-icon></button>
        </div>

        <div class="form-section">
          <!-- Name -->
          <mat-form-field appearance="outline" class="field-full">
            <mat-label>Name</mat-label>
            <input matInput [(ngModel)]="formName" maxlength="100" placeholder="z.B. DAX 40" />
          </mat-form-field>

          <!-- Code -->
          <mat-form-field appearance="outline" class="field-half">
            <mat-label>Code</mat-label>
            <input matInput [(ngModel)]="formCode" maxlength="50"
                   placeholder="z.B. DAX40" style="text-transform:uppercase" />
            <mat-hint>Eindeutiger Kurzschlüssel</mat-hint>
          </mat-form-field>

          <!-- Beschreibung -->
          <mat-form-field appearance="outline" class="field-full">
            <mat-label>Beschreibung (optional)</mat-label>
            <input matInput [(ngModel)]="formDescription" maxlength="255" />
          </mat-form-field>

          <!-- Datenquelle -->
          <div class="control-group">
            <label class="control-label">Datenquelle</label>
            <mat-button-toggle-group [(ngModel)]="formSource" class="source-toggle">
              <mat-button-toggle value="yahoo">Yahoo Finance</mat-button-toggle>
              <mat-button-toggle value="twelvedata">Twelve Data</mat-button-toggle>
            </mat-button-toggle-group>
          </div>

          <!-- Ticker-Format -->
          <div class="control-group">
            <label class="control-label">Ticker-Format</label>
            <mat-radio-group [(ngModel)]="formTickerFormat" class="format-radio-group">
              <mat-radio-button value="RAW" class="format-radio">
                <div class="radio-label">
                  <span class="radio-title">RAW – Unverändert</span>
                  <span class="radio-hint">Indizes (^GDAXI), US-Ticker (AAPL)</span>
                </div>
              </mat-radio-button>
              <mat-radio-button value="XETRA" class="format-radio">
                <div class="radio-label">
                  <span class="radio-title">XETRA – .DE anhängen</span>
                  <span class="radio-hint">ADS → ADS.DE · BMW → BMW.DE</span>
                </div>
              </mat-radio-button>
              <mat-radio-button value="CUSTOM" class="format-radio">
                <div class="radio-label">
                  <span class="radio-title">CUSTOM – Eigener Suffix</span>
                  <span class="radio-hint">z.B. .L für LSE · .PA für Euronext</span>
                </div>
              </mat-radio-button>
            </mat-radio-group>

            <!-- Custom Suffix – nur wenn CUSTOM gewählt -->
            @if (formTickerFormat === 'CUSTOM') {
              <mat-form-field appearance="outline" class="field-suffix">
                <mat-label>Suffix</mat-label>
                <input matInput [(ngModel)]="formCustomSuffix"
                       maxlength="10" placeholder=".L oder .PA" />
                <mat-hint>Wird direkt an den Ticker angehängt</mat-hint>
              </mat-form-field>
            }

            <!-- Vorschau -->
            <div class="format-preview">
              <mat-icon class="preview-icon">visibility</mat-icon>
              Vorschau: <strong>{{ formatPreview() }}</strong>
            </div>
          </div>

          <!-- Aktionen -->
          <div class="form-actions">
            <button mat-flat-button color="primary"
                    (click)="onSaveList()"
                    [disabled]="saving() || !formName.trim() || !formCode.trim()">
              @if (saving()) { <mat-spinner diameter="16" /> }
              @else { <mat-icon>save</mat-icon> }
              {{ mode() === 'new' ? 'Liste erstellen' : 'Speichern' }}
            </button>
            @if (mode() === 'edit') {
              <button mat-stroked-button color="warn"
                      (click)="onDeleteList()" [disabled]="saving()">
                <mat-icon>delete</mat-icon> Löschen
              </button>
            }
          </div>

          @if (saveError()) {
            <div class="inline-error">
              <mat-icon>error_outline</mat-icon> {{ saveError() }}
            </div>
          }
        </div>

        <!-- Symbole -->
        @if (mode() === 'edit' && detail()) {
          <mat-divider />
          <div class="symbols-section">
            <div class="symbols-title">
              <mat-icon>format_list_numbered</mat-icon>
              Symbole ({{ detail()!.symbols.length }})
            </div>

            <div class="add-symbol-row">
              <mat-form-field appearance="outline" class="sym-field-ticker">
                <mat-label>Ticker</mat-label>
                <input matInput [(ngModel)]="newRawSymbol"
                       placeholder="z.B. BMW" maxlength="20"
                       style="text-transform:uppercase" />
              </mat-form-field>
              <mat-form-field appearance="outline" class="sym-field-name">
                <mat-label>Name (optional)</mat-label>
                <input matInput [(ngModel)]="newDisplayName" maxlength="100" />
              </mat-form-field>
              <button mat-flat-button color="primary" class="add-sym-btn"
                      [disabled]="!newRawSymbol.trim() || addingSymbol()"
                      (click)="onAddSymbol()">
                @if (addingSymbol()) { <mat-spinner diameter="16" /> }
                @else { <mat-icon>add</mat-icon> }
              </button>
            </div>

            @if (symbolError()) {
              <div class="inline-error">
                <mat-icon>error_outline</mat-icon> {{ symbolError() }}
              </div>
            }

            <div class="symbol-list">
              @for (sym of detail()!.symbols; track sym.id) {
                <div class="symbol-row">
                  <span class="sym-raw">{{ sym.rawSymbol }}</span>
                  @if (sym.displayName) {
                    <span class="sym-name">{{ sym.displayName }}</span>
                  }
                  <button mat-icon-button class="sym-delete-btn"
                          (click)="onDeleteSymbol(sym)">
                    <mat-icon>remove_circle_outline</mat-icon>
                  </button>
                </div>
              }
              @if (detail()!.symbols.length === 0) {
                <div class="symbols-empty">Noch keine Symbole vorhanden</div>
              }
            </div>
          </div>
        }
      }
    </mat-card>
  `,
  styles: [`
    .editor-card {
      display: flex; flex-direction: column;
      padding: 0;
      /* Höhe passt sich dem Inhalt an – Seite scrollt bei langen Listen */
    }
    .editor-empty {
      display: flex; flex-direction: column; align-items: center;
      justify-content: center; height: 220px;
      gap: 10px; color: #9ca3af; font-size: 13px;
    }
    .editor-empty mat-icon { font-size: 40px; width: 40px; height: 40px; opacity: 0.3; }
    .editor-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 14px 16px 10px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0;
    }
    .editor-title {
      display: flex; align-items: center; gap: 8px;
      font-size: 13px; font-weight: 600; color: #374151;
    }
    .editor-title-icon { font-size: 18px; width: 18px; height: 18px; color: #1d9e75; }
    .form-section {
      padding: 14px 16px; display: flex; flex-direction: column; gap: 8px;
    }
    .field-full   { width: 100%; }
    .field-half   { width: 50%; }
    .field-suffix { width: 140px; margin-top: 8px; }
    .control-group { display: flex; flex-direction: column; gap: 6px; }
    .control-label {
      font-size: 10px; font-weight: 600; color: #6b7280;
      text-transform: uppercase; letter-spacing: 0.05em;
    }
    .source-toggle { width: 100%; }

    /* Format Radio */
    .format-radio-group {
      display: flex; flex-direction: column; gap: 4px;
      background: #f9fafb; border: 1px solid #e5e7eb;
      border-radius: 8px; padding: 8px 12px;
    }
    .format-radio { padding: 4px 0; }
    .radio-label { display: flex; flex-direction: column; line-height: 1.3; }
    .radio-title { font-size: 12px; font-weight: 600; color: #111827; }
    .radio-hint  { font-size: 11px; color: #9ca3af; font-family: 'SF Mono', Monaco, monospace; }

    /* Vorschau */
    .format-preview {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; color: #6b7280;
      background: #f0fdf4; border: 1px solid #bbf7d0;
      border-radius: 6px; padding: 6px 10px; margin-top: 4px;
    }
    .preview-icon { font-size: 14px; width: 14px; height: 14px; color: #1d9e75; }
    .format-preview strong { color: #166534; font-family: 'SF Mono', Monaco, monospace; }

    .form-actions { display: flex; gap: 8px; align-items: center; margin-top: 4px; }
    .inline-error {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; color: #b91c1c;
    }
    .inline-error mat-icon { font-size: 15px; width: 15px; height: 15px; }

    /* Symbole */
    .symbols-section {
      flex: 1; overflow: hidden; display: flex; flex-direction: column;
      padding: 12px 16px; gap: 10px;
    }
    .symbols-title {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; font-weight: 600; color: #6b7280;
      text-transform: uppercase; letter-spacing: 0.05em;
    }
    .symbols-title mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .add-symbol-row {
      display: flex; gap: 6px; align-items: flex-start; flex-wrap: wrap;
    }
    .sym-field-ticker { width: 110px; }
    .sym-field-name   { flex: 1; min-width: 140px; }
    .add-sym-btn { margin-top: 4px; min-width: 40px !important; padding: 0 8px !important; }
    .symbol-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
    .symbol-row {
      display: flex; align-items: center; gap: 8px;
      padding: 7px 10px; border: 1px solid #f3f4f6;
      border-radius: 6px; background: #fafafa; font-size: 12px;
    }
    .symbol-row:hover { background: #f3f4f6; border-color: #e5e7eb; }
    .sym-raw {
      font-family: 'SF Mono', Monaco, monospace;
      font-weight: 600; color: #111827; min-width: 60px;
    }
    .sym-name { flex: 1; color: #6b7280; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .sym-delete-btn {
      margin-left: auto; flex-shrink: 0; opacity: 0;
      transition: opacity 0.15s; color: #ef4444 !important;
    }
    .symbol-row:hover .sym-delete-btn { opacity: 1; }
    .symbols-empty { font-size: 12px; color: #9ca3af; padding: 12px 0; text-align: center; }
  `],
})
export class TickerListEditorComponent implements OnChanges {
  private readonly tickerListService = inject(TickerListService);

  readonly listToEdit = input<TickerList | null>(null);
  readonly isNew      = input<boolean>(false);
  readonly saved      = output<void>();
  readonly deleted    = output<void>();
  readonly closed     = output<void>();

  readonly mode         = signal<'new' | 'edit' | null>(null);
  readonly editingList  = signal<TickerList | null>(null);
  readonly detail       = signal<TickerListDetail | null>(null);
  readonly saving       = signal(false);
  readonly addingSymbol = signal(false);
  readonly saveError    = signal<string | null>(null);
  readonly symbolError  = signal<string | null>(null);

  formName         = '';
  formCode         = '';
  formDescription  = '';
  formSource: DataSource     = 'yahoo';
  formTickerFormat: TickerFormat = 'RAW';
  formCustomSuffix = '';
  newRawSymbol     = '';
  newDisplayName   = '';

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isNew'] && this.isNew())           this.openNew();
    if (changes['listToEdit'] && this.listToEdit()) this.openEdit(this.listToEdit()!);
  }

  /** Vorschau wie ein Beispiel-Ticker nach der Normalisierung aussieht. */
  formatPreview(): string {
    const example = this.formTickerFormat === 'RAW' ? '^GDAXI oder AAPL'
      : this.formTickerFormat === 'XETRA' ? `BMW → BMW.DE`
      : `HSBA → HSBA${this.formCustomSuffix || '<??>'}`;
    return example;
  }

  openNew(): void {
    this.mode.set('new');
    this.editingList.set(null);
    this.detail.set(null);
    this.formName = ''; this.formCode = ''; this.formDescription = '';
    this.formSource = 'yahoo'; this.formTickerFormat = 'RAW'; this.formCustomSuffix = '';
    this.saveError.set(null);
  }

  openEdit(list: TickerList): void {
    this.mode.set('edit');
    this.editingList.set(list);
    this.formName         = list.name;
    this.formCode         = list.code;
    this.formDescription  = list.description ?? '';
    this.formSource       = list.source;
    this.formTickerFormat = list.tickerFormat;
    this.formCustomSuffix = list.customSuffix ?? '';
    this.saveError.set(null);
    this.symbolError.set(null);
    this.loadDetail(list.id);
  }

  onClose(): void { this.mode.set(null); this.closed.emit(); }

  onSaveList(): void {
    if (!this.formName.trim() || !this.formCode.trim()) return;
    this.saving.set(true);
    this.saveError.set(null);

    const request: TickerListRequest = {
      name:         this.formName.trim(),
      code:         this.formCode.trim().toUpperCase(),
      description:  this.formDescription.trim() || null,
      source:       this.formSource,
      tickerFormat: this.formTickerFormat,
      customSuffix: this.formTickerFormat === 'CUSTOM'
                      ? (this.formCustomSuffix.trim() || null)
                      : null,
    };

    const call = this.mode() === 'new'
      ? this.tickerListService.createList(request)
      : this.tickerListService.updateList(this.editingList()!.id, request);

    call.subscribe({
      next:  (saved) => {
        this.saving.set(false);
        if (this.mode() === 'new') this.openEdit(saved);
        else this.editingList.set(saved);
        this.saved.emit();
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(
          err.status === 409 ? `Code '${request.code}' ist bereits vergeben`
                             : 'Speichern fehlgeschlagen'
        );
      },
    });
  }

  onDeleteList(): void {
    if (!confirm(`Liste "${this.editingList()!.name}" wirklich löschen?`)) return;
    this.saving.set(true);
    this.tickerListService.deleteList(this.editingList()!.id).subscribe({
      next:  () => { this.saving.set(false); this.mode.set(null); this.deleted.emit(); },
      error: () => { this.saving.set(false); this.saveError.set('Löschen fehlgeschlagen'); },
    });
  }

  onAddSymbol(): void {
    if (!this.newRawSymbol.trim() || !this.detail()) return;
    this.addingSymbol.set(true);
    this.symbolError.set(null);

    const request: TickerSymbolRequest = {
      rawSymbol:   this.newRawSymbol.trim().toUpperCase(),
      displayName: this.newDisplayName.trim() || null,
    };

    this.tickerListService.addSymbol(this.detail()!.id, request).subscribe({
      next: () => {
        this.addingSymbol.set(false);
        this.newRawSymbol = ''; this.newDisplayName = '';
        this.loadDetail(this.detail()!.id);
        this.saved.emit();
      },
      error: (err) => {
        this.addingSymbol.set(false);
        this.symbolError.set(
          err.status === 409 ? `Symbol '${request.rawSymbol}' existiert bereits`
                             : 'Symbol konnte nicht hinzugefügt werden'
        );
      },
    });
  }

  onDeleteSymbol(sym: TickerSymbol): void {
    this.tickerListService.deleteSymbol(this.detail()!.id, sym.id).subscribe({
      next:  () => { this.loadDetail(this.detail()!.id); this.saved.emit(); },
      error: () => this.symbolError.set('Symbol konnte nicht gelöscht werden'),
    });
  }

  private loadDetail(id: number): void {
    this.tickerListService.getListDetail(id).subscribe({
      next:  (d) => this.detail.set(d),
      error: ()  => this.symbolError.set('Symbole konnten nicht geladen werden'),
    });
  }
}

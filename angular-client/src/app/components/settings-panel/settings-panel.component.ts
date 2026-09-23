import {
  Component, HostListener, computed, effect, inject, input, output, signal, untracked
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MlModelInfo, VpnInfo, VpnRotateResult } from '../../models/stock.models';
import { MlService } from '../../services/ml.service';
import { VpnService } from '../../services/vpn.service';
import { MlModelInfoComponent } from '../ml-model-info/ml-model-info.component';

type MessageKind = 'ok' | 'warn' | 'error';

/**
 * SettingsPanelComponent
 *
 * Off-Canvas-Panel von rechts (Zahnrad im Header). Zeigt die aktuellen
 * VPN-Daten (Yahoo-Abrufe laufen über das VPN) und erlaubt, die IP-Adresse
 * zu wechseln. Die Komponente bleibt dauerhaft im DOM, damit ein laufender
 * IP-Wechsel auch bei geschlossenem Panel weiterläuft und beim erneuten
 * Öffnen sichtbar ist.
 */
@Component({
  selector: 'app-settings-panel',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule, MlModelInfoComponent],
  template: `
    <div class="backdrop" [class.visible]="open()" (click)="close()"></div>

    <aside class="panel" [class.open]="open()"
           role="dialog" aria-modal="true" aria-label="Einstellungen"
           [attr.aria-hidden]="!open()">

      <div class="panel-header">
        <h2 class="panel-title">Einstellungen</h2>
        <button mat-icon-button (click)="close()" aria-label="Schließen" matTooltip="Schließen">
          <mat-icon>close</mat-icon>
        </button>
      </div>

      <div class="panel-body">
        <section class="section">
          <div class="section-head">
            <h3 class="section-title">
              VPN <span class="section-hint">für Yahoo Finance</span>
            </h3>
            <button mat-icon-button (click)="reload()"
                    [disabled]="loadingInfo() || rotating()"
                    aria-label="VPN-Daten aktualisieren" matTooltip="Aktualisieren">
              <mat-icon [class.spin-icon]="loadingInfo()">refresh</mat-icon>
            </button>
          </div>

          @if (loadingInfo() && !info()) {
            <div class="loading-row">
              <mat-spinner diameter="18" /> VPN-Daten werden geladen …
            </div>
          } @else if (info(); as i) {
            <dl class="kv">
              <dt>Status</dt>
              <dd>
                <span class="chip" [class.chip-ok]="i.status === 'running'"
                                   [class.chip-bad]="i.status !== null && i.status !== 'running'">
                  {{ statusLabel(i.status) }}
                </span>
              </dd>

              <dt>IP-Adresse</dt>
              <dd class="mono">{{ i.ip ?? '–' }}</dd>

              <dt>Standort</dt>
              <dd>{{ location() }}</dd>

              <dt>Anbieter</dt>
              <dd>{{ i.organization ?? '–' }}</dd>
            </dl>

            @if (i.error) {
              <div class="note warn">{{ i.error }}</div>
            }
          } @else if (loadError()) {
            <div class="note error">{{ loadError() }}</div>
          }

          <button mat-flat-button color="primary" class="btn-rotate"
                  (click)="rotate()"
                  [disabled]="rotating() || analysisRunning()">
            @if (rotating()) {
              <mat-icon class="spin-icon">sync</mat-icon>
            } @else {
              <mat-icon>swap_horiz</mat-icon>
            }
            {{ rotating() ? 'Wechsle IP-Adresse …' : 'IP-Adresse ändern' }}
          </button>

          @if (rotating()) {
            <p class="hint">Das VPN wird neu gestartet – das kann bis zu einer Minute dauern.</p>
          } @else if (analysisRunning()) {
            <p class="hint">Während einer Analyse nicht möglich – der Wechsel würde laufende Abrufe unterbrechen.</p>
          }

          @if (message(); as m) {
            <div class="note" [class.ok]="m.kind === 'ok'"
                              [class.warn]="m.kind === 'warn'"
                              [class.error]="m.kind === 'error'">
              {{ m.text }}
            </div>
          }
        </section>

        <section class="section section-ml">
          <div class="section-head">
            <h3 class="section-title">
              KI-Modell <span class="section-hint">Umkehrsignal</span>
            </h3>
            <button mat-icon-button (click)="reloadMl()" [disabled]="mlLoading()"
                    aria-label="Modell-Info aktualisieren" matTooltip="Aktualisieren">
              <mat-icon [class.spin-icon]="mlLoading()">refresh</mat-icon>
            </button>
          </div>

          @if (mlLoading() && !mlInfo()) {
            <div class="loading-row">
              <mat-spinner diameter="18" /> Modell-Info wird geladen …
            </div>
          } @else if (mlInfo(); as info) {
            <app-ml-model-info [info]="info" />
          } @else if (mlError()) {
            <div class="note error">{{ mlError() }}</div>
          }
        </section>
      </div>
    </aside>
  `,
  styles: [`
    .backdrop {
      position: fixed; inset: 0; z-index: 200;
      background: rgba(17, 24, 39, 0.4);
      opacity: 0; visibility: hidden;
      transition: opacity 0.25s ease, visibility 0s linear 0.25s;
    }
    .backdrop.visible {
      opacity: 1; visibility: visible;
      transition: opacity 0.25s ease;
    }

    .panel {
      position: fixed; top: 0; right: 0; bottom: 0; z-index: 210;
      width: 380px; max-width: 100vw;
      display: flex; flex-direction: column;
      background: #fff; box-shadow: -4px 0 24px rgba(0, 0, 0, 0.15);
      transform: translateX(100%); visibility: hidden;
      transition: transform 0.25s ease, visibility 0s linear 0.25s;
    }
    .panel.open {
      transform: translateX(0); visibility: visible;
      transition: transform 0.25s ease;
    }

    .panel-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 12px 12px 12px 24px; border-bottom: 1px solid #e5e7eb;
    }
    .panel-title { margin: 0; font-size: 16px; font-weight: 600; }
    .panel-body  { flex: 1; overflow-y: auto; padding: 20px 24px; }

    .section-ml { margin-top: 28px; padding-top: 20px; border-top: 1px solid #e5e7eb; }

    .section-head {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: 8px;
    }
    .section-title { margin: 0; font-size: 14px; font-weight: 600; }
    .section-hint  { font-size: 11px; font-weight: 400; color: #6b7280; margin-left: 6px; }

    .loading-row {
      display: flex; align-items: center; gap: 10px;
      font-size: 13px; color: #6b7280; padding: 12px 0;
    }

    .kv {
      display: grid; grid-template-columns: 96px 1fr;
      gap: 8px 12px; margin: 0 0 16px; font-size: 13px;
    }
    .kv dt { color: #6b7280; }
    .kv dd { margin: 0; word-break: break-word; }
    .mono  { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

    .chip {
      display: inline-block; padding: 2px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 500; background: #f3f4f6; color: #4b5563;
    }
    .chip-ok  { background: #dcfce7; color: #166534; }
    .chip-bad { background: #fee2e2; color: #991b1b; }

    .btn-rotate { width: 100%; margin-top: 4px; }
    .hint { margin: 8px 0 0; font-size: 12px; color: #6b7280; }

    .note {
      margin-top: 12px; padding: 10px 12px; border-radius: 8px;
      font-size: 12px; line-height: 1.4;
      background: #f3f4f6; color: #374151;
    }
    .note.ok    { background: #dcfce7; color: #166534; }
    .note.warn  { background: #fef3c7; color: #92400e; }
    .note.error { background: #fee2e2; color: #991b1b; }

    .spin-icon { animation: spin 0.8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
  `],
})
export class SettingsPanelComponent {

  private readonly vpn = inject(VpnService);
  private readonly ml  = inject(MlService);

  /** Panel sichtbar? */
  readonly open            = input<boolean>(false);
  /** Läuft gerade eine Analyse? Dann ist der IP-Wechsel gesperrt. */
  readonly analysisRunning = input<boolean>(false);

  readonly closed = output<void>();

  readonly info        = signal<VpnInfo | null>(null);
  readonly loadingInfo = signal(false);
  readonly loadError   = signal<string | null>(null);
  readonly rotating    = signal(false);

  readonly mlInfo    = signal<MlModelInfo | null>(null);
  readonly mlLoading = signal(false);
  readonly mlError   = signal<string | null>(null);
  readonly message     = signal<{ kind: MessageKind; text: string } | null>(null);

  /** "Amsterdam, NH, Niederlande" – fehlende Teile werden ausgelassen. */
  readonly location = computed(() => {
    const i = this.info();
    if (!i) return '–';
    const parts = [i.city, i.region, this.countryName(i.country)].filter((p): p is string => !!p);
    return parts.length > 0 ? parts.join(', ') : '–';
  });

  constructor() {
    // Beim Öffnen die aktuellen VPN-Daten laden
    effect(() => {
      if (this.open()) {
        untracked(() => {
          this.reload();
          this.reloadMl();
        });
      }
    });
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open()) this.close();
  }

  close(): void {
    this.closed.emit();
  }

  reload(): void {
    if (this.rotating()) return;
    this.loadingInfo.set(true);
    this.loadError.set(null);
    this.vpn.getInfo().subscribe({
      next: (info) => {
        this.info.set(info);
        this.loadingInfo.set(false);
      },
      error: () => {
        this.loadError.set('VPN-Daten nicht abrufbar – läuft der agent-service-java?');
        this.loadingInfo.set(false);
      },
    });
  }

  reloadMl(): void {
    this.mlLoading.set(true);
    this.mlError.set(null);
    this.ml.getModelInfo().subscribe({
      next: (info) => {
        this.mlInfo.set(info);
        this.mlLoading.set(false);
      },
      error: () => {
        this.mlError.set('Modell-Info nicht abrufbar – läuft der agent-service-java?');
        this.mlLoading.set(false);
      },
    });
  }

  rotate(): void {
    if (this.rotating() || this.analysisRunning()) return;
    this.rotating.set(true);
    this.message.set(null);

    this.vpn.rotateIp().subscribe({
      next: (result) => {
        this.message.set(this.describe(result));
        this.rotating.set(false);
        this.reload();
      },
      error: () => {
        this.message.set({ kind: 'error', text: 'IP-Wechsel fehlgeschlagen – Agent nicht erreichbar.' });
        this.rotating.set(false);
      },
    });
  }

  statusLabel(status: string | null): string {
    switch (status) {
      case 'running': return 'Verbunden';
      case 'stopped': return 'Getrennt';
      case null:      return 'Unbekannt';
      default:        return status;
    }
  }

  private describe(result: VpnRotateResult): { kind: MessageKind; text: string } {
    if (result.error) {
      return { kind: 'error', text: `IP-Wechsel fehlgeschlagen: ${result.error}` };
    }
    if (result.changed) {
      return { kind: 'ok', text: `IP gewechselt: ${result.oldIp ?? 'unbekannt'} → ${result.newIp}` };
    }
    return {
      kind: 'warn',
      text: `IP unverändert (${result.newIp}) – nach ${result.attempts} Versuchen wurde wieder derselbe Server gewählt. ` +
            'Die Serverauswahl ist begrenzt; ein späterer Versuch kann klappen.',
    };
  }

  /** ISO-Ländercode → deutscher Name ("NL" → "Niederlande"), sonst der Code selbst. */
  private countryName(code: string | null): string | null {
    if (!code) return null;
    try {
      return new Intl.DisplayNames(['de'], { type: 'region' }).of(code) ?? code;
    } catch {
      return code;
    }
  }
}

import { Component, computed, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { MlExplanation } from '../../models/stock.models';
import { candleName, formatNumber, formatPoints } from '../../shared/ml-format.util';

export interface MlExplanationModalData {
  ticker: string;
  name: string | null;
  /** angezeigter Modellwert, z.B. 27.7 */
  reversalPct: number;
  /** Signalstufe für die Beschriftung */
  signalLabel: string;
  explanation: MlExplanation;
}

interface BarRow {
  label:   string;
  value:   string;
  effect:  string;
  /** Breite des Balkens in % der halben Balkenfläche (0-100) */
  width:   number;
  positive: boolean;
}

/**
 * Erklärung eines KI-Modellwerts ("Warum dieser Wert?").
 *
 * Zeigt, wie sich der Wert zusammensetzt: Ausgangswert des Modells, dann der
 * Einfluss der wichtigsten Merkmale als Balken nach links (senkt den Wert) bzw.
 * rechts (erhöht ihn), zuletzt der Modellwert. Dumb Component - die Zahlen
 * kommen fertig vom ml-service (XGBoost pred_contribs).
 */
@Component({
  selector: 'app-ml-explanation-modal',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <div class="header">
      <div>
        <h2>{{ data.ticker }} <span class="name">{{ data.name }}</span></h2>
        <div class="sub">KI-Signal · {{ candles() }}</div>
      </div>
      <button mat-icon-button mat-dialog-close aria-label="Schließen">
        <mat-icon>close</mat-icon>
      </button>
    </div>

    <div class="content">
      <div class="score">
        <span class="score-value">{{ score() }} %</span>
        <span class="score-label">{{ data.signalLabel }}</span>
      </div>

      <p class="lead">
        Das Modell schätzt, wie stark ein <strong>Kursanstieg von mehr als 3 %</strong>
        innerhalb der nächsten 5 {{ candles() }} zu erwarten ist. Der Wert ist ein
        Modellwert, keine garantierte Wahrscheinlichkeit.
      </p>

      @if (context(); as ctx) {
        <div class="context">{{ ctx }}</div>
      }

      <h3>Was bestimmt den Wert?</h3>

      <div class="row row-edge">
        <span class="row-label">Ausgangswert des Modells</span>
        <span class="row-total">{{ base() }} %</span>
      </div>

      @for (row of rows(); track row.label) {
        <div class="row">
          <div class="row-label">
            {{ row.label }}
            <span class="row-value">aktuell {{ row.value }}</span>
          </div>
          <div class="bar-area" aria-hidden="true">
            <div class="axis"></div>
            <div class="bar" [class.bar-pos]="row.positive" [class.bar-neg]="!row.positive"
                 [style.width.%]="row.width / 2"
                 [style.left.%]="row.positive ? 50 : 50 - row.width / 2"></div>
          </div>
          <span class="row-effect" [class.eff-pos]="row.positive" [class.eff-neg]="!row.positive">
            {{ row.effect }}
          </span>
        </div>
      }

      @if (other(); as o) {
        <div class="row">
          <span class="row-label row-muted">{{ o.label }}</span>
          <span></span>
          <span class="row-effect row-muted">{{ o.effect }}</span>
        </div>
      }

      <div class="row row-edge row-final">
        <span class="row-label">Modellwert</span>
        <span class="row-total">{{ score() }} %</span>
      </div>

      <p class="hint">
        Balken nach rechts erhöhen den Wert (mehr Anstieg erwartet), Balken nach links senken ihn.
        Pp = Prozentpunkte. Alle Zeitangaben zählen in Kerzen des gewählten Zeitrahmens.
      </p>
    </div>
  `,
  styles: [`
    .header { display: flex; justify-content: space-between; align-items: flex-start; padding: 16px 8px 0 24px; }
    .header h2 { margin: 0; font-size: 18px; }
    .name { font-size: 13px; font-weight: 400; color: #6b7280; margin-left: 6px; }
    .sub { font-size: 12px; color: #6b7280; margin-top: 2px; }
    .content { padding: 4px 24px 20px; font-size: 13px; color: #1f2937; }

    .score { display: flex; align-items: baseline; gap: 10px; margin: 8px 0 4px; }
    .score-value { font-size: 32px; font-weight: 700; }
    .score-label { font-size: 13px; color: #6b7280; }

    .lead { margin: 4px 0 8px; line-height: 1.5; }
    .context { margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; background: #f3f4f6; font-size: 12px; line-height: 1.45; }
    h3 { margin: 16px 0 8px; font-size: 13px; font-weight: 600; }

    .row { display: grid; grid-template-columns: 42% 1fr 64px; align-items: center; gap: 8px; padding: 5px 0; border-bottom: 1px solid #f3f4f6; }
    .row-edge { grid-template-columns: 1fr auto; font-weight: 600; background: #f9fafb; padding: 7px 8px; border-radius: 6px; border-bottom: none; margin-bottom: 2px; }
    .row-final { margin-top: 6px; }
    .row-total { font-variant-numeric: tabular-nums; }
    .row-label { line-height: 1.3; }
    .row-value { display: block; font-size: 11px; color: #6b7280; font-weight: 400; }
    .row-muted { color: #6b7280; font-size: 12px; }
    .row-effect { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
    .eff-pos { color: #166534; }
    .eff-neg { color: #991b1b; }

    .bar-area { position: relative; height: 14px; }
    .axis { position: absolute; left: 50%; top: 0; bottom: 0; width: 1px; background: #d1d5db; }
    .bar { position: absolute; top: 2px; height: 10px; border-radius: 3px; }
    .bar-pos { background: #22c55e; }
    .bar-neg { background: #ef4444; }

    .hint { margin: 12px 0 0; font-size: 11px; color: #6b7280; line-height: 1.45; }
  `],
})
export class MlExplanationModalComponent {

  readonly data = inject<MlExplanationModalData>(MAT_DIALOG_DATA);

  readonly candles = computed(() => candleName(this.data.explanation.interval));
  readonly score   = computed(() => formatNumber(this.data.reversalPct, 1));
  readonly base    = computed(() => formatNumber(this.data.explanation.base_pct, 1));

  /** Einordnung im Vergleich zum Training für diesen Zeitrahmen (nur bei Modellen ab 21.09.). */
  readonly context = computed(() => {
    const e = this.data.explanation;
    if (e.typical_score_pct == null || e.actual_rate_pct == null) return null;
    return `Zum Einordnen: Im Training lag der Modellwert für ${candleName(e.interval)} im Schnitt bei ` +
           `${formatNumber(e.typical_score_pct)} % – tatsächlich gab es in ${formatNumber(e.actual_rate_pct)} % ` +
           `der Fälle einen Anstieg über 3 %. Werte sind daher nur innerhalb desselben Zeitrahmens vergleichbar.`;
  });

  readonly rows = computed<BarRow[]>(() => {
    const factors = this.data.explanation.factors;
    const max = Math.max(...factors.map((f) => Math.abs(f.effect_pp)), 0.01);
    return factors.map((f) => ({
      label:    f.label,
      value:    f.value_text,
      effect:   formatPoints(f.effect_pp),
      width:    (Math.abs(f.effect_pp) / max) * 100,
      positive: f.effect_pp >= 0,
    }));
  });

  readonly other = computed(() => {
    const e = this.data.explanation;
    return e.other_count > 0
      ? { label: `${e.other_count} weitere Merkmale zusammen`, effect: formatPoints(e.other_pp) }
      : null;
  });
}

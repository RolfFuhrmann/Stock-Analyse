import { Component, computed, input } from '@angular/core';

import { MlIntervalBreakdown, MlModelInfo } from '../../models/stock.models';
import { candleName, formatNumber } from '../../shared/ml-format.util';

interface BreakdownRow {
  name:        string;
  train:       string;
  test:        string;
  positive:    string;
  typical:     string;
  untested:    boolean;
}

interface ImportanceRow {
  label: string;
  share: string;
  width: number;
}

interface CalibrationRow {
  range:     string;
  samples:    string;
  predicted:  string;
  actual:     string;
  predictedWidth: number;
  actualWidth:    number;
}

/**
 * Modell-Info des KI-Signals (Einstellungen → KI-Modell).
 *
 * Zeigt, was das Modell gelernt hat, woraus Training und Test bestehen, wie gut
 * es abschneidet und welche Merkmale es am stärksten nutzt. Dumb Component: die
 * Daten liefert GET /ml/info. Diagnosefelder (Zusammensetzung, Kalibrierung)
 * gibt es erst bei Modellen, die nach dem 21.09. trainiert wurden.
 */
@Component({
  selector: 'app-ml-model-info',
  standalone: true,
  template: `
    @if (!info().model_ready) {
      <div class="note">{{ info().error ?? 'Noch kein Modell trainiert.' }}</div>
    } @else {
      @if (info().calibrated === false) {
        <div class="note warn">
          Dieses Modell wurde vor der Kalibrierung trainiert – der Modellwert ist noch nicht als
          echte Wahrscheinlichkeit zu lesen. Ein neues Training (Einstellungen → KI-Modell neu
          trainieren, sofern verfügbar, oder <code>POST /model/train</code>) behebt das.
        </div>
      }
      <p class="lead">
        Das Modell lernt aus den Kursdaten aller Ticker, ob der Kurs innerhalb der nächsten
        <strong>{{ info().forecast_horizon }} Kerzen</strong> um mehr als
        <strong>{{ threshold() }} %</strong> steigt. Der angezeigte Wert (0–100 %) ist seine Einschätzung
        dafür – bei jedem Zeitrahmen (1d, 4h, 1h) zählt „Kerze“ anders.
      </p>

      <dl class="kv">
        <dt>Trainiert</dt><dd>{{ trainedAt() }}</dd>
        <dt>Nächstes Training</dt><dd>{{ nextRetrain() }}</dd>
        <dt>Trainingsdaten</dt><dd>{{ samples() }} Kerzen von {{ info().tickers_count }} Tickern</dd>
        <dt>Kalibriert</dt><dd>{{ info().calibrated ? 'ja' : 'nein – älteres Modell' }}</dd>
        <dt>Anstiege im Training</dt><dd>{{ positiveRate() }} %</dd>
        <dt>Signalstufen</dt><dd>{{ thresholds() }}</dd>
      </dl>

      <h4>Trefferqualität (Testmenge)</h4>
      <div class="metrics">
        <div class="metric" title="Trennschärfe: 50 % = Zufall, 100 % = perfekt">
          <span class="metric-value">{{ metric('roc_auc', true) }}</span><span class="metric-name">ROC-AUC</span>
        </div>
        <div class="metric" title="Anteil der gemeldeten Anstiege, die tatsächlich eintraten (Schwelle 50 %)">
          <span class="metric-value">{{ metric('precision', true) }}</span><span class="metric-name">Precision</span>
        </div>
        <div class="metric" title="Anteil der tatsächlichen Anstiege, die das Modell erkannt hat (Schwelle 50 %)">
          <span class="metric-value">{{ metric('recall', true) }}</span><span class="metric-name">Recall</span>
        </div>
      </div>

      @if (breakdown().length > 0) {
        <h4>Woraus Training und Test bestehen</h4>
        <table class="table">
          <thead>
            <tr><th>Zeitrahmen</th><th>Training</th><th>Test</th><th>Anstiege</th><th>Ø Modellwert</th></tr>
          </thead>
          <tbody>
            @for (row of breakdown(); track row.name) {
              <tr [class.untested]="row.untested">
                <td>{{ row.name }}</td><td>{{ row.train }}</td><td>{{ row.test }}</td>
                <td>{{ row.positive }}</td><td>{{ row.typical }}</td>
              </tr>
            }
          </tbody>
        </table>
        @if (untestedNames().length > 0) {
          <div class="note warn">
            Für {{ untestedNames().join(' und ') }} enthält die Testmenge keine Kerzen – die Trefferqualität oben
            sagt dazu nichts. „Ø Modellwert“ ist der durchschnittliche Wert des Modells im jeweiligen Zeitrahmen,
            „Anstiege“ der Anteil tatsächlicher Anstiege: liegt der Durchschnittswert weit darüber, ist der Wert
            nicht als Prozentsatz zu lesen (Klassen-Gewichtung im Training).
          </div>
        } @else {
          <div class="note">
            „Ø Modellwert“ ist der durchschnittliche Wert des Modells im jeweiligen Zeitrahmen, „Anstiege“ der Anteil
            tatsächlicher Anstiege. Liegt der Durchschnittswert weit darüber, ist der Wert nicht als Prozentsatz zu lesen.
          </div>
        }
      }

      @if (calibration().length > 0) {
        <details>
          <summary>Kalibrierung: Modellwert vs. Treffer</summary>
          <p class="small">Je Wertebereich: was das Modell im Schnitt meldete (grau) und wie oft der Anstieg tatsächlich kam (grün).</p>
          @for (row of calibration(); track row.range) {
            <div class="cal-row">
              <span class="cal-range">{{ row.range }}</span>
              <div class="cal-bars">
                <div class="cal-bar cal-pred" [style.width.%]="row.predictedWidth"></div>
                <div class="cal-bar cal-act" [style.width.%]="row.actualWidth"></div>
              </div>
              <span class="cal-nums">{{ row.predicted }} → {{ row.actual }} %</span>
            </div>
          }
        </details>
      }

      @if (importance().length > 0) {
        <details>
          <summary>Wichtigste Merkmale</summary>
          <p class="small">Wie stark das Modell die Merkmale insgesamt nutzt (Anteil an der Gesamtwichtigkeit).</p>
          @for (row of importance(); track row.label) {
            <div class="imp-row">
              <span class="imp-label">{{ row.label }}</span>
              <div class="imp-bar-wrap"><div class="imp-bar" [style.width.%]="row.width"></div></div>
              <span class="imp-share">{{ row.share }} %</span>
            </div>
          }
        </details>
      }
    }
  `,
  styles: [`
    :host { display: block; font-size: 13px; color: #1f2937; }
    .lead { margin: 0 0 12px; line-height: 1.5; }
    .kv { display: grid; grid-template-columns: 130px 1fr; gap: 6px 10px; margin: 0 0 12px; }
    .kv dt { color: #6b7280; }
    .kv dd { margin: 0; }
    h4 { margin: 14px 0 6px; font-size: 12px; font-weight: 600; }

    .metrics { display: flex; gap: 8px; }
    .metric { flex: 1; text-align: center; padding: 8px 4px; border-radius: 8px; background: #f3f4f6; cursor: help; }
    .metric-value { display: block; font-size: 16px; font-weight: 600; }
    .metric-name  { display: block; font-size: 11px; color: #6b7280; }

    .table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .table th { text-align: left; font-weight: 500; color: #6b7280; padding: 4px 4px 4px 0; border-bottom: 1px solid #e5e7eb; }
    .table td { padding: 4px 4px 4px 0; border-bottom: 1px solid #f3f4f6; font-variant-numeric: tabular-nums; }
    .table tr.untested td:nth-child(3) { color: #92400e; font-weight: 600; }

    .note { margin-top: 8px; padding: 8px 10px; border-radius: 8px; font-size: 12px; line-height: 1.45; background: #f3f4f6; color: #374151; }
    .note.warn { background: #fef3c7; color: #92400e; }

    details { margin-top: 12px; }
    summary { cursor: pointer; font-size: 12px; font-weight: 600; }
    .small { margin: 6px 0; font-size: 11px; color: #6b7280; }

    .cal-row, .imp-row { display: grid; align-items: center; gap: 8px; padding: 3px 0; font-size: 12px; }
    .cal-row { grid-template-columns: 56px 1fr 92px; }
    .cal-range { color: #6b7280; }
    .cal-bars { display: flex; flex-direction: column; gap: 2px; }
    .cal-bar { height: 6px; border-radius: 3px; }
    .cal-pred { background: #9ca3af; }
    .cal-act  { background: #22c55e; }
    .cal-nums { text-align: right; font-variant-numeric: tabular-nums; }

    .imp-row { grid-template-columns: 1fr 70px 40px; }
    .imp-bar-wrap { height: 6px; border-radius: 3px; background: #f3f4f6; }
    .imp-bar { height: 6px; border-radius: 3px; background: #6366f1; }
    .imp-share { text-align: right; font-variant-numeric: tabular-nums; }
  `],
})
export class MlModelInfoComponent {

  readonly info = input.required<MlModelInfo>();

  readonly threshold = computed(() => formatNumber(this.info().reversal_threshold_pct ?? 0, 0));
  readonly samples   = computed(() => (this.info().total_samples ?? 0).toLocaleString('de-DE'));
  readonly positiveRate = computed(() => formatNumber(this.info().positive_rate_pct ?? 0, 1));

  readonly trainedAt = computed(() => this.formatDateTime(this.info().trained_at));
  readonly nextRetrain = computed(() => this.formatDateTime(this.info().next_retrain));

  readonly thresholds = computed(() => {
    const t = this.info().thresholds;
    return t ? `schwach ab ${t.weak} %, mittel ab ${t.moderate} %, stark ab ${t.strong} %` : '–';
  });

  metric(key: 'roc_auc' | 'precision' | 'recall', asPercent: boolean): string {
    const value = this.info().metrics?.[key];
    if (value == null) return '–';
    return asPercent ? `${formatNumber(value * 100, 0)} %` : formatNumber(value, 3);
  }

  readonly breakdown = computed<BreakdownRow[]>(() => {
    const data = this.info().breakdown;
    if (!data) return [];
    return Object.entries(data).map(([interval, b]: [string, MlIntervalBreakdown]) => ({
      name:     candleName(interval),
      train:    b.train_samples.toLocaleString('de-DE'),
      test:     b.test_samples.toLocaleString('de-DE'),
      positive: b.positive_rate_pct != null ? `${formatNumber(b.positive_rate_pct)} %` : '–',
      typical:  b.mean_score_pct != null ? `${formatNumber(b.mean_score_pct)} %` : '–',
      untested: b.test_samples === 0,
    }));
  });

  readonly untestedNames = computed(() => this.breakdown().filter((r) => r.untested).map((r) => r.name));

  readonly calibration = computed<CalibrationRow[]>(() =>
    (this.info().calibration ?? []).map((c) => ({
      range:          `${c.bin_from}–${c.bin_to} %`,
      samples:        c.samples.toLocaleString('de-DE'),
      predicted:      formatNumber(c.predicted_pct, 0),
      actual:         formatNumber(c.actual_pct, 0),
      predictedWidth: Math.min(c.predicted_pct, 100),
      actualWidth:    Math.min(c.actual_pct, 100),
    })),
  );

  readonly importance = computed<ImportanceRow[]>(() => {
    const items = (this.info().feature_importance ?? []).slice(0, 8);
    const max = Math.max(...items.map((i) => i.importance), 0.0001);
    return items.map((i) => ({
      label: i.label,
      share: formatNumber(i.importance * 100, 1),
      width: (i.importance / max) * 100,
    }));
  });

  private formatDateTime(value: string | null | undefined): string {
    if (!value) return '–';
    const date = new Date(value);
    return isNaN(date.getTime())
      ? value
      : date.toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' });
  }
}

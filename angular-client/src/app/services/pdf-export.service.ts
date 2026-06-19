import { Injectable } from '@angular/core';
import { StockResult, AnalysisSummary } from '../models/stock.models';

/**
 * PdfExportService
 *
 * Erzeugt ein druckoptimiertes HTML-Dokument in einem neuen Browserfenster
 * und ruft window.print() auf. Dadurch kann der Nutzer das Ergebnis als PDF
 * lokal speichern – ohne externe Bibliothek, ohne Server-Roundtrip.
 */
@Injectable({ providedIn: 'root' })
export class PdfExportService {

  exportResults(results: StockResult[], summary: AnalysisSummary): void {
    const html = this.buildHtml(results, summary);
    const printWindow = window.open('', '_blank', 'width=1100,height=700');
    if (!printWindow) {
      console.warn('Popup blockiert – bitte Popup-Blocker deaktivieren.');
      return;
    }
    printWindow.document.write(html);
    printWindow.document.close();
    printWindow.onload = () => printWindow.print();
  }

  private buildHtml(results: StockResult[], summary: AnalysisSummary): string {
    const timestamp   = new Date().toLocaleString('de-DE');
    const sourceLabel = summary.source === 'yahoo' ? 'Yahoo Finance' : 'Twelve Data';
    const firstResult = results[0];
    const intervalLabel: Record<string, string> = { '1d': 'Daily', '4h': '4 Stunden', '1h': '1 Stunde' };
    const tfLabel = firstResult ? (intervalLabel[firstResult.interval ?? '1d'] ?? 'Daily') : 'Daily';

    return `<!DOCTYPE html>
<html lang="de">
<head>
  <meta charset="UTF-8"/>
  <title>Stock Analyse – ${timestamp}</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: 'Segoe UI', Arial, sans-serif; font-size: 11px; color: #1a1f2e; padding: 24px; }
    h1 { font-size: 18px; font-weight: 700; color: #1d9e75; margin-bottom: 4px; }
    .meta { font-size: 10px; color: #6b7280; margin-bottom: 20px; }
    .kpi-row { display: flex; gap: 16px; margin-bottom: 20px; }
    .kpi { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 16px; min-width: 100px; }
    .kpi-val { font-size: 20px; font-weight: 700; }
    .kpi-label { font-size: 9px; color: #6b7280; margin-top: 2px; }
    .kpi-green .kpi-val { color: #166534; }
    .kpi-amber .kpi-val { color: #854d0e; }
    table { width: 100%; border-collapse: collapse; font-size: 10px; }
    thead tr { background: #f9fafb; }
    th { padding: 7px 8px; font-size: 9px; font-weight: 600; color: #6b7280;
         text-transform: uppercase; letter-spacing: 0.05em;
         border-bottom: 2px solid #e5e7eb; text-align: center; }
    .th-left  { text-align: left; }
    .th-right { text-align: right; }
    td { padding: 6px 8px; border-bottom: 1px solid #f3f4f6; vertical-align: middle; }
    tr.row-all3 { background: #f0fdf4; }
    tr.row-2of3 { background: #fefce8; }
    .ticker-name { font-family: monospace; font-weight: 600; }
    .td-right  { text-align: right; font-variant-numeric: tabular-nums; }
    .td-center { text-align: center; }
    .td-left   { text-align: left; }
    .neg { color: #b91c1c; } .pos { color: #166534; }
    .badge { display: inline-block; padding: 2px 8px; border-radius: 20px; font-size: 9px; font-weight: 600; }
    .badge-true  { background: #dcfce7; color: #166534; }
    .badge-false { background: #f3f4f6; color: #6b7280; }
    .score-3 { background: #dcfce7; color: #166534; }
    .score-2 { background: #fef9c3; color: #854d0e; }
    .score-1, .score-0 { background: #f3f4f6; color: #9ca3af; }
    /* Umkehrformation Badges */
    .ml-badge { display:inline-block; padding:2px 7px; border-radius:10px; font-size:9px; font-weight:600; }
      .ml-none     { background:#f3f4f6; color:#9ca3af; }
      .ml-weak     { background:#fef9c3; color:#854d0e; }
      .ml-moderate { background:#ffedd5; color:#9a3412; }
      .ml-strong   { background:#fee2e2; color:#991b1b; }
      .candle-badge { display: inline-block; padding: 2px 7px; border-radius: 20px; font-size: 9px; font-weight: 500; white-space: nowrap; }
    .candle-s5 { background: #fce7f3; color: #9d174d; }
    .candle-s4 { background: #ede9fe; color: #5b21b6; }
    .candle-s3 { background: #dbeafe; color: #1e40af; }
    .candle-s2 { background: #dcfce7; color: #166534; }
    .candle-s1 { background: #f3f4f6; color: #374151; }
    .footer { margin-top: 20px; font-size: 9px; color: #9ca3af; border-top: 1px solid #e5e7eb; padding-top: 10px; }
    @media print { body { padding: 0; } }
  </style>
</head>
<body>
  <h1>◎ Stock Analyse</h1>
  <div class="meta">Erstellt: ${timestamp} · Datenquelle: ${sourceLabel} · Zeitrahmen: ${tfLabel}</div>

  <div class="kpi-row">
    <div class="kpi">
      <div class="kpi-val">${summary.total}</div>
      <div class="kpi-label">Analysiert</div>
    </div>
    <div class="kpi kpi-green">
      <div class="kpi-val">${summary.count3of3}</div>
      <div class="kpi-label">Alle 3 Kriterien</div>
    </div>
    <div class="kpi kpi-amber">
      <div class="kpi-val">${summary.count2of3}</div>
      <div class="kpi-label">2 von 3</div>
    </div>
  </div>

  <table>
    <thead>
      <tr>
        <th class="th-left">Basiswert</th>
        <th class="th-right">Kurs</th>
        <th class="th-right">Trend</th>
        <th>Elliott Wave</th>
        <th>Stochastik</th>
        <th>MACD-Histogramm</th>
        <th>Score</th>
        <th class="th-left">Umkehrformation</th>
      </tr>
    </thead>
    <tbody>
      ${results.map((r) => this.buildRow(r)).join('')}
    </tbody>
  </table>

  <div class="footer">
    Elliott Wave · Stochastik · MACD-Histogramm · Candlestick Pattern · KI-Umkehrsignal · Stock Analyse Platform
  </div>
</body>
</html>`;
  }

  private buildRow(r: StockResult): string {
    const rowClass = r.criteria_met === 3 ? 'row-all3' : r.criteria_met === 2 ? 'row-2of3' : '';
    const price    = r.current_price != null ? `$ ${r.current_price.toFixed(2)}` : '–';
    const trend    = r.trend_pct != null
      ? `<span class="${r.trend_pct >= 0 ? 'pos' : 'neg'}">${r.trend_pct >= 0 ? '+' : ''}${r.trend_pct.toFixed(1)}%</span>`
      : '–';
    const badge    = (v: boolean) =>
      `<span class="badge ${v ? 'badge-true' : 'badge-false'}">${v ? 'True' : 'False'}</span>`;
    const score    = `<span class="badge score-${r.criteria_met}">${r.criteria_met}/3</span>`;
    const candle   = r.candle_pattern
      ? `<span class="candle-badge candle-s${r.candle_strength}">${r.candle_pattern}</span>`
      : '–';
    const mlSignal: Record<string, string> = { strong: '🔥 Stark', moderate: '↑ Mittel', weak: '~ Schwach', none: '–' };
    const mlClass:  Record<string, string> = { strong: 'ml-strong', moderate: 'ml-moderate', weak: 'ml-weak', none: 'ml-none' };
    const ml = r.ml_available && r.reversal_pct != null
      ? `<span class="ml-badge ${mlClass[r.ml_signal] ?? 'ml-none'}">${r.reversal_pct.toFixed(0)}% ${mlSignal[r.ml_signal] ?? ''}</span>`
      : '–';

    return `<tr class="${rowClass}">
      <td><span class="ticker-name">${r.ticker}</span>${r.error ? `<div style="color:#ef4444;font-size:9px">${r.error}</div>` : ''}</td>
      <td class="td-right">${price}</td>
      <td class="td-right">${trend}</td>
      <td class="td-center">${badge(r.elliott_wave)}</td>
      <td class="td-center">${badge(r.stochastic)}</td>
      <td class="td-center">${badge(r.macd_histogram)}</td>
      <td class="td-center">${score}</td>
      <td class="td-left">${candle}</td>
      <td class="td-center">${ml}</td>
    </tr>`;
  }
}

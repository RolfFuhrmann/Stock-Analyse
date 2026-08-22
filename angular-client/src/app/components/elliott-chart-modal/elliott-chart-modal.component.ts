import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, Inject, OnDestroy, ViewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ColorType, IChartApi, LineStyle, createChart } from 'lightweight-charts';
import { ElliottChartData } from '../../models/stock.models';
import { barsToCandlestickData, swingsToZigzagLine, toChartTime } from '../../shared/elliott-chart.util';

export interface ElliottChartModalData {
  ticker: string;
  name: string | null;
  /** elliott_wave_stage, z.B. "A-B- -> 50% 75,00" */
  stage: string;
  chart: ElliottChartData;
}

/**
 * Großes Modal für den Elliott-Wave-Chart ("Option C", siehe CLAUDE.md
 * Abschnitt 3.2c), geöffnet per Klick auf die Chart-Vorschau in der
 * Ergebnistabelle. Zeigt Kerzen im ta4j-Elliott-Lookback-Fenster, die
 * erkannten Wellen-Beine als beschriftete Zickzack-Linie (A/B/C bzw. 1-5)
 * und - sofern vorhanden - das Kursziel der laufenden Welle als
 * gestrichelte Preislinie.
 */
@Component({
  selector: 'app-elliott-chart-modal',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatIconModule, MatButtonModule],
  template: `
    <div class="modal-header">
      <div>
        <h2>{{ data.ticker }} <span class="modal-name">{{ data.name }}</span></h2>
        @if (data.stage) {
          <div class="modal-stage">{{ data.stage }}</div>
        }
      </div>
      <button mat-icon-button mat-dialog-close aria-label="Schließen">
        <mat-icon>close</mat-icon>
      </button>
    </div>
    <div #container class="modal-chart"></div>
    <div class="modal-legend">
      <span><span class="legend-dot legend-swing"></span> erkannte Wellen (ta4j)</span>
      @if (data.chart.target; as target) {
        <span
          ><span class="legend-dot legend-target"></span> Kursziel{{
            target.retracement_pct ? ' (' + target.retracement_pct + '%)' : ''
          }}: {{ target.price }}</span
        >
      }
    </div>
  `,
  styles: [`
    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      padding: 16px 8px 0 16px;
    }
    h2 {
      font-size: 18px;
      margin: 0;
      font-weight: 600;
    }
    .modal-name {
      font-weight: 400;
      color: #6b7280;
      font-size: 14px;
      margin-left: 8px;
    }
    .modal-stage {
      font-size: 13px;
      color: #6b7280;
      margin-top: 2px;
    }
    .modal-chart {
      width: 720px;
      max-width: 80vw;
      height: 420px;
      padding: 8px 16px 0;
    }
    .modal-legend {
      display: flex;
      gap: 20px;
      padding: 8px 16px 16px;
      font-size: 12px;
      color: #4b5563;
    }
    .legend-dot {
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 50%;
      margin-right: 4px;
    }
    .legend-swing {
      background: #2563eb;
    }
    .legend-target {
      background: #ea580c;
    }
  `],
})
export class ElliottChartModalComponent implements AfterViewInit, OnDestroy {
  @ViewChild('container') containerRef!: ElementRef<HTMLDivElement>;

  private chart?: IChartApi;

  constructor(
    public dialogRef: MatDialogRef<ElliottChartModalComponent>,
    @Inject(MAT_DIALOG_DATA) public data: ElliottChartModalData,
  ) {}

  ngAfterViewInit(): void {
    const el = this.containerRef.nativeElement;

    this.chart = createChart(el, {
      width: el.clientWidth,
      height: 420,
      layout: { background: { type: ColorType.Solid, color: '#ffffff' }, textColor: '#1a1f2e' },
      grid: { vertLines: { color: '#f0f0f0' }, horzLines: { color: '#f0f0f0' } },
      timeScale: { timeVisible: true, secondsVisible: false },
    });

    const candleSeries = this.chart.addCandlestickSeries({
      upColor: '#16a34a',
      downColor: '#dc2626',
      borderVisible: false,
      wickUpColor: '#16a34a',
      wickDownColor: '#dc2626',
    });
    candleSeries.setData(barsToCandlestickData(this.data.chart.bars));

    const swings = this.data.chart.swings;
    const zigzag = swingsToZigzagLine(swings);
    if (zigzag.length > 1) {
      const lineSeries = this.chart.addLineSeries({
        color: '#2563eb',
        lineWidth: 2,
        priceLineVisible: false,
        lastValueVisible: false,
      });
      lineSeries.setData(zigzag);

      // Wellen-Label (A/B/C bzw. 1-5) am Endpunkt jedes Beins markieren.
      lineSeries.setMarkers(
        swings.map((s) => ({
          time: toChartTime(s.to_date),
          position: 'inBar' as const,
          color: '#2563eb',
          shape: 'circle' as const,
          text: s.label,
        })),
      );
    }

    const target = this.data.chart.target;
    if (target) {
      candleSeries.createPriceLine({
        price: target.price,
        color: '#ea580c',
        lineWidth: 2,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: target.retracement_pct ? `Ziel ${target.retracement_pct}%` : 'Ziel',
      });
    }

    this.chart.timeScale().fitContent();
  }

  ngOnDestroy(): void {
    this.chart?.remove();
  }
}

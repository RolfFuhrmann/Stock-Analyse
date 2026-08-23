import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild,
  input,
  output,
} from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { IChartApi } from 'lightweight-charts';
import { ElliottChartData } from '../../models/stock.models';
import { barsToCandlestickData, swingsToZigzagLine } from '../../shared/elliott-chart.util';

/**
 * Kleine, achsenlose Chart-Vorschau in der Ergebnistabelle ("Option C",
 * siehe CLAUDE.md Abschnitt 3.2c). Zeigt Kerzen + erkannte Wellen-Zickzack-
 * Linie des ta4j-Szenarios. Klick öffnet das große Modal
 * (ElliottChartModalComponent) mit vollständiger Achsenbeschriftung,
 * Wellen-Labels und Kursziel-Linie.
 */
@Component({
  selector: 'app-elliott-chart-thumbnail',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    @if (data()) {
      <div class="thumb-wrapper" (click)="opened.emit()" matTooltip="Wellen-Chart öffnen">
        <div #container class="thumb-chart"></div>
      </div>
    } @else {
      <span class="thumb-empty">–</span>
    }
  `,
  styles: [`
    .thumb-wrapper {
      width: 92px;
      height: 32px;
      cursor: pointer;
      border-radius: 4px;
    }
    .thumb-wrapper:hover {
      background: #f3f4f6;
    }
    .thumb-chart {
      width: 100%;
      height: 100%;
    }
    .thumb-empty {
      color: #9ca3af;
    }
  `],
})
export class ElliottChartThumbnailComponent implements AfterViewInit, OnChanges, OnDestroy {
  readonly data = input<ElliottChartData | null>(null);
  readonly opened = output<void>();

  @ViewChild('container') containerRef?: ElementRef<HTMLDivElement>;

  private chart?: IChartApi;
  private viewReady = false;

  ngAfterViewInit(): void {
    this.viewReady = true;
    void this.render();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['data'] && this.viewReady) {
      void this.render();
    }
  }

  ngOnDestroy(): void {
    this.chart?.remove();
  }

  /**
   * lightweight-charts wird per dynamischem Import nachgeladen statt statisch
   * importiert - dadurch landet die Bibliothek in einem separaten,
   * lazy-geladenen Chunk statt im initialen Bundle (das erst beim App-Start
   * geladen wird, lange bevor überhaupt Ergebnisse mit Chart-Daten existieren).
   * Reduziert das initiale Bundle spürbar (siehe angular.json-Budget).
   */
  private async render(): Promise<void> {
    const chartData = this.data();
    const el = this.containerRef?.nativeElement;
    if (!chartData || !el || chartData.bars.length === 0) {
      this.chart?.remove();
      this.chart = undefined;
      return;
    }

    const { createChart, ColorType } = await import('lightweight-charts');

    // Zwischen Start des Imports und Auflösung könnte data() sich geändert
    // haben oder die Komponente zerstört worden sein - erneut prüfen.
    if (this.data() !== chartData || !this.containerRef) {
      return;
    }

    this.chart?.remove();
    this.chart = createChart(el, {
      width: el.clientWidth || 92,
      height: 32,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: 'transparent', attributionLogo: false },
      grid: { vertLines: { visible: false }, horzLines: { visible: false } },
      rightPriceScale: { visible: false },
      timeScale: { visible: false },
      crosshair: { vertLine: { visible: false }, horzLine: { visible: false } },
      handleScroll: false,
      handleScale: false,
    });

    const candleSeries = this.chart.addCandlestickSeries({
      upColor: '#16a34a',
      downColor: '#dc2626',
      borderVisible: false,
      wickUpColor: '#16a34a',
      wickDownColor: '#dc2626',
    });
    candleSeries.setData(barsToCandlestickData(chartData.bars));

    const zigzag = swingsToZigzagLine(chartData.swings);
    if (zigzag.length > 1) {
      const lineSeries = this.chart.addLineSeries({
        color: '#2563eb',
        lineWidth: 2,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
      });
      lineSeries.setData(zigzag);
    }

    this.chart.timeScale().fitContent();
  }
}

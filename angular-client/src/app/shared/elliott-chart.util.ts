import type { CandlestickData, LineData, UTCTimestamp } from 'lightweight-charts';
import { ElliottChartBar, ElliottSwingPoint } from '../models/stock.models';

/**
 * Wandelt einen Backend-Datumsstring (reines Datum "1d" oder ISO-Timestamp
 * "1h"/"4h", siehe OhlcvBar-Kommentar in stock.models.ts) in einen
 * lightweight-charts-UTCTimestamp (Sekunden) um. Funktioniert für beide
 * Formate einheitlich über Date.parse, statt zwei verschiedene
 * lightweight-charts-Time-Formate (BusinessDay vs. UTCTimestamp) je nach
 * Intervall unterscheiden zu müssen.
 */
export function toChartTime(dateStr: string): UTCTimestamp {
  return Math.floor(Date.parse(dateStr) / 1000) as UTCTimestamp;
}

export function barsToCandlestickData(bars: ElliottChartBar[]): CandlestickData[] {
  return bars.map((b) => ({
    time: toChartTime(b.date),
    open: b.open,
    high: b.high,
    low: b.low,
    close: b.close,
  }));
}

/**
 * Verbindet alle vom Szenario erkannten Wellen-Beine zu einer durchgängigen
 * Zickzack-Linie (jeder Swing-Endpunkt ist zugleich Startpunkt des
 * nächsten), damit eine einzige Linien-Serie für den ganzen Wellenzug
 * genügt statt einer Serie pro Bein.
 */
export function swingsToZigzagLine(swings: ElliottSwingPoint[]): LineData[] {
  if (swings.length === 0) {
    return [];
  }
  const points: LineData[] = [{ time: toChartTime(swings[0].from_date), value: swings[0].from_price }];
  for (const swing of swings) {
    points.push({ time: toChartTime(swing.to_date), value: swing.to_price });
  }
  return points;
}

/**
 * Formatierung für die ML-Anzeigen (deutsche Schreibweise).
 */

/** Zahl mit fester Nachkommastellenzahl, deutsch ("4,1"). */
export function formatNumber(value: number, digits = 1): string {
  return value.toLocaleString('de-DE', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

/** Prozentpunkte mit Vorzeichen ("+4,1 Pp" / "−2,3 Pp"; echtes Minuszeichen). */
export function formatPoints(value: number): string {
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return `${sign}${formatNumber(Math.abs(value), 1)} Pp`;
}

/** Anzeigename des Zeitrahmens für Sätze: "Tageskerzen", "4h-Kerzen", "1h-Kerzen". */
export function candleName(interval: string): string {
  return interval === '1d' ? 'Tageskerzen' : `${interval}-Kerzen`;
}

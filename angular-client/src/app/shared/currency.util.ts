/**
 * ISO-4217-Code → Anzeigesymbol, für die auf der Plattform tatsächlich
 * vorkommenden Ticker-Herkünfte (DAX/Dow/weitere US- und internationale
 * Werte). Unbekannte Codes werden unverändert als Code angezeigt (z.B.
 * "SEK 123,45") statt geraten - immer noch korrekt, auch wenn nicht ganz
 * so hübsch wie ein echtes Symbol.
 */
const CURRENCY_SYMBOLS: Record<string, string> = {
  USD: '$',
  EUR: '€',
  GBP: '£',
  CHF: 'CHF',
  JPY: '¥',
  CAD: 'C$',
  AUD: 'A$',
  HKD: 'HK$',
};

/**
 * Währungssymbol für die Preisanzeige. Bevorzugt die vom Daten-Service
 * gelieferte Währung (StockResult.currency, ISO-4217 - siehe TickerQuote in
 * agent-service-java). Freie Ticker-Listen können Werte aus verschiedenen
 * Währungsräumen mischen, daher reicht eine reine Ticker-Suffix-Heuristik
 * nicht (die z.B. ".DE" → € annahm, aber z.B. Schweizer oder britische
 * Ticker falsch auf $ gemappt hätte). Fallback auf genau diese alte
 * Heuristik nur, falls der Daten-Service (noch) keine Währung liefert.
 */
export function currencySymbol(currency: string | null | undefined, ticker: string): string {
  if (currency) {
    const code = currency.toUpperCase();
    return CURRENCY_SYMBOLS[code] ?? code;
  }
  return ticker.toUpperCase().endsWith('.DE') ? '€' : '$';
}

package rf.stock.data.db.access.busines;

/**
 * Unterstützte Börsenplätze.
 * Der yahooSuffix steuert die Ticker-Normalisierung für Yahoo Finance –
 * XETRA-Ticker bekommen z.B. ".DE" angehängt, US-Plätze keinen Suffix.
 */
public enum Exchange {

    XETRA("XETRA", ".DE"),
    NYSE("NYSE", ""),
    NASDAQ("NASDAQ", ""),
    LSE("LSE", ".L"),
    EURONEXT("EURONEXT", ".PA");

    private final String displayName;
    private final String yahooSuffix;

    Exchange(String displayName, String yahooSuffix) {
        this.displayName = displayName;
        this.yahooSuffix = yahooSuffix;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getYahooSuffix() {
        return yahooSuffix;
    }

    /** Normalisiert einen Roh-Ticker für Yahoo Finance. */
    public String normalizeForYahoo(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new IllegalArgumentException("rawSymbol darf nicht leer sein");
        }
        // Bereits normalisierter Ticker wird nicht doppelt gesuffixed
        if (rawSymbol.contains(".")) {
            return rawSymbol.toUpperCase();
        }
        return rawSymbol.toUpperCase() + yahooSuffix;
    }
}

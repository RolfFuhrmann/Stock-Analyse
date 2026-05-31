package rf.stock.data.db.access.busines;

/**
 * Unterstützte Börsenplätze.
 * Der yahooSuffix wird nur noch im Angular-Client für die Anzeige berechnet –
 * in der DB wird ausschließlich raw_symbol gespeichert.
 */
public enum Exchange {

    XETRA("XETRA"),
    NYSE("NYSE"),
    NASDAQ("NASDAQ"),
    LSE("LSE"),
    EURONEXT("EURONEXT");

    private final String displayName;

    Exchange(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

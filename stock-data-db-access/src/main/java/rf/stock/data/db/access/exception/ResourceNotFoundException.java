package rf.stock.data.db.access.exception;

/** Wird geworfen wenn eine gesuchte Ressource nicht existiert → HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException forList(Long id) {
        return new ResourceNotFoundException("Ticker-Liste mit ID " + id + " nicht gefunden");
    }

    public static ResourceNotFoundException forListCode(String code) {
        return new ResourceNotFoundException("Ticker-Liste mit Code '" + code + "' nicht gefunden");
    }

    public static ResourceNotFoundException forSymbol(Long id) {
        return new ResourceNotFoundException("Ticker-Symbol mit ID " + id + " nicht gefunden");
    }
}

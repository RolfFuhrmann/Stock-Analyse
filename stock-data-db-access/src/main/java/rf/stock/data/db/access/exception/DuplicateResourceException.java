package rf.stock.data.db.access.exception;

/** Wird geworfen bei Verletzung von Unique-Constraints → HTTP 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}

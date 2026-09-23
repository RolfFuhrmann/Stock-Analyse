package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ergebnis von POST /vpn/rotate. Fehler werden nicht als HTTP-Fehler, sondern
 * im Feld "error" gemeldet - der Client zeigt sie direkt an.
 *
 * @param oldIp    Ausgangs-IP vor dem Wechsel (null, falls nicht ermittelbar)
 * @param newIp    Ausgangs-IP nach dem Wechsel (null bei Fehler)
 * @param changed  true, wenn sich die IP geändert hat
 * @param attempts Anzahl der Neustart-Versuche (bei gleicher IP wird erneut versucht)
 * @param error    Fehlermeldung, null bei Erfolg
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VpnRotateResult(
        String oldIp,
        String newIp,
        boolean changed,
        int attempts,
        String error) {

    public static VpnRotateResult success(String oldIp, String newIp, int attempts) {
        return new VpnRotateResult(oldIp, newIp, !newIp.equals(oldIp), attempts, null);
    }

    public static VpnRotateResult failed(String message) {
        return new VpnRotateResult(null, null, false, 0, message);
    }
}

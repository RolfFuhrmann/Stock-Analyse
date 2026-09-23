package rf.stock.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Aktuelle VPN-Daten für die Einstellungen im Client (GET /vpn/info).
 * Yahoo-Abrufe laufen über das VPN - die hier gezeigte IP ist die, unter der
 * Yahoo den Abruf sieht.
 *
 * @param status       Zustand des Tunnels laut Gluetun ("running", "stopped", ...) oder null, wenn nicht ermittelbar
 * @param ip           Ausgangs-IP, null wenn aktuell keine Verbindung besteht
 * @param country      ISO-Ländercode (z.B. "NL") - der Client zeigt den Namen an
 * @param organization Anbieter/AS des Servers laut IP-Datenbank
 * @param error        Hinweis, wenn Teile der Daten nicht ermittelt werden konnten
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VpnInfo(
        String status,
        String ip,
        String city,
        String region,
        String country,
        String organization,
        String error) {
}

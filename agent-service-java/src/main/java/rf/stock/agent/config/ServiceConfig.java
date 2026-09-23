package rf.stock.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Zentrale Konfiguration des Agent Service.
 * Alle Werte sind über application.yml und Umgebungsvariablen überschreibbar.
 */
@ConfigurationProperties(prefix = "services")
public record ServiceConfig(
        String yahooUrl,
        String twelvedataUrl,
        String mlUrl,
        String dbUrl,
        String vpnControlUrl) {
}

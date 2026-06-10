package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Stammdaten pro Ticker.
 * Verbindet das raw_symbol aus ticker_symbols mit dem normalisierten
 * API-Symbol (z.B. ADS → ADS.DE für Yahoo Finance / XETRA).
 * Wird vom history-fetcher befüllt und vom ML-Service gelesen.
 */
@Entity
@Table(name = "ticker_meta")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TickerMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalisiertes API-Symbol, z.B. ADS.DE oder AAPL – eindeutig. */
    @Column(nullable = false, unique = true, length = 30)
    private String ticker;

    /** Roh-Symbol wie in ticker_symbols gespeichert, z.B. ADS. */
    @Column(name = "raw_symbol", nullable = false, length = 20)
    private String rawSymbol;

    /** Datenquelle: yahoo | twelvedata. */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(length = 12)
    private String isin;

    @Column(length = 100)
    private String sector;

    @Column(length = 50)
    private String country;

    @Column(name = "last_refreshed")
    private LocalDateTime lastRefreshed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (source == null) source = "yahoo";
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

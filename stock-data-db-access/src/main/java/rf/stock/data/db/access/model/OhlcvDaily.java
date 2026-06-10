package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tageskerze (OHLCV) für einen Ticker.
 * Eindeutig per (ticker, trade_date) – Duplikate werden per UPSERT vermieden.
 */
@Entity
@Table(
    name = "ohlcv_daily",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_ohlcv_daily_ticker_date",
        columnNames = {"ticker", "trade_date"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OhlcvDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalisiertes API-Symbol, z.B. ADS.DE oder AAPL. */
    @Column(nullable = false, length = 30)
    private String ticker;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal close;

    private Long volume;

    /** Datenquelle des Abrufs: yahoo | twelvedata. */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private LocalDateTime fetchedAt;

    @PrePersist
    protected void onCreate() {
        if (source == null)    source    = "yahoo";
        if (fetchedAt == null) fetchedAt = LocalDateTime.now();
    }
}

package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stundenkerze (OHLCV) für einen Ticker.
 * Eindeutig per (ticker, trade_time) – Granularität: 1 Stunde.
 */
@Entity
@Table(
    name = "ohlcv_hourly",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_ohlcv_hourly_ticker_time",
        columnNames = {"ticker", "trade_time"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OhlcvHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String ticker;

    /**
     * Beginn der Stundenkerze in lokaler Börsenzeit ohne Zeitzone (NICHT UTC):
     * Xetra-Werte in Berliner Zeit (z.B. 09:00-17:00), US-Werte in New Yorker
     * Zeit (z.B. 09:30-15:30). So legen history-fetcher und agent-service-java
     * die Werte ab, der Zeitstempel wird unverändert aus dem Yahoo-/TwelveData-
     * Zeitstempel übernommen (Zone/Offset abgeschnitten).
     */
    @Column(name = "trade_time", nullable = false)
    private LocalDateTime tradeTime;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal close;

    private Long volume;

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

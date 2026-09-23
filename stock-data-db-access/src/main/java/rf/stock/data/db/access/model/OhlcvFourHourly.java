package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 4-Stunden-Kerze (OHLCV) für einen Ticker.
 * Eindeutig per (ticker, trade_time) – Granularität: 4 Stunden.
 * Für Yahoo-Ticker durch Aggregation aus ohlcv_hourly befüllt,
 * für TwelveData-Ticker direkt via interval="4h" abgerufen.
 */
@Entity
@Table(
    name = "ohlcv_4h",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_ohlcv_4h_ticker_time",
        columnNames = {"ticker", "trade_time"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OhlcvFourHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String ticker;

    /**
     * Kerzenbeginn in lokaler Börsenzeit ohne Zeitzone (NICHT UTC).
     * Yahoo (aus 1h aggregiert): Blockstart 0/4/8/12/16/20 Uhr, z.B. Xetra
     * 08:00, 12:00, 16:00. TwelveData (nativ): Blockgrenzen der Börse, z.B.
     * NYSE 09:30 und 13:30 (New Yorker Zeit).
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

package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;
import rf.stock.data.db.access.busines.Exchange;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "ticker_symbols",
    uniqueConstraints = @UniqueConstraint(columnNames = {"list_id", "raw_symbol"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TickerSymbol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "list_id", nullable = false)
    private TickerList tickerList;

    /** Roh-Ticker wie vom Nutzer eingegeben, z.B. "ADS" oder "AAPL". */
    @Column(name = "raw_symbol", nullable = false, length = 20)
    private String rawSymbol;

    /** Normalisierter Ticker für Yahoo Finance, z.B. "ADS.DE". */
    @Column(name = "yahoo_symbol", nullable = false, length = 20)
    private String yahooSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        // yahoo_symbol wird automatisch berechnet falls nicht explizit gesetzt
        if (yahooSymbol == null || yahooSymbol.isBlank()) {
            yahooSymbol = exchange.normalizeForYahoo(rawSymbol);
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

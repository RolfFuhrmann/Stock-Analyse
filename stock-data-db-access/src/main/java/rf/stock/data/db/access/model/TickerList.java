package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ticker_lists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TickerList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(length = 255)
    private String description;

    /** Datenquelle: "yahoo" oder "twelvedata". */
    @Column(nullable = false, length = 20)
    private String source;

    /**
     * Ticker-Format für Yahoo-Normalisierung:
     *   RAW    – Ticker unverändert (z.B. ^GDAXI, AAPL)
     *   XETRA  – .DE anhängen (z.B. ADS → ADS.DE)
     *   CUSTOM – customSuffix anhängen (z.B. .PA, .L)
     */
    @Column(name = "ticker_format", nullable = false, length = 10)
    private String tickerFormat;

    /** Nur relevant wenn tickerFormat = CUSTOM, z.B. ".PA" oder ".L". */
    @Column(name = "custom_suffix", length = 10)
    private String customSuffix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "tickerList", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<TickerSymbol> symbols = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (source == null)       source = "yahoo";
        if (tickerFormat == null) tickerFormat = "RAW";
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

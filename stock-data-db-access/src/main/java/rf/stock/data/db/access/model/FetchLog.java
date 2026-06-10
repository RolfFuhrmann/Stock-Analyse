package rf.stock.data.db.access.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Protokolleintrag für einen Datenabruf.
 * Wird vom history-fetcher nach jedem Ticker-Abruf geschrieben.
 */
@Entity
@Table(name = "fetch_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FetchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String ticker;

    /** Intervall des Abrufs: daily | hourly. */
    @Column(name = "interval_type", nullable = false, length = 10)
    private String intervalType;

    @Column(nullable = false, length = 20)
    private String source;

    /** Ergebnis: SUCCESS | ERROR | PARTIAL. */
    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "bars_fetched", nullable = false)
    private int barsFetched;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "run_at", nullable = false, updatable = false)
    private LocalDateTime runAt;

    @PrePersist
    protected void onCreate() {
        if (runAt == null) runAt = LocalDateTime.now();
    }
}

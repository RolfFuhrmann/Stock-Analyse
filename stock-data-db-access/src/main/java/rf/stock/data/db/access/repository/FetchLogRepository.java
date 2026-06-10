package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rf.stock.data.db.access.model.FetchLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FetchLogRepository extends JpaRepository<FetchLog, Long> {

    List<FetchLog> findByTickerOrderByRunAtDesc(String ticker);

    /** Letzter erfolgreicher Abruf für einen Ticker und Intervall. */
    @Query("""
        SELECT f FROM FetchLog f
        WHERE f.ticker = :ticker
          AND f.intervalType = :intervalType
          AND f.status = 'SUCCESS'
        ORDER BY f.runAt DESC
        LIMIT 1
        """)
    Optional<FetchLog> findLastSuccess(
        @Param("ticker") String ticker,
        @Param("intervalType") String intervalType
    );

    /** Alle Fehler seit einem bestimmten Zeitpunkt – für Monitoring. */
    @Query("""
        SELECT f FROM FetchLog f
        WHERE f.status = 'ERROR'
          AND f.runAt >= :since
        ORDER BY f.runAt DESC
        """)
    List<FetchLog> findErrorsSince(@Param("since") LocalDateTime since);

    /** Zusammenfassung des letzten Laufs pro Ticker. */
    @Query("""
        SELECT f FROM FetchLog f
        WHERE f.runAt = (
            SELECT MAX(f2.runAt) FROM FetchLog f2
            WHERE f2.ticker = f.ticker AND f2.intervalType = f.intervalType
        )
        ORDER BY f.ticker
        """)
    List<FetchLog> findLatestPerTicker();
}

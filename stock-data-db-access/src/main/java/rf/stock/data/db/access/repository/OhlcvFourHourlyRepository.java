package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rf.stock.data.db.access.model.OhlcvFourHourly;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OhlcvFourHourlyRepository extends JpaRepository<OhlcvFourHourly, Long> {

    /** Ticker mit Zeilenzahl, für die ML-Trainingsauswahl (21.09.) - unabhängig von ticker_meta. */
    @Query("SELECT o.ticker, COUNT(o) FROM OhlcvFourHourly o GROUP BY o.ticker")
    List<Object[]> countRowsByTicker();

    List<OhlcvFourHourly> findByTickerOrderByTradeTimeAsc(String ticker);

    List<OhlcvFourHourly> findByTickerAndTradeTimeBetweenOrderByTradeTimeAsc(
        String ticker, LocalDateTime from, LocalDateTime to
    );

    @Query("""
        SELECT o FROM OhlcvFourHourly o
        WHERE o.ticker = :ticker
        ORDER BY o.tradeTime DESC
        LIMIT :limit
        """)
    List<OhlcvFourHourly> findLatestByTicker(@Param("ticker") String ticker, @Param("limit") int limit);

    @Query("SELECT MAX(o.tradeTime) FROM OhlcvFourHourly o WHERE o.ticker = :ticker")
    Optional<LocalDateTime> findLatestTradeTimeByTicker(@Param("ticker") String ticker);

    /** Anzahl GRUPPIERT nach Ticker, in einem Query (siehe OhlcvDailyRepository.aggregateByTicker). */
    @Query("SELECT o.ticker, COUNT(o) FROM OhlcvFourHourly o GROUP BY o.ticker")
    List<Object[]> countGroupByTicker();

    boolean existsByTickerAndTradeTime(String ticker, LocalDateTime tradeTime);

    /** Für Upsert: liefert die vorhandene Kerze, falls schon vorhanden. */
    Optional<OhlcvFourHourly> findByTickerAndTradeTime(String ticker, LocalDateTime tradeTime);

    long countByTicker(String ticker);

    @Modifying
    @Query("DELETE FROM OhlcvFourHourly o WHERE o.ticker = :ticker")
    int deleteByTicker(@Param("ticker") String ticker);
}

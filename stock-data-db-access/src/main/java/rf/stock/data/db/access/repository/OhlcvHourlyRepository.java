package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rf.stock.data.db.access.model.OhlcvHourly;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OhlcvHourlyRepository extends JpaRepository<OhlcvHourly, Long> {

    List<OhlcvHourly> findByTickerOrderByTradeTimeAsc(String ticker);

    List<OhlcvHourly> findByTickerAndTradeTimeBetweenOrderByTradeTimeAsc(
        String ticker, LocalDateTime from, LocalDateTime to
    );

    @Query("""
        SELECT o FROM OhlcvHourly o
        WHERE o.ticker = :ticker
        ORDER BY o.tradeTime DESC
        LIMIT :limit
        """)
    List<OhlcvHourly> findLatestByTicker(@Param("ticker") String ticker, @Param("limit") int limit);

    @Query("SELECT MAX(o.tradeTime) FROM OhlcvHourly o WHERE o.ticker = :ticker")
    Optional<LocalDateTime> findLatestTradeTimeByTicker(@Param("ticker") String ticker);

    boolean existsByTickerAndTradeTime(String ticker, LocalDateTime tradeTime);

    long countByTicker(String ticker);

    @Modifying
    @Query("DELETE FROM OhlcvHourly o WHERE o.ticker = :ticker")
    int deleteByTicker(@Param("ticker") String ticker);
}

package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rf.stock.data.db.access.model.OhlcvDaily;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OhlcvDailyRepository extends JpaRepository<OhlcvDaily, Long> {

    /** Alle Tageskerzen für einen Ticker, chronologisch sortiert. */
    List<OhlcvDaily> findByTickerOrderByTradeDateAsc(String ticker);

    /** Tageskerzen für einen Ticker in einem Datumsbereich. */
    List<OhlcvDaily> findByTickerAndTradeDateBetweenOrderByTradeDateAsc(
        String ticker, LocalDate from, LocalDate to
    );

    /** Neueste N Kerzen für einen Ticker – für den täglichen Analyse-Lauf. */
    @Query("""
        SELECT o FROM OhlcvDaily o
        WHERE o.ticker = :ticker
        ORDER BY o.tradeDate DESC
        LIMIT :limit
        """)
    List<OhlcvDaily> findLatestByTicker(@Param("ticker") String ticker, @Param("limit") int limit);

    /** Letztes verfügbares Datum pro Ticker – für inkrementelle Updates. */
    @Query("SELECT MAX(o.tradeDate) FROM OhlcvDaily o WHERE o.ticker = :ticker")
    Optional<LocalDate> findLatestTradeDateByTicker(@Param("ticker") String ticker);

    boolean existsByTickerAndTradeDate(String ticker, LocalDate tradeDate);

    long countByTicker(String ticker);

    /** Löscht alle Kerzen eines Tickers – für Neuaufbau bei Datenproblemen. */
    @Modifying
    @Query("DELETE FROM OhlcvDaily o WHERE o.ticker = :ticker")
    int deleteByTicker(@Param("ticker") String ticker);
}

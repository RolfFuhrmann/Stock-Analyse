package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rf.stock.data.db.access.model.TickerMeta;

import java.util.List;
import java.util.Optional;

public interface TickerMetaRepository extends JpaRepository<TickerMeta, Long> {

    Optional<TickerMeta> findByTicker(String ticker);

    Optional<TickerMeta> findByRawSymbol(String rawSymbol);

    boolean existsByTicker(String ticker);

    List<TickerMeta> findBySource(String source);

    /** Alle Ticker-Symbole (normalisiert) für einen bestimmten Abruf-Typ. */
    @Query("SELECT m.ticker FROM TickerMeta m WHERE m.source = :source ORDER BY m.ticker")
    List<String> findAllTickersBySource(@Param("source") String source);
}

package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rf.stock.data.db.access.model.TickerSymbol;

import java.util.List;
import java.util.Optional;

@Repository
public interface TickerSymbolRepository extends JpaRepository<TickerSymbol, Long> {

    List<TickerSymbol> findByTickerListId(Long listId);

    Optional<TickerSymbol> findByTickerListIdAndRawSymbol(Long listId, String rawSymbol);

    boolean existsByTickerListIdAndRawSymbol(Long listId, String rawSymbol);

    /** Liefert alle raw_symbols einer Liste. */
    @Query("SELECT ts.rawSymbol FROM TickerSymbol ts WHERE ts.tickerList.id = :listId")
    List<String> findRawSymbolsByListId(@Param("listId") Long listId);

    /** Dasselbe per Listen-Code. */
    @Query("SELECT ts.rawSymbol FROM TickerSymbol ts WHERE ts.tickerList.code = :listCode")
    List<String> findRawSymbolsByListCode(@Param("listCode") String listCode);
}

package rf.stock.data.db.access.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rf.stock.data.db.access.model.TickerList;

import java.util.Optional;

@Repository
public interface TickerListRepository extends JpaRepository<TickerList, Long> {

    Optional<TickerList> findByCode(String code);

    boolean existsByCode(String code);
}

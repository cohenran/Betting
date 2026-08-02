package il.co.sportpredict.repo;

import il.co.sportpredict.domain.BettingSelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BettingSelectionRepository extends JpaRepository<BettingSelection, Long> {

    List<BettingSelection> findByRoundIdOrderByLineNoAsc(Long roundId);
}

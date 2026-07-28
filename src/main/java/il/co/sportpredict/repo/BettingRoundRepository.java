package il.co.sportpredict.repo;

import il.co.sportpredict.domain.BettingRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BettingRoundRepository extends JpaRepository<BettingRound, Long> {

    List<BettingRound> findTop20ByOrderByFetchedAtDesc();
}

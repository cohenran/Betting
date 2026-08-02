package il.co.sportpredict.repo;

import il.co.sportpredict.domain.Fighter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FighterRepository extends JpaRepository<Fighter, Long> {

    Optional<Fighter> findByNormalizedName(String normalizedName);

    List<Fighter> findTop25ByOrderByEloDesc();
}

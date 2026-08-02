package il.co.sportpredict.repo;

import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findBySportAndNormalizedName(Sport sport, String normalizedName);

    List<Team> findBySport(Sport sport);
}

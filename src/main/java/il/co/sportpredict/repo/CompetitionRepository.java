package il.co.sportpredict.repo;

import il.co.sportpredict.domain.Competition;
import il.co.sportpredict.domain.Sport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {

    Optional<Competition> findBySportAndNameAndCountry(Sport sport, String name, String country);
}

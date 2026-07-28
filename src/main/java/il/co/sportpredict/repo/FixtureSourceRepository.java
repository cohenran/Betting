package il.co.sportpredict.repo;

import il.co.sportpredict.domain.FixtureSource;
import il.co.sportpredict.domain.Sport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FixtureSourceRepository extends JpaRepository<FixtureSource, Long> {

    Optional<FixtureSource> findByProviderAndSportAndExternalId(String provider, Sport sport, String externalId);
}

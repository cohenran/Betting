package il.co.sportpredict.repo;

import il.co.sportpredict.domain.FightSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FightSourceRepository extends JpaRepository<FightSource, Long> {

    Optional<FightSource> findByProviderAndExternalId(String provider, String externalId);
}

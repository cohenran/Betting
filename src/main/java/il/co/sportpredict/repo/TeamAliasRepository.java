package il.co.sportpredict.repo;

import il.co.sportpredict.domain.TeamAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamAliasRepository extends JpaRepository<TeamAlias, Long> {

    Optional<TeamAlias> findByProviderAndExternalId(String provider, String externalId);

    List<TeamAlias> findByProviderAndNormalizedName(String provider, String normalizedName);

    List<TeamAlias> findByNormalizedName(String normalizedName);
}

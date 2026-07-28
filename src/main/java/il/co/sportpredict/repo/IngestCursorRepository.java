package il.co.sportpredict.repo;

import il.co.sportpredict.domain.IngestCursor;
import il.co.sportpredict.domain.Sport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IngestCursorRepository extends JpaRepository<IngestCursor, Long> {

    Optional<IngestCursor> findByProviderAndSport(String provider, Sport sport);
}

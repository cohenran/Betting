package il.co.sportpredict.repo;

import il.co.sportpredict.domain.IngestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngestRunRepository extends JpaRepository<IngestRun, Long> {

    List<IngestRun> findTop30ByOrderByStartedAtDesc();
}

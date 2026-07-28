package il.co.sportpredict.repo;

import il.co.sportpredict.domain.ModelState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelStateRepository extends JpaRepository<ModelState, Long> {

    Optional<ModelState> findByModelKey(String modelKey);
}

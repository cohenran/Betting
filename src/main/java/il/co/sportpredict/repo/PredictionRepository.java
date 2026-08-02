package il.co.sportpredict.repo;

import il.co.sportpredict.domain.Prediction;
import il.co.sportpredict.domain.Sport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findFirstByFixtureIdAndModelOrderByCreatedAtDesc(Long fixtureId, String model);

    Optional<Prediction> findFirstByFightIdAndModelOrderByCreatedAtDesc(Long fightId, String model);

    @Query("""
           select p from Prediction p
           where p.settled = false and p.fixture is not null
             and p.fixture.status = il.co.sportpredict.domain.EventStatus.FINISHED
             and p.fixture.homeScore is not null
           """)
    List<Prediction> findSettleableFixturePredictions();

    @Query("""
           select p from Prediction p
           where p.settled = false and p.fight is not null
             and p.fight.status = il.co.sportpredict.domain.EventStatus.FINISHED
             and p.fight.winner is not null
           """)
    List<Prediction> findSettleableFightPredictions();

    @Query("select p from Prediction p where p.sport = :sport and p.settled = true")
    List<Prediction> findSettled(@Param("sport") Sport sport);
}

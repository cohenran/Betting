package il.co.sportpredict.repo;

import il.co.sportpredict.domain.Fight;
import il.co.sportpredict.domain.Fighter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FightRepository extends JpaRepository<Fight, Long> {

    Optional<Fight> findByFighterAAndFighterBAndFightDate(Fighter a, Fighter b, Instant fightDate);

    @Query("""
           select f from Fight f join fetch f.fighterA join fetch f.fighterB
           where f.status = il.co.sportpredict.domain.EventStatus.FINISHED and f.learned = false and f.winner is not null
           order by f.fightDate asc
           """)
    List<Fight> findUnlearned();

    @Query("""
           select f from Fight f join fetch f.fighterA join fetch f.fighterB
           where f.fightDate between :from and :to order by f.fightDate asc
           """)
    List<Fight> findBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
           select f from Fight f join fetch f.fighterA join fetch f.fighterB
           where f.status = il.co.sportpredict.domain.EventStatus.FINISHED and f.winner is not null and f.fightDate >= :since
           order by f.fightDate asc
           """)
    List<Fight> findTrainingSet(@Param("since") Instant since);

    @Query("select f from Fight f join fetch f.fighterA join fetch f.fighterB where f.id = :id")
    Optional<Fight> findWithFighters(@Param("id") Long id);
}

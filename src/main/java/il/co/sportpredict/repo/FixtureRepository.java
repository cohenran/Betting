package il.co.sportpredict.repo;

import il.co.sportpredict.domain.EventStatus;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FixtureRepository extends JpaRepository<Fixture, Long> {

    Optional<Fixture> findBySportAndHomeTeamAndAwayTeamAndKickoff(
            Sport sport, Team homeTeam, Team awayTeam, Instant kickoff);

    /** Same pairing within a time window - providers disagree on kickoff by minutes. */
    @Query("""
           select f from Fixture f
           where f.sport = :sport and f.homeTeam = :home and f.awayTeam = :away
             and f.kickoff between :from and :to
           """)
    List<Fixture> findPairingNear(@Param("sport") Sport sport,
                                  @Param("home") Team home,
                                  @Param("away") Team away,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    @Query("""
           select f from Fixture f
             join fetch f.homeTeam join fetch f.awayTeam left join fetch f.competition
           where f.sport = :sport and f.status = il.co.sportpredict.domain.EventStatus.FINISHED
             and f.homeScore is not null and f.kickoff >= :since
           order by f.kickoff asc
           """)
    List<Fixture> findTrainingSet(@Param("sport") Sport sport, @Param("since") Instant since);

    @Query("""
           select f from Fixture f
             join fetch f.homeTeam join fetch f.awayTeam left join fetch f.competition
           where f.sport = :sport and f.kickoff between :from and :to
           order by f.kickoff asc
           """)
    List<Fixture> findBetween(@Param("sport") Sport sport,
                              @Param("from") Instant from,
                              @Param("to") Instant to);

    @Query("""
           select f from Fixture f
             join fetch f.homeTeam join fetch f.awayTeam
           where f.status = il.co.sportpredict.domain.EventStatus.FINISHED and f.learned = false and f.homeScore is not null
           order by f.kickoff asc
           """)
    List<Fixture> findUnlearned();

    @Query("""
           select f from Fixture f
             join fetch f.homeTeam join fetch f.awayTeam left join fetch f.competition
           where f.id = :id
           """)
    Optional<Fixture> findWithTeams(@Param("id") Long id);

    long countBySportAndStatus(Sport sport, EventStatus status);

    /**
     * Marks every finished fixture as learned in one statement. Saving the entities
     * individually meant tens of thousands of UPDATEs inside the rebuild transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update Fixture f set f.learned = true
           where f.status = il.co.sportpredict.domain.EventStatus.FINISHED
             and f.homeScore is not null and f.learned = false
           """)
    int markFinishedAsLearned();
}

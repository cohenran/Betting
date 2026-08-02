package il.co.sportpredict.repo;

import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import il.co.sportpredict.domain.TeamRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamRatingRepository extends JpaRepository<TeamRating, Long> {

    Optional<TeamRating> findByTeamAndSport(Team team, Sport sport);

    @Query("select r from TeamRating r join fetch r.team where r.sport = :sport order by r.elo desc")
    List<TeamRating> findRanked(@Param("sport") Sport sport);
}

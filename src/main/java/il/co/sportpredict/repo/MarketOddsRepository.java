package il.co.sportpredict.repo;

import il.co.sportpredict.domain.MarketOdds;
import il.co.sportpredict.domain.Sport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MarketOddsRepository extends JpaRepository<MarketOdds, Long> {

    Optional<MarketOdds> findByFixtureIdAndProvider(Long fixtureId, String provider);

    /** Everything for one sport, for building an in-memory index during a backtest. */
    @Query("select o from MarketOdds o where o.fixture.sport = :sport")
    List<MarketOdds> findBySport(@Param("sport") Sport sport);

    @Query("select count(o) from MarketOdds o where o.fixture.sport = :sport")
    long countBySport(@Param("sport") Sport sport);
}

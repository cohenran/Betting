package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.MarketOdds;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.FixtureSourceRepository;
import il.co.sportpredict.repo.MarketOddsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Persists one chunk of prices in its own transaction.
 *
 * <p>Separate from {@link OddsBackfillService} for two reasons: a transaction must not stay
 * open across the provider calls, and each chunk has to commit as it lands so progress is
 * visible in the table while a long backfill runs.
 */
@Component
@RequiredArgsConstructor
public class MarketOddsWriter {

    static final String PROVIDER = AllSportsProvider.NAME;

    private final FixtureSourceRepository fixtureSources;
    private final FixtureRepository fixtures;
    private final MarketOddsRepository marketOdds;

    public record ChunkResult(int stored, int updated, int unmatched) {
    }

    @Transactional
    public ChunkResult store(Sport sport, Map<String, OddsSnapshot> odds) {
        int stored = 0;
        int updated = 0;
        int unmatched = 0;

        for (OddsSnapshot snapshot : odds.values()) {
            Long fixtureId = fixtureSources
                    .findByProviderAndSportAndExternalId(PROVIDER, sport, snapshot.externalId())
                    .map(source -> source.getFixture().getId())
                    .orElse(null);
            if (fixtureId == null) {
                unmatched++;
                continue;
            }
            var existing = marketOdds.findByFixtureIdAndProvider(fixtureId, PROVIDER);
            MarketOdds row = existing.orElseGet(MarketOdds::new);
            if (existing.isEmpty()) {
                // A reference proxy is enough - the row only needs the foreign key.
                row.setFixture(fixtures.getReferenceById(fixtureId));
                row.setProvider(PROVIDER);
            }
            row.setMedianHome(snapshot.medianHome());
            row.setMedianDraw(snapshot.medianDraw());
            row.setMedianAway(snapshot.medianAway());
            row.setBestHome(snapshot.bestHome());
            row.setBestDraw(snapshot.bestDraw());
            row.setBestAway(snapshot.bestAway());
            row.setBookmakers(snapshot.bookmakers());
            row.setFetchedAt(Instant.now());
            marketOdds.save(row);
            if (existing.isEmpty()) {
                stored++;
            } else {
                updated++;
            }
        }
        return new ChunkResult(stored, updated, unmatched);
    }
}

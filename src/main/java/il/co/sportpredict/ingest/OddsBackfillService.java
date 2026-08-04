package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.MarketOdds;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.FixtureSourceRepository;
import il.co.sportpredict.repo.MarketOddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Pulls historical market prices into {@code market_odds}.
 *
 * <p>Requests are chunked: the provider returns 500 for a wide range (a 61-day window
 * across a dozen leagues is more than it will build), while a few days at a time works.
 * Prices join to fixtures by the provider's own match id, so no name matching is involved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OddsBackfillService {

    private static final String PROVIDER = AllSportsProvider.NAME;

    private final AllSportsOddsClient oddsClient;
    private final FixtureSourceRepository fixtureSources;
    private final FixtureRepository fixtures;
    private final MarketOddsRepository marketOdds;

    public record BackfillReport(String sport, String window, int chunks, int pricedMatches,
                                int stored, int updated, int unmatched) {
    }

    @Transactional
    public BackfillReport backfill(Sport sport, LocalDate from, LocalDate to, int chunkDays) {
        int chunk = Math.max(1, Math.min(chunkDays, 10));
        int chunks = 0;
        int priced = 0;
        int stored = 0;
        int updated = 0;
        int unmatched = 0;

        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(chunk)) {
            LocalDate end = start.plusDays(chunk - 1L).isAfter(to) ? to : start.plusDays(chunk - 1L);
            Map<String, OddsSnapshot> odds = oddsClient.fetch(sport, start, end);
            chunks++;
            priced += odds.size();

            for (OddsSnapshot snapshot : odds.values()) {
                Long fixtureId = fixtureSources
                        .findByProviderAndSportAndExternalId(PROVIDER, sport, snapshot.externalId())
                        .map(source -> source.getFixture().getId())
                        .orElse(null);
                if (fixtureId == null) {
                    unmatched++;
                    continue;
                }
                boolean isNew = upsert(fixtureId, snapshot);
                if (isNew) {
                    stored++;
                } else {
                    updated++;
                }
            }
        }

        BackfillReport report = new BackfillReport(sport.name(), from + ".." + to,
                chunks, priced, stored, updated, unmatched);
        log.info("odds backfill: {}", report);
        return report;
    }

    private boolean upsert(Long fixtureId, OddsSnapshot snapshot) {
        var existing = marketOdds.findByFixtureIdAndProvider(fixtureId, PROVIDER);
        MarketOdds row = existing.orElseGet(MarketOdds::new);
        if (existing.isEmpty()) {
            // A reference proxy is enough - the row only needs the foreign key.
            row.setFixture(fixtures.getReferenceById(fixtureId));
        }
        row.setProvider(PROVIDER);
        row.setMedianHome(snapshot.medianHome());
        row.setMedianDraw(snapshot.medianDraw());
        row.setMedianAway(snapshot.medianAway());
        row.setBestHome(snapshot.bestHome());
        row.setBestDraw(snapshot.bestDraw());
        row.setBestAway(snapshot.bestAway());
        row.setBookmakers(snapshot.bookmakers());
        row.setFetchedAt(Instant.now());
        marketOdds.save(row);
        return existing.isEmpty();
    }
}

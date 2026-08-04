package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.Sport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Pulls historical market prices into {@code market_odds}.
 *
 * <p>Requests are chunked: the provider returns 500 for a wide range (a 61-day window across
 * a dozen leagues is more than it will build), while a few days at a time works. Prices join
 * to fixtures by the provider's own match id, so no name matching is involved.
 *
 * <p>Each chunk is fetched outside any transaction and committed on its own, so the table
 * fills visibly as it runs and no database connection is held across the network calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OddsBackfillService {

    /** Log this often rather than every chunk, so a year's backfill stays readable. */
    private static final int LOG_EVERY = 10;

    private final AllSportsOddsClient oddsClient;
    private final MarketOddsWriter writer;

    /** Live progress, surfaced by /api/admin/job-status while a backfill runs. */
    @Getter
    private volatile String progress = "no backfill run yet";

    public record BackfillReport(String sport, String window, int chunks, int pricedMatches,
                                int stored, int updated, int unmatched, long seconds) {
    }

    public BackfillReport backfill(Sport sport, LocalDate from, LocalDate to, int chunkDays) {
        int chunk = Math.max(1, Math.min(chunkDays, 10));
        long totalChunks = (to.toEpochDay() - from.toEpochDay()) / chunk + 1;
        Instant startedAt = Instant.now();

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

            if (!odds.isEmpty()) {
                MarketOddsWriter.ChunkResult result = writer.store(sport, odds);
                stored += result.stored();
                updated += result.updated();
                unmatched += result.unmatched();
            }

            progress = "%s backfill chunk %d/%d (%s), %d stored, %d unmatched so far"
                    .formatted(sport, chunks, totalChunks, end, stored, unmatched);
            if (chunks % LOG_EVERY == 0 || chunks == totalChunks) {
                log.info("odds backfill: {}", progress);
            }
        }

        BackfillReport report = new BackfillReport(sport.name(), from + ".." + to,
                chunks, priced, stored, updated, unmatched,
                Duration.between(startedAt, Instant.now()).toSeconds());
        progress = "done: " + report;
        log.info("odds backfill finished: {}", report);
        return report;
    }
}

package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.IngestRun;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.repo.IngestRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs every enabled provider x sport combination in parallel (virtual threads), each
 * throttled by its own rate limiter, then merges the results sequentially.
 *
 * <p>Parallelism is what buys throughput here: with two providers on separate quotas the
 * effective records-per-minute is the sum of both budgets, not the slower one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestService {

    private final List<SportsProvider> providers;
    private final FixtureUpsertService fixtureUpsert;
    private final FightUpsertService fightUpsert;
    private final IngestRunRepository runs;

    public record JobResult(String provider, Sport sport, int requests, int records,
                            int created, int updated, String error) {
    }

    public record IngestSummary(LocalDate from, LocalDate to, List<JobResult> jobs) {

        public int totalRecords() {
            return jobs.stream().mapToInt(JobResult::records).sum();
        }

        public int totalRequests() {
            return jobs.stream().mapToInt(JobResult::requests).sum();
        }
    }

    public IngestSummary ingestRange(LocalDate from, LocalDate to, Set<Sport> sports) {
        return ingestRange(from, to, sports, Map.of());
    }

    /**
     * MMA history by season rather than by date. The per-date path costs one request per day
     * against a 100/day cap; a season costs a few pages, which is the difference between
     * eighteen days of backfill and a couple of minutes.
     */
    public List<JobResult> ingestMmaSeasons(List<Integer> seasons) {
        ApiSportsProvider apiSports = providers.stream()
                .filter(ApiSportsProvider.class::isInstance)
                .map(ApiSportsProvider.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("api-sports provider is not configured"));
        if (!apiSports.enabled()) {
            throw new IllegalStateException("api-sports is disabled or missing its API key");
        }

        List<JobResult> results = new ArrayList<>();
        for (Integer season : seasons) {
            SportsProvider.Batch<RawFight> batch = apiSports.fetchFightsBySeason(season);
            FixtureUpsertService.UpsertResult upserted = fightUpsert.upsertAll(batch.items());

            IngestRun run = new IngestRun(apiSports.name(), Sport.MMA,
                    LocalDate.of(season, 1, 1), LocalDate.of(season, 12, 31));
            run.setRequests(batch.requests());
            run.setRecords(batch.items().size());
            run.setCreated(upserted.created());
            run.setUpdated(upserted.updated());
            run.setError(batch.error());
            run.setFinishedAt(Instant.now());
            runs.save(run);

            results.add(new JobResult(apiSports.name(), Sport.MMA, batch.requests(),
                    batch.items().size(), upserted.created(), upserted.updated(), batch.error()));
        }
        return results;
    }

    /**
     * @param allowedProviders per-sport whitelist of provider names; a sport that is absent
     *                         or maps to an empty list may use every provider. Used by the
     *                         history backfill to keep the metered provider for MMA only.
     */
    public IngestSummary ingestRange(LocalDate from, LocalDate to, Set<Sport> sports,
                                     Map<Sport, List<String>> allowedProviders) {
        Set<Sport> wanted = sports == null || sports.isEmpty() ? EnumSet.allOf(Sport.class) : sports;
        Map<Sport, List<String>> whitelist = allowedProviders == null ? Map.of() : allowedProviders;
        record Job(SportsProvider provider, Sport sport) {
        }

        List<Job> jobs = new ArrayList<>();
        for (SportsProvider p : providers) {
            if (!p.enabled()) {
                log.debug("provider {} disabled or missing API key - skipped", p.name());
                continue;
            }
            for (Sport s : wanted) {
                if (!p.supportedSports().contains(s)) {
                    continue;
                }
                List<String> allowed = whitelist.get(s);
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(p.name())) {
                    log.debug("provider {} not routed for {} on this run", p.name(), s);
                    continue;
                }
                jobs.add(new Job(p, s));
            }
        }
        if (jobs.isEmpty()) {
            return new IngestSummary(from, to, List.of());
        }

        // Phase 1: fetch everything concurrently.
        record Fetched(Job job, SportsProvider.Batch<RawFixture> fixtures, SportsProvider.Batch<RawFight> fights) {
        }
        List<Fetched> fetched = new ArrayList<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Fetched>> futures = jobs.stream()
                    .map(job -> exec.submit(() -> {
                        if (job.sport() == Sport.MMA) {
                            return new Fetched(job, SportsProvider.Batch.empty(),
                                    job.provider().fetchFights(from, to));
                        }
                        return new Fetched(job, job.provider().fetchFixtures(job.sport(), from, to),
                                SportsProvider.Batch.empty());
                    }))
                    .toList();
            for (Future<Fetched> f : futures) {
                try {
                    fetched.add(f.get());
                } catch (Exception e) {
                    log.warn("fetch job failed: {}", e.toString());
                }
            }
        }

        // Phase 2: merge serially so concurrent providers cannot duplicate a match.
        List<JobResult> results = new ArrayList<>();
        for (Fetched f : fetched) {
            IngestRun run = new IngestRun(f.job().provider().name(), f.job().sport(), from, to);
            int requests = f.fixtures().requests() + f.fights().requests();
            String error = f.fixtures().error() != null ? f.fixtures().error() : f.fights().error();

            FixtureUpsertService.UpsertResult res;
            int records;
            if (f.job().sport() == Sport.MMA) {
                records = f.fights().items().size();
                res = fightUpsert.upsertAll(f.fights().items());
            } else {
                records = f.fixtures().items().size();
                res = fixtureUpsert.upsertAll(f.fixtures().items());
            }

            run.setRequests(requests);
            run.setRecords(records);
            run.setCreated(res.created());
            run.setUpdated(res.updated());
            run.setError(error);
            run.setFinishedAt(Instant.now());
            runs.save(run);

            results.add(new JobResult(f.job().provider().name(), f.job().sport(),
                    requests, records, res.created(), res.updated(), error));
        }

        log.info("ingest {}..{}: {} records, {} requests", from, to,
                results.stream().mapToInt(JobResult::records).sum(),
                results.stream().mapToInt(JobResult::requests).sum());
        return new IngestSummary(from, to, results);
    }
}

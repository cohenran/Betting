package il.co.sportpredict.ingest;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.IngestCursor;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.model.LearningService;
import il.co.sportpredict.model.backtest.BacktestService;
import il.co.sportpredict.repo.IngestCursorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Three timers:
 * <ul>
 *   <li>recent - results and new draws, then incremental learning;</li>
 *   <li>history - one chunk further back per night, until history-days is covered;</li>
 *   <li>retrain - chronological replay plus a Dixon-Coles refit.</li>
 * </ul>
 * The history walk moves <em>backwards</em>, which is exactly why the nightly replay
 * exists: Elo is only meaningful when results are applied in order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IngestScheduler {

    /** History is pulled for all providers at once, so one cursor per sport is enough. */
    private static final String CURSOR_PROVIDER = "all";

    private final IngestService ingest;
    private final LearningService learning;
    private final BacktestService backtest;
    private final IngestCursorRepository cursors;
    private final SportPredictProperties props;

    @Scheduled(cron = "${sportpredict.ingest.recent-cron}", zone = "Asia/Jerusalem")
    public void ingestRecent() {
        LocalDate from = LocalDate.now().minusDays(2);
        Map<String, Integer> horizons = props.getIngest().getLookaheadByProvider();
        try {
            if (horizons.isEmpty()) {
                ingest.ingestRange(from, LocalDate.now().plusDays(props.getIngest().getLookaheadDays()),
                        EnumSet.allOf(Sport.class));
            } else {
                // One call per provider so each gets a horizon matched to its request cost:
                // api-sports pays per day of range, allsports pays once for the whole range.
                horizons.forEach((provider, days) -> {
                    Set<Sport> sports = sportsFor(provider, props.getIngest().getRecentProviders());
                    if (sports.isEmpty()) {
                        return;
                    }
                    LocalDate to = LocalDate.now().plusDays(days);
                    ingest.ingestRange(from, to, sports, onlyProvider(provider));
                });
            }
            learning.processNewResults();
        } catch (Exception e) {
            log.error("recent ingest failed", e);
        }
    }

    /** Sports this provider is routed to serve. Absent or empty routing means all of them. */
    private Set<Sport> sportsFor(String provider, Map<Sport, List<String>> routing) {
        Set<Sport> out = EnumSet.noneOf(Sport.class);
        for (Sport sport : Sport.values()) {
            List<String> allowed = routing.get(sport);
            if (allowed == null || allowed.isEmpty() || allowed.contains(provider)) {
                out.add(sport);
            }
        }
        return out;
    }

    /** Whitelist restricting every sport to a single provider. */
    private Map<Sport, List<String>> onlyProvider(String provider) {
        Map<Sport, List<String>> map = new EnumMap<>(Sport.class);
        for (Sport sport : Sport.values()) {
            map.put(sport, List.of(provider));
        }
        return map;
    }

    @Scheduled(cron = "${sportpredict.ingest.history-cron}", zone = "Asia/Jerusalem")
    public void ingestHistoryChunk() {
        LocalDate limit = LocalDate.now().minusDays(props.getIngest().getHistoryDays());
        for (Sport sport : Sport.values()) {
            IngestCursor cursor = cursors.findByProviderAndSport(CURSOR_PROVIDER, sport)
                    .orElseGet(() -> cursors.save(new IngestCursor(CURSOR_PROVIDER, sport)));
            LocalDate to = cursor.getOldestPulled() == null
                    ? LocalDate.now().minusDays(1)
                    : cursor.getOldestPulled().minusDays(1);
            if (to.isBefore(limit)) {
                log.debug("history backfill for {} already reached {}", sport, limit);
                continue;
            }
            LocalDate from = to.minusDays(props.getIngest().getChunkDays());
            try {
                IngestService.IngestSummary summary = ingest.ingestRange(from, to, EnumSet.of(sport),
                        props.getIngest().getHistoryProviders());
                
                // Advance only when at least one provider actually completed. This also
                // covers the case where routing left no eligible provider at all - an empty
                // job list must not look like success, or the chunk is lost forever.
                boolean anySuccess = summary.jobs().stream().anyMatch(j -> j.error() == null);

                if (!anySuccess) {
                    log.warn("no provider completed {} {}..{} ({} jobs) - holding cursor for the next run",
                            sport, from, to, summary.jobs().size());
                    continue;
                }

                cursor.setOldestPulled(from);
                cursor.setUpdatedAt(java.time.Instant.now());
                cursors.save(cursor);
            } catch (Exception e) {
                log.error("history ingest failed for {} {}..{}", sport, from, to, e);
            }
        }
    }

    @Scheduled(cron = "${sportpredict.model.retrain-cron}", zone = "Asia/Jerusalem")
    public void retrain() {
        try {
            LearningService.RefitReport report = learning.rebuildAndRefit();
            log.info("nightly retrain: {}", report);
        } catch (Exception e) {
            log.error("retrain failed", e);
            return;
        }
        if (!props.getModel().isBacktestAfterRetrain()) {
            return;
        }
        try {
            // Stored so the paper-bet gate can check "does this model beat the base rates"
            // without re-running dozens of refits every morning.
            backtest.runAndStore(props.getModel().getBacktestHistoryDays(),
                    props.getModel().getBacktestStepDays(),
                    props.getModel().getBacktestTrainFraction());
        } catch (Exception e) {
            // Too little history yet is the normal case early on, not an error worth alarm.
            log.warn("nightly backtest skipped: {}", e.getMessage());
        }
    }
}

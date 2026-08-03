package il.co.sportpredict.ingest;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.IngestCursor;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.model.LearningService;
import il.co.sportpredict.repo.IngestCursorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumSet;

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
    private final IngestCursorRepository cursors;
    private final SportPredictProperties props;

    @Scheduled(cron = "${sportpredict.ingest.recent-cron}", zone = "Asia/Jerusalem")
    public void ingestRecent() {
        LocalDate from = LocalDate.now().minusDays(2);
        LocalDate to = LocalDate.now().plusDays(props.getIngest().getLookaheadDays());
        try {
            ingest.ingestRange(from, to, EnumSet.allOf(Sport.class));
            learning.processNewResults();
        } catch (Exception e) {
            log.error("recent ingest failed", e);
        }
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
                IngestService.IngestSummary summary = ingest.ingestRange(from, to, EnumSet.of(sport));
                
                boolean allFailed = !summary.jobs().isEmpty() && summary.jobs().stream()
                        .allMatch(j -> j.error() != null);
                
                if (allFailed) {
                    log.warn("All providers failed for {} {}..{}. Pausing history backfill for this sport.", sport, from, to);
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
        }
    }
}

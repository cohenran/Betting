package il.co.sportpredict.model;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.model.backtest.BacktestService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Runs the two long model jobs - full rebuild and walk-forward backtest - off the request
 * thread, and never at the same time.
 *
 * <p>Both replay the entire fixture history, so each takes minutes on real data: far too
 * long to hold an HTTP request open. They also share one lock, because running them
 * concurrently doubles peak memory for no benefit, and a rebuild resets the ratings a
 * backtest would otherwise be reading past.
 *
 * <p>Kept out of {@link LearningService} deliberately: calling a {@code @Transactional}
 * method from inside its own bean bypasses the proxy, so the work would run with no
 * transaction at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelJobs {

    private final LearningService learning;
    private final BacktestService backtest;
    private final SportPredictProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Getter
    private volatile String status = "not run yet";

    @Getter
    private volatile String currentJob = "none";

    /** Returns immediately: "started", or "busy: <job>" when one is already in flight. */
    public String startRetrain() {
        return start("retrain", () -> learning.rebuildAndRefit());
    }

    public String startBacktest(Integer historyDays, Integer stepDays, Double trainFraction) {
        return start("backtest", () -> backtest.runAndStore(
                historyDays != null ? historyDays : props.getModel().getBacktestHistoryDays(),
                stepDays != null ? stepDays : props.getModel().getBacktestStepDays(),
                trainFraction != null ? trainFraction : props.getModel().getBacktestTrainFraction()));
    }

    public boolean isRunning() {
        return running.get();
    }

    private String start(String job, Supplier<Object> work) {
        if (!running.compareAndSet(false, true)) {
            return "busy: " + currentJob + " is already running";
        }
        currentJob = job;
        Instant startedAt = Instant.now();
        status = job + " running since " + startedAt;
        Thread.startVirtualThread(() -> {
            try {
                Object result = work.get();
                status = "%s done in %ds: %s".formatted(job, elapsed(startedAt), result);
                log.info("{}", status);
            } catch (Exception e) {
                // Too little history is the normal early state, not something to alarm on.
                status = "%s failed after %ds: %s".formatted(job, elapsed(startedAt), e.getMessage());
                log.warn("{}", status, e);
            } finally {
                currentJob = "none";
                running.set(false);
            }
        });
        return "started";
    }

    private long elapsed(Instant from) {
        return Duration.between(from, Instant.now()).toSeconds();
    }
}

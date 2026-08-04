package il.co.sportpredict.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a full rebuild off the request thread.
 *
 * <p>A rebuild replays every finished fixture in one transaction, which takes minutes once
 * there is real history - far too long to hold an HTTP request open. Only one may run at a
 * time: two concurrent replays would both reset the ratings and interleave their updates.
 *
 * <p>Separate from {@link LearningService} on purpose: calling a {@code @Transactional}
 * method from inside its own bean bypasses the proxy, so the rebuild would run with no
 * transaction at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RetrainRunner {

    private final LearningService learning;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Getter
    private volatile String status = "not run yet";

    /** Returns immediately. Poll {@link #getStatus()} for progress. */
    public String start() {
        if (!running.compareAndSet(false, true)) {
            return "already running";
        }
        Instant startedAt = Instant.now();
        status = "running since " + startedAt;
        Thread.startVirtualThread(() -> {
            try {
                LearningService.RefitReport report = learning.rebuildAndRefit();
                status = "done in %ds: %s".formatted(
                        Duration.between(startedAt, Instant.now()).toSeconds(), report);
                log.info("retrain {}", status);
            } catch (Exception e) {
                status = "failed after %ds: %s".formatted(
                        Duration.between(startedAt, Instant.now()).toSeconds(), e);
                log.error("retrain failed", e);
            } finally {
                running.set(false);
            }
        });
        return "started";
    }

    public boolean isRunning() {
        return running.get();
    }
}

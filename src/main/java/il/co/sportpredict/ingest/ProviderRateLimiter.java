package il.co.sportpredict.ingest;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;

/**
 * Token bucket with an additional daily cap - the two limits the free tiers of
 * api-sports.io and allsportsapi.com actually enforce.
 *
 * <p>One instance per provider, so both providers really do run in parallel:
 * each blocks only on its own budget.
 */
@Slf4j
public class ProviderRateLimiter {

    private final String provider;
    private final double tokensPerMs;
    private final double burst;
    private final int dailyLimit;

    private double tokens;
    private long lastRefill = System.nanoTime();
    private LocalDate day = LocalDate.now();
    private int usedToday = 0;

    public ProviderRateLimiter(String provider, int requestsPerMinute, int dailyLimit) {
        this.provider = provider;
        // Add a 10% safety margin so we don't accidentally slip an extra request 
        // into the strict 60-second sliding window due to millisecond rounding.
        double safeRpm = requestsPerMinute > 1 ? requestsPerMinute * 0.9 : 1.0;
        this.tokensPerMs = safeRpm / 60_000.0;
        this.burst = 1.0;
        this.tokens = 1.0;
        this.dailyLimit = dailyLimit <= 0 ? Integer.MAX_VALUE : dailyLimit;
    }

    /** Blocks until a request slot is free. */
    public void acquire() throws InterruptedException, DailyLimitReachedException {
        while (true) {
            long sleepMs;
            synchronized (this) {
                refill();
                if (usedToday >= dailyLimit) {
                    throw new DailyLimitReachedException(provider + " daily limit reached (" + dailyLimit + ")");
                }
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    usedToday++;
                    return;
                }
                sleepMs = (long) Math.ceil((1.0 - tokens) / tokensPerMs);
            }
            Thread.sleep(Math.max(50, Math.min(sleepMs, 5_000)));
        }
    }

    public synchronized int remainingToday() {
        refill();
        return dailyLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, dailyLimit - usedToday);
    }

    private void refill() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
        long now = System.nanoTime();
        double elapsedMs = (now - lastRefill) / 1_000_000.0;
        lastRefill = now;
        tokens = Math.min(burst, tokens + elapsedMs * tokensPerMs);
    }

    /** Thrown when the provider's daily quota is gone - the run stops instead of hammering. */
    public static class DailyLimitReachedException extends Exception {
        public DailyLimitReachedException(String message) {
            super(message);
        }
    }
}

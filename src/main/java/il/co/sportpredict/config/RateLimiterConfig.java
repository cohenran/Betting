package il.co.sportpredict.config;

import il.co.sportpredict.ingest.ProviderRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One limiter per provider, shared by every client that spends that provider's quota.
 *
 * <p>Previously each client built its own limiter, so two clients on the same key each
 * believed they had the full budget - the daily cap would be breached at twice the
 * configured rate without either one noticing.
 */
@Configuration
public class RateLimiterConfig {

    @Bean("apiSportsLimiter")
    ProviderRateLimiter apiSportsLimiter(SportPredictProperties props) {
        SportPredictProperties.ApiSports cfg = props.getProviders().getApiSports();
        return new ProviderRateLimiter("api-sports", cfg.getRequestsPerMinute(), cfg.getDailyLimit());
    }

    @Bean("allsportsLimiter")
    ProviderRateLimiter allsportsLimiter(SportPredictProperties props) {
        SportPredictProperties.AllSports cfg = props.getProviders().getAllsports();
        return new ProviderRateLimiter("allsports", cfg.getRequestsPerMinute(), cfg.getDailyLimit());
    }
}

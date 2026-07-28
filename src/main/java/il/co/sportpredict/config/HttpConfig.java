package il.co.sportpredict.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class HttpConfig {

    /** Shared client for both sports data providers. Full URLs are passed per request. */
    @Bean
    RestClient sportsRestClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Kept separate so a slow or blocked betting site cannot stall data ingest. */
    @Bean
    RestClient scraperRestClient(SportPredictProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(props.getWinner().getTimeoutSeconds()));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.getWinner().getUserAgent())
                .defaultHeader("Accept-Language", "he-IL,he;q=0.9,en;q=0.8")
                .build();
    }
}

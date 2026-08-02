package il.co.sportpredict;

import il.co.sportpredict.config.SportPredictProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SportPredictProperties.class)
public class SportPredictApplication {

    public static void main(String[] args) {
        SpringApplication.run(SportPredictApplication.class, args);
    }
}

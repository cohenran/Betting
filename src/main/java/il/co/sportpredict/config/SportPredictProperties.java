package il.co.sportpredict.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "sportpredict")
public class SportPredictProperties {

    private String adminToken = "change-me";
    private Providers providers = new Providers();
    private Ingest ingest = new Ingest();
    private Model model = new Model();
    private Winner winner = new Winner();

    @Data
    public static class Providers {
        private ApiSports apiSports = new ApiSports();
        private AllSports allsports = new AllSports();
    }

    @Data
    public static class ApiSports {
        private boolean enabled = true;
        private String key = "";
        private int requestsPerMinute = 10;
        private int dailyLimit = 100;
        private String footballBaseUrl = "https://v3.football.api-sports.io";
        private String basketballBaseUrl = "https://v1.basketball.api-sports.io";
        private String mmaBaseUrl = "https://v1.mma.api-sports.io";
    }

    @Data
    public static class AllSports {
        private boolean enabled = true;
        private String key = "";
        private int requestsPerMinute = 30;
        private int dailyLimit = 1000;
        private String baseUrl = "https://apiv2.allsportsapi.com";
    }

    @Data
    public static class Ingest {
        private List<Integer> footballLeagues = new ArrayList<>();
        private List<Integer> allsportsFootballLeagues = new ArrayList<>();
        private List<Integer> basketballLeagues = new ArrayList<>();
        private List<Integer> seasons = new ArrayList<>();
        private int historyDays = 730;
        private int lookaheadDays = 14;
        private int chunkDays = 10;
        private String recentCron = "0 */15 * * * *";
        private String historyCron = "0 20 3 * * *";
    }

    @Data
    public static class Model {
        private int minMatchesForFit = 120;
        private String retrainCron = "0 0 4 * * *";
        private Football football = new Football();
        private Elo elo = new Elo();
        private Basketball basketball = new Basketball();
        private Ufc ufc = new Ufc();
    }

    @Data
    public static class Football {
        private double halfLifeDays = 240;
        private int iterations = 500;
        private double learningRate = 0.015;
        private double l2 = 0.02;
        private int maxGoals = 10;
        private double rhoMin = -0.20;
        private double rhoMax = 0.10;
        private int rhoSteps = 31;
        private double ouLine = 2.5;
    }

    @Data
    public static class Elo {
        private double kFootball = 20.0;
        private double kBasketball = 20.0;
        private double kUfc = 24.0;
        private double homeAdvantage = 65.0;
        private double initial = 1500.0;
    }

    @Data
    public static class Basketball {
        private double halfLifeDays = 180;
        private double marginSd = 11.5;
        private double eloPerPoint = 28.0;
        private double homeAdvantagePoints = 2.8;
    }

    @Data
    public static class Ufc {
        private double learningRate = 0.03;
        private double l2 = 0.001;
        private int epochsOnRefit = 8;
    }

    @Data
    public static class Winner {
        private boolean enabled = true;
        private boolean playwrightEnabled = true;
        private int timeoutSeconds = 45;
        private String userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
        private List<String> jsonEndpoints = new ArrayList<>();
        private double matchThreshold = 0.86;
        private int kickoffToleranceHours = 30;
    }
}

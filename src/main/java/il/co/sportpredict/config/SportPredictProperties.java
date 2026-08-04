package il.co.sportpredict.config;

import il.co.sportpredict.domain.Sport;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "sportpredict")
public class SportPredictProperties {

    private String adminToken = "change-me";
    private Providers providers = new Providers();
    private Ingest ingest = new Ingest();
    private Model model = new Model();
    private Winner winner = new Winner();
    private PaperBetting paperBetting = new PaperBetting();

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

        /**
         * Which providers may serve the history backfill, per sport. api-sports queries
         * day-by-day (10-day chunk = 10 requests) against a 100/day free cap, while
         * allsports takes a whole range in one request. Letting football and basketball
         * history touch api-sports burns the quota that MMA is the only consumer of.
         * Empty list or missing sport = all providers allowed.
         */
        private Map<Sport, List<String>> historyProviders = new EnumMap<>(Sport.class);

        /**
         * How far ahead each provider looks on the "recent" run. api-sports costs one
         * request per day of range, so it stays short; allsports covers the full betting
         * horizon for one request. Missing provider falls back to lookaheadDays.
         */
        private Map<String, Integer> lookaheadByProvider = new LinkedHashMap<>();
    }

    @Data
    public static class Model {
        private int minMatchesForFit = 120;
        private String retrainCron = "0 0 4 * * *";
        /** Walk-forward evaluation run after each nightly refit, and stored for gating. */
        private boolean backtestAfterRetrain = true;
        private int backtestHistoryDays = 540;
        private int backtestStepDays = 7;
        private double backtestTrainFraction = 0.6;
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
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
        private List<String> jsonEndpoints = new ArrayList<>();
        private double matchThreshold = 0.86;
        private int kickoffToleranceHours = 30;
    }

    @Data
    public static class PaperBetting {
        private boolean enabled = true;
        private String winnerUrl = "https://www.winner.co.il/mainbook/sport";
        private String cron = "0 0 8 * * *"; // Morning run: resolves yesterday, places today
        private double startingBankroll = 1000.0;

        /** Where the CSV ledgers live. Relative paths depend on the working directory. */
        private String dataDir = "/opt/sportpredict/paper";

        /**
         * Flat stake for the primary arm. Flat staking is the only arm that answers
         * "does the edge exist" - Kelly answers "how fast does the bankroll grow if the
         * probabilities are true", which is a different and currently unanswerable question.
         */
        private double flatStake = 10.0;

        /**
         * Refuse to place paper bets until the Dixon-Coles fit has at least this many
         * matches behind it. Below that, predictions are Elo cold-start and the edges are
         * noise, so a month of betting them measures nothing.
         */
        private int minFitSample = 300;

        /**
         * Refuse to bet unless the stored backtest shows the model beating the baseline
         * log-loss. Without this, the dry run can spend a month betting a model that is
         * measurably worse than assuming the league's base rates.
         */
        private boolean requireBacktestEdge = true;

        /** Only bet these sports. Football first - it is the one with a real fitted model. */
        private List<Sport> sports = new ArrayList<>(List.of(Sport.FOOTBALL));

        /** Ignore edges below this: tiny edges are indistinguishable from model error. */
        private double minEdge = 0.02;
    }
}

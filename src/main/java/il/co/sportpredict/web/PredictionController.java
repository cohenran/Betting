package il.co.sportpredict.web;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.EventStatus;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.TeamRating;
import il.co.sportpredict.model.PredictionService;
import il.co.sportpredict.model.PredictionView;
import il.co.sportpredict.model.backtest.BacktestService;
import il.co.sportpredict.model.football.FootballPredictor;
import il.co.sportpredict.winner.PaperBetManager;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.PredictionRepository;
import il.co.sportpredict.repo.TeamRatingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictions;
    private final BacktestService backtest;
    private final TeamRatingRepository ratings;
    private final FixtureRepository fixtures;
    private final PredictionRepository predictionRepo;
    private final PaperBetManager paperBets;
    private final FootballPredictor football;
    private final SportPredictProperties props;

    @GetMapping("/predictions/upcoming")
    public List<PredictionView> upcoming(@RequestParam(defaultValue = "FOOTBALL") Sport sport,
                                         @RequestParam(defaultValue = "7") int days) {
        return predictions.upcoming(sport, Math.min(days, 60));
    }

    @GetMapping("/predictions/fixture/{id}")
    public PredictionView fixture(@PathVariable Long id) {
        return predictions.predictFixture(id);
    }

    @GetMapping("/predictions/fight/{id}")
    public PredictionView fight(@PathVariable Long id) {
        return predictions.predictFight(id);
    }

    @GetMapping("/ratings")
    public List<Map<String, Object>> ratings(@RequestParam(defaultValue = "FOOTBALL") Sport sport,
                                             @RequestParam(defaultValue = "40") int limit) {
        return ratings.findRanked(sport).stream()
                .limit(limit)
                .map(this::ratingRow)
                .toList();
    }

    /**
     * Whether the dry run is actually recording anything. A month of "no value found" and
     * a month of "the scraper is broken" look identical in the ledger, so check this.
     */
    @GetMapping("/paper-betting/status")
    public Map<String, Object> paperBettingStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastRun", paperBets.getLastRunStatus());
        out.put("footballFitSample", football.currentParams().getSampleSize());
        out.put("minFitSample", props.getPaperBetting().getMinFitSample());
        out.put("bettingAllowed",
                football.currentParams().getSampleSize() >= props.getPaperBetting().getMinFitSample());
        return out;
    }

    @GetMapping("/backtest/football")
    public BacktestService.BacktestResult backtestFootball(
            @RequestParam(defaultValue = "540") int historyDays,
            @RequestParam(defaultValue = "7") int stepDays,
            @RequestParam(defaultValue = "0.6") double trainFraction) {
        return backtest.runFootball(historyDays, stepDays, trainFraction);
    }

    /** Dashboard counters plus live calibration over everything already settled. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Sport sport : Sport.values()) {
            Map<String, Object> perSport = new LinkedHashMap<>();
            if (sport != Sport.MMA) {
                perSport.put("finished", fixtures.countBySportAndStatus(sport, EventStatus.FINISHED));
                perSport.put("scheduled", fixtures.countBySportAndStatus(sport, EventStatus.SCHEDULED));
            }
            perSport.putAll(calibration(sport));
            out.put(sport.name(), perSport);
        }
        return out;
    }

    private Map<String, Object> calibration(Sport sport) {
        var settled = predictionRepo.findSettled(sport);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settledPredictions", settled.size());
        if (settled.isEmpty()) {
            return out;
        }
        double logLoss = settled.stream().filter(p -> p.getLogLoss() != null)
                .mapToDouble(p -> p.getLogLoss()).average().orElse(0);
        double brier = settled.stream().filter(p -> p.getBrier() != null)
                .mapToDouble(p -> p.getBrier()).average().orElse(0);
        long hits = settled.stream().filter(p -> {
            double pHome = p.getPHome() == null ? 0 : p.getPHome();
            double pDraw = p.getPDraw() == null ? 0 : p.getPDraw();
            double pAway = p.getPAway() == null ? 0 : p.getPAway();
            String pick = pHome >= pDraw && pHome >= pAway ? "HOME" : (pAway >= pDraw ? "AWAY" : "DRAW");
            return pick.equals(p.getOutcome());
        }).count();
        out.put("logLoss", round(logLoss));
        out.put("brier", round(brier));
        out.put("hitRate", round((double) hits / settled.size()));
        return out;
    }

    private Map<String, Object> ratingRow(TeamRating r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("team", r.getTeam().getName());
        row.put("elo", Math.round(r.getElo()));
        row.put("matches", r.getMatches());
        row.put("scored", round(r.getScored()));
        row.put("conceded", round(r.getConceded()));
        return row;
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

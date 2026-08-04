package il.co.sportpredict.model.football;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import il.co.sportpredict.domain.TeamRating;
import il.co.sportpredict.model.MatchPrediction;
import il.co.sportpredict.model.ModelStateStore;
import il.co.sportpredict.model.elo.EloService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Football 1X2 / over-under / BTTS predictions from the fitted Dixon-Coles model.
 *
 * <p>A team the fit has never seen (promoted side, new competition) gets attack/defense
 * derived from its Elo instead of the league average - a cold-start bridge, flagged as
 * {@code coldStart} in the prediction detail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FootballPredictor {

    public static final String MODEL = "dixon-coles";
    public static final String STATE_KEY = "football-dixon-coles";

    /** Elo points -> attack/defense strength, used only for unseen teams. */
    private static final double ELO_TO_STRENGTH = 0.30 / 100.0;

    private final ModelStateStore store;
    private final EloService elo;
    private final SportPredictProperties props;

    private volatile DixonColesParams params = new DixonColesParams();

    @PostConstruct
    void loadState() {
        store.load(STATE_KEY, DixonColesParams.class).ifPresent(p -> {
            params = p;
            log.info("loaded Dixon-Coles state ({} matches, {} teams)", p.getSampleSize(), p.getAttack().size());
        });
    }

    public void replaceParams(DixonColesParams fitted) {
        this.params = fitted;
    }

    public DixonColesParams currentParams() {
        return params;
    }

    public MatchPrediction predict(Fixture fixture) {
        DixonColesParams p = params;
        Long homeId = fixture.getHomeTeam().getId();
        Long awayId = fixture.getAwayTeam().getId();

        String compId = fixture.getCompetition() != null ? String.valueOf(fixture.getCompetition().getId()) : "0";

        boolean coldStart = !p.knows(homeId) || !p.knows(awayId);
        double attackHome = strength(p, fixture.getHomeTeam(), true);
        double defenseHome = strength(p, fixture.getHomeTeam(), false);
        double attackAway = strength(p, fixture.getAwayTeam(), true);
        double defenseAway = strength(p, fixture.getAwayTeam(), false);

        // Built from the strength values rather than params.lambdaHome(): that method reads
        // attack/defense straight from the fit, which would discard the Elo stand-in for a
        // team the fit has never seen.
        double base = p.baseGoalsOf(compId);
        double lambda = clampGoals(base * Math.exp(attackHome - defenseAway + p.homeAdvantageOf(compId)));
        double mu = clampGoals(base * Math.exp(attackAway - defenseHome));

        double line = props.getModel().getFootball().getOuLine();
        ScoreGrid.Result grid = ScoreGrid.compute(p, lambda, mu,
                props.getModel().getFootball().getMaxGoals(), line, compId);

        List<Map<String, Object>> topScores = grid.scores().stream().limit(5)
                .map(e -> Map.<String, Object>of("score", e.getKey(), "p", round(e.getValue())))
                .toList();

        Map<String, Object> detail = MatchPrediction.newDetail();
        detail.put("lambdaHome", round(lambda));
        detail.put("lambdaAway", round(mu));
        detail.put("topScores", topScores);
        detail.put("coldStart", coldStart);
        detail.put("fitSampleSize", p.getSampleSize());
        detail.put("rho", round(p.rhoOf(compId)));
        detail.put("eloHome", round(elo.ratingFor(fixture.getHomeTeam(), Sport.FOOTBALL).getElo()));
        detail.put("eloAway", round(elo.ratingFor(fixture.getAwayTeam(), Sport.FOOTBALL).getElo()));

        return new MatchPrediction(
                MODEL,
                "s" + p.getSampleSize(),
                grid.pHome(), grid.pDraw(), grid.pAway(),
                round(lambda), round(mu),
                line, round(grid.pOver()), round(grid.pBtts()),
                grid.topScore(),
                MatchPrediction.confidenceOf(grid.pHome(), grid.pDraw(), grid.pAway()),
                detail);
    }

    /**
     * Fitted strength when the team is in the fit, otherwise an Elo-derived stand-in.
     * Both attack (goals scored) and defense (goals prevented) rise with strength, so the
     * same signed value is used for either side.
     */
    private double strength(DixonColesParams p, Team team, boolean attackSide) {
        if (p.knows(team.getId())) {
            return attackSide ? p.attackOf(team.getId()) : p.defenseOf(team.getId());
        }
        TeamRating rating = elo.ratingFor(team, Sport.FOOTBALL);
        return (rating.getElo() - props.getModel().getElo().getInitial()) * ELO_TO_STRENGTH;
    }

    private double clampGoals(double v) {
        return Math.max(0.15, Math.min(6.0, v));
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

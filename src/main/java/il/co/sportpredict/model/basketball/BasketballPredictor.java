package il.co.sportpredict.model.basketball;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.TeamRating;
import il.co.sportpredict.model.MatchPrediction;
import il.co.sportpredict.model.elo.EloService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Basketball: Elo difference -> expected margin -> normal distribution over the margin.
 * Totals come from each side's exponentially weighted points scored/conceded.
 *
 * <p>Basketball has no draws in practice, so pDraw is 0 and the model is a straight
 * two-way probability plus a spread and a total.
 */
@Component
@RequiredArgsConstructor
public class BasketballPredictor {

    public static final String MODEL = "elo-normal";

    private static final NormalDistribution STANDARD = new NormalDistribution(0, 1);
    /** Spread of realised totals around the expected total, in points. */
    private static final double TOTAL_SD = 15.0;

    private final EloService elo;
    private final SportPredictProperties props;

    public MatchPrediction predict(Fixture fixture) {
        SportPredictProperties.Basketball cfg = props.getModel().getBasketball();
        TeamRating home = elo.ratingFor(fixture.getHomeTeam(), Sport.BASKETBALL);
        TeamRating away = elo.ratingFor(fixture.getAwayTeam(), Sport.BASKETBALL);

        double eloDiff = home.getElo() - away.getElo();
        double expectedMargin = eloDiff / cfg.getEloPerPoint() + cfg.getHomeAdvantagePoints();

        double pHome = STANDARD.cumulativeProbability(expectedMargin / cfg.getMarginSd());
        pHome = Math.min(0.995, Math.max(0.005, pHome));
        double pAway = 1 - pHome;

        double expectedTotal = expectedTotal(home, away);
        double homePoints = (expectedTotal + expectedMargin) / 2.0;
        double awayPoints = (expectedTotal - expectedMargin) / 2.0;

        // Line is the expected total rounded to the nearest half point, as books quote it.
        double line = Math.round(expectedTotal * 2) / 2.0;
        double pOver = 1 - STANDARD.cumulativeProbability((line - expectedTotal) / TOTAL_SD);

        Map<String, Object> detail = MatchPrediction.newDetail();
        detail.put("eloHome", round(home.getElo()));
        detail.put("eloAway", round(away.getElo()));
        detail.put("expectedMargin", round(expectedMargin));
        detail.put("expectedTotal", round(expectedTotal));
        detail.put("gamesHome", home.getMatches());
        detail.put("gamesAway", away.getMatches());
        detail.put("spread", round(-expectedMargin));

        return new MatchPrediction(
                MODEL,
                "elo",
                pHome, 0.0, pAway,
                round(homePoints), round(awayPoints),
                line, round(pOver), null,
                Math.round(homePoints) + "-" + Math.round(awayPoints),
                MatchPrediction.confidenceOf(pHome, 0.0, pAway),
                detail);
    }

    /** Average of what one side scores and the other concedes, both directions. */
    private double expectedTotal(TeamRating home, TeamRating away) {
        double fallback = 160.0;
        if (home.getMatches() == 0 || away.getMatches() == 0) {
            return fallback;
        }
        double homeSide = (home.getScored() + away.getConceded()) / 2.0;
        double awaySide = (away.getScored() + home.getConceded()) / 2.0;
        double total = homeSide + awaySide;
        return total < 80 ? fallback : total;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

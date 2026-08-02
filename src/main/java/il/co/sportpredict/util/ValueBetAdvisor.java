package il.co.sportpredict.util;

import il.co.sportpredict.domain.Sport;

import java.util.Optional;

/**
 * Utility class for identifying Value Bets and calculating stake sizing
 * using the Kelly Criterion.
 */
public class ValueBetAdvisor {

    public record BetRecommendation(
            String sport,
            String matchName,
            String recommendedSelection, // "HOME", "DRAW", "AWAY", or "NONE"
            double expectedValue,        // e.g. 0.05 means 5% edge
            double winProbability,       // The model's calculated probability
            double offeredOdds,          // The bookmaker's odds
            double kellyFraction,        // Fraction of bankroll to wager (e.g. 0.02 = 2%)
            double recommendedStakeAmount// Actual currency amount to bet
    ) {}

    /**
     * Analyzes a 3-way line (1X2) typically used in Football.
     */
    public static BetRecommendation analyze3Way(
            Sport sport,
            String homeTeam, String awayTeam,
            double pHome, double pDraw, double pAway,
            double oddsHome, double oddsDraw, double oddsAway,
            double currentBankroll,
            double kellyMultiplier) { // multiplier allows playing "Half-Kelly" to reduce variance (e.g. 0.5)

        String matchName = homeTeam + " vs " + awayTeam;
        
        // Calculate Expected Value (Edge) for each outcome: EV = (Probability * Odds) - 1
        double evHome = (pHome * oddsHome) - 1;
        double evDraw = (pDraw * oddsDraw) - 1;
        double evAway = (pAway * oddsAway) - 1;

        // Find the best option (the one with the highest EV)
        double bestEv = Math.max(evHome, Math.max(evDraw, evAway));
        
        // If there's no positive edge, don't bet.
        if (bestEv <= 0) {
            return new BetRecommendation(sport.name(), matchName, "NONE", bestEv, 0, 0, 0, 0);
        }

        String selection = "";
        double probability = 0;
        double odds = 0;

        if (bestEv == evHome) {
            selection = homeTeam + " (HOME)";
            probability = pHome;
            odds = oddsHome;
        } else if (bestEv == evDraw) {
            selection = "DRAW";
            probability = pDraw;
            odds = oddsDraw;
        } else {
            selection = awayTeam + " (AWAY)";
            probability = pAway;
            odds = oddsAway;
        }

        return buildRecommendation(sport, matchName, selection, bestEv, probability, odds, currentBankroll, kellyMultiplier);
    }

    /**
     * Analyzes a 2-way line (Moneyline) typically used in Basketball or MMA.
     */
    public static BetRecommendation analyze2Way(
            Sport sport,
            String teamA, String teamB,
            double pA, double pB,
            double oddsA, double oddsB,
            double currentBankroll,
            double kellyMultiplier) {

        String matchName = teamA + " vs " + teamB;
        
        double evA = (pA * oddsA) - 1;
        double evB = (pB * oddsB) - 1;

        double bestEv = Math.max(evA, evB);

        if (bestEv <= 0) {
            return new BetRecommendation(sport.name(), matchName, "NONE", bestEv, 0, 0, 0, 0);
        }

        String selection = bestEv == evA ? teamA : teamB;
        double probability = bestEv == evA ? pA : pB;
        double odds = bestEv == evA ? oddsA : oddsB;

        return buildRecommendation(sport, matchName, selection, bestEv, probability, odds, currentBankroll, kellyMultiplier);
    }

    private static BetRecommendation buildRecommendation(
            Sport sport, String matchName, String selection, double expectedValue,
            double probability, double odds, double currentBankroll, double kellyMultiplier) {

        // Kelly Criterion formula: f* = (p * (b) - q) / (b)
        // Where b = net decimal odds (odds - 1)
        // p = probability of win
        // q = probability of loss (1 - p)
        double b = odds - 1.0;
        double q = 1.0 - probability;
        
        double fullKellyFraction = (probability * b - q) / b;
        
        // Guard against negative fractions (though EV > 0 check already prevents this)
        if (fullKellyFraction < 0) fullKellyFraction = 0;

        // Apply multiplier (e.g. 0.5 for Half-Kelly, recommended to reduce volatility)
        double finalFraction = fullKellyFraction * kellyMultiplier;
        
        // Calculate recommended stake
        double stakeAmount = currentBankroll * finalFraction;

        return new BetRecommendation(
                sport.name(),
                matchName,
                selection,
                expectedValue,
                probability,
                odds,
                finalFraction,
                stakeAmount
        );
    }
}

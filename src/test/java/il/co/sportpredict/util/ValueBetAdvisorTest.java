package il.co.sportpredict.util;

import il.co.sportpredict.domain.Sport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ValueBetAdvisorTest {

    @Test
    void picksHighestEdgeNotHighestProbability() {
        // Home is the likely winner (55%) but priced fairly; the draw is the value.
        ValueBetAdvisor.BetRecommendation advice = ValueBetAdvisor.analyze3Way(
                Sport.FOOTBALL, "Maccabi Haifa", "Hapoel Beer Sheva",
                0.55, 0.25, 0.20,
                1.80, 4.60, 5.00,
                1000, 0.5);

        assertThat(advice.recommendedSelection()).isEqualTo("DRAW");
        // 0.25 * 4.60 - 1 = 0.15
        assertThat(advice.expectedValue()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void refusesToBetWithoutPositiveEdge() {
        ValueBetAdvisor.BetRecommendation advice = ValueBetAdvisor.analyze3Way(
                Sport.FOOTBALL, "A", "B",
                0.50, 0.27, 0.23,
                1.90, 3.30, 3.80,
                1000, 0.5);

        assertThat(advice.recommendedSelection()).isEqualTo("NONE");
        assertThat(advice.expectedValue()).isNegative();
        assertThat(advice.recommendedStakeAmount()).isZero();
    }

    @Test
    void fullKellyStakesFarMoreThanHalf() {
        // p=0.60 at odds 2.00 -> f* = (0.6*1 - 0.4)/1 = 0.20, i.e. 20% of bankroll.
        ValueBetAdvisor.BetRecommendation full = ValueBetAdvisor.analyze3Way(
                Sport.FOOTBALL, "A", "B", 0.60, 0.25, 0.15,
                2.00, 3.30, 6.00, 1000, 1.0);
        ValueBetAdvisor.BetRecommendation half = ValueBetAdvisor.analyze3Way(
                Sport.FOOTBALL, "A", "B", 0.60, 0.25, 0.15,
                2.00, 3.30, 6.00, 1000, 0.5);

        assertThat(full.kellyFraction()).isCloseTo(0.20, within(1e-9));
        assertThat(full.recommendedStakeAmount()).isCloseTo(200.0, within(1e-6));
        assertThat(half.recommendedStakeAmount()).isCloseTo(100.0, within(1e-6));
    }

    @Test
    void missingOddsNeverProduceABet() {
        ValueBetAdvisor.BetRecommendation advice = ValueBetAdvisor.analyze3Way(
                Sport.FOOTBALL, "A", "B", 0.60, 0.25, 0.15,
                0, 0, 0, 1000, 0.5);

        assertThat(advice.recommendedSelection()).isEqualTo("NONE");
        assertThat(advice.recommendedStakeAmount()).isZero();
    }

    @Test
    void twoWayLinePicksTheValueSide() {
        ValueBetAdvisor.BetRecommendation advice = ValueBetAdvisor.analyze2Way(
                Sport.MMA, "Fighter A", "Fighter B",
                0.40, 0.60,
                3.20, 1.50,
                500, 0.5);

        // 0.40 * 3.20 - 1 = 0.28 beats 0.60 * 1.50 - 1 = -0.10
        assertThat(advice.recommendedSelection()).isEqualTo("Fighter A");
        assertThat(advice.expectedValue()).isCloseTo(0.28, within(1e-9));
    }
}

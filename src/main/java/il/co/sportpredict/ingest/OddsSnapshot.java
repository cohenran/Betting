package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.Sport;

/**
 * Market prices for one match, summarised across the bookmakers a provider lists.
 *
 * <p>Football is three-way; basketball has no draw market worth betting, so
 * {@code medianDraw} and {@code bestDraw} are null there.
 *
 * <p>{@code median} is the market consensus and the honest benchmark. {@code best} is the
 * highest price on offer, recorded for reference only: shopping the top line across books
 * is not something a bettor tied to one operator can do, and using it would inflate every
 * edge.
 */
public record OddsSnapshot(
        String externalId,
        Sport sport,
        double medianHome,
        Double medianDraw,
        double medianAway,
        double bestHome,
        Double bestDraw,
        double bestAway,
        int bookmakers
) {

    public boolean twoWay() {
        return medianDraw == null;
    }

    public boolean usable() {
        if (bookmakers <= 0 || medianHome <= 1.0 || medianAway <= 1.0) {
            return false;
        }
        return twoWay() || medianDraw > 1.0;
    }

    /** How much over 100% the median book adds up to - the implied margin. */
    public double overround() {
        double sum = 1.0 / medianHome + 1.0 / medianAway;
        if (!twoWay()) {
            sum += 1.0 / medianDraw;
        }
        return sum - 1.0;
    }
}

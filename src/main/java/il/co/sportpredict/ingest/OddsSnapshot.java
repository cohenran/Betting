package il.co.sportpredict.ingest;

/**
 * 1X2 prices for one match, summarised across the bookmakers a provider lists.
 *
 * @param median the middle price per outcome - the market consensus, and the honest
 *               benchmark to measure a model against
 * @param best   the highest price per outcome. Recorded for reference only: shopping the
 *               best line across books is not something a bettor limited to one operator
 *               can actually do, and using it would inflate every edge.
 */
public record OddsSnapshot(
        String externalId,
        double medianHome,
        double medianDraw,
        double medianAway,
        double bestHome,
        double bestDraw,
        double bestAway,
        int bookmakers
) {

    public boolean usable() {
        return bookmakers > 0 && medianHome > 1.0 && medianDraw > 1.0 && medianAway > 1.0;
    }

    /** The implied bookmaker margin: how much over 100% the median book adds up to. */
    public double overround() {
        return 1.0 / medianHome + 1.0 / medianDraw + 1.0 / medianAway - 1.0;
    }
}

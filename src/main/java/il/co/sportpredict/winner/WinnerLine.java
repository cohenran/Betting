package il.co.sportpredict.winner;

import java.time.Instant;

/** One parsed line of a Winner form, before it is matched to a fixture. */
public record WinnerLine(
        int lineNo,
        String competitionRaw,
        String homeRaw,
        String awayRaw,
        Instant kickoff,
        Double oddsHome,
        Double oddsDraw,
        Double oddsAway,
        String rawText
) {
}

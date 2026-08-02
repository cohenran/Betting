package il.co.sportpredict.model;

import java.time.Instant;

/** What the web UI receives for one event. */
public record PredictionView(
        Long fixtureId,
        Long fightId,
        String sport,
        String competition,
        String home,
        String away,
        Instant startsAt,
        String status,
        Integer homeScore,
        Integer awayScore,
        MatchPrediction prediction
) {
}

package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.EventStatus;
import il.co.sportpredict.domain.Sport;

import java.time.Instant;

/** Provider-agnostic fixture record produced by every {@link SportsProvider}. */
public record RawFixture(
        Sport sport,
        String provider,
        String externalId,
        String homeExternalId,
        String homeName,
        String awayExternalId,
        String awayName,
        Instant kickoff,
        EventStatus status,
        Integer homeScore,
        Integer awayScore,
        Integer homeScoreHt,
        Integer awayScoreHt,
        String competitionName,
        String country,
        String season,
        String payload
) {
}

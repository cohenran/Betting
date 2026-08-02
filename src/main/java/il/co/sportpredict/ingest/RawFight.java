package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.EventStatus;

import java.time.Instant;

public record RawFight(
        String provider,
        String externalId,
        String eventName,
        Instant fightDate,
        String fighterAExternalId,
        String fighterAName,
        String fighterBExternalId,
        String fighterBName,
        String weightClass,
        Integer roundsScheduled,
        boolean titleFight,
        EventStatus status,
        /** "A", "B", "DRAW" or null when unknown. */
        String winnerSide,
        String method,
        Integer endRound,
        String payload
) {
}

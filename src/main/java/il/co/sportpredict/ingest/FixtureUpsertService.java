package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.*;
import il.co.sportpredict.repo.CompetitionRepository;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.FixtureSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Writes {@link RawFixture} records into the canonical tables. Runs single-threaded on
 * purpose: providers fetch in parallel, but merging is serialized so two providers
 * describing the same match cannot race into two rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FixtureUpsertService {

    /** Providers disagree on kickoff by minutes; treat anything inside this window as the same match. */
    private static final Duration KICKOFF_WINDOW = Duration.ofHours(6);

    private final FixtureRepository fixtures;
    private final FixtureSourceRepository sources;
    private final CompetitionRepository competitions;
    private final TeamResolver teamResolver;

    public record UpsertResult(int created, int updated, int skipped) {
        static UpsertResult zero() {
            return new UpsertResult(0, 0, 0);
        }

        UpsertResult plus(UpsertResult o) {
            return new UpsertResult(created + o.created, updated + o.updated, skipped + o.skipped);
        }
    }

    @Transactional
    public UpsertResult upsertAll(List<RawFixture> raws) {
        UpsertResult total = UpsertResult.zero();
        for (RawFixture raw : raws) {
            try {
                total = total.plus(upsert(raw));
            } catch (Exception e) {
                log.warn("upsert failed for {} {}: {}", raw.provider(), raw.externalId(), e.toString());
                total = total.plus(new UpsertResult(0, 0, 1));
            }
        }
        return total;
    }

    @Transactional
    public UpsertResult upsert(RawFixture raw) {
        Team home = teamResolver.resolve(raw.sport(), raw.provider(), raw.homeExternalId(), raw.homeName(), raw.country());
        Team away = teamResolver.resolve(raw.sport(), raw.provider(), raw.awayExternalId(), raw.awayName(), raw.country());
        if (home.getId().equals(away.getId())) {
            return new UpsertResult(0, 0, 1);
        }

        Optional<FixtureSource> known =
                sources.findByProviderAndSportAndExternalId(raw.provider(), raw.sport(), raw.externalId());

        Fixture fixture;
        boolean created = false;
        if (known.isPresent()) {
            fixture = known.get().getFixture();
        } else {
            fixture = findNear(raw, home, away).orElse(null);
            if (fixture == null) {
                fixture = new Fixture();
                fixture.setSport(raw.sport());
                fixture.setHomeTeam(home);
                fixture.setAwayTeam(away);
                fixture.setKickoff(raw.kickoff());
                created = true;
            }
        }

        applyUpdates(fixture, raw);
        fixture.setUpdatedAt(Instant.now());
        fixture = fixtures.save(fixture);

        if (known.isEmpty()) {
            sources.save(new FixtureSource(fixture, raw.provider(), raw.sport(), raw.externalId(), raw.payload()));
        } else {
            FixtureSource src = known.get();
            src.setPayload(raw.payload());
            src.setFetchedAt(Instant.now());
            sources.save(src);
        }
        return created ? new UpsertResult(1, 0, 0) : new UpsertResult(0, 1, 0);
    }

    private Optional<Fixture> findNear(RawFixture raw, Team home, Team away) {
        return fixtures.findPairingNear(raw.sport(), home, away,
                        raw.kickoff().minus(KICKOFF_WINDOW), raw.kickoff().plus(KICKOFF_WINDOW))
                .stream().findFirst();
    }

    /** Never downgrade a known result to an unknown one - providers lag behind each other. */
    private void applyUpdates(Fixture fixture, RawFixture raw) {
        if (fixture.getCompetition() == null && raw.competitionName() != null) {
            fixture.setCompetition(competition(raw));
        }
        if (raw.season() != null) {
            fixture.setSeason(raw.season());
        }
        if (!fixture.getStatus().isFinal() || raw.status().isFinal()) {
            fixture.setStatus(raw.status());
        }
        if (raw.homeScore() != null && raw.awayScore() != null) {
            fixture.setHomeScore(raw.homeScore());
            fixture.setAwayScore(raw.awayScore());
        }
        if (raw.homeScoreHt() != null && raw.awayScoreHt() != null) {
            fixture.setHomeScoreHt(raw.homeScoreHt());
            fixture.setAwayScoreHt(raw.awayScoreHt());
        }
        // A finished match without a score is unusable; keep it out of the learning queue.
        if (fixture.getStatus().isFinal() && fixture.getHomeScore() == null) {
            fixture.setStatus(EventStatus.SCHEDULED);
        }
    }

    private Competition competition(RawFixture raw) {
        // Non-null so the (sport, name, country) unique key actually de-duplicates.
        String country = (raw.country() == null || raw.country().isBlank()) ? "-" : raw.country();
        return competitions.findBySportAndNameAndCountry(raw.sport(), raw.competitionName(), country)
                .orElseGet(() -> competitions.save(
                        new Competition(raw.sport(), raw.competitionName(), country, null)));
    }
}

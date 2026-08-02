package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.Fight;
import il.co.sportpredict.domain.FightSource;
import il.co.sportpredict.domain.Fighter;
import il.co.sportpredict.repo.FightRepository;
import il.co.sportpredict.repo.FightSourceRepository;
import il.co.sportpredict.repo.FighterRepository;
import il.co.sportpredict.util.Names;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FightUpsertService {

    private final FightRepository fights;
    private final FightSourceRepository sources;
    private final FighterRepository fighters;

    @Transactional
    public FixtureUpsertService.UpsertResult upsertAll(List<RawFight> raws) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (RawFight raw : raws) {
            try {
                boolean isNew = upsert(raw);
                if (isNew) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("fight upsert failed for {}: {}", raw.externalId(), e.toString());
                skipped++;
            }
        }
        return new FixtureUpsertService.UpsertResult(created, updated, skipped);
    }

    private boolean upsert(RawFight raw) {
        Fighter a = fighter(raw.fighterAName());
        Fighter b = fighter(raw.fighterBName());
        if (a.getId().equals(b.getId())) {
            throw new IllegalStateException("same fighter on both sides: " + raw.fighterAName());
        }

        Optional<FightSource> known = sources.findByProviderAndExternalId(raw.provider(), raw.externalId());
        Fight fight;
        boolean isNew = false;
        if (known.isPresent()) {
            fight = known.get().getFight();
        } else {
            fight = fights.findByFighterAAndFighterBAndFightDate(a, b, raw.fightDate())
                    .or(() -> fights.findByFighterAAndFighterBAndFightDate(b, a, raw.fightDate()))
                    .orElse(null);
            if (fight == null) {
                fight = new Fight();
                fight.setFighterA(a);
                fight.setFighterB(b);
                fight.setFightDate(raw.fightDate());
                isNew = true;
            }
        }

        fight.setEventName(raw.eventName());
        fight.setWeightClass(raw.weightClass());
        fight.setRoundsScheduled(raw.roundsScheduled());
        fight.setTitleFight(raw.titleFight());
        fight.setMethod(raw.method());
        fight.setEndRound(raw.endRound());
        if (!fight.getStatus().isFinal() || raw.status().isFinal()) {
            fight.setStatus(raw.status());
        }
        if ("A".equals(raw.winnerSide())) {
            fight.setWinner(sameFighter(fight.getFighterA(), a) ? fight.getFighterA() : fight.getFighterB());
        } else if ("B".equals(raw.winnerSide())) {
            fight.setWinner(sameFighter(fight.getFighterA(), a) ? fight.getFighterB() : fight.getFighterA());
        }
        fight.setUpdatedAt(Instant.now());
        fight = fights.save(fight);

        if (known.isEmpty()) {
            sources.save(new FightSource(fight, raw.provider(), raw.externalId(), raw.payload()));
        } else {
            FightSource src = known.get();
            src.setPayload(raw.payload());
            src.setFetchedAt(Instant.now());
            sources.save(src);
        }
        return isNew;
    }

    private boolean sameFighter(Fighter stored, Fighter incoming) {
        return stored.getId().equals(incoming.getId());
    }

    private Fighter fighter(String rawName) {
        String normalized = Names.normalize(rawName);
        return fighters.findByNormalizedName(normalized)
                .orElseGet(() -> fighters.save(new Fighter(rawName, normalized)));
    }
}

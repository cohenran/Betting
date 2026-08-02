package il.co.sportpredict.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import il.co.sportpredict.domain.Fight;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Prediction;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.model.basketball.BasketballPredictor;
import il.co.sportpredict.model.football.FootballPredictor;
import il.co.sportpredict.model.ufc.UfcPredictor;
import il.co.sportpredict.repo.FightRepository;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Produces predictions and stores them. A stored prediction is never recomputed once the
 * event has started - that is what keeps the calibration numbers honest.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PredictionService {

    private final FixtureRepository fixtures;
    private final FightRepository fights;
    private final PredictionRepository predictions;
    private final FootballPredictor football;
    private final BasketballPredictor basketball;
    private final UfcPredictor ufc;
    private final ObjectMapper mapper;

    public MatchPrediction predict(Fixture fixture) {
        return fixture.getSport() == Sport.BASKETBALL
                ? basketball.predict(fixture)
                : football.predict(fixture);
    }

    @Transactional
    public PredictionView predictFixture(Long fixtureId) {
        Fixture fixture = fixtures.findWithTeams(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("unknown fixture " + fixtureId));
        MatchPrediction prediction = storedOrFresh(fixture);
        return view(fixture, prediction);
    }

    @Transactional
    public PredictionView predictFight(Long fightId) {
        Fight fight = fights.findWithFighters(fightId)
                .orElseThrow(() -> new IllegalArgumentException("unknown fight " + fightId));
        String model = UfcPredictor.MODEL;
        Optional<Prediction> stored = predictions.findFirstByFightIdAndModelOrderByCreatedAtDesc(fightId, model);
        boolean started = fight.getFightDate().isBefore(Instant.now());
        MatchPrediction prediction;
        if (started && stored.isPresent()) {
            prediction = fromEntity(stored.get());
        } else {
            prediction = ufc.predict(fight);
            persist(stored.orElse(null), null, fight, prediction, Sport.MMA);
        }
        return new PredictionView(null, fight.getId(), Sport.MMA.name(), fight.getEventName(),
                fight.getFighterA().getName(), fight.getFighterB().getName(),
                fight.getFightDate(), fight.getStatus().name(), null, null, prediction);
    }

    /** Upcoming events with predictions, for the dashboard. */
    @Transactional
    public List<PredictionView> upcoming(Sport sport, int days) {
        Instant from = Instant.now().minus(6, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(days, ChronoUnit.DAYS);
        if (sport == Sport.MMA) {
            return fights.findBetween(from, to).stream()
                    .map(f -> predictFight(f.getId()))
                    .toList();
        }
        return fixtures.findBetween(sport, from, to).stream()
                .map(f -> view(f, storedOrFresh(f)))
                .toList();
    }

    private MatchPrediction storedOrFresh(Fixture fixture) {
        String model = fixture.getSport() == Sport.BASKETBALL ? BasketballPredictor.MODEL : FootballPredictor.MODEL;
        Optional<Prediction> stored =
                predictions.findFirstByFixtureIdAndModelOrderByCreatedAtDesc(fixture.getId(), model);
        boolean started = fixture.getKickoff().isBefore(Instant.now());
        if (started && stored.isPresent()) {
            return fromEntity(stored.get());
        }
        MatchPrediction fresh = predict(fixture);
        persist(stored.orElse(null), fixture, null, fresh, fixture.getSport());
        return fresh;
    }

    private void persist(Prediction existing, Fixture fixture, Fight fight, MatchPrediction p, Sport sport) {
        Prediction entity = existing != null ? existing : new Prediction();
        if (entity.isSettled()) {
            return;
        }
        entity.setSport(sport);
        entity.setFixture(fixture);
        entity.setFight(fight);
        entity.setModel(p.model());
        entity.setModelVersion(p.version());
        entity.setPHome(p.pHome());
        entity.setPDraw(p.pDraw());
        entity.setPAway(p.pAway());
        entity.setExpectedHome(p.expectedHome());
        entity.setExpectedAway(p.expectedAway());
        entity.setOuLine(p.ouLine());
        entity.setPOver(p.pOver());
        entity.setPBtts(p.pBtts());
        entity.setTopScore(p.topScore());
        entity.setConfidence(p.confidence());
        entity.setDetail(toJson(p.detail()));
        entity.setCreatedAt(Instant.now());
        predictions.save(entity);
    }

    private MatchPrediction fromEntity(Prediction e) {
        return new MatchPrediction(e.getModel(), e.getModelVersion(),
                or0(e.getPHome()), or0(e.getPDraw()), or0(e.getPAway()),
                e.getExpectedHome(), e.getExpectedAway(),
                e.getOuLine(), e.getPOver(), e.getPBtts(),
                e.getTopScore(), or0(e.getConfidence()),
                fromJson(e.getDetail()));
    }

    private PredictionView view(Fixture f, MatchPrediction p) {
        return new PredictionView(
                f.getId(), null, f.getSport().name(),
                f.getCompetition() == null ? null : f.getCompetition().getName(),
                f.getHomeTeam().getName(), f.getAwayTeam().getName(),
                f.getKickoff(), f.getStatus().name(),
                f.getHomeScore(), f.getAwayScore(), p);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return MatchPrediction.newDetail();
        }
        try {
            return mapper.readValue(json, java.util.Map.class);
        } catch (Exception e) {
            return MatchPrediction.newDetail();
        }
    }

    private double or0(Double v) {
        return v == null ? 0 : v;
    }
}

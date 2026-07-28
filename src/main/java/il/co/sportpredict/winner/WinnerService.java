package il.co.sportpredict.winner;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.*;
import il.co.sportpredict.model.MatchPrediction;
import il.co.sportpredict.model.PredictionService;
import il.co.sportpredict.repo.BettingRoundRepository;
import il.co.sportpredict.repo.BettingSelectionRepository;
import il.co.sportpredict.repo.FixtureRepository;
import il.co.sportpredict.repo.TeamAliasRepository;
import il.co.sportpredict.repo.TeamRepository;
import il.co.sportpredict.util.Names;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a Winner round, binds each line to a fixture we know, and returns the model's
 * call for every line plus the edge implied by the printed odds.
 *
 * <p>Edge is {@code p * odds - 1}: positive means the model thinks the price is too long.
 * That is an estimate from a model, not a promise - see the calibration numbers on the
 * backtest page before trusting a number here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WinnerService {

    public static final String PROVIDER = "winner";

    private final WinnerFetcher fetcher;
    private final WinnerParser parser;
    private final HebrewAliasDictionary dictionary;
    private final TeamRepository teams;
    private final TeamAliasRepository aliases;
    private final FixtureRepository fixtures;
    private final PredictionService predictions;
    private final BettingRoundRepository rounds;
    private final BettingSelectionRepository selections;
    private final SportPredictProperties props;

    public record LineAnalysis(
            int lineNo,
            String competition,
            String homeRaw,
            String awayRaw,
            Instant kickoff,
            String sport,
            Long fixtureId,
            String matchedHome,
            String matchedAway,
            Double matchConfidence,
            Double oddsHome,
            Double oddsDraw,
            Double oddsAway,
            MatchPrediction prediction,
            String recommendation,
            Double recommendationProbability,
            Double recommendationEdge,
            Double edgeHome,
            Double edgeDraw,
            Double edgeAway,
            boolean needsMapping,
            String note
    ) {
    }

    public record RoundAnalysis(
            Long roundId,
            String url,
            String fetchMethod,
            String note,
            int lines,
            int predicted,
            List<String> unmatchedNames,
            List<LineAnalysis> results
    ) {
    }

    @Transactional
    public RoundAnalysis analyzeUrl(String url) {
        WinnerFetcher.Fetched fetched = fetcher.fetch(url);
        List<WinnerLine> lines = parser.parse(fetched);
        return analyze(url, fetched.method(), fetched.note(), lines);
    }

    @Transactional
    public RoundAnalysis analyzePastedText(String text) {
        return analyze("manual-paste", "TEXT", null, parser.parseText(text));
    }

    private RoundAnalysis analyze(String url, String method, String note, List<WinnerLine> lines) {
        BettingRound round = new BettingRound();
        round.setProvider(PROVIDER);
        round.setSourceUrl(url);
        round.setFetchMethod(method);
        round.setFormName(lines.isEmpty() ? null : lines.getFirst().competitionRaw());
        round = rounds.save(round);

        List<LineAnalysis> results = new ArrayList<>();
        Set<String> unmatched = new LinkedHashSet<>();
        int predicted = 0;

        for (WinnerLine line : lines) {
            LineAnalysis analysis = analyzeLine(line, round, unmatched);
            if (analysis.prediction() != null) {
                predicted++;
            }
            results.add(analysis);
        }

        log.info("winner round {}: {} lines, {} predicted, {} unmatched names",
                round.getId(), lines.size(), predicted, unmatched.size());
        return new RoundAnalysis(round.getId(), url, method, note,
                lines.size(), predicted, List.copyOf(unmatched), results);
    }

    private LineAnalysis analyzeLine(WinnerLine line, BettingRound round, Set<String> unmatched) {
        Sport sport = guessSport(line);
        Optional<Team> home = findTeam(sport, line.homeRaw());
        Optional<Team> away = findTeam(sport, line.awayRaw());

        if (home.isEmpty()) {
            unmatched.add(line.homeRaw());
        }
        if (away.isEmpty()) {
            unmatched.add(line.awayRaw());
        }

        BettingSelection selection = new BettingSelection();
        selection.setRound(round);
        selection.setLineNo(line.lineNo());
        selection.setRawText(line.rawText());
        selection.setCompetitionRaw(line.competitionRaw());
        selection.setHomeRaw(line.homeRaw());
        selection.setAwayRaw(line.awayRaw());
        selection.setKickoff(line.kickoff());
        selection.setOddsHome(line.oddsHome());
        selection.setOddsDraw(line.oddsDraw());
        selection.setOddsAway(line.oddsAway());

        if (home.isEmpty() || away.isEmpty()) {
            selections.save(selection);
            return incomplete(line, sport, "team name not recognised - add a mapping to enable this line");
        }

        Optional<FixtureMatch> matched = findFixture(sport, home.get(), away.get(), line.kickoff());
        MatchPrediction prediction;
        String noteText;
        Long fixtureId = null;
        boolean swapped = false;

        if (matched.isPresent()) {
            Fixture found = matched.get().fixture();
            swapped = matched.get().swapped();
            fixtureId = found.getId();
            selection.setFixture(found);
            selection.setMatchConfidence(swapped ? 0.8 : 1.0);
            prediction = predictions.predictFixture(fixtureId).prediction();
            noteText = swapped
                    ? "the database lists this match with home and away reversed - probabilities re-oriented to the form"
                    : null;
        } else {
            // No stored fixture (e.g. a friendly nobody's API covers): predict from ratings
            // anyway, using an in-memory fixture that is never saved.
            Fixture synthetic = new Fixture();
            synthetic.setSport(sport);
            synthetic.setHomeTeam(home.get());
            synthetic.setAwayTeam(away.get());
            synthetic.setKickoff(line.kickoff() != null ? line.kickoff() : Instant.now());
            prediction = predictions.predict(synthetic);
            selection.setMatchConfidence(0.5);
            noteText = "no fixture in the database - prediction is from current ratings only";
        }
        selections.save(selection);

        // Everything below is stated in the form's own orientation: "1" is always the
        // first team printed on the line.
        double pHome = swapped ? prediction.pAway() : prediction.pHome();
        double pDraw = prediction.pDraw();
        double pAway = swapped ? prediction.pHome() : prediction.pAway();
        Double edgeHome = edge(pHome, line.oddsHome());
        Double edgeDraw = edge(pDraw, line.oddsDraw());
        Double edgeAway = edge(pAway, line.oddsAway());

        String pick = pHome >= pDraw && pHome >= pAway ? "HOME" : (pAway >= pDraw ? "AWAY" : "DRAW");
        double pickProbability = Math.max(pHome, Math.max(pDraw, pAway));
        Double pickEdge = switch (pick) {
            case "HOME" -> edgeHome;
            case "DRAW" -> edgeDraw;
            default -> edgeAway;
        };

        return new LineAnalysis(line.lineNo(), line.competitionRaw(), line.homeRaw(), line.awayRaw(),
                line.kickoff(), sport.name(), fixtureId,
                home.get().getName(), away.get().getName(), selection.getMatchConfidence(),
                line.oddsHome(), line.oddsDraw(), line.oddsAway(),
                prediction, pick, round4(pickProbability), pickEdge,
                edgeHome, edgeDraw, edgeAway, false, noteText);
    }

    private LineAnalysis incomplete(WinnerLine line, Sport sport, String note) {
        return new LineAnalysis(line.lineNo(), line.competitionRaw(), line.homeRaw(), line.awayRaw(),
                line.kickoff(), sport.name(), null, null, null, null,
                line.oddsHome(), line.oddsDraw(), line.oddsAway(),
                null, null, null, null, null, null, null, true, note);
    }

    /** Winner mixes sports on one form; the competition label is the only hint available. */
    private Sport guessSport(WinnerLine line) {
        String text = ((line.competitionRaw() == null ? "" : line.competitionRaw()) + " "
                + (line.rawText() == null ? "" : line.rawText())).toLowerCase();
        if (text.contains("כדורסל") || text.contains("basket") || text.contains("nba")
                || text.contains("יורוליג")) {
            return Sport.BASKETBALL;
        }
        return Sport.FOOTBALL;
    }

    /** Manual alias -> Hebrew dictionary -> fuzzy over known teams. */
    public Optional<Team> findTeam(Sport sport, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = Names.normalize(rawName);

        Optional<Team> manual = aliases.findByProviderAndNormalizedName(PROVIDER, normalized).stream()
                .map(TeamAlias::getTeam)
                .filter(t -> t.getSport() == sport)
                .findFirst();
        if (manual.isPresent()) {
            return manual;
        }

        Optional<String> english = dictionary.toEnglish(sport, rawName);
        if (english.isPresent()) {
            Optional<Team> exact = teams.findBySportAndNormalizedName(sport, Names.normalize(english.get()));
            if (exact.isPresent()) {
                return exact;
            }
        }

        String needle = english.orElse(rawName);
        Team best = null;
        double bestScore = 0;
        for (Team team : teams.findBySport(sport)) {
            double score = Names.similarity(needle, team.getName());
            if (score > bestScore) {
                bestScore = score;
                best = team;
            }
        }
        return bestScore >= props.getWinner().getMatchThreshold() ? Optional.ofNullable(best) : Optional.empty();
    }

    private record FixtureMatch(Fixture fixture, boolean swapped) {
    }

    private Optional<FixtureMatch> findFixture(Sport sport, Team home, Team away, Instant kickoff) {
        int tolerance = props.getWinner().getKickoffToleranceHours();
        Instant from = kickoff != null ? kickoff.minus(tolerance, ChronoUnit.HOURS)
                : Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = kickoff != null ? kickoff.plus(tolerance, ChronoUnit.HOURS)
                : Instant.now().plus(21, ChronoUnit.DAYS);

        List<Fixture> direct = fixtures.findPairingNear(sport, home, away, from, to);
        if (!direct.isEmpty()) {
            return Optional.of(new FixtureMatch(direct.getFirst(), false));
        }
        // Some forms print the away side first; accept the reverse pairing and re-orient.
        List<Fixture> reversed = fixtures.findPairingNear(sport, away, home, from, to);
        return reversed.isEmpty()
                ? Optional.empty()
                : Optional.of(new FixtureMatch(reversed.getFirst(), true));
    }

    private Double edge(double probability, Double odds) {
        if (odds == null || odds <= 1.0) {
            return null;
        }
        return round4(probability * odds - 1.0);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

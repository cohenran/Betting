package il.co.sportpredict.web;

import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Fixture;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import il.co.sportpredict.ingest.AllSportsOddsClient;
import il.co.sportpredict.ingest.AllSportsProvider;
import il.co.sportpredict.ingest.IngestService;
import il.co.sportpredict.ingest.OddsSnapshot;
import il.co.sportpredict.ingest.TeamResolver;
import il.co.sportpredict.model.LearningService;
import il.co.sportpredict.model.backtest.BacktestService;
import il.co.sportpredict.repo.FixtureSourceRepository;
import il.co.sportpredict.repo.IngestRunRepository;
import il.co.sportpredict.repo.TeamRepository;
import il.co.sportpredict.winner.WinnerService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operator endpoints. All require the X-Admin-Token header to match sportpredict.admin-token. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final IngestService ingest;
    private final LearningService learning;
    private final BacktestService backtestService;
    private final AllSportsOddsClient oddsClient;
    private final FixtureSourceRepository fixtureSources;
    private final IngestRunRepository runs;
    private final TeamRepository teams;
    private final TeamResolver teamResolver;
    private final SportPredictProperties props;

    public record AliasRequest(@NotBlank String rawName, Sport sport, Long teamId, String teamName) {
    }

    @PostMapping("/ingest")
    public IngestService.IngestSummary ingest(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                              @RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to,
                                              @RequestParam(required = false) Set<Sport> sports) {
        authorize(token);
        LocalDate start = from != null ? from : LocalDate.now().minusDays(2);
        LocalDate end = to != null ? to : LocalDate.now().plusDays(props.getIngest().getLookaheadDays());
        return ingest.ingestRange(start, end, sports == null ? EnumSet.allOf(Sport.class) : sports);
    }

    @PostMapping("/learn")
    public Map<String, Object> learn(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        int learned = learning.processNewResults();
        return Map.of("learnedResults", learned);
    }

    @PostMapping("/retrain")
    public LearningService.RefitReport retrain(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        return learning.rebuildAndRefit();
    }

    /** Teaches the Winner matcher a name it could not resolve. */
    @PostMapping("/alias")
    public Map<String, Object> alias(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                     @RequestBody AliasRequest request) {
        authorize(token);
        Team team = resolveTeam(request);
        teamResolver.addAlias(team, WinnerService.PROVIDER, request.rawName());
        return Map.of("mapped", request.rawName(), "toTeam", team.getName(), "sport", team.getSport().name());
    }

    /**
     * Runs a walk-forward backtest and stores it, which is what the paper-bet gate reads.
     * The nightly job does this too; this exists so the gate can be populated on demand
     * instead of waiting for 04:00.
     */
    @PostMapping("/backtest")
    public BacktestService.StoredResult backtest(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) Integer historyDays,
            @RequestParam(required = false) Integer stepDays,
            @RequestParam(required = false) Double trainFraction) {
        authorize(token);
        return backtestService.runAndStore(
                historyDays != null ? historyDays : props.getModel().getBacktestHistoryDays(),
                stepDays != null ? stepDays : props.getModel().getBacktestStepDays(),
                trainFraction != null ? trainFraction : props.getModel().getBacktestTrainFraction());
    }

    /**
     * Checks that market odds actually join onto stored fixtures, without waiting for the
     * paper-bet run. A high unmatched count means the fixtures behind those prices were
     * never ingested - a league-filter problem, not an odds problem.
     */
    @GetMapping("/odds-check")
    public Map<String, Object> oddsCheck(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(defaultValue = "3") int days) {
        authorize(token);
        LocalDate from = LocalDate.now();
        Map<String, OddsSnapshot> odds = oddsClient.fetch(from, from.plusDays(days));

        List<Map<String, Object>> matched = new ArrayList<>();
        int unmatched = 0;
        for (OddsSnapshot snapshot : odds.values()) {
            var source = fixtureSources.findByProviderAndSportAndExternalId(
                    AllSportsProvider.NAME, Sport.FOOTBALL, snapshot.externalId());
            if (source.isEmpty()) {
                unmatched++;
                continue;
            }
            if (matched.size() < 15) {
                Fixture fixture = source.get().getFixture();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fixtureId", fixture.getId());
                row.put("match", fixture.getHomeTeam().getName() + " vs " + fixture.getAwayTeam().getName());
                row.put("kickoff", fixture.getKickoff());
                row.put("odds", List.of(snapshot.medianHome(), snapshot.medianDraw(), snapshot.medianAway()));
                row.put("bookmakers", snapshot.bookmakers());
                row.put("overround", Math.round(snapshot.overround() * 1000) / 1000.0);
                matched.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", from + ".." + from.plusDays(days));
        out.put("pricedMatches", odds.size());
        out.put("matchedToFixture", odds.size() - unmatched);
        out.put("unmatched", unmatched);
        out.put("sample", matched);
        return out;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        return runs.findTop30ByOrderByStartedAtDesc().stream().map(run -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", run.getProvider());
            row.put("sport", run.getSport().name());
            row.put("from", run.getFromDate());
            row.put("to", run.getToDate());
            row.put("requests", run.getRequests());
            row.put("records", run.getRecords());
            row.put("created", run.getCreated());
            row.put("updated", run.getUpdated());
            row.put("startedAt", run.getStartedAt());
            row.put("error", run.getError());
            return row;
        }).toList();
    }

    private Team resolveTeam(AliasRequest request) {
        if (request.teamId() != null) {
            return teams.findById(request.teamId())
                    .orElseThrow(() -> new IllegalArgumentException("unknown team id " + request.teamId()));
        }
        if (request.teamName() == null || request.sport() == null) {
            throw new IllegalArgumentException("provide teamId, or teamName plus sport");
        }
        String normalized = il.co.sportpredict.util.Names.normalize(request.teamName());
        return teams.findBySportAndNormalizedName(request.sport(), normalized)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no team named '" + request.teamName() + "' for " + request.sport()));
    }

    private void authorize(String token) {
        if (token == null || !token.equals(props.getAdminToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad or missing X-Admin-Token");
        }
    }
}

package il.co.sportpredict.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.EventStatus;
import il.co.sportpredict.domain.Sport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * api-sports.io reader (v3 football, v1 basketball, v1 mma).
 *
 * <p>Queries by <em>date</em> rather than by league+season: one request returns every
 * fixture of that day across all leagues, which is far cheaper against a 10 req/min,
 * 100 req/day free tier. Configured league ids are then filtered client-side
 * (empty list = keep everything).
 */
@Component
@Slf4j
public class ApiSportsProvider implements SportsProvider {

    public static final String NAME = "api-sports";

    private final RestClient http;
    private final SportPredictProperties props;
    private final ProviderRateLimiter limiter;
    private final Set<Integer> footballLeagues;
    private final Set<Integer> basketballLeagues;

    public ApiSportsProvider(RestClient sportsRestClient, SportPredictProperties props,
                             @Qualifier("apiSportsLimiter") ProviderRateLimiter limiter) {
        this.http = sportsRestClient;
        this.props = props;
        this.limiter = limiter;
        this.footballLeagues = new HashSet<>(props.getIngest().getFootballLeagues());
        this.basketballLeagues = new HashSet<>(props.getIngest().getBasketballLeagues());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled() {
        SportPredictProperties.ApiSports cfg = props.getProviders().getApiSports();
        return cfg.isEnabled() && cfg.getKey() != null && !cfg.getKey().isBlank();
    }

    @Override
    public Set<Sport> supportedSports() {
        return Set.of(Sport.FOOTBALL, Sport.BASKETBALL, Sport.MMA);
    }

    @Override
    public Batch<RawFixture> fetchFixtures(Sport sport, LocalDate from, LocalDate to) {
        List<RawFixture> out = new ArrayList<>();
        int requests = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                limiter.acquire();
                requests++;
                JsonNode root = get(baseUrl(sport) + endpoint(sport) + "?date=" + d);
                for (JsonNode node : root.path("response")) {
                    RawFixture f = sport == Sport.FOOTBALL ? mapFootball(node) : mapBasketball(node);
                    if (f != null) {
                        out.add(f);
                    }
                }
            } catch (ProviderRateLimiter.DailyLimitReachedException e) {
                return Batch.failed(out, requests, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Batch.failed(out, requests, "interrupted");
            } catch (Exception e) {
                log.warn("api-sports {} {} failed: {}", sport, d, e.getMessage());
                return Batch.failed(out, requests, sport + " " + d + ": " + e.getMessage());
            }
        }
        return Batch.of(out, requests);
    }

    @Override
    public Batch<RawFight> fetchFights(LocalDate from, LocalDate to) {
        List<RawFight> out = new ArrayList<>();
        int requests = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                limiter.acquire();
                requests++;
                JsonNode root = get(props.getProviders().getApiSports().getMmaBaseUrl() + "/fights?date=" + d);
                for (JsonNode node : root.path("response")) {
                    RawFight f = mapFight(node);
                    if (f != null) {
                        out.add(f);
                    }
                }
            } catch (ProviderRateLimiter.DailyLimitReachedException e) {
                return Batch.failed(out, requests, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Batch.failed(out, requests, "interrupted");
            } catch (Exception e) {
                log.warn("api-sports MMA {} failed: {}", d, e.getMessage());
                return Batch.failed(out, requests, "MMA " + d + ": " + e.getMessage());
            }
        }
        return Batch.of(out, requests);
    }

    private JsonNode get(String url) {
        return http.get()
                .uri(url)
                .header("x-apisports-key", props.getProviders().getApiSports().getKey())
                .retrieve()
                .body(JsonNode.class);
    }

    private String baseUrl(Sport sport) {
        SportPredictProperties.ApiSports cfg = props.getProviders().getApiSports();
        return switch (sport) {
            case FOOTBALL -> cfg.getFootballBaseUrl();
            case BASKETBALL -> cfg.getBasketballBaseUrl();
            case MMA -> cfg.getMmaBaseUrl();
        };
    }

    private String endpoint(Sport sport) {
        return switch (sport) {
            case FOOTBALL -> "/fixtures";
            case BASKETBALL -> "/games";
            case MMA -> "/fights";
        };
    }

    private RawFixture mapFootball(JsonNode n) {
        JsonNode league = n.path("league");
        int leagueId = league.path("id").asInt(-1);
        if (!footballLeagues.isEmpty() && !footballLeagues.contains(leagueId)) {
            return null;
        }
        JsonNode fixture = n.path("fixture");
        JsonNode teams = n.path("teams");
        JsonNode goals = n.path("goals");
        JsonNode halftime = n.path("score").path("halftime");

        String id = fixture.path("id").asText(null);
        if (id == null || teams.path("home").path("name").isMissingNode()) {
            return null;
        }
        return new RawFixture(
                Sport.FOOTBALL, NAME, id,
                teams.path("home").path("id").asText(null),
                teams.path("home").path("name").asText(),
                teams.path("away").path("id").asText(null),
                teams.path("away").path("name").asText(),
                Instant.ofEpochSecond(fixture.path("timestamp").asLong()),
                mapStatus(fixture.path("status").path("short").asText("")),
                intOrNull(goals.path("home")), intOrNull(goals.path("away")),
                intOrNull(halftime.path("home")), intOrNull(halftime.path("away")),
                league.path("name").asText(null),
                league.path("country").asText(null),
                league.path("season").asText(null),
                n.toString());
    }

    private RawFixture mapBasketball(JsonNode n) {
        JsonNode league = n.path("league");
        int leagueId = league.path("id").asInt(-1);
        if (!basketballLeagues.isEmpty() && !basketballLeagues.contains(leagueId)) {
            return null;
        }
        JsonNode teams = n.path("teams");
        JsonNode scores = n.path("scores");
        String id = n.path("id").asText(null);
        if (id == null || teams.path("home").path("name").isMissingNode()) {
            return null;
        }
        return new RawFixture(
                Sport.BASKETBALL, NAME, id,
                teams.path("home").path("id").asText(null),
                teams.path("home").path("name").asText(),
                teams.path("away").path("id").asText(null),
                teams.path("away").path("name").asText(),
                Instant.parse(n.path("date").asText()),
                mapStatus(n.path("status").path("short").asText("")),
                intOrNull(scores.path("home").path("total")),
                intOrNull(scores.path("away").path("total")),
                null, null,
                league.path("name").asText(null),
                n.path("country").path("name").asText(null),
                league.path("season").asText(null),
                n.toString());
    }

    private RawFight mapFight(JsonNode n) {
        JsonNode fighters = n.path("fighters");
        JsonNode a = fighters.path("first");
        JsonNode b = fighters.path("second");
        String id = n.path("id").asText(null);
        if (id == null || a.path("name").isMissingNode()) {
            return null;
        }
        EventStatus status = mapStatus(n.path("status").path("short").asText(""));
        String winner = null;
        if (a.path("winner").isBoolean() || b.path("winner").isBoolean()) {
            if (a.path("winner").asBoolean(false)) {
                winner = "A";
            } else if (b.path("winner").asBoolean(false)) {
                winner = "B";
            } else if (status.isFinal()) {
                winner = "DRAW";
            }
        }
        return new RawFight(
                NAME, id,
                n.path("slug").asText(n.path("category").asText(null)),
                Instant.parse(n.path("date").asText()),
                a.path("id").asText(null), a.path("name").asText(),
                b.path("id").asText(null), b.path("name").asText(),
                n.path("category").asText(null),
                intOrNull(n.path("rounds")),
                n.path("is_title").asBoolean(false) || n.path("title").asBoolean(false),
                status, winner,
                n.path("method").asText(null),
                intOrNull(n.path("round")),
                n.toString());
    }

    /** api-sports status short codes, all three sports. */
    private EventStatus mapStatus(String code) {
        return switch (code.toUpperCase()) {
            case "FT", "AET", "PEN", "AOT", "AP", "ENDED" -> EventStatus.FINISHED;
            case "NS", "TBD" -> EventStatus.SCHEDULED;
            case "PST", "POST", "SUSP" -> EventStatus.POSTPONED;
            case "CANC", "ABD", "AWD", "WO", "CANCELLED" -> EventStatus.CANCELLED;
            case "" -> EventStatus.SCHEDULED;
            default -> EventStatus.LIVE;
        };
    }

    private Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asInt();
    }
}

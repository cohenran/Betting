package il.co.sportpredict.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.EventStatus;
import il.co.sportpredict.domain.Sport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * allsportsapi.com reader. Accepts a from..to range in a single call, so a chunk of
 * days costs one request - the complement to api-sports' per-day queries.
 */
@Component
@Slf4j
public class AllSportsProvider implements SportsProvider {

    public static final String NAME = "allsports";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final RestClient http;
    private final SportPredictProperties props;
    private final ProviderRateLimiter limiter;
    private final Set<Integer> footballLeagues;

    public AllSportsProvider(RestClient sportsRestClient, SportPredictProperties props) {
        this.http = sportsRestClient;
        this.props = props;
        SportPredictProperties.AllSports cfg = props.getProviders().getAllsports();
        this.limiter = new ProviderRateLimiter(NAME, cfg.getRequestsPerMinute(), cfg.getDailyLimit());
        this.footballLeagues = new HashSet<>(props.getIngest().getAllsportsFootballLeagues());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled() {
        SportPredictProperties.AllSports cfg = props.getProviders().getAllsports();
        return cfg.isEnabled() && cfg.getKey() != null && !cfg.getKey().isBlank();
    }

    @Override
    public Set<Sport> supportedSports() {
        return Set.of(Sport.FOOTBALL, Sport.BASKETBALL);
    }

    @Override
    public Batch<RawFixture> fetchFixtures(Sport sport, LocalDate from, LocalDate to) {
        if (sport == Sport.MMA) {
            return Batch.empty();
        }
        List<RawFixture> out = new ArrayList<>();
        int requests = 0;
        // One call per 10-day window keeps responses a sane size.
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(10)) {
            LocalDate end = start.plusDays(9).isAfter(to) ? to : start.plusDays(9);
            try {
                limiter.acquire();
                requests++;
                JsonNode root = get(sport, start, end);
                if (root.path("success").asInt(0) != 1) {
                    String err = root.path("error").asText("unexpected response");
                    return Batch.failed(out, requests, err);
                }
                for (JsonNode node : root.path("result")) {
                    RawFixture f = map(sport, node);
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
                log.warn("allsports {} {}..{} failed: {}", sport, start, end, e.getMessage());
                return Batch.failed(out, requests, sport + " " + start + ": " + e.getMessage());
            }
        }
        return Batch.of(out, requests);
    }

    private JsonNode get(Sport sport, LocalDate from, LocalDate to) {
        String path = sport == Sport.FOOTBALL ? "/football/" : "/basketball/";
        String url = props.getProviders().getAllsports().getBaseUrl() + path
                + "?met=Fixtures"
                + "&APIkey=" + props.getProviders().getAllsports().getKey()
                + "&from=" + DATE.format(from)
                + "&to=" + DATE.format(to)
                + "&timezone=UTC";
        return http.get().uri(url).retrieve().body(JsonNode.class);
    }

    private RawFixture map(Sport sport, JsonNode n) {
        int leagueKey = n.path("league_key").asInt(-1);
        if (sport == Sport.FOOTBALL && !footballLeagues.isEmpty() && !footballLeagues.contains(leagueKey)) {
            return null;
        }
        String id = n.path("event_key").asText(null);
        String home = n.path("event_home_team").asText(null);
        String away = n.path("event_away_team").asText(null);
        if (id == null || home == null || away == null) {
            return null;
        }
        Instant kickoff = parseKickoff(n.path("event_date").asText(null), n.path("event_time").asText(null));
        if (kickoff == null) {
            return null;
        }
        int[] full = parseScore(n.path("event_final_result").asText(null));
        int[] half = parseScore(n.path("event_halftime_result").asText(null));
        return new RawFixture(
                sport, NAME, id,
                n.path("home_team_key").asText(null), home,
                n.path("away_team_key").asText(null), away,
                kickoff,
                mapStatus(n.path("event_status").asText(""), full != null),
                full == null ? null : full[0], full == null ? null : full[1],
                half == null ? null : half[0], half == null ? null : half[1],
                n.path("league_name").asText(null),
                n.path("country_name").asText(null),
                n.path("league_season").asText(null),
                n.toString());
    }

    private Instant parseKickoff(String date, String time) {
        if (date == null) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(date, DATE);
            LocalTime t = (time == null || time.isBlank()) ? LocalTime.of(12, 0) : LocalTime.parse(time, TIME);
            return LocalDateTime.of(d, t).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /** "2 - 1" -> [2,1]. Empty / "-" -> null. */
    private int[] parseScore(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains("-")) {
            return null;
        }
        String[] parts = raw.split("-");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private EventStatus mapStatus(String raw, boolean hasScore) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (s.startsWith("finished") || s.startsWith("after pen") || s.startsWith("after et") || s.equals("ft")) {
            return EventStatus.FINISHED;
        }
        if (s.contains("postponed")) {
            return EventStatus.POSTPONED;
        }
        if (s.contains("cancel") || s.contains("abandoned")) {
            return EventStatus.CANCELLED;
        }
        if (s.isEmpty()) {
            return hasScore ? EventStatus.FINISHED : EventStatus.SCHEDULED;
        }
        // A bare minute ("67") or "HT" means the game is running.
        return EventStatus.LIVE;
    }
}

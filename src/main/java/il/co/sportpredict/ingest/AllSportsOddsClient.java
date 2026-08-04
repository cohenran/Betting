package il.co.sportpredict.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import il.co.sportpredict.config.SportPredictProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads 1X2 prices from allsportsapi's {@code met=Odds} endpoint.
 *
 * <p>The response is an object keyed by match id, each holding one entry per bookmaker:
 * <pre>
 * {"success":1,"result":{"1726035":[
 *    {"odd_bookmakers":"WilliamHill","odd_1":2.7,"odd_x":2.9,"odd_2":2.45, ...},
 *    {"odd_bookmakers":"Marathon","odd_1":2.65,"odd_x":3.08,"odd_2":2.45, ...}]}}
 * </pre>
 *
 * <p>Those keys are the same {@code event_key} stored in {@code fixture_source.external_id}
 * for this provider, so odds join straight onto fixtures with no name matching.
 *
 * <p>One request covers a whole date range, so this is nearly free against the quota.
 */
@Component
@Slf4j
public class AllSportsOddsClient {

    private final RestClient http;
    private final SportPredictProperties props;
    private final ProviderRateLimiter limiter;
    private final ObjectMapper mapper;

    public AllSportsOddsClient(RestClient sportsRestClient, SportPredictProperties props,
                               @Qualifier("allsportsLimiter") ProviderRateLimiter limiter,
                               ObjectMapper mapper) {
        this.http = sportsRestClient;
        this.props = props;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    /** Keyed by provider match id. Empty map on any failure - never throws. */
    public Map<String, OddsSnapshot> fetch(LocalDate from, LocalDate to) {
        SportPredictProperties.AllSports cfg = props.getProviders().getAllsports();
        if (!cfg.isEnabled() || cfg.getKey() == null || cfg.getKey().isBlank()) {
            return Map.of();
        }
        String url = cfg.getBaseUrl() + "/football/?met=Odds"
                + "&APIkey=" + cfg.getKey()
                + "&from=" + from
                + "&to=" + to;
        try {
            limiter.acquire();
            JsonNode root = http.get().uri(url).retrieve().body(JsonNode.class);
            return parse(root);
        } catch (ProviderRateLimiter.DailyLimitReachedException e) {
            log.warn("odds fetch skipped: {}", e.getMessage());
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            log.warn("odds fetch failed for {}..{}: {}", from, to, e.getMessage());
            return Map.of();
        }
    }

    /** Visible for testing against a captured response. */
    public Map<String, OddsSnapshot> parse(JsonNode root) {
        if (root == null || root.path("success").asInt(0) != 1) {
            return Map.of();
        }
        JsonNode result = root.path("result");
        if (!result.isObject()) {
            return Map.of();
        }

        Map<String, OddsSnapshot> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> matches = result.fields();
        while (matches.hasNext()) {
            Map.Entry<String, JsonNode> entry = matches.next();
            OddsSnapshot snapshot = summarise(entry.getKey(), entry.getValue());
            if (snapshot != null && snapshot.usable()) {
                out.put(entry.getKey(), snapshot);
            }
        }
        return out;
    }

    private OddsSnapshot summarise(String matchId, JsonNode bookmakerRows) {
        if (!bookmakerRows.isArray()) {
            return null;
        }
        List<Double> home = new ArrayList<>();
        List<Double> draw = new ArrayList<>();
        List<Double> away = new ArrayList<>();

        for (JsonNode row : bookmakerRows) {
            Double h = odds(row, "odd_1");
            Double d = odds(row, "odd_x");
            Double a = odds(row, "odd_2");
            // A bookmaker is only usable when all three prices are present, otherwise the
            // median would mix outcomes priced by different subsets of books.
            if (h != null && d != null && a != null) {
                home.add(h);
                draw.add(d);
                away.add(a);
            }
        }
        if (home.isEmpty()) {
            return null;
        }
        return new OddsSnapshot(matchId,
                median(home), median(draw), median(away),
                Collections.max(home), Collections.max(draw), Collections.max(away),
                home.size());
    }

    private Double odds(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        double d;
        if (value.isNumber()) {
            d = value.asDouble();
        } else {
            try {
                d = Double.parseDouble(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return (d > 1.0 && d < 1000.0) ? d : null;
    }

    private double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }
}

package il.co.sportpredict.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Sport;
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
 * Reads market prices from allsportsapi's {@code met=Odds} endpoints.
 *
 * <p>The two sports return genuinely different shapes, so each has its own parser.
 *
 * <p><b>Football</b> - flat array of bookmaker rows, numeric prices:
 * <pre>
 * {"result":{"1726035":[{"odd_bookmakers":"WilliamHill","odd_1":2.7,"odd_x":2.9,"odd_2":2.45}]}}
 * </pre>
 *
 * <p><b>Basketball</b> - nested market to outcome to bookmaker, prices as strings:
 * <pre>
 * {"result":{"247684":{"Home/Away":{"Home":{"Pncl":"1.69"},"Away":{"Pncl":"2.31"}}}}}
 * </pre>
 * Only {@code Home/Away} is used. The {@code 3Way Result} market prices a
 * regulation-time draw, which is not a bet anyone sensible places on basketball.
 *
 * <p>The map keys are the same {@code event_key} stored in
 * {@code fixture_source.external_id}, so prices join onto fixtures by id with no name
 * matching. One request covers a whole date range.
 */
@Component
@Slf4j
public class AllSportsOddsClient {

    /** The only basketball market worth reading. */
    private static final String MONEYLINE = "Home/Away";

    private final RestClient http;
    private final SportPredictProperties props;
    private final ProviderRateLimiter limiter;

    public AllSportsOddsClient(RestClient sportsRestClient, SportPredictProperties props,
                               @Qualifier("allsportsLimiter") ProviderRateLimiter limiter) {
        this.http = sportsRestClient;
        this.props = props;
        this.limiter = limiter;
    }

    /** Keyed by provider match id. Empty map on any failure - never throws. */
    public Map<String, OddsSnapshot> fetch(Sport sport, LocalDate from, LocalDate to) {
        SportPredictProperties.AllSports cfg = props.getProviders().getAllsports();
        if (!cfg.isEnabled() || cfg.getKey() == null || cfg.getKey().isBlank()) {
            return Map.of();
        }
        if (sport == Sport.MMA) {
            return Map.of();
        }
        String path = sport == Sport.FOOTBALL ? "/football/" : "/basketball/";
        String url = cfg.getBaseUrl() + path + "?met=Odds"
                + "&APIkey=" + cfg.getKey()
                + "&from=" + from
                + "&to=" + to;
        try {
            limiter.acquire();
            JsonNode root = http.get().uri(url).retrieve().body(JsonNode.class);
            return parse(sport, root);
        } catch (ProviderRateLimiter.DailyLimitReachedException e) {
            log.warn("odds fetch skipped: {}", e.getMessage());
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            log.warn("{} odds fetch failed for {}..{}: {}", sport, from, to, e.getMessage());
            return Map.of();
        }
    }

    /** Visible for testing against captured responses. */
    public Map<String, OddsSnapshot> parse(Sport sport, JsonNode root) {
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
            OddsSnapshot snapshot = sport == Sport.FOOTBALL
                    ? summariseFootball(entry.getKey(), entry.getValue())
                    : summariseBasketball(entry.getKey(), entry.getValue());
            if (snapshot != null && snapshot.usable()) {
                out.put(entry.getKey(), snapshot);
            }
        }
        return out;
    }

    private OddsSnapshot summariseFootball(String matchId, JsonNode bookmakerRows) {
        if (!bookmakerRows.isArray()) {
            return null;
        }
        List<Double> home = new ArrayList<>();
        List<Double> draw = new ArrayList<>();
        List<Double> away = new ArrayList<>();

        for (JsonNode row : bookmakerRows) {
            Double h = odds(row.get("odd_1"));
            Double d = odds(row.get("odd_x"));
            Double a = odds(row.get("odd_2"));
            // Only count a bookmaker that priced all three, otherwise the medians would
            // mix outcomes quoted by different subsets of books.
            if (h != null && d != null && a != null) {
                home.add(h);
                draw.add(d);
                away.add(a);
            }
        }
        if (home.isEmpty()) {
            return null;
        }
        return new OddsSnapshot(matchId, Sport.FOOTBALL,
                median(home), median(draw), median(away),
                Collections.max(home), Collections.max(draw), Collections.max(away),
                home.size());
    }

    private OddsSnapshot summariseBasketball(String matchId, JsonNode markets) {
        JsonNode moneyline = markets.path(MONEYLINE);
        if (!moneyline.isObject()) {
            return null;
        }
        Map<String, Double> homeByBook = pricesByBookmaker(moneyline.path("Home"));
        Map<String, Double> awayByBook = pricesByBookmaker(moneyline.path("Away"));

        List<Double> home = new ArrayList<>();
        List<Double> away = new ArrayList<>();
        // Pair by bookmaker: a book that quoted only one side would otherwise skew a median.
        for (Map.Entry<String, Double> entry : homeByBook.entrySet()) {
            Double opposite = awayByBook.get(entry.getKey());
            if (opposite != null) {
                home.add(entry.getValue());
                away.add(opposite);
            }
        }
        if (home.isEmpty()) {
            return null;
        }
        return new OddsSnapshot(matchId, Sport.BASKETBALL,
                median(home), null, median(away),
                Collections.max(home), null, Collections.max(away),
                home.size());
    }

    private Map<String, Double> pricesByBookmaker(JsonNode outcome) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!outcome.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> books = outcome.fields();
        while (books.hasNext()) {
            Map.Entry<String, JsonNode> book = books.next();
            Double price = odds(book.getValue());
            if (price != null) {
                out.put(book.getKey(), price);
            }
        }
        return out;
    }

    /** Accepts numbers and strings; basketball quotes prices as strings. */
    private Double odds(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        double d;
        if (value.isNumber()) {
            d = value.asDouble();
        } else if (value.isTextual()) {
            try {
                d = Double.parseDouble(value.asText().replace(',', '.').trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
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

package il.co.sportpredict.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import il.co.sportpredict.config.SportPredictProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/** Parses a response captured from the live met=Odds endpoint. */
class AllSportsOddsClientTest {

    private final AllSportsOddsClient client = new AllSportsOddsClient(
            mock(org.springframework.web.client.RestClient.class),
            new SportPredictProperties(),
            mock(ProviderRateLimiter.class),
            new ObjectMapper());

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void takesTheMedianAcrossBookmakersAndRecordsTheBest() throws Exception {
        String json = """
                {"success":1,"result":{"1726035":[
                  {"odd_bookmakers":"WilliamHill","odd_1":2.7,"odd_x":2.9,"odd_2":2.45,"o+2.5":null},
                  {"odd_bookmakers":"Marathon","odd_1":2.65,"odd_x":3.08,"odd_2":2.45,"ah0_1":1.9},
                  {"odd_bookmakers":"Bet365","odd_1":2.80,"odd_x":3.20,"odd_2":2.30}]}}
                """;

        Map<String, OddsSnapshot> parsed = client.parse(mapper.readTree(json));

        assertThat(parsed).hasSize(1);
        OddsSnapshot snapshot = parsed.get("1726035");
        assertThat(snapshot.bookmakers()).isEqualTo(3);
        assertThat(snapshot.medianHome()).isCloseTo(2.70, within(1e-9));
        assertThat(snapshot.medianDraw()).isCloseTo(3.08, within(1e-9));
        assertThat(snapshot.medianAway()).isCloseTo(2.45, within(1e-9));
        assertThat(snapshot.bestHome()).isCloseTo(2.80, within(1e-9));
        assertThat(snapshot.usable()).isTrue();
        // Three-way overround on real prices sits a few percent above zero.
        assertThat(snapshot.overround()).isBetween(0.0, 0.20);
    }

    @Test
    void ignoresBookmakersMissingAnyOfTheThreeOutcomes() throws Exception {
        String json = """
                {"success":1,"result":{"99":[
                  {"odd_bookmakers":"Partial","odd_1":2.0,"odd_2":3.0},
                  {"odd_bookmakers":"Full","odd_1":2.1,"odd_x":3.4,"odd_2":3.2}]}}
                """;

        OddsSnapshot snapshot = client.parse(mapper.readTree(json)).get("99");

        // Mixing a book that priced only two outcomes would bias one leg's median.
        assertThat(snapshot.bookmakers()).isEqualTo(1);
        assertThat(snapshot.medianHome()).isCloseTo(2.1, within(1e-9));
    }

    @Test
    void dropsMatchesWithNoUsablePrices() throws Exception {
        String json = """
                {"success":1,"result":{"5":[{"odd_bookmakers":"X","odd_1":null,"odd_x":null,"odd_2":null}]}}
                """;

        assertThat(client.parse(mapper.readTree(json))).isEmpty();
    }

    @Test
    void failedResponseYieldsEmptyMap() throws Exception {
        assertThat(client.parse(mapper.readTree("{\"success\":0,\"error\":\"no plan\"}"))).isEmpty();
        assertThat(client.parse(mapper.readTree("{\"success\":1,\"result\":[]}"))).isEmpty();
    }
}

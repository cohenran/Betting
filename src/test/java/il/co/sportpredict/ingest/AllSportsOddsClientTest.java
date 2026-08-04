package il.co.sportpredict.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/** Parses responses captured from the live met=Odds endpoints. The two sports differ. */
class AllSportsOddsClientTest {

    private final AllSportsOddsClient client = new AllSportsOddsClient(
            mock(org.springframework.web.client.RestClient.class),
            new SportPredictProperties(),
            mock(ProviderRateLimiter.class));

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- football: flat bookmaker rows, numeric prices ----------

    @Test
    void footballTakesTheMedianAcrossBookmakersAndRecordsTheBest() throws Exception {
        String json = """
                {"success":1,"result":{"1726035":[
                  {"odd_bookmakers":"WilliamHill","odd_1":2.7,"odd_x":2.9,"odd_2":2.45,"o+2.5":null},
                  {"odd_bookmakers":"Marathon","odd_1":2.65,"odd_x":3.08,"odd_2":2.45,"ah0_1":1.9},
                  {"odd_bookmakers":"Bet365","odd_1":2.80,"odd_x":3.20,"odd_2":2.30}]}}
                """;

        Map<String, OddsSnapshot> parsed = client.parse(Sport.FOOTBALL, mapper.readTree(json));

        assertThat(parsed).hasSize(1);
        OddsSnapshot snapshot = parsed.get("1726035");
        assertThat(snapshot.sport()).isEqualTo(Sport.FOOTBALL);
        assertThat(snapshot.twoWay()).isFalse();
        assertThat(snapshot.bookmakers()).isEqualTo(3);
        assertThat(snapshot.medianHome()).isCloseTo(2.70, within(1e-9));
        assertThat(snapshot.medianDraw()).isCloseTo(3.08, within(1e-9));
        assertThat(snapshot.medianAway()).isCloseTo(2.45, within(1e-9));
        assertThat(snapshot.bestHome()).isCloseTo(2.80, within(1e-9));
        assertThat(snapshot.usable()).isTrue();
        assertThat(snapshot.overround()).isBetween(0.0, 0.20);
    }

    @Test
    void footballIgnoresBookmakersMissingAnyOfTheThreeOutcomes() throws Exception {
        String json = """
                {"success":1,"result":{"99":[
                  {"odd_bookmakers":"Partial","odd_1":2.0,"odd_2":3.0},
                  {"odd_bookmakers":"Full","odd_1":2.1,"odd_x":3.4,"odd_2":3.2}]}}
                """;

        OddsSnapshot snapshot = client.parse(Sport.FOOTBALL, mapper.readTree(json)).get("99");

        // Mixing a book that priced only two outcomes would bias one leg's median.
        assertThat(snapshot.bookmakers()).isEqualTo(1);
        assertThat(snapshot.medianHome()).isCloseTo(2.1, within(1e-9));
    }

    // ---------- basketball: nested markets, prices as strings ----------

    @Test
    void basketballReadsTheMoneylineMarketAndIgnoresTheThreeWayDraw() throws Exception {
        String json = """
                {"success":1,"result":{"247684":{
                  "3Way Result":{"Home":{"Marathon":"1.69"},"Draw":{"Marathon":"14.75"},
                                 "Away":{"Marathon":"2.31"}},
                  "Home/Away":{"Home":{"Pncl":"1.69","bet365":"1.71","Betano":"1.62"},
                               "Away":{"Pncl":"2.31","bet365":"2.20","Betano":"2.40"}}}}}
                """;

        Map<String, OddsSnapshot> parsed = client.parse(Sport.BASKETBALL, mapper.readTree(json));

        OddsSnapshot snapshot = parsed.get("247684");
        assertThat(snapshot.sport()).isEqualTo(Sport.BASKETBALL);
        assertThat(snapshot.twoWay()).isTrue();
        assertThat(snapshot.medianDraw()).isNull();
        assertThat(snapshot.bookmakers()).isEqualTo(3);
        assertThat(snapshot.medianHome()).isCloseTo(1.69, within(1e-9));
        assertThat(snapshot.medianAway()).isCloseTo(2.31, within(1e-9));
        assertThat(snapshot.bestHome()).isCloseTo(1.71, within(1e-9));
        assertThat(snapshot.usable()).isTrue();
        // Two-way overround must not try to include a draw leg.
        assertThat(snapshot.overround()).isBetween(0.0, 0.20);
    }

    @Test
    void basketballPairsPricesByBookmaker() throws Exception {
        String json = """
                {"success":1,"result":{"5":{"Home/Away":{
                  "Home":{"A":"1.50","B":"1.55","OnlyHome":"9.99"},
                  "Away":{"A":"2.60","B":"2.50"}}}}}
                """;

        OddsSnapshot snapshot = client.parse(Sport.BASKETBALL, mapper.readTree(json)).get("5");

        // A book quoting only one side must be dropped, not medianed against another book.
        assertThat(snapshot.bookmakers()).isEqualTo(2);
        assertThat(snapshot.medianHome()).isCloseTo(1.525, within(1e-9));
        assertThat(snapshot.bestHome()).isCloseTo(1.55, within(1e-9));
    }

    @Test
    void basketballWithoutAMoneylineMarketIsDropped() throws Exception {
        String json = """
                {"success":1,"result":{"7":{"3Way Result":{"Home":{"A":"1.5"},"Away":{"A":"2.5"}}}}}
                """;

        assertThat(client.parse(Sport.BASKETBALL, mapper.readTree(json))).isEmpty();
    }

    // ---------- shared ----------

    @Test
    void failedResponseYieldsEmptyMap() throws Exception {
        assertThat(client.parse(Sport.FOOTBALL, mapper.readTree("{\"success\":0,\"error\":\"no plan\"}"))).isEmpty();
        assertThat(client.parse(Sport.FOOTBALL, mapper.readTree("{\"success\":1,\"result\":[]}"))).isEmpty();
        assertThat(client.parse(Sport.BASKETBALL, mapper.readTree("{\"success\":1,\"result\":{}}"))).isEmpty();
    }

    @Test
    void dropsMatchesWithNoUsablePrices() throws Exception {
        String json = """
                {"success":1,"result":{"5":[{"odd_bookmakers":"X","odd_1":null,"odd_x":null,"odd_2":null}]}}
                """;

        assertThat(client.parse(Sport.FOOTBALL, mapper.readTree(json))).isEmpty();
    }
}

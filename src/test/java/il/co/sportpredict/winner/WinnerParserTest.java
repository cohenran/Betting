package il.co.sportpredict.winner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WinnerParserTest {

    private final WinnerParser parser = new WinnerParser(new ObjectMapper());

    @Test
    void parsesPastedHebrewLines() {
        String pasted = """
                1. מכבי חיפה - הפועל באר שבע 2.10 3.25 3.40
                2. 15/08 21:00 ריאל מדריד - ברצלונה 1.95 3.60 3.70
                שורה שאינה משחק
                """;

        List<WinnerLine> lines = parser.parseText(pasted);

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst().homeRaw()).isEqualTo("מכבי חיפה");
        assertThat(lines.getFirst().awayRaw()).isEqualTo("הפועל באר שבע");
        assertThat(lines.getFirst().oddsHome()).isEqualTo(2.10);
        assertThat(lines.getFirst().oddsDraw()).isEqualTo(3.25);
        assertThat(lines.getFirst().oddsAway()).isEqualTo(3.40);
        assertThat(lines.get(1).kickoff()).isNotNull();
        assertThat(lines.get(1).homeRaw()).isEqualTo("ריאל מדריד");
    }

    @Test
    void parsesEventsOutOfArbitraryJson() {
        String json = """
                {"data":{"programs":[{"games":[
                  {"homeTeam":{"name":"מכבי תל אביב"},"awayTeam":{"name":"בית\\"ר ירושלים"},
                   "league":"ליגת העל","startTime":"2026-08-15T18:30:00Z",
                   "odds":[{"name":"1","ratio":1.85},{"name":"X","ratio":3.40},{"name":"2","ratio":4.20}]}
                ]}]}}
                """;

        List<WinnerLine> lines = parser.parseJson(json);

        assertThat(lines).hasSize(1);
        WinnerLine line = lines.getFirst();
        assertThat(line.homeRaw()).isEqualTo("מכבי תל אביב");
        assertThat(line.awayRaw()).contains("ירושלים");
        assertThat(line.competitionRaw()).isEqualTo("ליגת העל");
        assertThat(line.oddsHome()).isEqualTo(1.85);
        assertThat(line.oddsDraw()).isEqualTo(3.40);
        assertThat(line.oddsAway()).isEqualTo(4.20);
        assertThat(line.kickoff()).isNotNull();
    }

    @Test
    void parsesTableRowsFromHtml() {
        String html = """
                <table><tbody>
                  <tr><td>1</td><td>הפועל תל אביב - מכבי נתניה 2.40 3.10 2.95</td></tr>
                  <tr><td>לא משחק</td></tr>
                </tbody></table>
                """;

        List<WinnerLine> lines = parser.parseHtml(html);

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().awayRaw()).isEqualTo("מכבי נתניה");
    }
}

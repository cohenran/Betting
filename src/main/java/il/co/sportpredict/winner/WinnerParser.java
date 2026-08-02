package il.co.sportpredict.winner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever {@link WinnerFetcher} came back with into {@link WinnerLine}s.
 *
 * <p>Three inputs are supported and tried in order of reliability: the site's own JSON,
 * the rendered DOM, and plain text pasted by hand into the UI. All three go through the
 * same shape-based heuristics rather than fixed selectors, because the markup changes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WinnerParser {

    private static final ZoneId ISRAEL = ZoneId.of("Asia/Jerusalem");

    /** "Hapoel - Maccabi 2.10 3.25 3.40", with optional leading line number and time. */
    private static final Pattern LINE = Pattern.compile(
            "(?:(?<no>\\d{1,2})\\s*[.)]\\s*)?"
                    + "(?:(?<date>\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)\\s+)?"
                    + "(?:(?<time>\\d{1,2}:\\d{2})\\s+)?"
                    + "(?<home>[^\\d\\-–|]{2,40}?)\\s*[-–|]\\s*(?<away>[^\\d\\-–|]{2,40}?)\\s+"
                    + "(?<o1>\\d{1,2}\\.\\d{1,2})\\s+(?<ox>\\d{1,2}\\.\\d{1,2})\\s+(?<o2>\\d{1,2}\\.\\d{1,2})");

    private static final List<String> HOME_KEYS =
            List.of("hometeam", "home", "team1", "teama", "first", "participanthome", "homename");
    private static final List<String> AWAY_KEYS =
            List.of("awayteam", "away", "team2", "teamb", "second", "participantaway", "awayname");
    private static final List<String> ODDS_KEYS =
            List.of("odd", "odds", "ratio", "rate", "price", "coefficient", "yachas");
    private static final List<String> TIME_KEYS =
            List.of("kickoff", "starttime", "startdate", "eventdate", "date", "time", "gametime");
    private static final List<String> COMPETITION_KEYS =
            List.of("league", "competition", "tournament", "category", "sport", "leaguename", "eventtype");

    private final ObjectMapper mapper;

    /** Parses the most trustworthy representation available. */
    public List<WinnerLine> parse(WinnerFetcher.Fetched fetched) {
        if (fetched.hasJson()) {
            List<WinnerLine> fromJson = new ArrayList<>();
            for (String payload : fetched.jsonPayloads()) {
                fromJson.addAll(parseJson(payload));
            }
            if (!fromJson.isEmpty()) {
                return renumber(fromJson);
            }
        }
        if (fetched.html() != null) {
            List<WinnerLine> fromHtml = parseHtml(fetched.html());
            if (!fromHtml.isEmpty()) {
                return fromHtml;
            }
        }
        return List.of();
    }

    public List<WinnerLine> parseJson(String json) {
        List<WinnerLine> out = new ArrayList<>();
        try {
            collect(mapper.readTree(json), out);
        } catch (Exception e) {
            log.debug("json parse failed: {}", e.getMessage());
        }
        return out;
    }

    public List<WinnerLine> parseHtml(String html) {
        Document doc = Jsoup.parse(html);
        Map<String, WinnerLine> byPairing = new LinkedHashMap<>();
        for (Element element : doc.select("tr, li, div, p")) {
            // Only leaf-ish blocks: a whole page's text never matches cleanly.
            String text = element.text();
            if (text.length() < 12 || text.length() > 220) {
                continue;
            }
            parseLine(text, byPairing.size() + 1)
                    .ifPresent(line -> byPairing.putIfAbsent(line.homeRaw() + "|" + line.awayRaw(), line));
        }
        return renumber(new ArrayList<>(byPairing.values()));
    }

    /** For text pasted into the UI: one event per line. */
    public List<WinnerLine> parseText(String text) {
        List<WinnerLine> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (raw.isBlank()) {
                continue;
            }
            parseLine(raw.trim(), out.size() + 1).ifPresent(out::add);
        }
        return renumber(out);
    }

    private java.util.Optional<WinnerLine> parseLine(String text, int lineNo) {
        Matcher m = LINE.matcher(text);
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        String home = clean(m.group("home"));
        String away = clean(m.group("away"));
        if (home.isEmpty() || away.isEmpty()) {
            return java.util.Optional.empty();
        }
        int number = m.group("no") != null ? Integer.parseInt(m.group("no")) : lineNo;
        return java.util.Optional.of(new WinnerLine(
                number, null, home, away,
                parseWhen(m.group("date"), m.group("time")),
                parseDouble(m.group("o1")), parseDouble(m.group("ox")), parseDouble(m.group("o2")),
                text));
    }

    private void collect(JsonNode node, List<WinnerLine> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collect(child, out));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        WinnerLine line = buildLine(node, out.size() + 1);
        if (line != null) {
            out.add(line);
            return;
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            collect(children.next(), out);
        }
    }

    /** An object is an event if it names two sides and carries at least two odds. */
    private WinnerLine buildLine(JsonNode node, int lineNo) {
        String home = firstText(node, HOME_KEYS);
        String away = firstText(node, AWAY_KEYS);
        if (home == null || away == null) {
            String[] split = splitPairing(node);
            if (split == null) {
                return null;
            }
            home = split[0];
            away = split[1];
        }
        List<Double> odds = odds(node);
        if (odds.size() < 2) {
            return null;
        }
        return new WinnerLine(
                lineNo,
                firstText(node, COMPETITION_KEYS),
                clean(home), clean(away),
                instant(node),
                odds.get(0),
                odds.size() >= 3 ? odds.get(1) : null,
                odds.size() >= 3 ? odds.get(2) : odds.get(1),
                node.toString().length() > 1500 ? node.toString().substring(0, 1500) : node.toString());
    }

    /** Handles "Team A - Team B" packed into a single name field. */
    private String[] splitPairing(JsonNode node) {
        for (String key : List.of("name", "title", "event", "eventname", "description")) {
            String value = firstText(node, List.of(key));
            if (value == null) {
                continue;
            }
            for (String separator : List.of(" - ", " – ", " vs ", " נגד ")) {
                int at = value.indexOf(separator);
                if (at > 0 && at < value.length() - separator.length()) {
                    return new String[]{value.substring(0, at), value.substring(at + separator.length())};
                }
            }
        }
        return null;
    }

    private List<Double> odds(JsonNode node) {
        List<Double> out = new ArrayList<>();
        // Direct numeric fields (odd1 / ratioX / price2 ...) in document order.
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey().toLowerCase();
            JsonNode value = entry.getValue();
            if (ODDS_KEYS.stream().anyMatch(key::contains)) {
                Double parsed = asOdds(value);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        });
        if (out.size() >= 2) {
            return out;
        }
        // Or an outcomes array: [{name:"1", ratio:2.1}, {name:"X", ...}, {name:"2", ...}]
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) {
                return;
            }
            for (JsonNode child : entry.getValue()) {
                for (String key : ODDS_KEYS) {
                    Double parsed = asOdds(child.get(key));
                    if (parsed != null) {
                        out.add(parsed);
                        break;
                    }
                }
            }
        });
        return out;
    }

    private Double asOdds(JsonNode value) {
        if (value == null) {
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
        // Decimal odds live between 1.01 and ~200; anything else is some other number.
        return (d > 1.0 && d < 200.0) ? d : null;
    }

    private Instant instant(JsonNode node) {
        for (String key : TIME_KEYS) {
            String value = firstText(node, List.of(key));
            if (value == null) {
                continue;
            }
            Instant parsed = parseFlexible(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Instant parseFlexible(String value) {
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            // fall through to the local formats below
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T')).atZone(ISRAEL).toInstant();
        } catch (Exception ignored) {
            // not an ISO local date-time
        }
        Matcher m = Pattern.compile("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?:\\s+(\\d{1,2}):(\\d{2}))?").matcher(v);
        if (m.find()) {
            return parseWhen(m.group(0), null);
        }
        if (v.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(v);
            return v.length() > 10 ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        return null;
    }

    /** Winner prints "dd/MM HH:mm" in local time and omits the year. */
    private Instant parseWhen(String date, String time) {
        if (date == null && time == null) {
            return null;
        }
        LocalDate day = LocalDate.now(ISRAEL);
        if (date != null) {
            String[] parts = date.trim().split("/");
            try {
                int dayOfMonth = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int year = parts.length > 2 ? normalizeYear(Integer.parseInt(parts[2])) : day.getYear();
                LocalDate parsed = LocalDate.of(year, month, dayOfMonth);
                // A day/month more than 6 months in the past means next year's round.
                if (parts.length <= 2 && parsed.isBefore(day.minusMonths(6))) {
                    parsed = parsed.plusYears(1);
                }
                day = parsed;
            } catch (Exception e) {
                return null;
            }
        }
        LocalTime at = LocalTime.of(20, 0);
        if (time != null) {
            try {
                String[] hm = time.trim().split(":");
                at = LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
            } catch (Exception ignored) {
                // keep the default evening kickoff
            }
        }
        return LocalDateTime.of(day, at).atZone(ISRAEL).toInstant();
    }

    private int normalizeYear(int year) {
        return year < 100 ? 2000 + year : year;
    }

    private String firstText(JsonNode node, List<String> keys) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey().toLowerCase().replace("_", "");
            if (!keys.contains(key)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
            if (value.isObject()) {
                for (String nested : List.of("name", "title", "hebrewname", "shortname")) {
                    JsonNode child = value.get(nested);
                    if (child != null && child.isTextual()) {
                        return child.asText();
                    }
                }
            }
        }
        return null;
    }

    private List<WinnerLine> renumber(List<WinnerLine> lines) {
        List<WinnerLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            WinnerLine l = lines.get(i);
            out.add(new WinnerLine(i + 1, l.competitionRaw(), l.homeRaw(), l.awayRaw(), l.kickoff(),
                    l.oddsHome(), l.oddsDraw(), l.oddsAway(), l.rawText()));
        }
        return out;
    }

    private String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private Double parseDouble(String s) {
        try {
            return s == null ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

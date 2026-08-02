package il.co.sportpredict.winner;

import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.util.Names;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hebrew (as printed on Winner forms) -> English (as used by the sports APIs).
 *
 * <p>Seeded from {@code aliases/hebrew-teams.csv}. Anything missing is reported by
 * {@link WinnerService} as an unmatched line, and a one-off mapping can be added through
 * the admin API - stored in {@code team_alias} and reused from then on.
 */
@Component
@Slf4j
public class HebrewAliasDictionary {

    private static final String RESOURCE = "aliases/hebrew-teams.csv";

    private final Map<String, String> bySportAndName = new HashMap<>();

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("{} not found - Hebrew team names will not resolve", RESOURCE);
            return;
        }
        int loaded = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("sport,")) {
                    continue;
                }
                String[] parts = line.split(",", 3);
                if (parts.length < 3) {
                    continue;
                }
                String sport = parts[0].trim().toUpperCase();
                String hebrew = parts[1].trim();
                String english = parts[2].trim();
                bySportAndName.put(key(sport, hebrew), english);
                loaded++;
            }
        } catch (Exception e) {
            log.warn("failed to read {}: {}", RESOURCE, e.getMessage());
        }
        log.info("loaded {} Hebrew team aliases", loaded);
    }

    /** Exact then fuzzy lookup, first for the given sport, then for any sport. */
    public Optional<String> toEnglish(Sport sport, String hebrewName) {
        String normalized = Names.normalize(hebrewName);
        String exact = bySportAndName.get(key(sport.name(), hebrewName));
        if (exact == null) {
            exact = bySportAndName.get(key("ANY", hebrewName));
        }
        if (exact != null) {
            return Optional.of(exact);
        }

        String best = null;
        double bestScore = 0;
        for (Map.Entry<String, String> e : bySportAndName.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            if (!parts[0].equals(sport.name()) && !parts[0].equals("ANY")) {
                continue;
            }
            double score = Names.similarity(normalized, parts[1]);
            if (score > bestScore) {
                bestScore = score;
                best = e.getValue();
            }
        }
        return bestScore >= 0.90 ? Optional.ofNullable(best) : Optional.empty();
    }

    public int size() {
        return bySportAndName.size();
    }

    private String key(String sport, String name) {
        return sport + "|" + Names.normalize(name);
    }
}

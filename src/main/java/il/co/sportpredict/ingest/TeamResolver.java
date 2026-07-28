package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.domain.Team;
import il.co.sportpredict.domain.TeamAlias;
import il.co.sportpredict.repo.TeamAliasRepository;
import il.co.sportpredict.repo.TeamRepository;
import il.co.sportpredict.util.Names;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Maps a provider's team spelling onto one canonical {@link Team} row, so records from
 * api-sports and allsportsapi land on the same fixture.
 *
 * <p>Lookup order: provider id -> exact normalized name -> alias from any provider -> fuzzy
 * match within the sport -> create. Every resolution is cached as a {@link TeamAlias},
 * so the fuzzy path runs at most once per provider spelling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamResolver {

    private static final double FUZZY_THRESHOLD = 0.93;

    private final TeamRepository teams;
    private final TeamAliasRepository aliases;

    public Team resolve(Sport sport, String provider, String externalId, String rawName, String country) {
        String normalized = Names.normalize(rawName);

        if (externalId != null && !externalId.isBlank()) {
            Optional<TeamAlias> byId = aliases.findByProviderAndExternalId(provider, externalId);
            if (byId.isPresent()) {
                return byId.get().getTeam();
            }
        }

        Team team = teams.findBySportAndNormalizedName(sport, normalized)
                .or(() -> aliasByName(sport, normalized))
                .or(() -> fuzzy(sport, rawName))
                .orElseGet(() -> teams.save(new Team(sport, rawName, normalized, country)));

        rememberAlias(team, provider, externalId, rawName, normalized);
        return team;
    }

    /** Adds or updates a manual mapping (used by the Winner unmatched-name UI). */
    public TeamAlias addAlias(Team team, String provider, String rawName) {
        String normalized = Names.normalize(rawName);
        return aliases.findByProviderAndNormalizedName(provider, normalized).stream()
                .findFirst()
                .map(existing -> {
                    existing.setTeam(team);
                    return aliases.save(existing);
                })
                .orElseGet(() -> aliases.save(new TeamAlias(team, provider, null, rawName, normalized)));
    }

    private Optional<Team> aliasByName(Sport sport, String normalized) {
        return aliases.findByNormalizedName(normalized).stream()
                .map(TeamAlias::getTeam)
                .filter(t -> t.getSport() == sport)
                .findFirst();
    }

    private Optional<Team> fuzzy(Sport sport, String rawName) {
        List<Team> candidates = teams.findBySport(sport);
        Team best = null;
        double bestScore = 0;
        for (Team t : candidates) {
            double score = Names.similarity(rawName, t.getName());
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best != null && bestScore >= FUZZY_THRESHOLD) {
            log.debug("fuzzy team match '{}' -> '{}' ({})", rawName, best.getName(), bestScore);
            return Optional.of(best);
        }
        return Optional.empty();
    }

    private void rememberAlias(Team team, String provider, String externalId, String rawName, String normalized) {
        boolean known = externalId != null && !externalId.isBlank()
                ? aliases.findByProviderAndExternalId(provider, externalId).isPresent()
                : !aliases.findByProviderAndNormalizedName(provider, normalized).isEmpty();
        if (!known) {
            aliases.save(new TeamAlias(team, provider, externalId, rawName, normalized));
        }
    }
}

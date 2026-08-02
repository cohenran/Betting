package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.Sport;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface SportsProvider {

    /** Stable short id, stored in fixture_source.provider. */
    String name();

    boolean enabled();

    Set<Sport> supportedSports();

    /** Fetch everything the provider has for the range. Never throws - errors land in the batch. */
    Batch<RawFixture> fetchFixtures(Sport sport, LocalDate from, LocalDate to);

    default Batch<RawFight> fetchFights(LocalDate from, LocalDate to) {
        return Batch.empty();
    }

    /** Result of one fetch: the records, how many HTTP calls it cost, and any error text. */
    record Batch<T>(List<T> items, int requests, String error) {

        public static <T> Batch<T> empty() {
            return new Batch<>(List.of(), 0, null);
        }

        public static <T> Batch<T> of(List<T> items, int requests) {
            return new Batch<>(items, requests, null);
        }

        public static <T> Batch<T> failed(List<T> partial, int requests, String error) {
            return new Batch<>(partial, requests, error);
        }
    }
}

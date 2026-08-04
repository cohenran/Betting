package il.co.sportpredict.ingest;

import il.co.sportpredict.domain.IngestRun;
import il.co.sportpredict.domain.Sport;
import il.co.sportpredict.repo.IngestRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The history backfill must be able to keep a metered provider for the one sport that has
 * no alternative source. Getting this wrong fails silently - the quota drains into sports
 * that had a free provider available.
 */
class IngestServiceRoutingTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 10);

    private IngestService service;

    /** Records which sports it was asked for, and returns nothing. */
    private static class FakeProvider implements SportsProvider {
        private final String name;
        private final Set<Sport> supported;

        FakeProvider(String name, Set<Sport> supported) {
            this.name = name;
            this.supported = supported;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Set<Sport> supportedSports() {
            return supported;
        }

        @Override
        public Batch<RawFixture> fetchFixtures(Sport sport, LocalDate from, LocalDate to) {
            return Batch.of(List.of(), 1);
        }

        @Override
        public Batch<RawFight> fetchFights(LocalDate from, LocalDate to) {
            return Batch.of(List.of(), 1);
        }
    }

    @BeforeEach
    void setUp() {
        FixtureUpsertService fixtureUpsert = mock(FixtureUpsertService.class);
        FightUpsertService fightUpsert = mock(FightUpsertService.class);
        IngestRunRepository runs = mock(IngestRunRepository.class);
        when(fixtureUpsert.upsertAll(anyList())).thenReturn(new FixtureUpsertService.UpsertResult(0, 0, 0));
        when(fightUpsert.upsertAll(anyList())).thenReturn(new FixtureUpsertService.UpsertResult(0, 0, 0));
        when(runs.save(any(IngestRun.class))).thenAnswer(call -> call.getArgument(0));

        List<SportsProvider> providers = List.of(
                new FakeProvider("api-sports", Set.of(Sport.FOOTBALL, Sport.BASKETBALL, Sport.MMA)),
                new FakeProvider("allsports", Set.of(Sport.FOOTBALL, Sport.BASKETBALL)));

        service = new IngestService(providers, fixtureUpsert, fightUpsert, runs);
    }

    @Test
    void withoutRoutingEveryCapableProviderRuns() {
        IngestService.IngestSummary summary =
                service.ingestRange(FROM, TO, EnumSet.of(Sport.FOOTBALL));

        assertThat(summary.jobs()).extracting(IngestService.JobResult::provider)
                .containsExactlyInAnyOrder("api-sports", "allsports");
    }

    @Test
    void routingKeepsTheMeteredProviderOffFootball() {
        Map<Sport, List<String>> routing = Map.of(
                Sport.FOOTBALL, List.of("allsports"),
                Sport.BASKETBALL, List.of("allsports"),
                Sport.MMA, List.of("api-sports"));

        IngestService.IngestSummary football =
                service.ingestRange(FROM, TO, EnumSet.of(Sport.FOOTBALL), routing);
        IngestService.IngestSummary mma =
                service.ingestRange(FROM, TO, EnumSet.of(Sport.MMA), routing);

        assertThat(football.jobs()).extracting(IngestService.JobResult::provider)
                .containsExactly("allsports");
        assertThat(mma.jobs()).extracting(IngestService.JobResult::provider)
                .containsExactly("api-sports");
    }

    @Test
    void routingToAnUnknownProviderYieldsNoJobsRatherThanFallingBack() {
        // The scheduler treats an empty job list as failure and holds the cursor, so this
        // must not quietly run every provider instead.
        IngestService.IngestSummary summary = service.ingestRange(FROM, TO, EnumSet.of(Sport.MMA),
                Map.of(Sport.MMA, List.of("does-not-exist")));

        assertThat(summary.jobs()).isEmpty();
    }
}

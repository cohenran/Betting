package il.co.sportpredict.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamesTest {

    @Test
    void stripsClubNoiseAndAccents() {
        assertThat(Names.normalize("F.C. Barcelona")).isEqualTo("barcelona");
        assertThat(Names.normalize("Atlético Madrid")).isEqualTo("atletico madrid");
        assertThat(Names.normalize("Maccabi Tel Aviv FC")).isEqualTo("maccabi tel aviv");
    }

    @Test
    void matchesProviderSpellingVariants() {
        assertThat(Names.similarity("Maccabi Tel Aviv", "Maccabi Tel-Aviv FC")).isGreaterThan(0.93);
        assertThat(Names.similarity("Hapoel Beer Sheva", "Hapoel Be'er Sheva")).isGreaterThan(0.93);
        assertThat(Names.similarity("Maccabi Haifa", "Maccabi Tel Aviv")).isLessThan(0.93);
    }

    @Test
    void detectsHebrew() {
        assertThat(Names.containsHebrew("מכבי חיפה")).isTrue();
        assertThat(Names.containsHebrew("Maccabi Haifa")).isFalse();
    }

    @Test
    void nameMadeOnlyOfNoiseWordsSurvives() {
        assertThat(Names.normalize("United")).isEqualTo("united");
    }
}

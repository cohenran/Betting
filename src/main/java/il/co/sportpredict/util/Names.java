package il.co.sportpredict.util;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Team / fighter name normalization and fuzzy comparison. */
public final class Names {

    private static final JaroWinklerSimilarity JARO = new JaroWinklerSimilarity();

    /** Club-type noise words that differ between providers for the same club. */
    private static final List<String> NOISE = List.of(
            "fc", "afc", "cf", "sc", "ac", "as", "ss", "ssc", "cd", "sv", "vfl", "vfb", "bsc",
            "club", "futbol", "football", "calcio", "team", "bc", "kk", "bk", "basket", "basketball",
            "fk", "if", "aik", "cska", "united", "utd"
    );

    private Names() {
    }

    /** Lowercase, de-accent, drop punctuation and club noise words. Keeps Hebrew as-is. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
        // Join initials before splitting on punctuation, so "F.C." collapses to "fc"
        // (a noise word) instead of the tokens "f" and "c". Same for Hebrew "מ.ס.".
        s = s.replaceAll("(?<=\\b\\p{L})\\.", "");
        // Hebrew gershayim / geresh and general punctuation
        s = s.replaceAll("[\\u05f3\\u05f4'\"`.,()\\[\\]{}/\\\\|!?:;*+_\\-]", " ");
        s = s.replaceAll("\\s+", " ").trim();

        String[] tokens = s.split(" ");
        StringBuilder out = new StringBuilder();
        for (String t : tokens) {
            if (t.isBlank() || NOISE.contains(t)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(t);
        }
        String result = out.toString();
        // A name made only of noise words (e.g. "United") must not collapse to empty.
        return result.isBlank() ? s : result;
    }

    /** 0..1 similarity of two raw names. Max of full-string Jaro-Winkler and token overlap. */
    public static double similarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0.0;
        }
        if (na.equals(nb)) {
            return 1.0;
        }
        double jaro = JARO.apply(na, nb);
        return Math.max(jaro, tokenOverlap(na, nb));
    }

    private static double tokenOverlap(String na, String nb) {
        Set<String> ta = new LinkedHashSet<>(Arrays.asList(na.split(" ")));
        Set<String> tb = new LinkedHashSet<>(Arrays.asList(nb.split(" ")));
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        long shared = ta.stream().filter(tb::contains).count();
        // Containment rather than Jaccard: "maccabi haifa" vs "maccabi haifa fc reserves".
        return (double) shared / Math.min(ta.size(), tb.size()) * 0.95;
    }

    public static boolean containsHebrew(String s) {
        if (s == null) {
            return false;
        }
        return s.codePoints().anyMatch(c -> c >= 0x0590 && c <= 0x05FF);
    }
}

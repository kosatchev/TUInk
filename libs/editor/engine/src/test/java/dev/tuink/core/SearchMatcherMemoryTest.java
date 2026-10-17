package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SearchMatcherMemoryTest {
    @Test
    void wildcardSearchDoesNotExpandALongUnicodeLineIntoSourceSizedIndexes() throws Exception {
        runProbe("bmp");
    }

    @Test
    void wildcardSearchStreamsSupplementaryCharactersWithOriginalUtf16Ranges() throws Exception {
        runProbe("supplementary");
    }

    private static void runProbe(String mode) throws Exception {
        String classpath = String.join(File.pathSeparator,
            location(SearchMatcherMemoryTest.class), location(SearchMatcher.class), location(SearchOptions.class));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, MemoryProbe.class.getName(), mode
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Wildcard probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        private static final SearchOptions WILDCARD = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);

        public static void main(String[] args) {
            if (args[0].equals("bmp")) {
                int asciiLength = 11 * 1024 * 1024;
                String source = "a".repeat(asciiLength) + "Ā";
                SearchMatcher single = SearchMatcher.compile("?", WILDCARD);
                require(single.countMatches(source) == asciiLength + 1, "Every code point must be counted");
                require(single.findIntersecting(source, 7, 9).equals(List.of(
                    new SearchMatcher.Match(7, 8), new SearchMatcher.Match(8, 9))), "Visible matches must stay bounded");
                require(SearchMatcher.compile("a?a", WILDCARD).countMatches(source) == asciiLength / 3,
                    "Internal question states must reset between non-overlapping matches");
                require(SearchMatcher.compile("??a??", WILDCARD).countMatches(source) == source.length() / 5,
                    "Question lookahead must advance without per-match rescans");
                require(SearchMatcher.compile("a?a*Ā", WILDCARD).findAll(source).equals(List.of(
                    new SearchMatcher.Match(0, source.length()))), "Greedy stars must retain the final Unicode match");
                require(SearchMatcher.compile("*ā", new SearchOptions(true, SearchOptions.MatchMode.WILDCARD))
                    .matchesRange(source, 0, source.length()), "Case folding must preserve source offsets");
            } else {
                int repeats = 3_000_000;
                String source = "🙂a".repeat(repeats);
                SearchMatcher pairs = SearchMatcher.compile("?a", WILDCARD);
                require(pairs.countMatches(source) == repeats, "Supplementary characters consume one question");
                require(pairs.findLastBefore(source, source.length()).equals(
                    new SearchMatcher.Match(source.length() - 3, source.length())), "Last match must use UTF-16 offsets");
                require(pairs.findIntersecting(source, 1, 4).equals(List.of(
                    new SearchMatcher.Match(0, 3), new SearchMatcher.Match(3, 6))), "Visible matches retain their full ranges");
                require(SearchMatcher.compile("*?a", WILDCARD).matchesRange(source, 0, source.length()),
                    "The greedy last segment must stream overlapping Unicode candidates");
                require(SearchMatcher.compile("🙂?🙂", WILDCARD).countMatches(source) == repeats / 2,
                    "Shift-And matches must preserve non-overlap across surrogate pairs");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}

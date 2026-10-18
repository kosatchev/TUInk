package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SaveTargetSuggesterTest {
    @Test
    void keepsPreferredTargetWhenItIsAvailable() {
        Path preferred = Path.of("/work/untitled.txt");

        Path suggestion = SaveTargetSuggester.firstAvailable(preferred, path -> false);

        assertEquals(preferred, suggestion);
    }

    @Test
    void addsFirstAvailableSuffixBeforeExtension() {
        Path preferred = Path.of("/work/report-copy.txt");
        Set<Path> occupied = Set.of(
            preferred,
            Path.of("/work/report-copy-1.txt"),
            Path.of("/work/report-copy-2.txt")
        );

        Path suggestion = SaveTargetSuggester.firstAvailable(preferred, occupied::contains);

        assertEquals(Path.of("/work/report-copy-3.txt"), suggestion);
    }

    @Test
    void addsSuffixToFileWithoutExtension() {
        Path preferred = Path.of("notes");

        Path suggestion = SaveTargetSuggester.firstAvailable(preferred, preferred::equals);

        assertEquals(Path.of("notes-1"), suggestion);
    }

    @Test
    void occupiedRawFilenamesKeepTheirBytesWhenNumbered() {
        assumeTrue(File.separatorChar == '/');
        Path preferred = Path.of(URI.create("file:///tmp/parent-%FE/report-%FF.txt"));
        Path first = Path.of(URI.create("file:///tmp/parent-%FE/report-%FF-1.txt"));
        Path second = Path.of(URI.create("file:///tmp/parent-%FE/report-%FF-2.txt"));

        assertEquals(second, SaveTargetSuggester.firstAvailable(preferred, Set.of(preferred, first)::contains));
    }

    @Test
    void collisionsAreCheckedAfterTruncationAndTheCopyMarkerSurvivesWiderNumbers() {
        Path original = Path.of("a".repeat(251) + ".txt");
        Set<Path> occupied = new HashSet<>();
        occupied.add(Path.of("a".repeat(246) + "-copy.txt"));
        for (int number = 1; number <= 10; number++) {
            String suffix = "-copy-" + number;
            occupied.add(Path.of("a".repeat(255 - suffix.length() - 4) + suffix + ".txt"));
        }
        List<Path> checked = new ArrayList<>();

        Path actual = SaveTargetSuggester.firstAvailable(original, "-copy", candidate -> {
            checked.add(candidate);
            return occupied.contains(candidate);
        });

        assertEquals(Path.of("a".repeat(243) + "-copy-11.txt"), actual);
        assertEquals(12, checked.size());
        assertEquals(actual, checked.getLast());
        assertEquals(occupied, new HashSet<>(checked.subList(0, checked.size() - 1)));
    }

    @Test
    void anOversizedGeneratedPreferredNameIsShortenedBeforeCheckingAvailability() {
        Path preferred = Path.of("a".repeat(300) + ".txt");
        Path expected = Path.of("a".repeat(251) + ".txt");

        assertEquals(expected, SaveTargetSuggester.firstAvailable(preferred, candidate -> {
            assertEquals(expected, candidate);
            return false;
        }));
    }
}

package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
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
}

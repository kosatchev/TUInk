package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuiframe.cell.CellText;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirtyTabSummaryTest {
    @Test
    void listsDirtyTabNamesWhenTheyFit() {
        String summary = DirtyTabSummary.build(
            List.of(Path.of("/work/notes.txt"), Path.of("/work/config.yml")),
            80
        );

        assertEquals("Unsaved (2): notes.txt, config.yml", summary);
    }

    @Test
    void replacesHiddenNamesWithRemainingCount() {
        String expected = "Unsaved (4): a.txt, b.txt, … +2";
        String summary = DirtyTabSummary.build(
            List.of(
                Path.of("a.txt"),
                Path.of("b.txt"),
                Path.of("c.txt"),
                Path.of("d.txt")
            ),
            CellText.width(expected)
        );

        assertEquals(expected, summary);
    }

    @Test
    void disambiguatesDuplicateNamesAndUnnamedTabs() {
        assertEquals(
            List.of("/one/config.yml", "/two/config.yml", "--no name-- #1", "--no name-- #2"),
            DirtyTabSummary.displayLabels(
                java.util.Arrays.asList(
                    Path.of("/one/config.yml"),
                    Path.of("/two/config.yml"),
                    null,
                    null
                )
            )
        );
    }
}

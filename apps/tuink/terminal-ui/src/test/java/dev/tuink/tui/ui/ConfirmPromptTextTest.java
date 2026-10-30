package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfirmPromptTextTest {
    private static final String PREFIX = "Overwrite: ";
    private static final String SUFFIX = " ? (y/N)";

    @Test
    void keepsTheFullPathWhenItFits() {
        assertEquals("Overwrite: /work/note.txt ? (y/N)",
            ConfirmPromptText.fileQuestion(PREFIX, Path.of("/work/note.txt"), SUFFIX, 60));
    }

    @Test
    void longPathsKeepTheBasenameAndDirectoryTailBeforeTheQuestion() {
        String text = ConfirmPromptText.fileQuestion(PREFIX,
            Path.of("/very/long/parent/another/parent/project/victim.txt"), SUFFIX, 54);

        assertTrue(text.startsWith(PREFIX + "…"));
        assertTrue(text.contains("project/victim.txt"));
        assertTrue(text.endsWith(SUFFIX));
        assertTrue(CellText.width(text) <= 54);
    }

    @Test
    void veryLongBasenamesRemainActionableAtNormalWidth() {
        String name = "begin-" + "long-".repeat(40) + "-ending.txt";
        String text = ConfirmPromptText.fileQuestion(PREFIX, Path.of("/parent", name), SUFFIX, 54);
        ConfirmBarView.Placement placed = ConfirmBarView.build(text, 80)
            .place(new Object(), 1, new Rect(0, 0, 80, 3));

        assertTrue(text.contains("begin-"));
        assertTrue(text.contains("…"));
        assertTrue(text.contains("-ending.txt"));
        assertTrue(text.endsWith(SUFFIX));
        assertTrue(CellText.width(text) <= 54);
        assertTrue(placed.decisionsAvailable());
    }

    @Test
    void unicodeCompactionKeepsWholeGraphemesAndCellBudget() {
        String name = "👩‍💻e\u0301中".repeat(30) + ".txt";
        String text = ConfirmPromptText.fileQuestion(PREFIX, Path.of("/parent", name), SUFFIX, 54);

        assertTrue(text.contains("👩‍💻e\u0301中"));
        assertTrue(text.endsWith(".txt" + SUFFIX));
        assertFalse(text.contains("�"));
        assertEquals(text, CellText.clip(text, 0, CellText.width(text)));
        assertTrue(CellText.width(text) <= 54);
    }

    @Test
    void anUnidentifiableFileCannotBecomeAnApparentlyCompleteQuestion() {
        String text = ConfirmPromptText.fileQuestion(PREFIX, Path.of("/parent/long-filename.txt"),
            SUFFIX, CellText.width(PREFIX + SUFFIX) + 6);
        int cols = CellText.width(PREFIX + SUFFIX) + 6 + 26;
        ConfirmBarView view = ConfirmBarView.build(text, cols);

        assertTrue(view.row().contains("Enlarge"));
        assertFalse(view.place(new Object(), 1, new Rect(0, 0, cols, 3)).decisionsAvailable());
    }
}

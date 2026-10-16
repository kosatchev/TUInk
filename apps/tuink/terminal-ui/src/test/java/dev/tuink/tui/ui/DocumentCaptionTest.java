package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.CellText;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocumentCaptionTest {
    @Test
    void fullPathsFitNormallyAndBasenameTakesPriorityOverDirectories() {
        Path file = Path.of("one", "two", "three", "report.java");
        assertEquals(file.toString(), DocumentCaption.fitPath(file, 80));
        assertEquals("report.java", DocumentCaption.fitPath(file, 11));
        for (int width = 13; width <= 24; width++) {
            String caption = DocumentCaption.fitPath(file, width);
            assertTrue(caption.endsWith("report.java"), caption);
            assertTrue(CellText.width(caption) <= width, caption);
        }
    }

    @Test
    void longNamesPreserveExtensionsAndUnicodeGraphemes() {
        Path file = Path.of("directory", "e\u0301👩‍💻界-very-long-name.java");
        for (int width = 7; width <= 20; width++) {
            String caption = DocumentCaption.fitPath(file, width);
            assertTrue(caption.endsWith(".java"), caption);
            assertTrue(CellText.width(caption) <= width, caption);
        }
        assertEquals(0, DocumentCaption.extensionCells(".gitignore"));
        assertEquals(TabBarView.NO_NAME_LABEL, DocumentCaption.fitPath(null, 30));
    }
}

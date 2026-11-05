package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellText;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.markdown.MarkdownVisualCopy;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownVisualCopyPartialTableTest {
    @Test void selectingAnOrdinaryCellCannotLeakAnUnselectedImagesDisplayTextOrDestination() {
        var ready = ready();
        String projection = ready.document().projection().text();
        int start = projection.indexOf("public");
        assertTrue(start >= 0);
        String copied = MarkdownVisualCopy.copy(ready, start, start + "public".length());
        assertTrue(copied.contains("public"), copied);
        assertFalse(copied.contains("Hidden"), copied);
        assertFalse(copied.contains("[image:"), copied);
        assertFalse(copied.contains("https://private.test"), copied);
        assertFrameIntact(copied);
    }

    @Test void selectingTheImageCellKeepsItsOwnDisplayDecorationWithoutCopyingTheNeighbour() {
        var ready = ready();
        String projection = ready.document().projection().text();
        int start = projection.indexOf("Hidden");
        assertTrue(start >= 0);
        String copied = MarkdownVisualCopy.copy(ready, start, start + "Hidden".length());
        assertTrue(copied.contains("Hidden"), copied);
        assertTrue(copied.contains("[image:"), copied);
        assertTrue(copied.contains("https://private.test"), copied);
        assertFalse(copied.contains("public"), copied);
        assertFrameIntact(copied);
    }

    @Test void selectingPublicTextCannotCopyAnUnselectedImagesDecorationInTheSameCell() {
        var ready = sameCellReady();
        String projection = ready.document().projection().text();
        int start = projection.indexOf("public");
        assertTrue(start >= 0);
        String copied = MarkdownVisualCopy.copy(ready, start, start + "public".length());
        assertTrue(copied.contains("public"), copied);
        assertFalse(copied.contains("Hidden"), copied);
        assertFalse(copied.contains("[image:"), copied);
        assertFalse(copied.contains("https://private.test"), copied);
        assertFrameIntact(copied);
    }

    @Test void selectingAnImagesAltKeepsOnlyThatAtomsDecorationInsideASharedCell() {
        var ready = sameCellReady();
        String projection = ready.document().projection().text();
        int start = projection.indexOf("Hidden");
        assertTrue(start >= 0);
        String copied = MarkdownVisualCopy.copy(ready, start, start + "Hidden".length());
        assertTrue(copied.contains("Hidden"), copied);
        assertTrue(copied.contains("[image:"), copied);
        assertTrue(copied.contains("https://private.test"), copied);
        assertFalse(copied.contains("public"), copied);
        assertFrameIntact(copied);
    }

    private static MarkdownLayout.Result ready() {
        var snapshot = new DocumentSnapshot(new DocumentId("visual-copy-table-image"), 0,
            List.of("| A | B |", "|---|---|", "| ![Hidden](https://private.test) | public |"));
        return new MarkdownLayout(80).layout(new CommonmarkMarkdownParser().parse(snapshot));
    }

    private static MarkdownLayout.Result sameCellReady() {
        var snapshot = new DocumentSnapshot(new DocumentId("visual-copy-shared-table-cell"), 0,
            List.of("| A |", "|---|", "| ![Hidden](https://private.test) public |"));
        return new MarkdownLayout(80).layout(new CommonmarkMarkdownParser().parse(snapshot));
    }

    private static void assertFrameIntact(String copied) {
        assertTrue(copied.startsWith("┌"), copied);
        assertTrue(copied.endsWith("┘"), copied);
        assertEquals(1, copied.lines().mapToInt(CellText::width).distinct().count(), copied);
    }
}

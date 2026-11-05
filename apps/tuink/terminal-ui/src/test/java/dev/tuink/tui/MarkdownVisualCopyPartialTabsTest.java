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

class MarkdownVisualCopyPartialTabsTest {
    @Test void selectingATabInsideInlineCodeKeepsItsDisplayedWidthAfterAnUnselectedCharacter() {
        assertSelectedTabMatchesPreparedCells("`a\tb`");
    }

    @Test void selectingATabInsideInlineCodeKeepsThePhaseOfUnselectedPrecedingInlineText() {
        assertSelectedTabMatchesPreparedCells("left `\tb`");
    }

    private static void assertSelectedTabMatchesPreparedCells(String source) {
        var snapshot = new DocumentSnapshot(new DocumentId("visual-copy-partial-inline-tab"), 0, List.of(source));
        var ready = new MarkdownLayout(80).layout(new CommonmarkMarkdownParser().parse(snapshot));
        int selected = ready.document().projection().text().indexOf('\t');
        assertTrue(selected >= 0, "The parser must preserve this inline-code tab");
        var line = ready.lines().stream().filter(row -> row.text().contains("\t")).findFirst().orElseThrow();
        int column = CellText.visualColumnAt(line.text(), line.text().indexOf('\t'));
        int displayedCells = 4 - column % 4;
        assertEquals(3, displayedCells, "The fixture must not accidentally use zero-phase tab stops");
        assertEquals(" ".repeat(displayedCells), MarkdownVisualCopy.copy(ready, selected, selected + 1));
    }
}

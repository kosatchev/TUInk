package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.PreviewSelectionTest.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.style.UiStyles;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreviewKeyboardViewportTest {
    @Test void downVisitsEveryRowOfATallDiagramAndPagesKeepOneRowOfOverlap() {
        var state = ready(tallDiagram(65), new Rect(0, 0, 21, 8));
        for (int row = 1; row < 65; row++) {
            state.moveSelection(PreviewSelection.Move.DOWN, false);
            assertEquals(row, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret().row());
            assertTrue(row >= state.top && row < state.top + 7);
            assertEquals(0, state.cursorOffset(), "Every drawn row still targets the Mermaid source");
        }
        state.moveSelection(PreviewSelection.Move.START, false);
        assertEquals(0, state.top);
        state.moveSelection(PreviewSelection.Move.PAGE_DOWN, false);
        assertEquals(6, state.top);
        assertEquals(6, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret().row());
        state.moveSelection(PreviewSelection.Move.PAGE_DOWN, false);
        assertEquals(12, state.top);
        assertEquals(12, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret().row());
        state.moveSelection(PreviewSelection.Move.PAGE_UP, false);
        assertEquals(6, state.top);
        assertEquals(6, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret().row());
        state.close();
    }

    @Test void wheelAndPanCarryVisualCaretSoTheNextArrowDoesNotJumpBack() {
        var state = ready(tallDiagram(65), new Rect(0, 0, 21, 8));
        state.scroll(3, 3);
        assertEquals(3, state.top);
        assertEquals(3, state.left);
        assertEquals(new PreviewSelection.VisualPosition(3, 3), state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret());
        state.moveSelection(PreviewSelection.Move.RIGHT, false);
        assertEquals(3, state.top);
        assertEquals(3, state.left);
        assertEquals(new PreviewSelection.VisualPosition(3, 4), state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret());
        state.moveSelection(PreviewSelection.Move.DOWN, false);
        assertEquals(new PreviewSelection.VisualPosition(4, 4), state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret());
        assertEquals(3, state.top);
        state.close();
    }

    @Test void panningPreservesCopySelectionWhileSourceActionsFollowTheVisibleCaret() {
        var lines = new ArrayList<MarkdownLayout.Line>();
        StringBuilder semantic = new StringBuilder();
        for (int row = 0; row < 12; row++) {
            if (row > 0) semantic.append('\n');
            int start = semantic.length(); String text = "row" + row;
            semantic.append(text); lines.add(line(text, 0, text.length(), start, semantic.length()));
        }
        var state = ready(result(semantic.toString(), lines.toArray(MarkdownLayout.Line[]::new)), new Rect(0, 0, 21, 5));
        state.selection.selectRange(semantic.toString(), 0, 4);
        state.scroll(3, 0);
        assertEquals("row0", state.selectedText());
        assertEquals(semantic.indexOf("row3") + 4, state.cursorOffset());
        assertEquals(3, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret().row());
        state.close();
    }

    @Test void readonlyCaretAndPositionAreVisibleAtANonzeroOrigin() {
        var state = ready(tallDiagram(12), new Rect(4, 2, 30, 6));
        state.selection.selectAtColumn(state.result, 7, 15, false);
        state.scroll(3, 3);
        var placement = state.placement(MarkdownPreviewState.Placement.Focus.CONTENT);
        Point caret = MarkdownPreviewPainter.cursor(placement);
        assertNotNull(caret);
        assertTrue(placement.bounds().contains(caret));
        var buffer = new CellBuffer(12, 80);
        MarkdownPreviewPainter.paint(new Canvas(buffer), placement, UiStyles.forTheme(UiStyles.Theme.CYAN), true);
        assertTrue(buffer.get(caret.y(), caret.x()).style().underline());
        state.close();
    }

    @Test void aLongSearchFieldScrollsItsOwnCaretInsteadOfClippingTheQueryEnd() {
        var state = ready(tallDiagram(12), new Rect(4, 2, 45, 6));
        state.setSearchQuery("needle".repeat(30)); state.beginSearch();
        state.searchField.moveEnd();
        var placement = state.placement(MarkdownPreviewState.Placement.Focus.QUERY);
        var caret = MarkdownPreviewPainter.cursor(placement);
        assertNotNull(caret);
        assertTrue(placement.searchInput().bounds().contains(caret));
        assertEquals(state.bounds.y(), caret.y());
        state.close();
    }

    private static MarkdownLayout.Result tallDiagram(int rows) {
        String source = "flowchart TD\n A-->B";
        String display = "│" + " ".repeat(118) + "│";
        var runs = List.of(new MarkdownLayout.Run(0, display.length(), MarkdownLayout.Role.DIAGRAM, SOURCE, 0, source.length()));
        var lines = new MarkdownLayout.Line[rows];
        for (int row = 0; row < rows; row++) lines[row] = new MarkdownLayout.Line(display, runs, "", SOURCE, "mermaid");
        return result(source, lines);
    }

    private static MarkdownPreviewState ready(MarkdownLayout.Result result, Rect bounds) {
        var state = new MarkdownPreviewState(); state.active = true; state.result = result; state.bounds = bounds;
        state.requestedId = ID; state.requestedRevision = result.document().revision(); state.requestedWidth = result.columns();
        return state;
    }
}

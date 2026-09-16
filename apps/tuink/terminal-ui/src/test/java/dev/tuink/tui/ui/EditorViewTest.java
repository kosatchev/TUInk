package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.input.KeyEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorViewTest {
    private static final int CONTENT_TOP_ROW = 1;
    private static final int CONTENT_ROWS = 3;
    private static final int GUTTER_WIDTH = 3;
    private static final int DEFAULT_COLUMNS = 40;

    @Test
    void shiftClickExtendsFromPreviousClickWithoutTriggeringAdjacentDoubleClick() {
        Fixture fixture = fixture("word");

        assertFalse(fixture.press(0, 1, false));
        fixture.release();
        assertEquals(new Position(0, 1), fixture.state().getCursor());
        assertNull(fixture.state().getSelectionAnchor());

        assertFalse(fixture.press(0, 2, true));
        fixture.release();

        assertSelection(fixture, new Position(0, 1), new Position(0, 2), "o");
    }

    @Test
    void shiftClickExtendsBackwardFromTheCurrentCursor() {
        Fixture fixture = fixture("abcdefg");
        fixture.engine().moveCursorTo(new Position(0, 5));

        assertFalse(fixture.press(0, 2, true));
        fixture.release();

        assertSelection(fixture, new Position(0, 5), new Position(0, 2), "cde");
    }

    @Test
    void shiftClickPreservesAnExistingSelectionAnchor() {
        Fixture fixture = fixture("abcdefgh");
        fixture.engine().restoreSelection(new Position(0, 6), new Position(0, 1));

        assertFalse(fixture.press(0, 4, true));
        fixture.release();

        assertSelection(fixture, new Position(0, 1), new Position(0, 4), "bcd");
    }

    @Test
    void ordinaryClickStillReplacesAndCollapsesAnExistingSelection() {
        Fixture fixture = fixture("abcdefgh");
        fixture.engine().restoreSelection(new Position(0, 6), new Position(0, 1));

        assertFalse(fixture.press(0, 4, false));
        fixture.release();

        assertEquals(new Position(0, 4), fixture.state().getCursor());
        assertNull(fixture.state().getSelectionAnchor());
        assertFalse(fixture.state().hasSelection());
    }

    @Test
    void shiftClickMapsScrolledUnicodeCellsToGraphemeSafeTextOffsets() {
        Fixture fixture = fixture(
            16,
            "hidden",
            "0123456789ab😀cdQRSTUVWXYZ",
            "tail"
        );
        fixture.engine().moveCursorTo(new Position(2, 1));
        fixture.state().setTopLine(1);
        fixture.state().setLeftColumn(10);
        fixture.state().setLeftVisualColumn(10);

        assertFalse(fixture.press(0, 4, true));
        fixture.release();

        assertSelection(fixture, new Position(2, 1), new Position(1, 14), "cdQRSTUVWXYZ\nt");
        assertEquals(1, fixture.state().getTopLine());
        assertEquals(10, fixture.state().getLeftVisualColumn());
    }

    private static Fixture fixture(String... lines) {
        return fixture(DEFAULT_COLUMNS, lines);
    }

    private static Fixture fixture(int columns, String... lines) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, lines.length == 0 ? "" : lines[0]);
        for (int index = 1; index < lines.length; index++) {
            buffer.insertLine(index, lines[index]);
        }
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        TerminalEditorTextLayout textLayout = new TerminalEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, textLayout);
        return new Fixture(
            model,
            state,
            textLayout,
            engine,
            new EditorView(CONTENT_TOP_ROW),
            columns
        );
    }

    private static void assertSelection(
        Fixture fixture,
        Position anchor,
        Position cursor,
        String selectedText
    ) {
        assertEquals(anchor, fixture.state().getSelectionAnchor());
        assertEquals(cursor, fixture.state().getCursor());
        assertTrue(fixture.state().hasSelection());
        assertEquals(selectedText, fixture.model().getTextRange(anchor, cursor));
    }

    private record Fixture(
        TextModel model,
        EditorState state,
        TerminalEditorTextLayout textLayout,
        EditorEngine engine,
        EditorView view,
        int columns
    ) {
        private boolean press(int viewportRow, int viewportColumn, boolean extendSelection) {
            KeyEvent.MouseEvent press = new KeyEvent.MouseEvent(
                CONTENT_TOP_ROW + viewportRow,
                GUTTER_WIDTH + viewportColumn,
                KeyEvent.MouseKind.PRESS,
                KeyEvent.MouseButton.LEFT
            );
            return view.handleMousePress(
                press,
                model,
                state,
                plan(),
                CONTENT_ROWS,
                engine,
                CONTENT_TOP_ROW,
                extendSelection
            );
        }

        private void release() {
            view.handleMouseRelease(engine);
        }

        private RenderPlan plan() {
            return new RenderPlan(
                List.of(),
                "",
                "",
                null,
                null,
                null,
                null,
                0,
                0,
                true,
                false,
                false,
                CONTENT_ROWS,
                state.getTopLine(),
                model.lineCount(),
                state.getLeftColumn(),
                state.getLeftVisualColumn(),
                textLayout.maxVisualLineWidth(4),
                columns,
                GUTTER_WIDTH,
                0
            );
        }
    }
}

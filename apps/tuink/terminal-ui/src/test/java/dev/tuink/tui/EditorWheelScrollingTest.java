package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.TerminalEditorTextLayout;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorWheelScrollingTest {
    @Test
    void wheelStopsAtTheLastFullPageWithoutMovingTheTextCursor() throws ReflectiveOperationException {
        Fixture fixture = fixture(20);
        Position cursor = new Position(4, 2);
        fixture.state().setCursor(cursor);
        fixture.state().setTopLine(14);

        fixture.wheel(3, 6, false);
        assertEquals(14, fixture.state().getTopLine());
        assertEquals(cursor, fixture.state().getCursor());

        fixture.wheel(Integer.MIN_VALUE, 6, false);
        assertEquals(0, fixture.state().getTopLine());
        fixture.wheel(Integer.MAX_VALUE, 6, false);
        assertEquals(14, fixture.state().getTopLine());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void wrappedWheelClampsVisualRowsAndPreservesTheTextCursor() throws ReflectiveOperationException {
        Fixture fixture = fixture(10);
        Position cursor = new Position(1, 3);
        fixture.state().setCursor(cursor);

        fixture.wheel(Integer.MAX_VALUE, 6, true);
        // Each 20-cell line uses three 10-cell rows, including the empty cursor row.
        assertEquals(8, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());

        fixture.wheel(3, 6, true);
        assertEquals(8, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
        assertEquals(cursor, fixture.state().getCursor());

        fixture.wheel(Integer.MIN_VALUE, 6, true);
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void growingViewportAndShortDocumentsCannotLeaveAnEmptyPage() throws ReflectiveOperationException {
        Fixture fixture = fixture(20);
        fixture.state().setTopLine(14);

        fixture.wheel(3, 18, false);
        assertEquals(2, fixture.state().getTopLine());

        Fixture shortDocument = fixture(2);
        shortDocument.wheel(3, 6, false);
        assertEquals(0, shortDocument.state().getTopLine());
    }

    private static Fixture fixture(int lineCount) {
        SimpleLineBuffer lines = new SimpleLineBuffer();
        lines.setLine(0, "abcdefghijklmnopqrst");
        for (int line = 1; line < lineCount; line++) {
            lines.insertLine(line, "abcdefghijklmnopqrst");
        }
        TextModel model = new TextModel(lines);
        return new Fixture(model, new TerminalEditorTextLayout(model), new EditorState());
    }

    private record Fixture(TextModel model, TerminalEditorTextLayout textLayout, EditorState state) {
        private void wheel(int delta, int visibleRows, boolean wrapped) throws ReflectiveOperationException {
            int textWidth = 10;
            int top = wrapped
                ? textLayout.wrappedRowsBefore(state.getTopLine(), textWidth, false, 4) + state.getTopWrapSegment()
                : state.getTopLine();
            int total = wrapped ? textLayout.totalWrappedRows(textWidth, false, 4) : model.lineCount();
            RenderPlan plan = new RenderPlan(
                List.of(), "", "", null, null, null, null, 0, 0, true, wrapped, false,
                visibleRows, top, total, state.getLeftColumn(), state.getLeftVisualColumn(), 20, 14, 3, 1
            );
            Method handler = TerminalEditorApp.class.getDeclaredMethod(
                "applyWheelVerticalScroll", TextModel.class, EditorTextLayout.class, EditorState.class,
                RenderPlan.class, int.class, int.class
            );
            handler.setAccessible(true);
            handler.invoke(null, model, textLayout, state, plan, delta, 4);
        }
    }
}

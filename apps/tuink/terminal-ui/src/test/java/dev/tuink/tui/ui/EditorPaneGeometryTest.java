package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.TuiSessionTestSupport;
import dev.tuink.tui.render.RenderRasterizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditorPaneGeometryTest {
    private static final int ROWS = 10;
    private static final int COLS = 60;
    private static final Rect PANE = new Rect(15, 2, 30, 5);

    @Test
    void sourceAndScrollbarsStayInsidePaneWhileWindowTextRetainsTerminalCoordinates() {
        Fixture fixture = fixture();
        RenderPlan plan = fixture.plan(false);
        try (RenderRasterizer rasterizer = new RenderRasterizer()) {
            var cells = rasterizer.rasterize(plan, ROWS, COLS, PANE.y());
            assertEquals("M", cells.get(0, 0).grapheme());
            assertEquals("S", cells.get(ROWS - 1, 0).grapheme());
            assertEquals("1", cells.get(PANE.y(), PANE.x() + 1).grapheme());
            assertEquals("A", cells.get(PANE.y(), plan.scrollLayout().contentBounds().x()).grapheme());
            assertEquals("中", cells.get(PANE.y(), plan.scrollLayout().contentBounds().x() + 1).grapheme());
            for (int row = PANE.y(); row < PANE.bottomExclusive(); row++) {
                for (int col = 0; col < PANE.x(); col++) assertEquals(" ", cells.get(row, col).grapheme());
                for (int col = PANE.rightExclusive(); col < COLS; col++) assertEquals(" ", cells.get(row, col).grapheme());
            }
            var bars = EditorScrollbars.from(plan, ROWS, COLS, PANE.y());
            assertNotNull(bars.vertical());
            assertNotNull(bars.horizontal());
            assertEquals(PANE.rightExclusive() - 1, bars.vertical().track().x());
            assertTrue(PANE.contains(bars.horizontal().track()));
            assertFalse(" ".equals(cells.get(bars.vertical().track().y(), bars.vertical().track().x()).grapheme()));
        }
    }

    @Test
    void pointerIgnoresNeighborPaneAndMapsUnicodeCellsWithinSource() {
        Fixture fixture = fixture();
        RenderPlan plan = fixture.plan(false);
        EditorView view = fixture.view();
        var outside = view.handlePointer(pointer(PANE.y(), PANE.x() - 1, PointerEvent.Kind.PRESS),
            fixture.engine(), plan, ROWS, COLS, plan.contentRows(), PANE.y(), false);
        assertFalse(outside.consumed());
        assertEquals(new Position(0, 0), fixture.state().getCursor());

        int emojiColumn = plan.scrollLayout().contentBounds().x() + 3;
        var press = pointer(PANE.y(), emojiColumn, PointerEvent.Kind.PRESS);
        assertEquals(new Position(0, 2), view.mouseToTextPosition(press, fixture.model(), fixture.textLayout(),
            fixture.state(), plan, plan.contentRows(), PANE.y()));
        assertTrue(view.handlePointer(press, fixture.engine(), plan, ROWS, COLS, plan.contentRows(), PANE.y(), false).textInteraction());
        assertEquals(new Position(0, 2), fixture.state().getCursor());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void cursorAndWrappingUsePaneWidthInsteadOfTerminalWidth() {
        Fixture fixture = fixture();
        fixture.state().setCursor(new Position(0, 4));
        RenderPlan visible = fixture.plan(false);
        assertTrue(visible.cursorInViewport());
        assertEquals(visible.scrollLayout().contentBounds().x() + 5, visible.cursorCol());
        assertEquals(new EditorView.CursorPosition(PANE.y() + 1, visible.cursorCol() + 1),
            fixture.view().cursorFromPlan(visible, PANE.y()));

        fixture.state().setCursor(new Position(0, 40));
        RenderPlan clipped = fixture.plan(false);
        assertFalse(clipped.cursorInViewport());
        assertEquals(clipped.scrollLayout().contentBounds().rightExclusive() - 1, clipped.cursorCol());

        RenderPlan wrapped = fixture.plan(true);
        assertTrue(wrapped.cursorInViewport());
        assertTrue(wrapped.cursorRow() > 0);
        assertTrue(wrapped.totalLines() > fixture.model().lineCount());
        assertTrue(PANE.contains(wrapped.cursorCol(), PANE.y() + wrapped.cursorRow()));
    }

    @Test
    void wheelOverNeighborPaneDoesNotScrollSourceAndSourceWheelStillWorks() {
        Fixture fixture = fixture();
        RenderPlan plan = fixture.plan(false);
        assertFalse(fixture.view().handleWheel(pointer(PANE.y() + 1, 2, PointerEvent.Kind.SCROLL_DOWN),
            fixture.textLayout(), fixture.state(), plan, plan.contentRows(), PANE.y()));
        assertEquals(0, fixture.state().getTopLine());
        assertTrue(fixture.view().handleWheel(pointer(PANE.y() + 1, PANE.x() + 4, PointerEvent.Kind.SCROLL_DOWN),
            fixture.textLayout(), fixture.state(), plan, plan.contentRows(), PANE.y()));
        assertTrue(fixture.state().getTopLine() > 0);
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind,
            kind == PointerEvent.Kind.PRESS ? PointerEvent.Button.LEFT : PointerEvent.Button.NONE, Set.of());
    }

    private static Fixture fixture() {
        SimpleLineBuffer lines = new SimpleLineBuffer();
        lines.setLine(0, "A中😀\tB" + "abcdefghij".repeat(5));
        for (int i = 1; i < 6; i++) lines.insertLine(i, "abcdefghij".repeat(6));
        TextModel model = new TextModel(lines);
        EditorState state = new EditorState();
        var textLayout = new TerminalEditorTextLayout(model);
        var engine = new EditorEngine(model, state, textLayout);
        var view = new EditorView();
        view.bindCommands(TuiSessionTestSupport.commands(new TabSession(model, state, textLayout, engine)));
        return new Fixture(model, state, textLayout, engine, view);
    }

    private record Fixture(TextModel model, EditorState state, TerminalEditorTextLayout textLayout,
        EditorEngine engine, EditorView view) {
        RenderPlan plan(boolean wrapped) {
            var layout = EditorLayout.resolve(model, textLayout, state, ROWS, COLS, PANE.y(), 0,
                wrapped, false, 4, PANE);
            return RenderPlanBuilder.standalone(line -> List.of(), textLayout).build(model, state, ROWS, COLS,
                "MENU", "STATUS", "", SearchOptions.DEFAULT, null, null, null, null, wrapped, false, layout);
        }
    }
}

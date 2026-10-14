package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorViewScrollbarTest {
    private static final int ROWS = 9;
    private static final int COLS = 14;
    private static final int CONTENT_TOP = 1;
    private static final int CONTENT_ROWS = 6;
    private static final int GUTTER = 3;

    @Test
    void verticalDragKeepsTheGrabPointAndContinuesOutsideTheTrackWithoutMovingTheCursor() {
        Fixture fixture = fixture(10);
        Position cursor = new Position(3, 2);
        fixture.state().setCursor(cursor);

        EditorView.ScrollPointerResult press = fixture.pointer(3, COLS - 1, false);
        assertEquals(EditorView.ScrollAxis.VERTICAL, press.dragAxis());
        assertEquals(2, press.dragThumbOffset());
        assertEquals(0, fixture.state().getTopLine());

        EditorView.ScrollPointerResult stationary = fixture.drag(3, COLS - 1, press, false);
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(press.dragThumbOffset(), stationary.dragThumbOffset());

        fixture.drag(Integer.MAX_VALUE, Integer.MIN_VALUE, press, false);
        assertEquals(4, fixture.state().getTopLine());
        assertEquals(cursor, fixture.state().getCursor());

        fixture.drag(Integer.MIN_VALUE, Integer.MAX_VALUE, press, false);
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void clickingTheVerticalTrackPagesWithoutStartingADrag() {
        Fixture fixture = fixture(20);
        Position cursor = new Position(2, 1);
        fixture.state().setCursor(cursor);

        EditorView.ScrollPointerResult press = fixture.pointer(6, COLS - 1, false);

        assertTrue(press.consumed());
        assertEquals(EditorView.ScrollAxis.NONE, press.dragAxis());
        assertEquals(CONTENT_ROWS - 1, fixture.state().getTopLine());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void horizontalDragMapsVisualOffsetsAcrossTabsAndWideGraphemes() {
        Fixture fixture = fixture(1, "A\t😀abcdefghijklm");
        Position cursor = new Position(0, 4);
        fixture.state().setCursor(cursor);

        EditorView.ScrollPointerResult press = fixture.pointer(CONTENT_TOP + CONTENT_ROWS, 6, false);
        assertEquals(EditorView.ScrollAxis.HORIZONTAL, press.dragAxis());
        assertEquals(3, press.dragThumbOffset());

        fixture.drag(-20, 8, press, false);
        assertEquals(4, fixture.state().getLeftVisualColumn());
        assertEquals(2, fixture.state().getLeftColumn());
        assertEquals(cursor, fixture.state().getCursor());

        fixture.drag(-20, COLS + 50, press, false);
        assertEquals(10, fixture.state().getLeftVisualColumn());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void wrappedDocumentCannotCaptureAnInvisibleHorizontalScrollbar() {
        Fixture fixture = fixture(10);

        EditorView.ScrollPointerResult press = fixture.pointer(CONTENT_TOP + CONTENT_ROWS, 6, true);

        assertFalse(press.consumed());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void verticalTrackUsesWrappedRowsAndPreservesTheTextCursor() {
        Fixture fixture = fixture(10);
        Position cursor = new Position(1, 4);
        fixture.state().setCursor(cursor);

        EditorView.ScrollPointerResult press = fixture.pointer(6, COLS - 1, true);

        assertTrue(press.consumed());
        // Each line has three wrapped rows; paging five rows reaches line 1, segment 2.
        assertEquals(1, fixture.state().getTopLine());
        assertEquals(2, fixture.state().getTopWrapSegment());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void horizontalCaptureEndsWhenWrappingHidesTheTrack() {
        Fixture fixture = fixture(10);
        EditorView.ScrollPointerResult press = fixture.pointer(CONTENT_TOP + CONTENT_ROWS, 6, false);
        assertEquals(EditorView.ScrollAxis.HORIZONTAL, press.dragAxis());

        EditorView.ScrollPointerResult drag = fixture.drag(3, 12, press, true);

        assertFalse(drag.consumed());
        assertEquals(EditorView.ScrollAxis.NONE, drag.dragAxis());
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void captureEndsWhenResizeLeavesNoRoomForTheTrack() {
        Fixture fixture = fixture(10);
        EditorView.ScrollPointerResult press = fixture.pointer(3, COLS - 1, false);
        assertEquals(EditorView.ScrollAxis.VERTICAL, press.dragAxis());

        EditorView.ScrollPointerResult drag = fixture.view().handleScrollbarPointer(
            5, COLS - 1, 2, COLS, CONTENT_TOP, fixture.plan(false), fixture.model(),
            fixture.textLayout(), fixture.state(), press.dragAxis(), press.dragThumbOffset()
        );

        assertFalse(drag.consumed());
        assertEquals(EditorView.ScrollAxis.NONE, drag.dragAxis());
        assertEquals(0, fixture.state().getTopLine());
    }

    @Test
    void tinyTracksAreHiddenAndCannotResetAScrolledDocument() {
        Fixture fixture = fixture(20);
        fixture.state().setTopLine(10);
        fixture.state().setLeftVisualColumn(7);
        RenderPlan plan = fixture.plan(false);

        assertNull(EditorScrollbars.from(plan, 3, COLS, CONTENT_TOP).vertical());
        var vertical = fixture.view().handleScrollbarPointer(
            1, COLS - 1, 3, COLS, CONTENT_TOP, plan, fixture.model(), fixture.textLayout(), fixture.state(),
            EditorView.ScrollAxis.VERTICAL, 0
        );
        assertFalse(vertical.consumed());
        assertEquals(10, fixture.state().getTopLine());

        int narrowCols = GUTTER + 2;
        assertNull(EditorScrollbars.from(plan, ROWS, narrowCols, CONTENT_TOP).horizontal());
        var horizontal = fixture.view().handleScrollbarPointer(
            7, GUTTER, ROWS, narrowCols, CONTENT_TOP, plan, fixture.model(), fixture.textLayout(), fixture.state(),
            EditorView.ScrollAxis.HORIZONTAL, 0
        );
        assertFalse(horizontal.consumed());
        assertEquals(7, fixture.state().getLeftVisualColumn());
    }

    @Test
    void staleOversizedOffsetsAreClampedBeforeApplyingScrollbarInput() {
        Fixture fixture = fixture(10);
        Position cursor = new Position(3, 2);
        fixture.state().setCursor(cursor);
        fixture.state().setTopLine(Integer.MAX_VALUE);
        fixture.state().setLeftColumn(Integer.MAX_VALUE);
        fixture.state().setLeftVisualColumn(Integer.MAX_VALUE);

        EditorView.ScrollPointerResult vertical = fixture.pointer(3, COLS - 1, false);
        assertTrue(vertical.consumed());
        assertEquals(4, fixture.state().getTopLine());

        EditorView.ScrollPointerResult horizontal = fixture.pointer(CONTENT_TOP + CONTENT_ROWS, 8, false);
        assertTrue(horizontal.consumed());
        assertEquals(10, fixture.state().getLeftVisualColumn());
        assertEquals(10, fixture.state().getLeftColumn());
        assertEquals(cursor, fixture.state().getCursor());
    }

    @Test
    void horizontalTrackIsAbsentWhenTheRenderPlanHasNoDocumentRows() {
        RenderPlan plan = new RenderPlan(
            List.of(), "", "", null, null, null, null, 0, 0, true, false, false,
            0, 0, 20, 0, 7, 200, COLS, GUTTER, 1,
            ScrollLayout.vertical(new Rect(GUTTER, CONTENT_TOP, COLS - GUTTER - 1, 0), 20)
        );

        assertNull(EditorScrollbars.from(plan, 3, COLS, CONTENT_TOP).horizontal());
    }

    @Test
    void rendererAndPointerUseTheResolvedGeometryThroughResizeAndOverlayFrames() {
        Fixture fixture = fixture(3, "long text ".repeat(5));
        RenderPlanBuilder builder = new RenderPlanBuilder(ignored -> List.of(), fixture.textLayout());
        for (boolean wrapped : new boolean[] {false, true}) {
            for (int rows = 1; rows <= 8; rows++) {
                for (int columns = 1; columns <= 14; columns++) {
                    for (int overlays : new int[] {0, 2}) {
                        EditorLayout layout = EditorLayout.resolve(fixture.model(), fixture.textLayout(), fixture.state(),
                            rows, columns, CONTENT_TOP, overlays, wrapped, true, 4);
                        RenderPlan plan = builder.build(fixture.model(), fixture.state(), rows, columns, "", "",
                            null, SearchOptions.DEFAULT, overlays == 0 ? null : "prompt\ncontrols",
                            null, null, null, wrapped, true, layout);
                        EditorScrollbars bars = EditorScrollbars.from(plan, rows, columns, CONTENT_TOP);
                        assertSame(layout.scrollLayout(), plan.scrollLayout());
                        assertSame(plan.scrollLayout(), bars.layout());
                        assertEquals(layout.scrollbarWidth() != 0, bars.vertical() != null);
                        assertEquals(layout.horizontalScrollbarHeight() != 0, bars.horizontal() != null);
                        assertEquals(layout.contentRows(), bars.layout().contentBounds().height());
                        assertEquals(layout.contentColumns(), bars.layout().contentBounds().width());
                        for (var bar : new dev.tuiframe.scroll.Scrollbar[] {bars.vertical(), bars.horizontal()}) {
                            if (bar != null) {
                                var track = bar.track();
                                assertTrue(fixture.view().handleScrollbarPointer(track.y(), track.x(), rows,
                                    columns, CONTENT_TOP, plan, fixture.model(), fixture.textLayout(), fixture.state()).consumed());
                            }
                        }
                    }
                }
            }
        }
    }

    private static Fixture fixture(int lineCount) {
        return fixture(lineCount, "abcdefghijklmnopqrst");
    }

    private static Fixture fixture(int lineCount, String text) {
        SimpleLineBuffer lines = new SimpleLineBuffer();
        lines.setLine(0, text);
        for (int i = 1; i < lineCount; i++) {
            lines.insertLine(i, text);
        }
        TextModel model = new TextModel(lines);
        return new Fixture(new EditorView(), model, new TerminalEditorTextLayout(model), new EditorState());
    }

    private record Fixture(EditorView view, TextModel model, TerminalEditorTextLayout textLayout, EditorState state) {
        private EditorView.ScrollPointerResult pointer(int row, int col, boolean wrapped) {
            return view.handleScrollbarPointer(
                row, col, ROWS, COLS, CONTENT_TOP, plan(wrapped), model, textLayout, state
            );
        }

        private EditorView.ScrollPointerResult drag(
            int row, int col, EditorView.ScrollPointerResult press, boolean wrapped
        ) {
            return view.handleScrollbarPointer(
                row, col, ROWS, COLS, CONTENT_TOP, plan(wrapped), model, textLayout, state,
                press.dragAxis(), press.dragThumbOffset()
            );
        }

        private RenderPlan plan(boolean wrapped) {
            int width = COLS - GUTTER - 1;
            int topRow = wrapped
                ? textLayout.wrappedRowsBefore(state.getTopLine(), width, false, 4) + state.getTopWrapSegment()
                : state.getTopLine();
            int totalRows = wrapped ? textLayout.totalWrappedRows(width, false, 4) : model.lineCount();
            return new RenderPlan(
                List.of(), "", "", null, null, null, null, 0, 0, true, wrapped, false,
                CONTENT_ROWS, topRow, totalRows, state.getLeftColumn(),
                state.getLeftVisualColumn(), 20, COLS, GUTTER, 1,
                scrollLayout(wrapped)
            );
        }

        private ScrollLayout scrollLayout(boolean wrapped) {
            Rect content = new Rect(GUTTER, CONTENT_TOP, COLS - GUTTER - 1, CONTENT_ROWS);
            return new ScrollLayout(content, content, null,
                new Rect(COLS - 1, CONTENT_TOP, 1, CONTENT_ROWS),
                wrapped ? null : new Rect(GUTTER, CONTENT_TOP + CONTENT_ROWS, content.width(), 1));
        }
    }
}

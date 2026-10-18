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
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
    void modifiedDragOutsideViewportScrollsAndExtendsSelectionAtTheClampedRow() {
        String[] lines = new String[20];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = "row-" + index;
        }
        Fixture fixture = fixture(lines);
        fixture.press(1, 1, false);
        PointerEvent drag = new PointerEvent(
            CONTENT_TOP_ROW + CONTENT_ROWS + 10,
            GUTTER_WIDTH + 1,
            PointerEvent.Kind.DRAG,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.ALT)
        );

        fixture.view().handleMouseDrag(
            drag,
            fixture.model(),
            fixture.state(),
            fixture.plan(),
            CONTENT_ROWS,
            fixture.engine(),
            CONTENT_TOP_ROW
        );

        assertEquals(6, fixture.state().getTopLine());
        assertEquals(new Position(1, 1), fixture.state().getSelectionAnchor());
        assertEquals(new Position(8, 1), fixture.state().getCursor());
        assertEquals(Set.of(KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.ALT), drag.modifiers());
        fixture.release();
        assertFalse(fixture.view().isMouseSelecting());
        assertTrue(fixture.state().hasSelection());
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

    @Test
    void stationaryEdgeDragKeepsScrollingUntilReleaseOrTheDocumentEnd() {
        Fixture fixture = fixture(java.util.stream.IntStream.range(0, 30)
            .mapToObj(index -> "row-" + index).toArray(String[]::new));
        fixture.press(1, 1, false);
        fixture.drag(CONTENT_TOP_ROW + CONTENT_ROWS + 10, GUTTER_WIDTH + 1);
        assertEquals(6, fixture.state().getTopLine());
        assertEquals(50_000_000L, fixture.view().nanosUntilSelectionScroll(fixture.clock().get()));
        assertFalse(fixture.tick(49_999_999L));
        assertTrue(fixture.tick(1L));
        assertEquals(12, fixture.state().getTopLine());
        assertEquals(new Position(1, 1), fixture.state().getSelectionAnchor());
        for (int index = 0; index < 10; index++) {
            fixture.tick(50_000_000L);
        }
        assertEquals(27, fixture.state().getTopLine());
        assertEquals(-1L, fixture.view().nanosUntilSelectionScroll(fixture.clock().get()));
        fixture.release();
        assertFalse(fixture.tick(1_000_000_000L));
        assertTrue(fixture.state().hasSelection());
    }

    @Test
    void stationaryHorizontalDragScrollsBothDirectionsAndInteriorMovementStopsTheTimer() {
        Fixture fixture = fixture("0123456789".repeat(40), "0123456789".repeat(40), "tail");
        fixture.press(0, 1, false);
        fixture.drag(CONTENT_TOP_ROW + 1, DEFAULT_COLUMNS - 1);
        int firstLeft = fixture.state().getLeftVisualColumn();
        assertTrue(firstLeft > 0);
        assertTrue(fixture.tick(50_000_000L));
        assertTrue(fixture.state().getLeftVisualColumn() > firstLeft);
        fixture.drag(CONTENT_TOP_ROW + 1, GUTTER_WIDTH);
        int left = fixture.state().getLeftVisualColumn();
        assertTrue(fixture.tick(50_000_000L));
        assertTrue(fixture.state().getLeftVisualColumn() < left);
        fixture.drag(CONTENT_TOP_ROW + 1, GUTTER_WIDTH + 15);
        assertEquals(-1L, fixture.view().nanosUntilSelectionScroll(fixture.clock().get()));
        assertFalse(fixture.tick(50_000_000L));
    }

    @Test
    void cancellingCaptureDisarmsAutoscrollWithoutDiscardingTheSelection() {
        Fixture fixture = fixture("x".repeat(400));
        fixture.press(0, 1, false);
        fixture.drag(CONTENT_TOP_ROW + 1, DEFAULT_COLUMNS - 1);
        Position cursor = fixture.state().getCursor();
        fixture.view().cancelMouseSelection();
        assertEquals(-1L, fixture.view().nanosUntilSelectionScroll(fixture.clock().get()));
        assertFalse(fixture.tick(50_000_000L));
        assertEquals(cursor, fixture.state().getCursor());
        assertTrue(fixture.state().hasSelection());
    }

    @Test
    void stationaryVerticalDragAdvancesWrappedSegmentsOfTheSameLine() {
        Fixture fixture = fixture("x".repeat(500));
        fixture.press(0, 1, false);
        fixture.view().handleMouseDrag(new PointerEvent(CONTENT_TOP_ROW + CONTENT_ROWS - 1,
            GUTTER_WIDTH + 1, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT, Set.of()),
            fixture.model(), fixture.state(), fixture.plan(true), CONTENT_ROWS, fixture.engine(), CONTENT_TOP_ROW);
        int firstSegment = fixture.state().getTopWrapSegment();
        assertTrue(firstSegment > 0);
        assertTrue(fixture.view().scrollSelectionIfDue(fixture.model(), fixture.state(), fixture.plan(true),
            CONTENT_ROWS, fixture.engine(), CONTENT_TOP_ROW, fixture.clock().addAndGet(50_000_000L)));
        assertEquals(0, fixture.state().getTopLine());
        assertTrue(fixture.state().getTopWrapSegment() > firstSegment);
        assertEquals(0, fixture.state().getLeftVisualColumn());
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
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        return new Fixture(
            model,
            state,
            textLayout,
            engine,
            new EditorView(clock::get),
            columns,
            clock
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
        int columns,
        AtomicLong clock
    ) {
        private boolean press(int viewportRow, int viewportColumn, boolean extendSelection) {
            PointerEvent press = new PointerEvent(
                CONTENT_TOP_ROW + viewportRow,
                GUTTER_WIDTH + viewportColumn,
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT,
                extendSelection ? Set.of(KeyStroke.Modifier.SHIFT) : Set.of()
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

        private void drag(int row, int column) {
            view.handleMouseDrag(new PointerEvent(row, column, PointerEvent.Kind.DRAG,
                PointerEvent.Button.LEFT, Set.of()), model, state, plan(), CONTENT_ROWS, engine, CONTENT_TOP_ROW);
        }

        private boolean tick(long elapsedNanos) {
            return view.scrollSelectionIfDue(model, state, plan(), CONTENT_ROWS, engine, CONTENT_TOP_ROW,
                clock.addAndGet(elapsedNanos));
        }

        private RenderPlan plan() {
            return plan(false);
        }

        private RenderPlan plan(boolean wrapLines) {
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
                wrapLines,
                false,
                CONTENT_ROWS,
                state.getTopLine(),
                model.lineCount(),
                state.getLeftColumn(),
                state.getLeftVisualColumn(),
                textLayout.maxVisualLineWidth(4),
                columns,
                GUTTER_WIDTH,
                0,
                ScrollLayout.vertical(new Rect(GUTTER_WIDTH, CONTENT_TOP_ROW,
                    Math.max(0, columns - GUTTER_WIDTH), CONTENT_ROWS), 0)
            );
        }
    }
}

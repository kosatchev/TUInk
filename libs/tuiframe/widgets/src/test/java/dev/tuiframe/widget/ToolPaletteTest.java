package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolPaletteTest {
    private final Rect bounds = new Rect(2, 3, 30, 12);
    private final Rect viewport = new Rect(0, 0, 40, 20);
    private final FocusContext context = new FocusContext(true, true,
        new FocusSpace(new Point(10, 5), new Rect(0, 0, 40, 20)));
    private ToolPalette.Placement place(ToolPalette.State state) {
        return new ToolPalette("Search", true, false, true, state, Set.of()).layout(bounds, viewport);
    }
    private PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of());
    }
    @Test void hintMenuEmitsAnOwnerControlledToggleForBothVisibilityStates() {
        for (boolean visible : new boolean[] {true, false}) {
            var state = new ToolPalette.State(null, true, 8);
            var placed = new ToolPalette("Files", true, false, visible, state, Set.of()).layout(bounds, viewport);
            var cells = new CellBuffer(20, 40); placed.paintMenu(new Canvas(cells), role -> null);
            var text = new StringBuilder();
            for (int col = placed.menuBounds().x(); col < placed.menuBounds().rightExclusive(); col++)
                text.append(cells.get(placed.menuBounds().bottomExclusive() - 1, col).grapheme());
            assertTrue(text.toString().contains(visible ? "Hide hints" : "Show hints"));
            var change = placed.handle(KeyStroke.of(KeyStroke.Key.ENTER), context).output().orElseThrow();
            assertEquals(ToolPalette.Action.TOGGLE_HINTS, change.action());
            assertFalse(change.state().menuOpen());
        }
    }
    @Test void hintsReleaseTheirRowWhenHiddenAndNeverDisplaceTheMinimumContent() {
        for (int height = 0; height <= 12; height++) for (boolean visible : new boolean[] {true, false}) {
            var bounds = new Rect(2, 3, 30, height);
            var body = ToolPalette.bodyLayout(bounds, visible, 2);
            assertTrue(bounds.contains(body.contentBounds())); assertTrue(bounds.contains(body.hintBounds()));
            assertEquals(height, body.contentBounds().height() + body.hintBounds().height());
            assertEquals(visible && height > 2 ? 1 : 0, body.hintBounds().height());
            assertTrue(body.contentBounds().intersection(body.hintBounds()).isEmpty());
            var cells = new CellBuffer(18, 40); cells.fill(new Cell("·", CellStyle.DEFAULT));
            var info = new CellStyle(CellColor.WHITE, CellColor.BLUE, false, false);
            body.paintHint(new Canvas(cells), role -> role.equals(WidgetThemeKeys.NOTICE_INFO) ? info : null, "Enter Open");
            for (int row = 0; row < cells.rows(); row++) for (int col = 0; col < cells.cols(); col++) {
                if (body.hintBounds().contains(col, row)) assertEquals(info, cells.get(row, col).style());
                else assertEquals("·", cells.get(row, col).grapheme());
            }
        }
    }
    @Test void translatedTitleDragRemainsCapturedOutsideTheHeaderAndEndsOnRelease() {
        var press = place(ToolPalette.State.initial()).handle(pointer(8, 16, PointerEvent.Kind.PRESS), context, 0L);
        assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());
        var drag = place(press.output().orElseThrow().state()).handle(pointer(16, 16, PointerEvent.Kind.DRAG), context, 500_000_000L);
        assertEquals(ToolPalette.Action.DRAG, drag.output().orElseThrow().action());
        assertEquals(11, drag.output().orElseThrow().row());
        var release = place(drag.output().orElseThrow().state()).handle(pointer(16, 16, PointerEvent.Kind.RELEASE), context, 510_000_000L);
        assertEquals(ToolPalette.Action.DROP, release.output().orElseThrow().action());
        assertEquals(PointerCaptureRequest.RELEASE, release.pointerCaptureRequest());
        assertFalse(release.output().orElseThrow().state().pressed());
    }
    @Test void arrowClickTogglesButCancellingThePressNeverTogglesOrReorders() {
        var press = place(ToolPalette.State.initial()).handle(pointer(8, 12, PointerEvent.Kind.PRESS), context);
        var active = place(press.output().orElseThrow().state());
        assertEquals(ToolPalette.Action.TOGGLE, active.handle(pointer(8, 12, PointerEvent.Kind.RELEASE), context).output().orElseThrow().action());
        var cancelled = active.cancelPointerCapture(PointerCaptureCancellation.RESIZE);
        assertEquals(ToolPalette.Action.NONE, cancelled.output().orElseThrow().action());
        assertEquals(PointerCaptureRequest.RELEASE, cancelled.pointerCaptureRequest());
    }
    @Test void aSingleTitleClickTogglesOnReleaseWhileCancellationKeepsItsState() {
        var pressed = place(ToolPalette.State.initial()).handle(pointer(8, 18, PointerEvent.Kind.PRESS), context);
        assertEquals(ToolPalette.Action.NONE, pressed.output().orElseThrow().action());
        var held = place(pressed.output().orElseThrow().state());
        assertEquals(ToolPalette.Action.TOGGLE, held.handle(pointer(8, 18, PointerEvent.Kind.RELEASE), context).output().orElseThrow().action());
        assertEquals(ToolPalette.Action.NONE, held.cancelPointerCapture(PointerCaptureCancellation.RESIZE).output().orElseThrow().action());
    }
    @Test void menuPrefersItsOwnerBodyAndPreservesTheHeaderInNarrowWindows() {
        var opened = place(ToolPalette.State.initial()).handle(new TextInput(" "), context).output().orElseThrow().state();
        var wide = new ToolPalette("Search", true, false, true, opened, Set.of()).layout(bounds, new Rect(0, 0, 80, 20));
        assertTrue(bounds.contains(wide.menuBounds()));
        assertEquals(bounds.rightExclusive() - 1, wide.menuBounds().rightExclusive());
        assertTrue(wide.bounds().intersection(wide.menuBounds()).isEmpty());
        var compact = new ToolPalette("Search", true, false, true, opened, Set.of()).layout(new Rect(2, 3, 16, 12), viewport);
        assertTrue(new Rect(2, 4, 16, 11).contains(compact.menuBounds()));
        var tiny = new ToolPalette("Search", true, false, true, opened, Set.of())
            .layout(new Rect(2, 2, 30, 1), new Rect(0, 0, 40, 5));
        assertTrue(tiny.bounds().intersection(tiny.menuBounds()).isEmpty());
        assertEquals(2, tiny.menuBounds().height());
        var last = tiny.handle(KeyStroke.of(KeyStroke.Key.END), context).output().orElseThrow();
        assertEquals(ToolPalette.Action.TOGGLE_HINTS,
            place(last.state()).handle(KeyStroke.of(KeyStroke.Key.ENTER), context).output().orElseThrow().action());
    }
    @Test void defaultHeaderHasAnAccentAndItsFocusedVariantUsesTheSameHeaderRole() {
        var cells = new CellBuffer(20, 40); var header = place(ToolPalette.State.initial());
        header.paint(new Canvas(cells), role -> null, WidgetState.NORMAL);
        assertEquals(CellColor.CYAN, cells.get(bounds.y(), bounds.x() + 2).style().fg());
        var normal = cells.get(bounds.y(), bounds.x() + 2).style();
        assertEquals(normal, cells.get(bounds.y(), bounds.rightExclusive() - 2).style());
        header.paint(new Canvas(cells), role -> null, WidgetState.FOCUSED);
        assertNotEquals(normal, cells.get(bounds.y(), bounds.x() + 2).style());
    }
    @Test void menusStayInTheViewportAndAvoidTheHeaderWheneverThereIsAnotherRow() {
        var state = new ToolPalette.State(null, true, 0);
        for (int width : new int[] {0, 1, 8, 16, 30, 60}) for (int height = 0; height <= 20; height++) {
            var view = new Rect(2, 3, width, height);
            int y = view.y() + height / 2;
            var owner = new Rect(view.x(), y, Math.min(30, width), view.bottomExclusive() - y);
            var header = new ToolPalette("Search", true, false, true, state, Set.of()).layout(owner, view);
            assertTrue(view.contains(header.menuBounds()), view.toString());
            if (height >= 2) assertTrue(header.menuBounds().intersection(header.bounds()).isEmpty(), view.toString());
            var cells = new CellBuffer(26, 70); cells.fill(new Cell("·", CellStyle.DEFAULT));
            header.paintMenu(new Canvas(cells), role -> null);
            for (int row = 0; row < cells.rows(); row++) for (int col = 0; col < cells.cols(); col++)
                if (!view.contains(col, row)) assertEquals("·", cells.get(row, col).grapheme());
        }
    }
    @Test void menuIsKeyboardAccessibleAndFitsAboveABottomHeader() {
        var opened = place(ToolPalette.State.initial()).handle(new TextInput(" "), context).output().orElseThrow();
        var bottom = new ToolPalette("Search", true, false, true, opened.state(), Set.of()).layout(new Rect(0, 19, 30, 1), viewport);
        assertTrue(viewport.contains(bottom.menuBounds()));
        var next = bottom.handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN), context).output().orElseThrow();
        var choice = place(next.state()).handle(KeyStroke.of(KeyStroke.Key.ENTER), context).output().orElseThrow();
        assertEquals(ToolPalette.Action.MAXIMIZE, choice.action());
        assertFalse(choice.state().menuOpen());
    }
    @Test void aHeaderReflowBetweenPressAndReleaseKeepsTheOriginalArrowClick() {
        var press = place(ToolPalette.State.initial()).handle(pointer(8, 12, PointerEvent.Kind.PRESS), context);
        var moved = new ToolPalette("Search", true, false, true, press.output().orElseThrow().state(), Set.of())
            .layout(new Rect(2, 0, 30, 12), viewport);
        var release = moved.handle(pointer(8, 12, PointerEvent.Kind.RELEASE), context);
        assertEquals(ToolPalette.Action.TOGGLE, release.output().orElseThrow().action());
        assertEquals(PointerCaptureRequest.RELEASE, release.pointerCaptureRequest());
    }
    @Test void stationaryHoldArmsAtHalfASecondAndOnlyMotionStartsAReorder() {
        var press = place(ToolPalette.State.initial()).handle(pointer(8, 16, PointerEvent.Kind.PRESS), context, 0L).output().orElseThrow();
        assertEquals(400_000_000L, press.state().nanosUntilWakeup(100_000_000L));
        assertTrue(press.state().tick(499_999_999L).isEmpty());
        var armed = press.state().tick(500_000_000L).orElseThrow();
        assertEquals(ToolPalette.Action.NONE, armed.action()); assertFalse(armed.state().dragging());
        assertEquals(-1L, armed.state().nanosUntilWakeup(500_000_000L));
        var moved = place(armed.state()).handle(pointer(12, 16, PointerEvent.Kind.DRAG), context, 500_000_000L).output().orElseThrow();
        assertEquals(ToolPalette.Action.DRAG, moved.action()); assertTrue(moved.state().dragging());
        var drop = place(moved.state()).handle(pointer(12, 16, PointerEvent.Kind.RELEASE), context, 510_000_000L).output().orElseThrow();
        assertEquals(ToolPalette.Action.DROP, drop.action());
        var cancelled = place(armed.state()).cancelPointerCapture(PointerCaptureCancellation.RESIZE).output().orElseThrow();
        assertTrue(cancelled.state().tick(1_000_000_000L).isEmpty());
        assertEquals(-1L, cancelled.state().nanosUntilWakeup(1_000_000_000L));
    }
    @Test void earlyMotionCancelsUntilReleaseEvenAfterReturningAndWaiting() {
        for (var point : new int[][] {{8, 17}, {9, 16}}) {
            var press = place(ToolPalette.State.initial()).handle(pointer(8, 16, PointerEvent.Kind.PRESS), context, 0L).output().orElseThrow();
            var moved = place(press.state()).handle(pointer(point[0], point[1], PointerEvent.Kind.DRAG), context, 499_999_999L).output().orElseThrow();
            assertEquals(ToolPalette.Action.NONE, moved.action()); assertFalse(moved.state().dragging());
            assertEquals(-1L, moved.state().nanosUntilWakeup(1_000_000_000L));
            assertTrue(moved.state().tick(1_000_000_000L).isEmpty());
            var returned = place(moved.state()).handle(pointer(8, 16, PointerEvent.Kind.DRAG), context, 1_100_000_000L).output().orElseThrow();
            var release = place(returned.state()).handle(pointer(8, 16, PointerEvent.Kind.RELEASE), context, 1_200_000_000L).output().orElseThrow();
            assertEquals(ToolPalette.Action.NONE, release.action()); assertFalse(release.state().pressed());
            assertEquals(PointerCaptureRequest.RELEASE, place(returned.state()).handle(pointer(8, 16, PointerEvent.Kind.RELEASE), context, 1_200_000_000L).pointerCaptureRequest());
        }
    }
    @Test void aStationaryHoldOrDisclosureDragNeverStartsAReorder() {
        for (int column : new int[] {12, 16}) {
            var press = place(ToolPalette.State.initial()).handle(pointer(8, column, PointerEvent.Kind.PRESS), context, 0L).output().orElseThrow();
            if (column == 12) {
                assertTrue(press.state().tick(500_000_000L).isEmpty());
                var moved = place(press.state()).handle(pointer(12, column, PointerEvent.Kind.DRAG), context, 1_100_000_000L).output().orElseThrow();
                assertFalse(moved.state().dragging()); assertTrue(moved.state().tick(2_000_000_000L).isEmpty());
            } else {
                var armed = press.state().tick(500_000_000L).orElseThrow();
                assertFalse(armed.state().dragging());
                assertEquals(ToolPalette.Action.NONE, place(armed.state())
                    .handle(pointer(8, column, PointerEvent.Kind.RELEASE), context, 510_000_000L).output().orElseThrow().action());
            }
        }
    }
}

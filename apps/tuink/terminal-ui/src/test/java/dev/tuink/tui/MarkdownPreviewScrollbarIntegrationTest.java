package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.ScrollStyle;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real frame/input pipeline: track painting and pointer admission share one layout. */
class MarkdownPreviewScrollbarIntegrationTest {
    @TempDir Path directory;

    @Test void thumbsDragOutsideBothTracksAndStationaryGrabDoesNotQuantizeTheOffset() throws Exception {
        try (var window = window()) {
            var state = preview(window);
            state.scroll(7, 9); window.frame();
            var original = state.selection.snapshot();
            var vertical = state.scrollbars().vertical();
            Point grab = thumb(vertical);
            window.event(pointer(PointerEvent.Kind.PRESS, grab));
            assertTrue(state.pointerCaptureActive());
            window.event(pointer(PointerEvent.Kind.DRAG, grab));
            assertEquals(7, state.top, "a stationary grab must preserve the exact viewport offset");
            window.event(pointer(PointerEvent.Kind.DRAG, new Point(0, window.height + 200)));
            assertEquals(state.result.lines().size() - state.contentBounds().height(), state.top);
            assertFalse(state.selection.snapshot().selected(), "scrollbar input must not start copy selection");
            window.event(pointer(PointerEvent.Kind.RELEASE, new Point(0, window.height + 200)));
            assertFalse(state.pointerCaptureActive());
            assertNotEquals(original.caret(), state.selection.caret(), "the visible readonly caret travels with the viewport");

            var horizontal = state.scrollbars().horizontal();
            assertNotNull(horizontal);
            window.event(pointer(PointerEvent.Kind.PRESS, thumb(horizontal)));
            assertTrue(state.pointerCaptureActive());
            window.event(pointer(PointerEvent.Kind.DRAG, new Point(window.width + 400, 0)));
            assertEquals(state.result.extent() - state.contentBounds().width(), state.left);
            window.event(pointer(PointerEvent.Kind.RELEASE, new Point(window.width + 400, 0)));
            assertFalse(state.pointerCaptureActive());
        }
    }

    @Test void trackClicksPageAndWheelMovesThreeRowsOrColumnsWithoutSelectingChrome() throws Exception {
        try (var window = window()) {
            var state = preview(window);
            var vertical = state.scrollbars().vertical();
            int expected = ScrollInteraction.pageScrollFromTrackClick(state.top,
                vertical.track().bottomExclusive() - 1, vertical.track().y(), vertical.visibleItems(), vertical.totalItems());
            window.event(pointer(PointerEvent.Kind.PRESS,
                new Point(vertical.track().x(), vertical.track().bottomExclusive() - 1)));
            assertEquals(expected, state.top);
            assertFalse(state.pointerCaptureActive(), "track paging does not capture a thumb");
            int before = state.top;
            window.event(pointer(PointerEvent.Kind.SCROLL_DOWN, new Point(state.contentBounds().x(), state.contentBounds().y())));
            assertEquals(before + 3, state.top);
            window.event(pointer(PointerEvent.Kind.SCROLL_RIGHT, new Point(state.contentBounds().x(), state.contentBounds().y())));
            assertEquals(3, state.left);
            var horizontal = state.scrollbars().horizontal();
            int previous = state.left;
            int horizontalPage = ScrollInteraction.pageScrollFromTrackClick(previous,
                horizontal.track().rightExclusive() - 1, horizontal.track().x(), horizontal.visibleItems(), horizontal.totalItems());
            window.event(pointer(PointerEvent.Kind.PRESS,
                new Point(horizontal.track().rightExclusive() - 1, horizontal.track().y())));
            assertEquals(horizontalPage, state.left);
            var selected = state.selection.snapshot();
            var corner = new Point(state.scrollbars().vertical().track().x(), state.scrollbars().horizontal().track().y());
            window.event(pointer(PointerEvent.Kind.PRESS, corner));
            assertEquals(selected, state.selection.snapshot());
            assertFalse(state.pointerCaptureActive());
        }
    }

    @Test void resizeKeyboardTabSwitchAndClosingEndTheCapturedGesture() throws Exception {
        try (var window = window()) {
            var state = preview(window);
            window.event(pointer(PointerEvent.Kind.PRESS, thumb(state.scrollbars().vertical())));
            window.width -= 8;
            window.event(new ResizeEvent(window.width, window.height));
            assertFalse(state.pointerCaptureActive());
            window.await(state::ready);
            window.event(pointer(PointerEvent.Kind.PRESS, thumb(state.scrollbars().vertical())));
            window.command(TuinkCommand.SEARCH_OPEN);
            assertFalse(state.pointerCaptureActive());
            assertTrue(state.searchVisible);
            window.command(TuinkCommand.SEARCH_CLOSE);
            window.event(pointer(PointerEvent.Kind.PRESS, thumb(state.scrollbars().vertical())));
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            assertFalse(state.pointerCaptureActive());
            assertFalse(state.active);
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.await(state::ready);
            window.event(pointer(PointerEvent.Kind.PRESS, thumb(state.scrollbars().vertical())));
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(state.closed);
            assertFalse(state.pointerCaptureActive());
            window.event(pointer(PointerEvent.Kind.RELEASE, new Point(0, 0)));
        }
    }

    @Test void paintingUsesTheCommonProportionalThumbAndFirstBodyRowHasNoPermanentHeader() throws Exception {
        try (var window = window()) {
            var state = preview(window);
            var p = state.placement(MarkdownPreviewState.Placement.Focus.CONTENT); var body = p.contentBounds();
            assertEquals(p.bounds().y(), body.y());
            var actual = new CellBuffer(window.height, window.width);
            var expected = new CellBuffer(window.height, window.width);
            var palette = UiStyles.forTheme(UiStyles.Theme.CYAN);
            MarkdownPreviewPainter.paint(new Canvas(actual), p, palette, true);
            var track = p.scrollbars().vertical();
            track.draw(new Canvas(expected), ScrollStyle.fromParent(palette.renderBody(), palette.renderBorder()));
            for (int y = track.track().y(); y < track.track().bottomExclusive(); y++)
                assertEquals(expected.get(y, track.track().x()), actual.get(y, track.track().x()));
            assertEquals("P", actual.get(body.y(), body.x()).grapheme(), "the document starts on the first content row");
            assertFalse(row(actual, body.y()).contains("Ctrl+P"));
        }
    }

    @Test void unfocusedFindKeepsItsExactBodyAndScrollbarsWhileHidingItsCursor() throws Exception {
        try (var window = window()) {
            var state = preview(window); state.setSearchQuery("Payload"); state.beginSearch();
            var focused = state.placement(MarkdownPreviewState.Placement.Focus.QUERY);
            var unfocused = focused.withFocus(MarkdownPreviewState.Placement.Focus.NONE);
            var contentFocused = focused.withFocus(MarkdownPreviewState.Placement.Focus.CONTENT);
            assertSame(focused.searchInput(), unfocused.searchInput());
            assertSame(focused.searchInput(), contentFocused.searchInput());
            assertEquals(focused.contentBounds(), contentFocused.contentBounds());
            assertEquals(focused.scrollbars(), contentFocused.scrollbars());
            var bodyCursor = MarkdownPreviewPainter.cursor(contentFocused);
            assertNotNull(bodyCursor);
            assertTrue(contentFocused.contentBounds().contains(bodyCursor.x(), bodyCursor.y()));
            assertEquals(focused.contentBounds(), unfocused.contentBounds());
            assertEquals(focused.scrollbars(), unfocused.scrollbars());
            assertNull(MarkdownPreviewPainter.cursor(unfocused));
            var buffer = new CellBuffer(window.height, window.width);
            MarkdownPreviewPainter.paint(new Canvas(buffer), unfocused, UiStyles.forTheme(UiStyles.Theme.CYAN), true);
            assertTrue(row(buffer, state.bounds.y()).contains("Find: Payload"));
            assertEquals(state.bounds.y() + 1, focused.contentBounds().y());
        }
    }

    private EditorWindowFixture window() throws Exception {
        var lines = new ArrayList<String>();
        lines.add("# Payload"); lines.add(""); lines.add("```text");
        for (int i = 0; i < 100; i++) lines.add("ROW" + i + "_中😀_" + "long_content_".repeat(25));
        lines.add("```");
        return new EditorWindowFixture(directory, false, lines);
    }
    private static MarkdownPreviewState preview(EditorWindowFixture window) throws Exception {
        window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
        window.await(() -> window.preview().ready());
        return window.preview();
    }
    private static Point thumb(Scrollbar bar) {
        assertNotNull(bar);
        int offset = ScrollInteraction.thumbOffset(bar.visibleItems(), bar.totalItems(), bar.offset());
        return bar.axis() == Scrollbar.Axis.VERTICAL ? new Point(bar.track().x(), bar.track().y() + offset)
            : new Point(bar.track().x() + offset, bar.track().y());
    }
    private static PointerEvent pointer(PointerEvent.Kind kind, Point at) {
        return new PointerEvent(at.y(), at.x(), kind, PointerEvent.Button.LEFT, Set.of());
    }
    private static String row(CellBuffer buffer, int row) {
        var text = new StringBuilder();
        for (int x = 0; x < buffer.cols(); x++) text.append(buffer.get(row, x).grapheme());
        return text.toString();
    }
}

package dev.tuiframe.scroll;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class ScrollViewportTest {
    @Test
    void bothAxesNegotiateSpaceAndKeepMetadataPinned() {
        ScrollLayout layout = ScrollLayout.calculate(new Rect(3, 2, 20, 8), 30, 40, 6);
        assertEquals(new Rect(3, 2, 19, 7), layout.contentBounds());
        assertEquals(new Rect(3, 2, 13, 7), layout.leadingBounds());
        assertEquals(new Rect(16, 2, 6, 7), layout.trailingBounds());
        assertEquals(new Rect(22, 2, 1, 7), layout.verticalScrollbarBounds());
        assertEquals(new Rect(3, 9, 13, 1), layout.horizontalScrollbarBounds());

        ScrollLayout induced = ScrollLayout.calculate(new Rect(0, 0, 8, 4), 4, 20, 0);
        assertEquals(new Rect(0, 0, 7, 3), induced.contentBounds());
        assertNotNull(induced.verticalScrollbarBounds());
        assertNotNull(induced.horizontalScrollbarBounds());
    }

    @Test
    void tinyViewportsNeverLoseAllContentToCompetingTracks() {
        for (int width = 0; width <= 3; width++) {
            for (int height = 0; height <= 3; height++) {
                Rect bounds = new Rect(0, 0, width, height);
                ScrollLayout layout = ScrollLayout.calculate(bounds, 100, 100, 20);
                assertTrue(bounds.contains(layout.contentBounds()));
                assertTrue(layout.contentBounds().contains(layout.leadingBounds()));
                if (layout.verticalScrollbarBounds() != null) {
                    assertTrue(layout.verticalScrollbarBounds().height() >= 2);
                }
                if (layout.horizontalScrollbarBounds() != null) {
                    assertTrue(layout.horizontalScrollbarBounds().width() >= 2);
                }
            }
        }
        ScrollLayout tiny = ScrollLayout.calculate(new Rect(0, 0, 2, 2), 100, 100, 0);
        assertNotNull(tiny.verticalScrollbarBounds());
        assertNull(tiny.horizontalScrollbarBounds());
    }

    @Test
    void grabbingEitherThumbDoesNotJumpAndCapturedDragClampsOutsideTrack() {
        for (Scrollbar.Axis axis : Scrollbar.Axis.values()) {
            Rect track = axis == Scrollbar.Axis.VERTICAL ? new Rect(6, 3, 1, 10) : new Rect(3, 6, 10, 1);
            Scrollbar bar = new Scrollbar(axis, track, 30, 7);
            int thumb = ScrollInteraction.thumbOffset(10, 30, 7);
            Point grab = point(axis, 3 + thumb + 1, 6);
            Scrollbar.Press press = bar.press(grab).orElseThrow();
            assertEquals(7, press.offset());
            assertEquals(1, press.drag().grabOffset());
            assertEquals(7, bar.drag(grab, press.drag()));
            assertEquals(0, bar.drag(point(axis, -100, -20), press.drag()));
            assertEquals(20, bar.drag(point(axis, 100, 200), press.drag()));
            assertTrue(bar.press(point(axis, 100, 6)).isEmpty());
        }
    }

    @Test
    void pageClicksDoNotCaptureAndArithmeticDoesNotOverflow() {
        Scrollbar bar = Scrollbar.vertical(new Rect(1, 0, 1, 10), 100, 30);
        Scrollbar.Press press = bar.press(new Point(1, 9)).orElseThrow();
        assertEquals(39, press.offset());
        assertNull(press.drag());
        assertEquals(Integer.MAX_VALUE - 10,
            ScrollInteraction.scrollBy(Integer.MAX_VALUE - 20, 100, 10, Integer.MAX_VALUE));
        assertEquals(0, ScrollInteraction.scrollBy(1, Integer.MIN_VALUE, 10, 100));
    }

    @Test
    void oneCellTracksNeverCaptureOrResetAnExistingOffset() {
        for (Scrollbar.Axis axis : Scrollbar.Axis.values()) {
            Scrollbar bar = new Scrollbar(axis, new Rect(3, 4, 1, 1), 50, 10);
            Scrollbar.Press press = bar.press(new Point(3, 4)).orElseThrow();
            assertEquals(10, press.offset());
            assertNull(press.drag());
            assertTrue(bar.grab(new Point(3, 4)).isEmpty());
            assertEquals(10, bar.drag(new Point(20, 20), new Scrollbar.Drag(axis, 0)));
        }
    }

    @Test
    void inheritedStylesAndJunctionUseParentBackgroundAndBorderForeground() {
        CellStyle body = new CellStyle(CellColor.WHITE, CellColor.BLUE, false, false);
        CellStyle border = new CellStyle(CellColor.YELLOW, CellColor.RED, true, false);
        ScrollStyle inherited = ScrollStyle.fromParent(body, border);
        CellStyle expected = new CellStyle(CellColor.YELLOW, CellColor.BLUE, true, false);
        assertEquals(expected, inherited.track());
        assertEquals(expected, inherited.thumb());

        ScrollLayout layout = ScrollLayout.calculate(new Rect(1, 1, 6, 5), 20, 20, 0);
        CellBuffer cells = new CellBuffer(8, 10);
        Canvas canvas = new Canvas(cells);
        layout.paintCorner(canvas, inherited);
        Scrollbar.vertical(layout.verticalScrollbarBounds(), 20, 3).draw(canvas, inherited);
        Scrollbar.horizontal(layout.horizontalScrollbarBounds(), 20, 3).draw(canvas, inherited);
        assertEquals("┘", cells.get(5, 6).grapheme());
        assertEquals(expected, cells.get(5, 6).style());
        assertEquals(expected, cells.get(1, 6).style());
        assertEquals(expected, cells.get(5, 1).style());

        ScrollLayout pinned = ScrollLayout.calculate(new Rect(1, 1, 8, 5), 20, 20, 2);
        pinned.paintCorner(canvas, inherited);
        assertEquals(" ", cells.get(5, 8).grapheme());
        assertEquals(expected, cells.get(5, 8).style());
        assertEquals(expected, cells.get(5, 7).style());
    }

    private static Point point(Scrollbar.Axis axis, int along, int across) {
        return axis == Scrollbar.Axis.VERTICAL ? new Point(across, along) : new Point(along, across);
    }
}

package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabBarTest {
    private static final CellStyle BASE = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle BASE_MONO = CellStyle.DEFAULT;
    private static final CellStyle ACTIVE = CellStyle.fg(CellColor.BRIGHT_CYAN);
    private static final CellStyle ACTIVE_MONO = new CellStyle(
        CellColor.DEFAULT,
        CellColor.DEFAULT,
        true,
        false
    );
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_MAGENTA);

    @Test
    void canvasPainterMatchesLegacyForNormalTabsAndHitGeometry() {
        List<TabBar.TabItem> tabs = List.of(
            new TabBar.TabItem(Path.of("one.txt"), false, false),
            new TabBar.TabItem(Path.of("two.txt"), true, true)
        );
        TabBar legacyBar = tabBar();
        TabBar canvasBar = tabBar();
        CellBuffer legacy = filled(3, 36);
        CellBuffer actual = filled(3, 36);

        legacyBar.draw(legacy, 32, tabs, 1, true);
        canvasBar.draw(new Canvas(actual), 32, tabs, 1, true);

        assertBuffersEqual(legacy, actual);
        for (int col = -1; col <= 32; col++) {
            assertEquals(legacyBar.hit(1, col), canvasBar.hit(1, col), "hit at " + col);
        }
        assertEquals(".", actual.get(0, 0).grapheme());
        assertEquals(".", actual.get(1, 32).grapheme());
        assertEquals(".", actual.get(2, 0).grapheme());
    }

    @Test
    void canvasTabsUseTextRelativeStopsAndKeepUnicodeHitGeometry() {
        TabBar bar = tabBar();
        List<TabBar.TabItem> tabs = List.of(
            new TabBar.TabItem(Path.of("a\t中e\u0301"), true, false),
            new TabBar.TabItem(Path.of("second-long-name"), false, false)
        );
        CellBuffer buffer = filled(3, 16);

        bar.draw(new Canvas(buffer), 14, tabs, 0, true);

        assertEquals(" ", buffer.get(1, 0).grapheme());
        assertEquals("[", buffer.get(1, 1).grapheme());
        assertEquals("a", buffer.get(1, 2).grapheme());
        assertEquals(" ", buffer.get(1, 3).grapheme());
        assertEquals(" ", buffer.get(1, 4).grapheme());
        assertEquals("中", buffer.get(1, 5).grapheme());
        assertTrue(buffer.get(1, 6).continuation());
        assertEquals("e\u0301", buffer.get(1, 7).grapheme());
        assertEquals("*", buffer.get(1, 8).grapheme());
        assertEquals("x", buffer.get(1, 10).grapheme());
        assertEquals("]", buffer.get(1, 11).grapheme());
        assertEquals(">", buffer.get(1, 13).grapheme());
        assertEquals(ACTIVE, buffer.get(1, 3).style());
        assertEquals(ACTIVE, buffer.get(1, 6).style());
        assertEquals(new TabBar.Hit(true, 0, true, false, false), bar.hit(1, 10));
        assertEquals(new TabBar.Hit(true, -1, false, false, true), bar.hit(1, 13));
    }

    @Test
    void overflowArrowsAndHitsFollowTheLogicalViewportWhileScrolling() {
        TabBar bar = tabBar();
        List<TabBar.TabItem> tabs = List.of(
            new TabBar.TabItem(Path.of("a"), false, false),
            new TabBar.TabItem(Path.of("b"), false, false),
            new TabBar.TabItem(Path.of("c"), false, false)
        );
        CellBuffer first = filled(3, 14);

        bar.draw(new Canvas(first), 14, tabs, 0, true);

        assertEquals(" ", first.get(1, 0).grapheme());
        assertEquals(">", first.get(1, 13).grapheme());
        assertEquals(new TabBar.Hit(true, 0, true, false, false), bar.hit(1, 4));
        assertEquals(new TabBar.Hit(true, 1, true, false, false), bar.hit(1, 10));
        assertEquals(new TabBar.Hit(true, -1, false, false, true), bar.hit(1, 13));

        bar.scrollRight(tabs);
        CellBuffer scrolled = filled(3, 14);
        bar.draw(new Canvas(scrolled), 14, tabs, 1, true);

        assertEquals("<", scrolled.get(1, 0).grapheme());
        assertEquals(" ", scrolled.get(1, 13).grapheme());
        assertEquals(new TabBar.Hit(true, -1, false, true, false), bar.hit(1, 0));
        assertEquals(new TabBar.Hit(true, 1, true, false, false), bar.hit(1, 4));
        assertEquals(new TabBar.Hit(true, 2, true, false, false), bar.hit(1, 10));
        assertFalse(bar.hit(1, 13).consumed());
    }

    @Test
    void translatedParentClipLimitsPaintWithoutChangingHitsAndCanvasState() {
        TabBar bar = tabBar();
        CellBuffer buffer = filled(5, 14);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(1, 0, 8, 4));

        bar.draw(
            canvas,
            10,
            List.of(new TabBar.TabItem(Path.of("a"), false, false)),
            0,
            true
        );

        assertEquals(".", buffer.get(2, 2).grapheme());
        assertEquals("a", buffer.get(2, 3).grapheme());
        assertEquals(" ", buffer.get(2, 10).grapheme());
        assertEquals(".", buffer.get(2, 11).grapheme());
        assertEquals(".", buffer.get(1, 3).grapheme());
        assertEquals(new TabBar.Hit(true, 0, false, false, false), bar.hit(1, 0));
        assertFalse(bar.hit(2, 0).consumed());

        assertTrue(canvas.put(1, 0, "z", ACCENT));
        assertEquals("z", buffer.get(1, 3).grapheme());
    }

    @Test
    void exceptionalPaintRestoresTheCallerCanvasState() {
        TabBar bar = new TabBar(BASE, BASE_MONO, null, ACTIVE_MONO);
        CellBuffer buffer = filled(4, 10);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 1);
        assertTrue(canvas.clip(0, 0, 8, 3));

        assertThrows(
            IllegalArgumentException.class,
            () -> bar.draw(
                canvas,
                8,
                List.of(new TabBar.TabItem(Path.of("a"), false, false)),
                0,
                true
            )
        );

        assertTrue(canvas.put(0, 0, "z", ACCENT));
        assertEquals("z", buffer.get(1, 1).grapheme());
    }

    @Test
    void strictViewportAndParentClipPreserveCrossingWidePairs() {
        CellBuffer viewportBuffer = filled(3, 8);
        viewportBuffer.set(1, 5, "中", ACCENT);
        Cell viewportLead = viewportBuffer.get(1, 5);
        Cell viewportContinuation = viewportBuffer.get(1, 6);

        tabBar().draw(new Canvas(viewportBuffer), 6, List.of(), 0, true);

        assertSame(viewportLead, viewportBuffer.get(1, 5));
        assertSame(viewportContinuation, viewportBuffer.get(1, 6));
        assertEquals(".", viewportBuffer.get(1, 7).grapheme());

        CellBuffer clippedBuffer = filled(3, 8);
        clippedBuffer.set(1, 1, "界", ACCENT);
        Cell clippedLead = clippedBuffer.get(1, 1);
        Cell clippedContinuation = clippedBuffer.get(1, 2);
        Canvas clipped = new Canvas(clippedBuffer);
        assertTrue(clipped.clip(0, 1, 2, 1));
        TabBar clippedBar = tabBar();

        clippedBar.draw(
            clipped,
            7,
            List.of(new TabBar.TabItem(Path.of("中"), false, false)),
            0,
            true
        );

        assertEquals("[", clippedBuffer.get(1, 0).grapheme());
        assertSame(clippedLead, clippedBuffer.get(1, 1));
        assertSame(clippedContinuation, clippedBuffer.get(1, 2));
        assertEquals(".", clippedBuffer.get(1, 3).grapheme());
        assertTrue(clippedBar.hit(1, 4).closeButton());
    }

    @Test
    void rowFillAndSeparatorsReuseOneImmutableBlankCell() {
        TabBar bar = tabBar();
        CellBuffer buffer = filled(3, 18);
        List<TabBar.TabItem> tabs = List.of(
            new TabBar.TabItem(Path.of("a"), false, false),
            new TabBar.TabItem(Path.of("b"), false, false)
        );

        bar.draw(new Canvas(buffer), 18, tabs, -1, true);

        assertSame(buffer.get(1, 5), buffer.get(1, 11));
        assertSame(buffer.get(1, 5), buffer.get(1, 17));
        assertEquals(BASE, buffer.get(1, 5).style());
    }

    private static TabBar tabBar() {
        return new TabBar(BASE, BASE_MONO, ACTIVE, ACTIVE_MONO);
    }

    private static CellBuffer filled(int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }

    private static void assertBuffersEqual(CellBuffer expected, CellBuffer actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.cols(), actual.cols());
        for (int row = 0; row < expected.rows(); row++) {
            for (int col = 0; col < expected.cols(); col++) {
                assertEquals(expected.get(row, col), actual.get(row, col), col + "," + row);
            }
            assertEquals(expected.rowSignature1(row), actual.rowSignature1(row));
            assertEquals(expected.rowSignature2(row), actual.rowSignature2(row));
        }
    }
}

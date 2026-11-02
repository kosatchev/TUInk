package dev.tuiframe.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedRowPainterTest {
    private static final CellStyle BODY = new CellStyle(CellColor.RED, CellColor.DEFAULT, false, false);
    private static final CellStyle SELECTED = new CellStyle(CellColor.BLUE, CellColor.YELLOW, true, true);
    private static final CellStyle MARKER = new CellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT, false, false);

    @Test void preparedAndStreamingRowsHaveIdenticalTabsWideClippingAndDynamicDecorations() {
        String text = " \t界e\u0301😀Z  \t";
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 4, 100_000)) {
            for (int phase = 0; phase < 4; phase++) {
                try (var handle = cache.acquire(new PreparedStyledRow.Key(1, 2, 3, 4, 0, 0, text.length(), 4, phase),
                    text, BODY, List.of())) {
                    for (int width = 1; width <= 15; width++) {
                        for (int hidden = 0; hidden < 10; hidden++) {
                            CellBuffer prepared = new CellBuffer(3, 20), streamed = new CellBuffer(3, 20);
                            PreparedRowPainter.Overlay overlay = (start, end, base) -> end > 4 && start < 9 ? SELECTED : base;
                            PreparedRowPainter.paint(new Canvas(prepared), handle.row(), 1, 2, width, hidden,
                                overlay, true, true, MARKER);
                            PreparedRowPainter.paintStreaming(new Canvas(streamed), text, 4, phase, BODY,
                                1, 2, width, hidden, overlay, true, true, MARKER);
                            for (int column = 0; column < 20; column++) assertEquals(prepared.get(1, column), streamed.get(1, column),
                                "phase=" + phase + ", width=" + width + ", hidden=" + hidden + ", column=" + column);
                        }
                    }
                }
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void streamingRefusalPathReadsOnlyTheVisiblePrefixAndBuildsNoSourceSizedValue() {
        var text = new CountingAscii(1_000_000);
        var cells = new CellBuffer(3, 16);
        int end = PreparedRowPainter.paintStreaming(new Canvas(cells), text, 4, 0, CellStyle.DEFAULT,
            1, 0, 12, 0, null, false, false, null);
        assertEquals(-1, end);
        assertTrue(text.reads < 40, "reads=" + text.reads);
        for (int column = 0; column < 12; column++) assertEquals("A", cells.get(1, column).grapheme());
        assertEquals(" ", cells.get(1, 12).grapheme());
    }

    @Test void emptyGenericSpanInsideACombiningGraphemeRemainsEmpty() {
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 2, 100_000);
             var handle = cache.acquire(new PreparedStyledRow.Key(1, 2, 3, 4, 0, 0, 3, 4, 0), "e\u0301x", BODY,
                 List.of(new PreparedStyledRow.Span(1, 1, SELECTED)))) {
            assertEquals(BODY, handle.row().baseStyle(0));
            assertEquals(BODY, handle.row().baseStyle(1));
        }
    }

    @Test void borrowedGeometryFallbackPreservesTheOriginalTabPhaseAndLocalOverlayOffsets() {
        String text = "ab\t界e\u0301😀Z";
        var full = TerminalText.metrics(text, 4);
        int from = 1, to = text.length();
        var geometry = full.view(from, to);
        int phase = full.visualColumnAt(from) % 4;
        PreparedRowPainter.Overlay overlay = (start, end, base) -> end > 3 && start < 7 ? SELECTED : base;
        for (int hidden = 0; hidden < 10; hidden++) {
            for (int width = 1; width < 12; width++) {
                var expected = new CellBuffer(3, 16);
                var actual = new CellBuffer(3, 16);
                PreparedRowPainter.paintStreaming(new Canvas(expected), geometry.sequence(), 4, phase, BODY,
                    1, 2, width, hidden, overlay, false, false, null);
                PreparedRowPainter.paintGeometry(new Canvas(actual), geometry, BODY, 1, 2, width, hidden, overlay);
                for (int column = 0; column < 16; column++)
                    assertEquals(expected.get(1, column), actual.get(1, column),
                        "width=" + width + ", hidden=" + hidden + ", column=" + column);
            }
        }
    }

    private static final class CountingAscii implements CharSequence {
        private final int length;
        private int reads;
        CountingAscii(int length) { this.length = length; }
        @Override public int length() { return length; }
        @Override public char charAt(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
            reads++; return 'A';
        }
        @Override public CharSequence subSequence(int start, int end) { throw new AssertionError("No slice copying in fallback"); }
        @Override public String toString() { throw new AssertionError("No source materialization in fallback"); }
    }
}

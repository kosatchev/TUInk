package dev.tuiframe.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedStyledRowTest {
    private static final CellStyle BODY = CellStyle.DEFAULT;
    private static final CellStyle BLUE = new CellStyle(CellColor.BLUE, CellColor.DEFAULT, false, false);
    private static final CellStyle RED = new CellStyle(CellColor.RED, CellColor.DEFAULT, true, true);

    @Test void geometryPreservesGraphemesAbsoluteTabPhaseAndStableStyles() {
        String text = "\tA界e\u0301 \t";
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 2, 100_000);
             var frame = cache.acquire(key(text.length(), 1), text, BODY,
                 List.of(new PreparedStyledRow.Span(2, 4, BLUE), new PreparedStyledRow.Span(4, 5, RED)))) {
            var row = frame.row();
            assertEquals(6, row.graphemeCount());
            assertEquals(3, row.advance(0));
            assertTrue(row.isTab(0));
            assertEquals(3, row.visualOffset(1));
            assertEquals("界", row.display(2));
            assertEquals(2, row.advance(2));
            assertEquals("e\u0301", row.display(3));
            assertEquals(1, row.advance(3));
            assertEquals(BLUE, row.baseStyle(2));
            assertEquals(RED, row.baseStyle(3));
            assertEquals(1, row.leadingWhitespaceEnd());
            assertEquals(4, row.trailingWhitespaceStart());
            assertTrue(row.isExactSpace(4));
            assertEquals(3, row.advance(5));
            assertEquals(11, row.visualWidth());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void tabPhaseChangesPreparedGeometryEvenForTheSameText() {
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 2, 100_000);
             var zero = cache.acquire(key(2, 0), "\tx", BODY, List.of());
             var one = cache.acquire(key(2, 1), "\tx", BODY, List.of())) {
            assertEquals(4, zero.row().advance(0));
            assertEquals(3, one.row().advance(0));
            assertNotSame(zero.row(), one.row());
        }
    }

    @Test void emptyAndOutOfRowRangesDoNotChangeBaseStyles() {
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 2, 100_000);
             var frame = cache.acquire(key(3, 0), "a界b", BODY,
                 List.of(new PreparedStyledRow.Span(1, 1, RED), new PreparedStyledRow.Span(10, 20, RED)))) {
            for (int index = 0; index < frame.row().graphemeCount(); index++) assertEquals(BODY, frame.row().baseStyle(index));
        }
    }

    @Test void controlDisplayIsSanitizedWithoutChangingOriginalOffsets() {
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 2, 100_000);
             var frame = cache.acquire(key(3, 0), "a\u001bb", BODY, List.of())) {
            var row = frame.row();
            assertEquals(3, row.graphemeCount());
            assertFalse(row.display(1).contains("\u001b"));
            assertEquals(1, row.metrics().start(1));
            assertEquals(2, row.metrics().end(1));
        }
    }

    @Test void theRowCannotExposeItsMutableArrayStorage() {
        for (var method : PreparedStyledRow.class.getMethods()) assertFalse(method.getReturnType().isArray(), method.toString());
        for (var component : PreparedStyledRow.Key.class.getRecordComponents()) assertTrue(component.getType().isPrimitive());
    }

    @Test void aPreparedSliceDoesNotRetainOrReadItsMutableBackingSource() {
        var text = new StringBuilder("界x");
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 2, 100_000);
             var frame = cache.acquire(key(text.length(), 0), text, BODY, List.of())) {
            text.setCharAt(0, 'a');
            text.append("more");
            assertEquals("界x", frame.row().metrics().text());
            assertEquals("界", frame.row().display(0));
            assertEquals(2, frame.row().advance(0));
        }
    }

    private static PreparedStyledRow.Key key(int length, int phase) {
        return new PreparedStyledRow.Key(1, 2, 3, 4, 0, 0, length, 4, phase);
    }
}

package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.text.TerminalText;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.markdown.MarkdownDocument;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownGeometryTest {
    @Test void navigationAndPaintingBorrowOneIndexWithoutTouchingInvisibleRows() {
        var budget = RenderMemoryBudget.bounded(100_000);
        var result = result(40, 20_000);
        try (var geometry = new MarkdownGeometry(result, budget); var row = geometry.acquire(12_000)) {
            assertEquals(6, geometry.visualWidth(12_000));
            assertEquals(1, geometry.offsetAtColumn(12_000, 2, TerminalText.Bias.FLOOR));
            try (var painting = geometry.acquire(12_000)) { assertSame(row.metrics(), painting.metrics()); }
            assertEquals(1, geometry.misses());
            assertEquals(3, geometry.hits());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void refusalUsesStreamingGeometryAndDoesNotAllocateAnUnadmittedIndex() {
        var budget = RenderMemoryBudget.bounded(1);
        try (var geometry = new MarkdownGeometry(result(40, 1), budget)) {
            assertNull(geometry.acquire(0));
            assertEquals(6, geometry.visualWidth(0));
            assertEquals(2, geometry.offsetAtColumn(0, 4, TerminalText.Bias.CEIL));
            assertEquals(0, budget.usedBytes());
            assertEquals(0, geometry.retainedBytes());
        }
    }

    @Test void reflowGetsANewIdentityAndClosedGeometryKeepsOnlyBorrowedRowCharges() {
        var budget = RenderMemoryBudget.bounded(100_000);
        var first = new MarkdownGeometry(result(40, 1), budget);
        var second = new MarkdownGeometry(result(60, 1), budget);
        assertNotEquals(first.identity(), second.identity());
        assertEquals(first.revision(), second.revision());
        var retained = first.acquire(0);
        first.close(); second.close();
        assertTrue(budget.usedBytes() > 0);
        assertEquals("x\t界", retained.metrics().text());
        assertThrows(IllegalStateException.class, first::result);
        retained.close();
        assertEquals(0, budget.usedBytes());
    }

    private static MarkdownLayout.Result result(int columns, int count) {
        var document = new MarkdownDocument(new DocumentId("geometry"), 3, List.of(), List.of());
        var line = new MarkdownLayout.Line("x\t界", List.of(), "x\t界", null, "code");
        return new MarkdownLayout.Result(document, Collections.nCopies(count, line), columns, 6);
    }
}

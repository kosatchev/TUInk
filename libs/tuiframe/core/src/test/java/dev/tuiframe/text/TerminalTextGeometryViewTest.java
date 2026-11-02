package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalTextGeometryViewTest {
    @Test void streamingFallbackMatchesIndexedGeometryWithoutMetricArrays() {
        for (String text : List.of("", "ascii\tend", "界e\u0301👩‍💻\tend", "\u001b", "\ud800x", "\u0301x")) {
            var indexed = TerminalText.metrics(text, 4);
            var streamed = TerminalText.streamingGeometry(text, 4);
            assertEquals(indexed.graphemeCount(), streamed.graphemeCount(), text);
            assertEquals(indexed.visualWidth(), streamed.visualWidth(), text);
            for (int index = 0; index < indexed.graphemeCount(); index++) {
                assertEquals(indexed.start(index), streamed.start(index));
                assertEquals(indexed.end(index), streamed.end(index));
                assertEquals(indexed.width(index), streamed.width(index));
                assertEquals(indexed.isTab(index), streamed.isTab(index));
                assertEquals(indexed.isWhitespace(index), streamed.isWhitespace(index));
            }
            for (int offset = -1; offset <= text.length() + 1; offset++) {
                assertEquals(indexed.floorBoundary(offset), streamed.floorBoundary(offset), text + " floor " + offset);
                assertEquals(indexed.ceilBoundary(offset), streamed.ceilBoundary(offset), text + " ceil " + offset);
                assertEquals(indexed.previousBoundary(offset), streamed.previousBoundary(offset), text + " previous " + offset);
                assertEquals(indexed.nextBoundary(offset), streamed.nextBoundary(offset), text + " next " + offset);
                assertEquals(indexed.isBoundary(offset), streamed.isBoundary(offset), text + " boundary " + offset);
                assertEquals(indexed.visualColumnAt(offset), streamed.visualColumnAt(offset), text + " column " + offset);
            }
            for (int column = -1; column <= indexed.visualWidth() + 1; column++)
                for (var bias : TerminalText.Bias.values())
                    assertEquals(indexed.offsetForVisualColumn(column, bias), streamed.offsetForVisualColumn(column, bias), text + column + bias);
        }
    }

    @Test void aViewUsesExistingBoundariesWithoutReadingTheHiddenPrefix() {
        var source = new CountingText("prefix".repeat(2000) + "\t界e\u0301😀end");
        var metrics = TerminalText.metrics(source, 4);
        source.reads = 0;
        var view = metrics.view(12000, source.length());
        assertEquals(0, source.reads);
        assertEquals(metrics.visualWidth() - metrics.visualColumnAt(12000), view.visualWidth());
        assertEquals(0, source.reads);
        assertEquals(4, view.visualColumnAtBoundary(1));
        assertEquals(0, source.reads);
    }

    @Test void viewOffsetsAndTabsRemainRelativeToTheOriginalColumns() {
        var metrics = TerminalText.metrics("x\t界e\u0301z", 4);
        var view = metrics.view(1, 5);
        assertEquals("\t界e\u0301", view.text());
        assertEquals(3, view.graphemeCount());
        assertEquals(0, view.start(0));
        assertEquals(1, view.end(0));
        assertEquals(3, view.visualColumnAtBoundary(1));
        assertEquals(6, view.visualWidth());
        assertEquals(1, view.offsetForVisualColumn(3, TerminalText.Bias.CEIL));
        assertEquals(0, view.previousBoundary(0));
        assertEquals(4, view.nextBoundary(4));
        assertEquals(2, view.floorBoundary(3));
        assertEquals(4, view.ceilBoundary(3));
        var nested = view.view(1, 4);
        assertEquals("界e\u0301", nested.text());
        assertEquals(3, nested.visualWidth());
        assertThrows(IllegalArgumentException.class, () -> metrics.view(4, 5));
    }

    private static final class CountingText implements CharSequence {
        final String text; int reads;
        CountingText(String text) { this.text = text; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { reads++; return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { reads++; return text.subSequence(start, end); }
    }
}

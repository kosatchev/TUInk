package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClipboardTextTest {
    @Test
    void preparesExactMultilineUnicodeTextWithReversedAndClampedRanges() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("first😀", "é界", "last"));
        Position start = new Position(0, 2);
        Position end = new Position(2, 2);
        assertEquals("rst😀\né界\nla", ClipboardText.copy(model, start, end, 100_000));
        assertEquals("rst😀\né界\nla", ClipboardText.copy(model, end, start, 100_000));
        assertEquals("first😀\né界\nlast",
            ClipboardText.copy(model, new Position(0, 0), new Position(99, 99), 100_000));
    }

    @Test
    void refusesAllocatingCopiesBeforeReadingTheRangeButReusesWholeLines() {
        TextModel model = new TextModel(new SimpleLineBuffer()) {
            @Override
            public String getTextRange(Position start, Position end) {
                throw new AssertionError("Refused copy must not allocate its text");
            }
        };
        model.replaceLines(List.of("first", "last"));
        assertThrows(ClipboardLimitException.class,
            () -> ClipboardText.copy(model, new Position(0, 1), new Position(1, 2), 0));
        TextModel line = new TextModel(new SimpleLineBuffer());
        String text = "immutable 😀";
        line.replaceLines(List.of(text));
        assertSame(line.getLine(0), ClipboardText.copy(line, new Position(0, 0), new Position(0, text.length()), 0));
        assertEquals("", ClipboardText.copy(line, new Position(0, 1), new Position(0, 1), 0));
    }

    @Test
    void rejectsEndpointsInsideSurrogatePairsWithoutPublishingText() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("😀tail"));
        assertThrows(IllegalArgumentException.class,
            () -> ClipboardText.copy(model, new Position(0, 1), new Position(0, 2), 100_000));
    }

    @Test
    void editedWholeLineRequiresAdmissionBeforeMaterializingItsBlocks() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("a".repeat(1_000_000)));
        model.insertChar(new Position(0, 500_000), 'b');
        Position end = new Position(0, model.lineLength(0));
        LineText.Work before = LineText.work();

        assertThrows(ClipboardLimitException.class,
            () -> ClipboardText.copy(model, new Position(0, 0), end, 0));
        assertEquals(before.materializedCodeUnits(), LineText.work().materializedCodeUnits());
        String copied = ClipboardText.copy(model, new Position(0, 0), end, 7_000_000);
        assertEquals('b', copied.charAt(500_000));
        assertSame(copied, ClipboardText.copy(model, new Position(0, 0), end, 0));
    }
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextModelLineChangesTest {
    @Test
    void recordsBoundaryLinesForEveryKindOfTextMutation() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("abc", "def", "ghi"));
        assertChange(model, new TextModel.LineChange(1, 1, 1),
            () -> model.insertChar(new Position(1, 0), 'x'));
        assertChange(model, new TextModel.LineChange(1, 1, 1),
            () -> model.deleteBackward(new Position(1, 1)));
        assertChange(model, new TextModel.LineChange(1, 1, 1),
            () -> model.deleteForward(new Position(1, 0)));
        assertChange(model, new TextModel.LineChange(0, 1, 1),
            () -> model.deleteRange(new Position(0, 0), new Position(0, 1)));
        assertChange(model, new TextModel.LineChange(0, 1, 1),
            () -> model.insertText(new Position(0, 0), "text"));
        assertChange(model, new TextModel.LineChange(1, 1, 2),
            () -> model.insertNewline(new Position(1, 0)));
        assertChange(model, new TextModel.LineChange(1, 2, 1),
            () -> model.deleteBackward(new Position(2, 0)));
        assertChange(model, new TextModel.LineChange(1, 2, 1),
            () -> model.deleteForward(new Position(1, model.getLine(1).length())));
        assertChange(model, new TextModel.LineChange(1, 1, 3),
            () -> model.insertText(new Position(1, 0), "one\ntwo\nthree"));
        assertChange(model, new TextModel.LineChange(1, 3, 1),
            () -> model.deleteRange(new Position(1, 0), new Position(3, 0)));
    }

    @Test
    void retainsOrderedChangesAndReturnsAnImmutableSnapshot() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        long before = model.revision();
        model.insertText(new Position(0, 0), "a\nb\nc");
        model.deleteBackward(new Position(2, 0));
        List<TextModel.LineChange> changes = model.lineChangesSince(before);
        assertEquals(List.of(
            new TextModel.LineChange(0, 1, 3), new TextModel.LineChange(1, 2, 1)
        ), changes);
        assertThrows(UnsupportedOperationException.class,
            () -> changes.add(new TextModel.LineChange(0, 1, 1)));
        model.insertChar(new Position(0, 0), 'x');
        assertEquals(2, changes.size());
        assertEquals(List.of(), model.lineChangesSince(model.revision()));
    }

    @Test
    void unavailableHistoryAndContentReplacementRequestAFullReset() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        long before = model.revision();
        for (int mutation = 0; mutation < 128; mutation++) {
            model.insertChar(new Position(0, 0), 'x');
        }
        assertEquals(128, model.lineChangesSince(before).size());
        model.insertChar(new Position(0, 0), 'x');
        assertNull(model.lineChangesSince(before));
        assertEquals(128, model.lineChangesSince(before + 1).size());
        assertNull(model.lineChangesSince(-1));
        assertNull(model.lineChangesSince(model.revision() + 1));
        before = model.revision();
        model.replaceLines(List.of("replaced"));
        assertNull(model.lineChangesSince(before));
        long replaced = model.revision();
        model.insertChar(new Position(0, 0), 'x');
        assertEquals(List.of(new TextModel.LineChange(0, 1, 1)), model.lineChangesSince(replaced));
    }

    private static void assertChange(TextModel model, TextModel.LineChange expected, Runnable mutation) {
        long before = model.revision();
        mutation.run();
        assertEquals(List.of(expected), model.lineChangesSince(before));
    }
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextModelUnicodeEditingTest {
    private static final String COMBINING_E = "e\u0301";
    private static final String FAMILY = "👨‍👩‍👧‍👦";
    private static final String MODIFIED_EMOJI = "👍🏽";

    @Test
    void backspaceDeletesOneCompleteCombiningSequence() {
        TextModel model = modelWith("A" + COMBINING_E + "B");

        Position cursor = model.deleteBackward(new Position(0, 3));

        assertEquals("AB", model.getLine(0));
        assertEquals(new Position(0, 1), cursor);
    }

    @Test
    void backspaceDeletesOneCompleteEmojiCluster() {
        TextModel model = modelWith("A" + FAMILY + MODIFIED_EMOJI + "B");
        int afterFamily = 1 + FAMILY.length();

        Position cursor = model.deleteBackward(new Position(0, afterFamily));

        assertEquals("A" + MODIFIED_EMOJI + "B", model.getLine(0));
        assertEquals(new Position(0, 1), cursor);
    }

    @Test
    void backspaceFromInsideAClusterDoesNotLeaveBrokenUtf16OrCombiningText() {
        TextModel model = modelWith("A" + FAMILY + "B");

        Position cursor = model.deleteBackward(new Position(0, 5));

        assertEquals("AB", model.getLine(0));
        assertEquals(new Position(0, 1), cursor);
    }

    @Test
    void deleteForwardDeletesOneCompleteCombiningSequence() {
        TextModel model = modelWith("A" + COMBINING_E + "B");

        Position cursor = model.deleteForward(new Position(0, 1));

        assertEquals("AB", model.getLine(0));
        assertEquals(new Position(0, 1), cursor);
    }

    @Test
    void deleteForwardFromInsideAClusterDeletesThatWholeCluster() {
        TextModel model = modelWith("A" + MODIFIED_EMOJI + "B");

        Position cursor = model.deleteForward(new Position(0, 3));

        assertEquals("AB", model.getLine(0));
        assertEquals(new Position(0, 1), cursor);
    }

    private static TextModel modelWith(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        return model;
    }
}

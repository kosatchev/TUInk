package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EditorViewportBehaviorUnicodeTest {
    @Test
    void clicksOnEitherCellOfWideGraphemeReturnAGraphemeBoundary() {
        TextModel model = model("A中😀B");
        EditorState state = new EditorState();

        Position cjkLeft = hit(model, state, 4);
        Position cjkRight = hit(model, state, 5);
        Position emojiLeft = hit(model, state, 6);
        Position emojiRight = hit(model, state, 7);

        assertEquals(new Position(0, 1), cjkLeft);
        assertEquals(new Position(0, 1), cjkRight);
        assertEquals(new Position(0, 2), emojiLeft);
        assertEquals(new Position(0, 2), emojiRight);
        assertTrue(DisplayText.isBoundary(model.getLine(0), emojiRight.col()));
    }

    @Test
    void horizontalScrollInsideTabMapsFollowingTextDirectlyFromAbsoluteCellColumn() {
        TextModel model = model("\tX");
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);

        Position beforeX = EditorViewportBehavior.pointerToTextPosition(
            model,
            state,
            1,
            2,
            1,
            5,
            3,
            4
        );

        assertEquals(new Position(0, 1), beforeX);
    }

    private static Position hit(TextModel model, EditorState state, int mouseCol) {
        return EditorViewportBehavior.pointerToTextPosition(model, state, 1, 2, 1, mouseCol, 3, 4);
    }

    private static TextModel model(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        return new TextModel(buffer);
    }
}

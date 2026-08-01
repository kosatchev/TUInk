package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.unicode.GraphemeText;
import org.junit.jupiter.api.Test;

class EditorViewportBehaviorUnicodeTest {
    @Test
    void clicksOnEitherCellOfWideGraphemeReturnAGraphemeBoundary() {
        TextModel model = model("A中😀B");
        EditorTextLayout textLayout = new TestEditorTextLayout(model);
        EditorState state = new EditorState();

        Position cjkLeft = hit(model, textLayout, state, 4);
        Position cjkRight = hit(model, textLayout, state, 5);
        Position emojiLeft = hit(model, textLayout, state, 6);
        Position emojiRight = hit(model, textLayout, state, 7);

        assertEquals(new Position(0, 1), cjkLeft);
        assertEquals(new Position(0, 1), cjkRight);
        assertEquals(new Position(0, 2), emojiLeft);
        assertEquals(new Position(0, 2), emojiRight);
        assertTrue(GraphemeText.isBoundary(model.getLine(0), emojiRight.col()));
    }

    @Test
    void horizontalScrollInsideTabMapsFollowingTextDirectlyFromAbsoluteCellColumn() {
        TextModel model = model("\tX");
        EditorTextLayout textLayout = new TestEditorTextLayout(model);
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);

        Position beforeX = EditorViewportBehavior.pointerToTextPosition(
            model,
            textLayout,
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

    @Test
    void modelAwareViewportOperationsRejectALayoutBoundToAnotherModel() {
        TextModel model = model("first");
        EditorTextLayout foreignLayout = new TestEditorTextLayout(model("second"));
        EditorState state = new EditorState();

        assertThrows(
            IllegalArgumentException.class,
            () -> EditorViewportBehavior.pointerToTextPosition(
                model,
                foreignLayout,
                state,
                1,
                2,
                1,
                3,
                3,
                4
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> EditorViewportBehavior.normalizeWrapTop(
                model,
                foreignLayout,
                0,
                0,
                10,
                false,
                4
            )
        );
    }

    private static Position hit(
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        int mouseCol
    ) {
        return EditorViewportBehavior.pointerToTextPosition(
            model,
            textLayout,
            state,
            1,
            2,
            1,
            mouseCol,
            3,
            4
        );
    }

    private static TextModel model(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        return new TextModel(buffer);
    }
}

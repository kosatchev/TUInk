package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import org.junit.jupiter.api.Test;

class EditorLayoutTest {
    @Test
    void horizontalScrollbarReducesAvailableContentRows() {
        EditorLayout layout = resolve(model("1234567890"), 5, 12, 1, 0, false);

        assertAll(
            () -> assertEquals(3, layout.gutterWidth()),
            () -> assertEquals(9, layout.contentColumns()),
            () -> assertEquals(2, layout.contentRows()),
            () -> assertEquals(0, layout.scrollbarWidth()),
            () -> assertEquals(1, layout.horizontalScrollbarHeight())
        );
    }

    @Test
    void exactWidthDoesNotReserveHorizontalScrollbar() {
        EditorLayout layout = resolve(model("123456789"), 5, 12, 1, 0, false);

        assertAll(
            () -> assertEquals(9, layout.contentColumns()),
            () -> assertEquals(3, layout.contentRows()),
            () -> assertEquals(0, layout.scrollbarWidth()),
            () -> assertEquals(0, layout.horizontalScrollbarHeight())
        );
    }

    @Test
    void cursorAtEndOfExactWidthLineReservesItsOwnCell() {
        TextModel model = model("123456789");
        EditorState state = new EditorState();
        state.setCursor(new Position(0, 9));

        EditorLayout layout = EditorLayout.resolve(model, state, 5, 12, 1, 0, false, false, 4);

        assertEquals(1, layout.horizontalScrollbarHeight());
        assertEquals(2, layout.contentRows());
    }

    @Test
    void horizontalScrollbarCanForceVerticalScrollbarAtFixedPoint() {
        EditorLayout layout = resolve(
            model("12345678", "12345678", "12345678"),
            5,
            10,
            1,
            0,
            false
        );

        assertAll(
            () -> assertEquals(6, layout.contentColumns()),
            () -> assertEquals(2, layout.contentRows()),
            () -> assertEquals(1, layout.scrollbarWidth()),
            () -> assertEquals(1, layout.horizontalScrollbarHeight())
        );
    }

    @Test
    void wrappingIsRecomputedAfterVerticalScrollbarNarrowsViewport() {
        TextModel model = model("1234567890123456789");
        EditorState state = new EditorState();

        EditorLayout layout = EditorLayout.resolve(model, state, 5, 9, 1, 0, true, false, 4);

        assertAll(
            () -> assertEquals(5, layout.contentColumns()),
            () -> assertEquals(3, layout.contentRows()),
            () -> assertEquals(1, layout.scrollbarWidth()),
            () -> assertEquals(0, layout.horizontalScrollbarHeight()),
            () -> assertEquals(4, model.totalWrappedRows(layout.contentColumns(), false, 4))
        );
    }

    @Test
    void contentTopAndOverlayRowsShareTheSameLayoutBudget() {
        EditorLayout tabBarLayout = resolve(model("x"), 6, 20, 2, 0, false);
        EditorLayout overlayLayout = resolve(model("x"), 8, 20, 1, 2, false);

        assertAll(
            () -> assertEquals(3, tabBarLayout.contentRows()),
            () -> assertEquals(2, tabBarLayout.contentTopRow()),
            () -> assertEquals(4, overlayLayout.contentRows()),
            () -> assertEquals(2, overlayLayout.overlayRows())
        );
    }

    @Test
    void narrowTerminalNeverProducesNegativeGeometry() {
        EditorLayout layout = resolve(model("a very long line"), 1, 1, 1, 0, false);

        assertAll(
            () -> assertEquals(0, layout.contentRows()),
            () -> assertEquals(0, layout.contentColumns()),
            () -> assertEquals(0, layout.scrollbarWidth()),
            () -> assertEquals(0, layout.horizontalScrollbarHeight())
        );
    }

    private static EditorLayout resolve(
        TextModel model,
        int rows,
        int cols,
        int contentTopRow,
        int overlayRows,
        boolean wrapLines
    ) {
        return EditorLayout.resolve(
            model,
            new EditorState(),
            rows,
            cols,
            contentTopRow,
            overlayRows,
            wrapLines,
            false,
            4
        );
    }

    private static TextModel model(String... lines) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, lines.length == 0 ? "" : lines[0]);
        for (int i = 1; i < lines.length; i++) {
            buffer.insertLine(i, lines[i]);
        }
        return new TextModel(buffer);
    }
}

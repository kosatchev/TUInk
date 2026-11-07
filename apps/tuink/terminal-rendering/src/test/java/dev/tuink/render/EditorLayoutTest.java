package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuiframe.geometry.Rect;
import org.junit.jupiter.api.Test;

class EditorLayoutTest {
    @Test
    void paneGeometryRetainsTerminalDimensionsAndPlacesBothScrollbarsInsideThePane() {
        TextModel model = model("0123456789".repeat(10), "second", "third", "fourth", "fifth", "sixth");
        EditorLayout layout = EditorLayout.resolve(model, new TerminalEditorTextLayout(model), new EditorState(),
            10, 80, 2, 1, false, false, 4, new Rect(25, 2, 40, 6));

        assertAll(
            () -> assertEquals(80, layout.cols()),
            () -> assertEquals(10, layout.rows()),
            () -> assertEquals(new Rect(25, 2, 40, 6), layout.editorBounds()),
            () -> assertEquals(new Rect(28, 2, 36, 5), layout.scrollLayout().contentBounds()),
            () -> assertEquals(new Rect(64, 2, 1, 5), layout.scrollLayout().verticalScrollbarBounds()),
            () -> assertEquals(new Rect(28, 7, 36, 1), layout.scrollLayout().horizontalScrollbarBounds())
        );
    }

    @Test
    void paneWrappingUsesItsOwnWidthAndGlobalChromeStillClipsItsHeight() {
        TextModel model = model("12345678901234567890");
        EditorLayout layout = EditorLayout.resolve(model, new TerminalEditorTextLayout(model), new EditorState(),
            12, 80, 2, 2, true, false, 4, new Rect(30, 0, 10, 12));

        assertAll(
            () -> assertEquals(new Rect(30, 2, 10, 7), layout.editorBounds()),
            () -> assertEquals(7, layout.contentColumns()),
            () -> assertEquals(7, layout.contentRows()),
            () -> assertNull(layout.scrollLayout().horizontalScrollbarBounds()),
            () -> assertNull(layout.scrollLayout().verticalScrollbarBounds())
        );
    }

    @Test
    void paneTooNarrowForTheGutterHasNoTextOrScrollbarOutsideItsBounds() {
        TextModel model = model("long text", "second", "third");
        for (int width = 0; width <= 3; width++) {
            EditorLayout layout = EditorLayout.resolve(model, new TerminalEditorTextLayout(model), new EditorState(),
                8, 80, 1, 0, false, false, 4, new Rect(30, 1, width, 6));
            assertEquals(0, layout.contentColumns());
            assertEquals(30 + width, layout.scrollLayout().contentBounds().x());
            assertTrue(layout.editorBounds().contains(layout.scrollLayout().contentBounds()));
            assertNull(layout.scrollLayout().horizontalScrollbarBounds());
            assertNull(layout.scrollLayout().verticalScrollbarBounds());
        }
    }

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
        TerminalEditorTextLayout textLayout = new TerminalEditorTextLayout(model);

        EditorLayout layout = EditorLayout.resolve(
            model,
            textLayout,
            state,
            5,
            12,
            1,
            0,
            false,
            false,
            4
        );

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
        TerminalEditorTextLayout textLayout = new TerminalEditorTextLayout(model);

        EditorLayout layout = EditorLayout.resolve(
            model,
            textLayout,
            state,
            5,
            9,
            1,
            0,
            true,
            false,
            4
        );

        assertAll(
            () -> assertEquals(5, layout.contentColumns()),
            () -> assertEquals(3, layout.contentRows()),
            () -> assertEquals(1, layout.scrollbarWidth()),
            () -> assertEquals(0, layout.horizontalScrollbarHeight()),
            () -> assertEquals(4, textLayout.totalWrappedRows(layout.contentColumns(), false, 4))
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

    @Test
    void horizontalScrollbarNeverTakesTheLastDocumentRow() {
        TextModel longLines = model("long text ".repeat(20), "second line");
        EditorLayout singleRow = resolve(longLines, 3, 100, 1, 0, false);
        EditorLayout twoRows = resolve(longLines, 4, 100, 1, 0, false);
        EditorLayout hiddenByOverlay = resolve(longLines, 5, 100, 1, 2, false);
        EditorLayout noRows = resolve(longLines, 2, 100, 1, 0, false);

        assertAll(
            () -> assertEquals(1, singleRow.contentRows()),
            () -> assertEquals(0, singleRow.horizontalScrollbarHeight()),
            () -> assertEquals(1, twoRows.contentRows()),
            () -> assertEquals(1, twoRows.horizontalScrollbarHeight()),
            () -> assertEquals(1, hiddenByOverlay.contentRows()),
            () -> assertEquals(0, hiddenByOverlay.horizontalScrollbarHeight()),
            () -> assertEquals(0, noRows.contentRows()),
            () -> assertEquals(0, noRows.horizontalScrollbarHeight())
        );
    }

    @Test
    void oneCellContentColumnAndOneRowViewportDoNotReserveInvisibleTracks() {
        EditorLayout narrow = resolve(model("long text", "second", "third"), 4, 4, 1, 0, false);
        assertAll(
            () -> assertEquals(2, narrow.contentRows()),
            () -> assertEquals(1, narrow.contentColumns()),
            () -> assertNull(narrow.scrollLayout().horizontalScrollbarBounds()),
            () -> assertEquals(0, narrow.horizontalScrollbarHeight())
        );

        for (boolean wrapped : new boolean[] {false, true}) {
            EditorLayout singleRow = resolve(model("long text", "second", "third"), 3, 20, 1, 0, wrapped);
            assertAll(
                () -> assertEquals(1, singleRow.contentRows()),
                () -> assertEquals(17, singleRow.contentColumns()),
                () -> assertNull(singleRow.scrollLayout().verticalScrollbarBounds()),
                () -> assertEquals(0, singleRow.scrollbarWidth())
            );
        }
    }

    @Test
    void competingTracksUseTheCommonTinyViewportPriority() {
        EditorLayout tiny = resolve(model("long text", "second", "third"), 4, 8, 1, 0, false);
        assertAll(
            () -> assertEquals(2, tiny.contentRows()),
            () -> assertEquals(4, tiny.contentColumns()),
            () -> assertNotNull(tiny.scrollLayout().verticalScrollbarBounds()),
            () -> assertNull(tiny.scrollLayout().horizontalScrollbarBounds())
        );
    }

    @Test
    void reservedDimensionsMatchSharedGeometryThroughTinySizesAndOverlays() {
        TextModel model = model("long text ".repeat(5), "second", "third");
        for (boolean wrapped : new boolean[] {false, true}) {
            for (int rows = 0; rows <= 8; rows++) {
                for (int columns = 0; columns <= 12; columns++) {
                    for (int overlays : new int[] {0, 2}) {
                        EditorLayout layout = resolve(model, rows, columns, 1, overlays, wrapped);
                        var scroll = layout.scrollLayout();
                        assertEquals(layout.contentRows(), scroll.contentBounds().height());
                        assertEquals(layout.contentColumns(), scroll.contentBounds().width());
                        assertEquals(layout.scrollbarWidth() != 0, scroll.verticalScrollbarBounds() != null);
                        assertEquals(layout.horizontalScrollbarHeight() != 0, scroll.horizontalScrollbarBounds() != null);
                        assertEquals(Math.max(0, Math.max(1, rows) - 2 - overlays),
                            layout.contentRows() + layout.horizontalScrollbarHeight());
                        if (scroll.verticalScrollbarBounds() != null) {
                            assertTrue(scroll.verticalScrollbarBounds().height() >= 2);
                        }
                        if (scroll.horizontalScrollbarBounds() != null) {
                            assertTrue(scroll.horizontalScrollbarBounds().width() >= 2);
                            assertTrue(layout.contentRows() >= 1);
                        }
                        if (wrapped) {
                            assertNull(scroll.horizontalScrollbarBounds());
                        }
                    }
                }
            }
        }
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
            new TerminalEditorTextLayout(model),
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

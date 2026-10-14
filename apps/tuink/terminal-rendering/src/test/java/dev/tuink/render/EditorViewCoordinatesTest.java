package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.text.TerminalText;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorViewCoordinates.Top;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorViewCoordinatesTest {
    @Test
    void overflowingSegmentsCrossLogicalLinesAndOutOfRangeOffsetsClampToTheFinalRow() {
        var coordinates = new EditorViewCoordinates(new TerminalEditorTextLayout(model("ab", "cdef", "")),
            2, true, false, 4);

        assertEquals(new Top(1, 0), coordinates.normalizeTop(new Top(0, 2)));
        assertEquals(new Top(1, 2), coordinates.normalizeTop(new Top(0, 4)));
        assertEquals(4, coordinates.rowOffset(new Top(0, 4)));
        assertEquals(new Top(2, 0), coordinates.normalizeTop(new Top(1, Integer.MAX_VALUE)));
        assertEquals(new Top(2, 0), coordinates.topAtRow(Integer.MAX_VALUE));
        assertEquals(new Top(0, 0), coordinates.topAtRow(-1));
        assertEquals(new Position(2, 0), coordinates.positionAt(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void textAndVisualCoordinatesRoundTripGraphemeBoundariesAcrossEveryGeometry() {
        TextModel model = model("A中😀B", "a\t👩‍💻e\u0301 words wrap here", "", "abcdefgh");
        var layout = new TerminalEditorTextLayout(model);
        for (boolean wrap : List.of(false, true)) {
            for (boolean words : List.of(false, true)) {
                for (int width : new int[] {1, 2, 4, 7, 12, 40}) {
                    for (int tabWidth : new int[] {2, 4}) {
                        var coordinates = new EditorViewCoordinates(layout, width, wrap, words, tabWidth);
                        for (int line = 0; line < model.lineCount(); line++) {
                            var metrics = TerminalText.metrics(model.getLine(line), tabWidth);
                            for (int offset = 0; offset <= model.getLine(line).length(); offset++) {
                                if (!metrics.isBoundary(offset)) {
                                    continue;
                                }
                                Position position = new Position(line, offset);
                                var visual = coordinates.toVisual(position);
                                assertEquals(position, coordinates.positionAt(visual.row(), visual.column()),
                                    position + " width=" + width + " wrap=" + wrap + " words=" + words);
                                assertEquals(visual.row(), coordinates.rowOffset(coordinates.topForAnchor(position)));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyVisualRowUsesTheSameAnchorForScrollingAndCursorRendering() {
        TextModel model = model("one two three extraordinary", "A\t界👩‍💻e\u0301", "", "abcd");
        var layout = new TerminalEditorTextLayout(model);
        var builder = builder(layout);
        EditorState state = new EditorState();
        for (boolean words : List.of(false, true)) {
            for (int cols : new int[] {7, 10, 16, 40}) {
                RenderPlan sizing = build(builder, model, state, cols, true, words);
                var coordinates = new EditorViewCoordinates(layout,
                    sizing.cols() - sizing.gutterWidth() - sizing.scrollbarWidth(), true, words, 4);
                for (int row = 0; row < coordinates.totalRows(); row++) {
                    Top top = coordinates.topAtRow(row);
                    Position anchor = coordinates.anchor(top);
                    coordinates.applyTop(state, top);
                    state.setCursor(anchor);

                    RenderPlan plan = build(builder, model, state, cols, true, words);

                    assertEquals(row, coordinates.rowOffset(top));
                    assertEquals(row, plan.topLine());
                    assertEquals(anchor, coordinates.positionInViewport(state, plan.contentRows(), 0, 0));
                    assertTrue(plan.cursorInViewport());
                    assertEquals(0, plan.cursorRow());
                    assertEquals(plan.gutterWidth(), plan.cursorCol());
                }
            }
        }
    }

    @Test
    void emptyRowsBelowEofHitTheFinalInsertionOffsetInBothWrapModes() {
        for (String last : List.of("last line 界e\u0301", "abcd", "")) {
            TextModel model = model("first", last);
            var layout = new TerminalEditorTextLayout(model);
            for (boolean wrap : List.of(false, true)) {
                var coordinates = new EditorViewCoordinates(layout, 4, wrap, false, 4);
                Position eof = new Position(1, last.length());
                for (int column : new int[] {0, 1, 99, Integer.MAX_VALUE}) {
                    assertEquals(eof, coordinates.positionAt(coordinates.totalRows(), column));
                    assertEquals(eof, coordinates.positionAt(Integer.MAX_VALUE, column));
                }
                EditorState state = new EditorState();
                assertNull(coordinates.positionInViewport(state, 3, -1, 0));
                assertNull(coordinates.positionInViewport(state, 3, 3, 0));
            }
        }
    }

    @Test
    void wrappedHitsKeepSharedBoundariesOnTheHitRowAndExposeTheEofInsertionRow() {
        var coordinates = new EditorViewCoordinates(new TerminalEditorTextLayout(model("abcdefgh")), 4, true, false, 4);

        assertEquals(new Position(0, 3), coordinates.positionAt(0, 99));
        assertEquals(new Position(0, 7), coordinates.positionAt(1, 99));
        assertEquals(new Position(0, 8), coordinates.positionAt(2, 99));
        assertEquals(new Top(0, 1), coordinates.topForAnchor(new Position(0, 4)));
        assertEquals(new Top(0, 2), coordinates.topForAnchor(new Position(0, 8)));
    }

    @Test
    void reflowAnchorsAndPointerMappingUseTheSameWordAndGraphemeBoundaries() {
        TextModel model = model("prefix", "e\u0301界\t👩‍💻 alpha beta\t words ".repeat(10));
        var layout = new TerminalEditorTextLayout(model);
        var narrow = new EditorViewCoordinates(layout, 7, true, false, 2);
        Position anchor = narrow.anchor(new Top(1, 13));
        for (boolean words : List.of(false, true)) {
            for (int width : new int[] {1, 4, 12, 20}) {
                var reflowed = new EditorViewCoordinates(layout, width, true, words, 4);
                Top top = reflowed.topForAnchor(anchor);
                Position visibleStart = reflowed.anchor(top);
                EditorState state = new EditorState();
                reflowed.applyTop(state, top);

                assertEquals(visibleStart, reflowed.positionInViewport(state, 3, 0, 0));
                assertEquals(0, reflowed.inViewport(anchor, state).row());
                assertEquals(new Top(1, 13), narrow.topForAnchor(anchor));
                assertTrue(TerminalText.metrics(model.getLine(visibleStart.line()), 4).isBoundary(visibleStart.col()));
            }
        }
    }

    @Test
    void horizontalOffsetsInsideTabsAgreeWithCursorAndPointerCoordinates() {
        TextModel model = model("\tX中😀");
        var coordinates = new EditorViewCoordinates(new TerminalEditorTextLayout(model), 8, false, false, 4);
        EditorState state = new EditorState();
        coordinates.applyHorizontalOffset(state, 2);

        assertEquals(2, state.getLeftVisualColumn());
        assertEquals(0, state.getLeftColumn());
        assertEquals(new Position(0, 1), coordinates.positionInViewport(state, 2, 0, 2));
        assertEquals(new EditorViewCoordinates.VisualPosition(0, 2), coordinates.inViewport(new Position(0, 1), state));
        assertEquals(new Position(0, 2), coordinates.positionInViewport(state, 2, 0, 3));
        assertEquals(new Position(0, 2), coordinates.positionInViewport(state, 2, 0, 4));
    }

    @Test
    void coordinatesObserveEditsThroughTheInjectedLayoutWithoutRetainingTheirOwnCache() {
        TextModel model = model("abcd");
        var coordinates = new EditorViewCoordinates(new TerminalEditorTextLayout(model), 4, true, false, 4);
        assertEquals(2, coordinates.totalRows());

        model.replaceLines(List.of("界", "second line"));

        assertEquals(new Position(1, 11), coordinates.positionAt(Integer.MAX_VALUE, 0));
        assertEquals(new Position(1, 4), coordinates.anchor(new Top(1, 1)));
        assertEquals(new EditorViewCoordinates.VisualPosition(0, 2), coordinates.toVisual(new Position(0, 1)));
    }

    private static RenderPlanBuilder builder(TerminalEditorTextLayout layout) {
        var builder = new RenderPlanBuilder(line -> List.of(), layout);
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder;
    }

    private static RenderPlan build(RenderPlanBuilder builder, TextModel model, EditorState state,
        int cols, boolean wrap, boolean words) {
        return builder.build(model, state, 8, cols, "", "", null, SearchOptions.DEFAULT,
            null, null, null, null, wrap, words);
    }

    private static TextModel model(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        return model;
    }
}

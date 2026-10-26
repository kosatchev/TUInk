package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderTest {
    @Test
    void cursorVisibilityIsRecordedBeforeVerticalCoordinateIsClamped() {
        TextModel model = model("zero", "one", "two", "three", "four");
        EditorState state = new EditorState();
        state.setCursor(new Position(0, 0));
        state.setTopLine(3);

        RenderPlan plan = build(model, state, 5, 12, false, false);

        assertAll(
            () -> assertFalse(plan.cursorInViewport()),
            () -> assertEquals(0, plan.cursorRow()),
            () -> assertTrue(plan.cursorCol() >= 0),
            () -> assertTrue(plan.cursorCol() < plan.cols())
        );
    }

    @Test
    void cursorVisibilityIsRecordedBeforeHorizontalCoordinateIsClamped() {
        TextModel model = model("0123456789");
        EditorState state = new EditorState();
        state.setCursor(new Position(0, 0));
        state.setLeftVisualColumn(4);

        RenderPlan leftOfViewport = build(model, state, 5, 8, false, false);

        state.setLeftVisualColumn(0);
        state.setCursor(new Position(0, 10));
        RenderPlan rightOfViewport = build(model, state, 5, 8, false, false);

        assertAll(
            () -> assertFalse(leftOfViewport.cursorInViewport()),
            () -> assertEquals(leftOfViewport.gutterWidth(), leftOfViewport.cursorCol()),
            () -> assertFalse(rightOfViewport.cursorInViewport()),
            () -> assertEquals(
                rightOfViewport.cols() - 1 - rightOfViewport.scrollbarWidth(),
                rightOfViewport.cursorCol()
            )
        );
    }

    @Test
    void visibleCursorIsMarkedVisible() {
        TextModel model = model("abcdef");
        EditorState state = new EditorState();
        state.setCursor(new Position(0, 3));

        RenderPlan plan = build(model, state, 5, 12, false, false);

        assertAll(
            () -> assertTrue(plan.cursorInViewport()),
            () -> assertEquals(plan.gutterWidth() + 3, plan.cursorCol()),
            () -> assertEquals(0, plan.cursorRow())
        );
    }

    @Test
    void clippedTabRetainsAbsoluteSourceColumnAndHiddenCellCount() {
        TextModel model = model("A\tB");
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);

        RenderPlan plan = build(model, state, 5, 8, false, false);
        LineVisual line = plan.lines().get(0);

        assertAll(
            () -> assertEquals("\tB", line.text()),
            () -> assertEquals(1, line.sourceVisualColumn()),
            () -> assertEquals(1, line.leadingClippedCells()),
            () -> assertEquals(
                plan.leftVisualColumn(),
                line.sourceVisualColumn() + line.leadingClippedCells()
            )
        );
    }

    @Test
    void wrappedTabSegmentKeepsAbsoluteTabStopMetadata() {
        TextModel model = model("ab\tc");
        EditorState state = new EditorState();
        state.setTopWrapSegment(1);
        state.setCursor(new Position(0, 2));

        RenderPlan plan = build(model, state, 5, 6, true, false);
        LineVisual line = plan.lines().get(0);

        assertAll(
            () -> assertEquals("\tc", line.text()),
            () -> assertEquals(2, line.sourceVisualColumn()),
            () -> assertEquals(0, line.leadingClippedCells()),
            () -> assertEquals(1, plan.topLine()),
            () -> assertEquals(0, plan.cursorRow()),
            () -> assertEquals(plan.gutterWidth(), plan.cursorCol()),
            () -> assertTrue(plan.cursorInViewport())
        );
    }

    @Test
    void clippedWideGraphemeKeepsWholeClusterAndVisualMetadata() {
        String family = "👨‍👩‍👧‍👦";
        TextModel model = model("A" + family + "B");
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);

        RenderPlan plan = build(model, state, 5, 4, false, false);
        LineVisual line = plan.lines().get(0);

        assertAll(
            () -> assertEquals(family, line.text()),
            () -> assertEquals(1, line.sourceVisualColumn()),
            () -> assertEquals(1, line.leadingClippedCells())
        );
    }

    @Test
    void visualCacheRecomputesOnlyTheChangedLineSemantically() {
        String family = "👨‍👩‍👧‍👦";
        TextModel model = model("A" + family + "B");
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);
        RenderPlanBuilder builder = builder(model);

        build(builder, model, state, 5, 4, false, false);
        model.insertChar(new Position(0, 0), 'X');
        RenderPlan changed = build(builder, model, state, 5, 4, false, false);
        LineVisual line = changed.lines().get(0);

        assertAll(
            () -> assertEquals(family, line.text()),
            () -> assertEquals(2, line.sourceVisualColumn()),
            () -> assertEquals(0, line.leadingClippedCells())
        );
    }

    @Test
    void tabWidthChangeDoesNotReuseStaleVisualMetrics() {
        TextModel model = model("A\tB");
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);
        RenderPlanBuilder builder = builder(model);

        RenderPlan tabFour = build(builder, model, state, 5, 6, false, false);
        builder.setTabWidth(2);
        RenderPlan tabTwo = build(builder, model, state, 5, 6, false, false);

        assertAll(
            () -> assertEquals("\tB", tabFour.lines().get(0).text()),
            () -> assertEquals(1, tabFour.lines().get(0).sourceVisualColumn()),
            () -> assertEquals(1, tabFour.lines().get(0).leadingClippedCells()),
            () -> assertEquals("B", tabTwo.lines().get(0).text()),
            () -> assertEquals(2, tabTwo.lines().get(0).sourceVisualColumn()),
            () -> assertEquals(0, tabTwo.lines().get(0).leadingClippedCells())
        );
    }

    @Test
    void narrowTerminalsKeepPlanCoordinatesAndMetadataBounded() {
        TextModel model = model("\t界abc", "second");
        EditorState state = new EditorState();
        state.setCursor(new Position(0, model.getLine(0).length()));

        for (int rows = 1; rows <= 4; rows++) {
            for (int cols = 1; cols <= 7; cols++) {
                assertBounded(build(model, state, rows, cols, false, false), rows, cols);
                assertBounded(build(model, state, rows, cols, true, false), rows, cols);
                assertBounded(build(model, state, rows, cols, true, true), rows, cols);
            }
        }
    }

    private static void assertBounded(RenderPlan plan, int rows, int cols) {
        assertAll(
            "terminal " + rows + "x" + cols,
            () -> assertEquals(cols, plan.cols()),
            () -> assertEquals(plan.contentRows(), plan.lines().size()),
            () -> assertTrue(plan.cursorRow() >= 0),
            () -> assertTrue(plan.cursorRow() < Math.max(1, plan.contentRows())),
            () -> assertTrue(plan.cursorCol() >= 0),
            () -> assertTrue(plan.cursorCol() < cols),
            () -> assertTrue(plan.scrollbarWidth() >= 0),
            () -> assertTrue(plan.scrollbarWidth() <= 1),
            () -> assertTrue(
                plan.lines().stream().allMatch(
                    line -> line.sourceVisualColumn() >= 0 && line.leadingClippedCells() >= 0
                )
            )
        );
    }

    private static RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        return build(builder(model), model, state, rows, cols, wrapLines, wrapByWords);
    }

    private static RenderPlanBuilder builder(TextModel model) {
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(
            line -> List.of(),
            new TerminalEditorTextLayout(model)
        );
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder;
    }

    private static RenderPlan build(
        RenderPlanBuilder builder,
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        return builder.build(
            model,
            state,
            rows,
            cols,
            "top",
            "bottom",
            null,
            SearchOptions.DEFAULT,
            null,
            null,
            null,
            null,
            wrapLines,
            wrapByWords
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

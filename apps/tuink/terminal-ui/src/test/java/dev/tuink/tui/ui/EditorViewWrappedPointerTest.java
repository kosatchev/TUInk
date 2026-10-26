package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditorViewWrappedPointerTest {
    @Test
    void clickingTheBlankTailOfAWordWrappedRowKeepsTheCaretOnThatRow() {
        Fixture fixture = fixture(12, true, "one two three extraordinary tail words");
        assertEquals("one two ", fixture.plan().lines().getFirst().text());

        fixture.click(0, 11, false);

        assertCaret(fixture, new Position(0, 7), 0, 7);
    }

    @Test
    void shiftClickAndDragShareTheSameWrappedRowBoundary() {
        Fixture fixture = fixture(12, true, "prefix", "one two three extraordinary tail words");
        fixture.engine.moveCursorTo(new Position(1, 1));
        fixture.click(1, 11, true);

        assertEquals(new Position(1, 1), fixture.state.getSelectionAnchor());
        assertCaret(fixture, new Position(1, 7), 1, 7);
        assertEquals("ne two", fixture.model.getTextRange(fixture.state.getSelectionAnchor(), fixture.state.getCursor()));

        fixture.view.cancelMouseSelection();
        fixture.press(1, 1, false);
        RenderPlan plan = fixture.plan();
        fixture.view.handleMouseDrag(
            pointer(plan, 1, 11, PointerEvent.Kind.DRAG, false),
            fixture.model, fixture.state, plan, plan.contentRows(), fixture.engine, 1
        );
        fixture.view.handleMouseRelease(fixture.engine);

        assertEquals(new Position(1, 1), fixture.state.getSelectionAnchor());
        assertCaret(fixture, new Position(1, 7), 1, 7);
    }

    @Test
    void hardWrappedBlankTailsStayBeforeTheNextSegmentWithoutSplittingGraphemes() {
        for (Case example : List.of(
            new Case("ab界x", 3, 2, 1),
            new Case("abce\u0301界", 5, 4, 3),
            new Case("abc👨‍👩‍👧‍👦界", 6, 5, 3),
            new Case("a\tZ", 2, 1, 0)
        )) {
            Fixture fixture = fixture(example.width(), false, example.text());

            fixture.click(0, example.pointerColumn(), false);

            assertCaret(fixture, new Position(0, example.expectedColumn()), 0, example.expectedColumn());
        }
    }

    @Test
    void draggingPastAFullRowAndAnOversizedTabCannotEnterTheNextRow() {
        Fixture ascii = fixture(4, false, "abcdefghij");
        assertEquals(new Position(0, 3), ascii.hit(0, 20));

        Fixture tab = fixture(1, false, "\tX");
        assertEquals(List.of(0, 1, 2), tab.layout.wrapStarts(0, 1, false, 4));
        assertEquals(new Position(0, 0), tab.hit(0, 20));
        assertEquals(new Position(0, 1), tab.hit(1, 20));
    }

    @Test
    void aViewportStartingInsideAWrappedLineClampsAgainstTheDisplayedSegment() {
        Fixture fixture = fixture(12, true, "hidden", "one two three extraordinary tail words ".repeat(4));
        fixture.state.setTopLine(1);
        fixture.state.setTopWrapSegment(1);
        assertEquals("three ", fixture.plan().lines().getFirst().text());

        fixture.click(0, 11, false);

        assertCaret(fixture, new Position(1, 13), 0, 5);
        assertEquals(new Position(1, 8), fixture.hit(0, -1));
        assertEquals(1, fixture.state.getTopLine());
        assertEquals(1, fixture.state.getTopWrapSegment());
    }

    @Test
    void finalSegmentsAndTheEmptyInsertionRowRemainClickableAtLineEnd() {
        Fixture partial = fixture(12, true, "one two three");
        partial.click(1, 11, false);
        assertCaret(partial, new Position(0, 13), 1, 5);

        Fixture full = fixture(4, false, "abcdefgh");
        full.click(2, 3, false);
        assertCaret(full, new Position(0, 8), 2, 0);

        Fixture empty = fixture(12, true, "");
        empty.click(0, 11, false);
        assertCaret(empty, new Position(0, 0), 0, 0);
    }

    @Test
    void blankRowsBelowTheDocumentHitTheEndOfTheFinalLineInEitherWrapMode() {
        for (boolean wrapLines : List.of(false, true)) {
            for (boolean wrapByWords : List.of(false, true)) {
                Fixture fixture = fixture(wrapLines, 12, wrapByWords, "first", "last line has more words 界e\u0301");
                Position end = new Position(1, fixture.model.getLine(1).length());

                assertEquals(end, fixture.hit(8, 0));
                assertEquals(end, fixture.hit(8, 11));
                fixture.click(8, 0, false);

                assertEquals(end, fixture.state.getCursor());
            }
        }
    }

    @Test
    void draggingBelowTheDocumentIncludesItsEntireFinalLineWhenReplacingInEitherWrapMode() {
        for (boolean wrapLines : List.of(false, true)) {
            for (boolean wrapByWords : List.of(false, true)) {
                Fixture fixture = fixture(wrapLines, 12, wrapByWords, "first", "last line has more words 界e\u0301");
                fixture.press(0, 0, false);
                RenderPlan plan = fixture.plan();
                fixture.view.handleMouseDrag(
                    pointer(plan, plan.contentRows() + 3, 0, PointerEvent.Kind.DRAG, false),
                    fixture.model, fixture.state, plan, plan.contentRows(), fixture.engine, 1
                );
                fixture.view.handleMouseRelease(fixture.engine);

                assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
                assertEquals(new Position(1, fixture.model.getLine(1).length()), fixture.state.getCursor());
                fixture.engine.insertInput("replacement");

                assertEquals(1, fixture.model.lineCount());
                assertEquals("replacement", fixture.model.getLine(0));
            }
        }
    }

    @Test
    void blankRowsAfterAnEmptyFinalLinePreserveItsInsertionPosition() {
        for (boolean wrapLines : List.of(false, true)) {
            Fixture fixture = fixture(wrapLines, 12, true, "first", "");

            assertEquals(new Position(1, 0), fixture.hit(8, 11));
        }
    }

    @Test
    void unwrappedEofClampingPreservesHitsOnTheLastLineAndRejectsRowsOutsideContent() {
        Fixture fixture = fixture(false, 12, false, "first", "last");

        assertEquals(new Position(1, 0), fixture.hit(1, 0));
        assertEquals(new Position(1, 4), fixture.hit(2, 0));
        assertNull(fixture.hit(-1, 0));
        assertNull(fixture.hit(fixture.plan().contentRows(), 0));
    }

    private static void assertCaret(Fixture fixture, Position position, int row, int column) {
        assertEquals(position, fixture.state.getCursor());
        RenderPlan plan = fixture.plan();
        assertTrue(plan.cursorInViewport());
        assertEquals(row, plan.cursorRow());
        assertEquals(plan.gutterWidth() + column, plan.cursorCol());
    }

    private static Fixture fixture(int width, boolean wrapByWords, String... lines) {
        return fixture(true, width, wrapByWords, lines);
    }

    private static Fixture fixture(boolean wrapLines, int width, boolean wrapByWords, String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        EditorState state = new EditorState();
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        return new Fixture(model, state, layout, new EditorEngine(model, state, layout), wrapLines, width, wrapByWords);
    }

    private static PointerEvent pointer(RenderPlan plan, int row, int column, PointerEvent.Kind kind, boolean shift) {
        return new PointerEvent(
            1 + row, plan.gutterWidth() + column, kind, PointerEvent.Button.LEFT,
            shift ? Set.of(KeyStroke.Modifier.SHIFT) : Set.of()
        );
    }

    private record Case(String text, int width, int pointerColumn, int expectedColumn) {
    }

    private static final class Fixture {
        private final TextModel model;
        private final EditorState state;
        private final TerminalEditorTextLayout layout;
        private final EditorEngine engine;
        private final boolean wrapLines;
        private final int width;
        private final boolean wrapByWords;
        private final EditorView view = new EditorView();
        private final RenderPlanBuilder builder;

        private Fixture(
            TextModel model, EditorState state, TerminalEditorTextLayout layout,
            EditorEngine engine, boolean wrapLines, int width, boolean wrapByWords
        ) {
            this.model = model;
            this.state = state;
            this.layout = layout;
            this.engine = engine;
            this.wrapLines = wrapLines;
            this.width = width;
            this.wrapByWords = wrapByWords;
            builder = RenderPlanBuilder.standalone(line -> List.of(), layout);
            builder.setSyntaxHighlightingEnabled(false);
            builder.setBracketMatchingEnabled(false);
            builder.setRainbowBracketsEnabled(false);
        }

        private RenderPlan plan() {
            // The scrolled fixture needs a vertical scrollbar. Other fixtures
            // fit vertically, preserving the requested text width in both cases.
            int scrollbar = state.getTopLine() > 0 || state.getTopWrapSegment() > 0 ? 1 : 0;
            RenderPlan plan = builder.build(
                model, state, 12, width + 3 + scrollbar, "", "", null, SearchOptions.DEFAULT,
                null, null, null, null, wrapLines, wrapByWords
            );
            assertEquals(width, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
            return plan;
        }

        private Position hit(int row, int column) {
            RenderPlan plan = plan();
            return view.mouseToTextPosition(
                pointer(plan, row, column, PointerEvent.Kind.PRESS, false),
                model, layout, state, plan, plan.contentRows(), 1
            );
        }

        private void press(int row, int column, boolean shift) {
            RenderPlan plan = plan();
            view.handleMousePress(
                pointer(plan, row, column, PointerEvent.Kind.PRESS, shift),
                model, state, plan, plan.contentRows(), engine, 1, shift
            );
        }

        private void click(int row, int column, boolean shift) {
            press(row, column, shift);
            view.handleMouseRelease(engine);
        }
    }
}

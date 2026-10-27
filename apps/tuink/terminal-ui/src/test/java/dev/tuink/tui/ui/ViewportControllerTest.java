package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuiframe.input.PointerEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ViewportControllerTest {
    @Test
    void completedCommandWithoutDocumentEffectsPreservesManualViewportAndSelection() {
        Fixture fixture = manuallyPannedFixture();
        Position cursor = new Position(0, 2);
        Position anchor = new Position(1, 1);
        fixture.state().setCursor(cursor);
        fixture.state().setSelectionAnchor(anchor);

        fixture.controller().onCommandResult(ViewportController.CursorPolicy.PRESERVE);
        fixture.ensureCursorVisible();

        assertEquals(7, fixture.state().getTopLine());
        assertEquals(10, fixture.state().getLeftVisualColumn());
        assertEquals(cursor, fixture.state().getCursor());
        assertEquals(anchor, fixture.state().getSelectionAnchor());
    }

    @Test
    void completedDocumentInteractionRestoresCursorFollowing() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().onCommandResult(ViewportController.CursorPolicy.FOLLOW_CURSOR);
        fixture.ensureCursorVisible();

        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void pageScrollRemainsUntilACompletedCursorInteraction() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().onCommandResult(ViewportController.CursorPolicy.PAGE_SCROLL);
        fixture.ensureCursorVisible();

        assertEquals(7, fixture.state().getTopLine());
        assertEquals(10, fixture.state().getLeftVisualColumn());
        fixture.controller().onCommandResult(ViewportController.CursorPolicy.FOLLOW_CURSOR);
        fixture.ensureCursorVisible();
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void saveAndTabRoundTripPreserveWrappedViewportWhileAnotherTabFollowsItsCursor() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("0123456789".repeat(8), "another line"));
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        state.setTopWrapSegment(7);
        ViewportController controller = new ViewportController();
        controller.onManualVerticalPan();

        controller.onCommandResult(ViewportController.CursorPolicy.PRESERVE);
        controller.ensureCursorVisible(engine, 3, 8, true, false);
        assertEquals(7, state.getTopWrapSegment());

        controller.onCommandResult(ViewportController.CursorPolicy.PRESERVE);
        Fixture anotherTab = manuallyPannedFixture();
        anotherTab.controller().onCommandResult(ViewportController.CursorPolicy.FOLLOW_CURSOR);
        anotherTab.ensureCursorVisible();
        anotherTab.controller().onCommandResult(ViewportController.CursorPolicy.PRESERVE);
        controller.ensureCursorVisible(engine, 3, 8, true, false);
        assertEquals(0, state.getTopLine());
        assertEquals(7, state.getTopWrapSegment());
        assertEquals(0, anotherTab.state().getTopLine());
        assertEquals(0, anotherTab.state().getLeftVisualColumn());

        controller.onCommandResult(ViewportController.CursorPolicy.FOLLOW_CURSOR);
        controller.ensureCursorVisible(engine, 3, 8, true, false);
        assertEquals(0, state.getTopWrapSegment());
    }

    @Test
    void pageScrollingWithASelectionSurvivesTheFollowingRenderAndResumesCursorFollowing() {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, "abcdefghijklmnopqrstuvwxyz1234");
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        ViewportController controller = new ViewportController();
        engine.moveCursorTo(new Position(0, 1));
        EditorViewNavigation navigation = dev.tuink.tui.TuiSessionTestSupport.navigation(engine);
        navigation.moveDown(true, 4, true, false);

        for (int expectedTop : new int[] {3, 5}) {
            controller.onCommandResult(ViewportController.CursorPolicy.PAGE_SCROLL);
            navigation.pageDown(3, 4, true, false);
            controller.ensureCursorVisible(engine, 3, 4, true, false);
            assertEquals(expectedTop, state.getTopWrapSegment());
            assertEquals(new Position(0, 5), state.getCursor());
            assertEquals(new Position(0, 1), state.getSelectionAnchor());
        }

        controller.onCommandResult(ViewportController.CursorPolicy.FOLLOW_CURSOR);
        engine.moveRight();
        controller.ensureCursorVisible(engine, 3, 4, true, false);
        assertEquals(1, state.getTopWrapSegment());
    }

    @Test
    void growingTheViewportClampsManualPanningWithoutMovingTheTextCursorOrSelection() {
        Fixture fixture = manuallyPannedFixture();
        Position cursor = new Position(0, 2);
        Position anchor = new Position(1, 1);
        fixture.state().setCursor(cursor);
        fixture.state().setSelectionAnchor(anchor);
        fixture.state().setTopLine(17);
        fixture.state().setLeftVisualColumn(72);

        fixture.controller().ensureCursorVisible(fixture.engine(), 10, 75, false, false, 4);

        assertEquals(10, fixture.state().getTopLine());
        assertEquals(5, fixture.state().getLeftVisualColumn());
        assertEquals(5, fixture.state().getLeftColumn());
        assertEquals(cursor, fixture.state().getCursor());
        assertEquals(anchor, fixture.state().getSelectionAnchor());
    }

    @Test
    void growingAWrappedViewportUsesTheActiveTabWidthAndKeepsTheCursor() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(java.util.List.of("\t".repeat(10)));
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        engine.setTabWidth(2);
        state.setTopWrapSegment(4);
        Position cursor = new Position(0, 1);
        state.setCursor(cursor);
        ViewportController controller = new ViewportController();
        controller.onManualVerticalPan();

        controller.ensureCursorVisible(engine, 4, 4, true, false, 2);

        assertEquals(0, state.getTopLine());
        assertEquals(2, state.getTopWrapSegment());
        assertEquals(cursor, state.getCursor());
    }

    @Test
    void aHiddenViewportKeepsItsManualPositionUntilItBecomesVisible() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().ensureCursorVisible(fixture.engine(), 0, 0, false, false, 4);

        assertEquals(7, fixture.state().getTopLine());
        assertEquals(10, fixture.state().getLeftVisualColumn());
    }

    @Test
    void wrappedResizeKeepsTheTopTextAndRoundTripsWithoutDrifting() {
        Fixture fixture = wrappedFixture("0123456789".repeat(100), 17);
        Position cursor = new Position(0, 2);
        Position anchor = new Position(0, 5);
        fixture.state().setCursor(cursor);
        fixture.state().setSelectionAnchor(anchor);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);

        for (int width : new int[] {12, 11, 19, 8}) {
            fixture.controller().ensureCursorVisible(fixture.engine(), 3, width, true, false, 4);

            assertEquals(136 / width, fixture.state().getTopWrapSegment());
            assertEquals(0, fixture.state().getTopLine());
            assertEquals(cursor, fixture.state().getCursor());
            assertEquals(anchor, fixture.state().getSelectionAnchor());
        }
    }

    @Test
    void resizedTopRowIsTheSameForPointerHitsRenderingAndTheRetainedTextAnchor() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("prefix", "e\u0301界\t👩‍💻 alpha beta ".repeat(50)));
        EditorState state = new EditorState();
        var layout = new TerminalEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, layout);
        engine.setTabWidth(2);
        state.setTopLine(1);
        state.setTopWrapSegment(13);
        Position cursor = new Position(0, 2);
        state.setCursor(cursor);
        ViewportController controller = new ViewportController();
        controller.onManualVerticalPan();
        controller.ensureCursorVisible(engine, 4, 11, true, true, 2);
        var initial = new EditorViewCoordinates(layout, 11, true, true, 2);
        Position anchor = initial.anchor(EditorViewCoordinates.Top.from(state));
        var builder = RenderPlanBuilder.standalone(line -> List.of(), layout);
        builder.setTabWidth(2);
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        EditorView view = new EditorView();
        view.setTabWidth(2);

        for (int width : new int[] {17, 1, 12, 11}) {
            controller.ensureCursorVisible(engine, 4, width, true, true, 2);
            var coordinates = new EditorViewCoordinates(layout, width, true, true, 2);
            int columns = width + 3 + (width + 3 >= 8 ? 1 : 0);
            var plan = builder.build(model, state, 6, columns, "", "", null, SearchOptions.DEFAULT,
                null, null, null, null, true, true);
            assertEquals(width, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
            Position hit = view.mouseToTextPosition(new PointerEvent(1, plan.gutterWidth(),
                PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()), model, layout, state, plan, 4, 1);

            assertEquals(coordinates.anchor(EditorViewCoordinates.Top.from(state)), hit);
            assertEquals(coordinates.toVisual(hit).row(), plan.topLine());
            assertEquals(0, coordinates.inViewport(anchor, state).row());
            assertTrue(model.getLine(hit.line()).substring(hit.col()).startsWith(plan.lines().getFirst().text()));
            assertEquals(cursor, state.getCursor());
        }
        assertEquals(13, state.getTopWrapSegment());
    }

    @Test
    void resizeUsesTheLatestManualScrollInsteadOfTheLastRenderedPosition() {
        Fixture fixture = wrappedFixture("0123456789".repeat(100), 17);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);
        fixture.state().setTopWrapSegment(23);
        fixture.controller().onManualVerticalPan();

        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 12, true, false, 4);
        assertEquals(15, fixture.state().getTopWrapSegment());
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);
        assertEquals(23, fixture.state().getTopWrapSegment());
    }

    @Test
    void wideningDoesNotCarryAnObsoleteSegmentNumberIntoTheNextLogicalLine() {
        Fixture fixture = wrappedFixture("prefix", 0);
        fixture.engine().model().replaceLines(List.of(
            "prefix", "abcdefghijklmnopqrst", "remaining document ".repeat(30)
        ));
        fixture.state().setTopLine(1);
        fixture.state().setTopWrapSegment(3);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 4, true, false, 4);

        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 12, true, false, 4);

        assertEquals(1, fixture.state().getTopLine());
        assertEquals(1, fixture.state().getTopWrapSegment());
    }

    @Test
    void wrapChangesKeepAGraphemeSafeTextAnchorWithWideCharactersAndTabs() {
        for (boolean wrapWords : List.of(false, true)) {
            Fixture fixture = wrappedFixture("e\u0301界\t👨‍👩‍👧‍👦 alpha beta\t界🌲 ".repeat(40), 12);
            fixture.engine().setTabWidth(2);
            fixture.controller().ensureCursorVisible(fixture.engine(), 3, 11, true, wrapWords, 2);
            int textAnchor = fixture.engine().textLayout().wrapStarts(0, 11, wrapWords, 2).get(12);

            fixture.engine().setTabWidth(4);
            fixture.controller().ensureCursorVisible(fixture.engine(), 3, 17, true, !wrapWords, 4);

            List<Integer> starts = fixture.engine().textLayout().wrapStarts(0, 17, !wrapWords, 4);
            int segment = fixture.state().getTopWrapSegment();
            assertTrue(starts.get(segment) <= textAnchor);
            assertTrue(segment + 1 == starts.size() || starts.get(segment + 1) > textAnchor);

            fixture.engine().setTabWidth(2);
            fixture.controller().ensureCursorVisible(fixture.engine(), 3, 11, true, wrapWords, 2);
            assertEquals(12, fixture.state().getTopWrapSegment());
        }
    }

    @Test
    void zeroSizedIntermediateFramesKeepTheLastVisibleWrapGeometry() {
        Fixture fixture = wrappedFixture("0123456789".repeat(100), 17);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);

        for (int[] size : List.of(new int[] {0, 0}, new int[] {0, 5}, new int[] {3, 0})) {
            fixture.controller().ensureCursorVisible(fixture.engine(), size[0], size[1], true, false, 4);
            assertEquals(17, fixture.state().getTopWrapSegment());
        }

        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 12, true, false, 4);
        assertEquals(11, fixture.state().getTopWrapSegment());
    }

    @Test
    void eachTabReanchorsUsingItsOwnLastVisibleGeometry() {
        Fixture first = wrappedFixture("0123456789".repeat(100), 17);
        Fixture second = wrappedFixture("0123456789".repeat(100), 20);
        first.controller().ensureCursorVisible(first.engine(), 3, 8, true, false, 4);
        second.controller().ensureCursorVisible(second.engine(), 3, 9, true, false, 4);

        second.controller().ensureCursorVisible(second.engine(), 3, 13, true, false, 4);
        first.controller().ensureCursorVisible(first.engine(), 3, 12, true, false, 4);

        assertEquals(13, second.state().getTopWrapSegment());
        assertEquals(11, first.state().getTopWrapSegment());
    }

    @Test
    void clampingAfterResizeEstablishesANewAnchor() {
        Fixture fixture = wrappedFixture("0123456789".repeat(100), 17);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);

        fixture.controller().ensureCursorVisible(fixture.engine(), 20, 80, true, false, 4);
        assertEquals(0, fixture.state().getTopWrapSegment());
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);
        assertEquals(0, fixture.state().getTopWrapSegment());
    }

    @Test
    void navigationAfterResizeResumesCursorFollowingAndReplacesTheOldAnchor() {
        Fixture fixture = wrappedFixture("0123456789".repeat(100), 17);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 12, true, false, 4);

        fixture.controller().onCommandResult(ViewportController.CursorPolicy.FOLLOW_CURSOR);
        fixture.engine().moveRight();
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 12, true, false, 4);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);

        assertEquals(0, fixture.state().getTopWrapSegment());
        assertEquals(new Position(0, 1), fixture.state().getCursor());
    }

    @Test
    void changedDocumentInvalidatesTheRetainedTextAnchor() {
        Fixture fixture = wrappedFixture("0123456789".repeat(100), 17);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 8, true, false, 4);
        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 12, true, false, 4);
        fixture.engine().model().replaceLines(List.of("new contents ".repeat(100)));

        fixture.controller().ensureCursorVisible(fixture.engine(), 3, 4, true, false, 4);

        assertEquals(33, fixture.state().getTopWrapSegment());
    }

    private static Fixture manuallyPannedFixture() {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, "0123456789".repeat(8));
        for (int line = 1; line < 20; line++) {
            buffer.insertLine(line, "row-" + line);
        }
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        state.setTopLine(7);
        state.setLeftColumn(10);
        state.setLeftVisualColumn(10);
        ViewportController controller = new ViewportController();
        controller.onScrollbarInteraction(
            new EditorView.ScrollPointerResult(true, EditorView.ScrollAxis.HORIZONTAL, 0),
            0,
            10,
            0,
            7
        );
        return new Fixture(state, engine, controller);
    }

    private static Fixture wrappedFixture(String text, int segment) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(text));
        EditorState state = new EditorState();
        state.setTopWrapSegment(segment);
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        ViewportController controller = new ViewportController();
        controller.onManualVerticalPan();
        return new Fixture(state, engine, controller);
    }

    private record Fixture(EditorState state, EditorEngine engine, ViewportController controller) {
        private void ensureCursorVisible() {
            controller.ensureCursorVisible(engine, 3, 8, false, false);
        }
    }
}

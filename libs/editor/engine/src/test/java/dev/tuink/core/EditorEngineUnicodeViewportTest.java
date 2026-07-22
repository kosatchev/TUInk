package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.unicode.GraphemeText;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineUnicodeViewportTest {
    private static final String COMBINING_E = "e\u0301";
    private static final String FAMILY = "👨‍👩‍👧‍👦";

    @Test
    void horizontalCursorMovementVisitsOnlyGraphemeBoundaries() {
        Fixture fixture = fixture("A" + COMBINING_E + FAMILY + "中");
        String text = fixture.model().getLine(0);

        for (int expected : List.of(1, 3, 14, 15)) {
            fixture.engine().moveRight();
            assertEquals(new Position(0, expected), fixture.state().getCursor());
            assertEquals(true, GraphemeText.isBoundary(text, expected));
        }

        for (int expected : List.of(14, 3, 1, 0)) {
            fixture.engine().moveLeft();
            assertEquals(new Position(0, expected), fixture.state().getCursor());
            assertEquals(true, GraphemeText.isBoundary(text, expected));
        }
    }

    @Test
    void horizontalMovementNormalizesCursorPlacedInsideACluster() {
        Fixture fixture = fixture("A" + FAMILY + "B");

        fixture.state().setCursor(new Position(0, 5));
        fixture.engine().moveLeft();
        assertEquals(new Position(0, 1), fixture.state().getCursor());

        fixture.state().setCursor(new Position(0, 5));
        fixture.engine().moveRight();
        assertEquals(new Position(0, 12), fixture.state().getCursor());
    }

    @Test
    void wrapAwareVisibilityScrollsToCursorSegmentWithinOneLine() {
        Fixture fixture = fixture("abcdefghi");
        fixture.state().setCursor(new Position(0, 9));
        fixture.state().setLeftColumn(4);
        fixture.state().setLeftVisualColumn(4);

        fixture.engine().ensureCursorVisible(2, 4, true, false);

        assertEquals(0, fixture.state().getTopLine());
        assertEquals(1, fixture.state().getTopWrapSegment());
        assertEquals(0, fixture.state().getLeftColumn());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void wrapAwareVisibilityUsesAbsoluteTabStopsAndUnicodeCellWidths() {
        Fixture fixture = fixture("\t中ab");
        fixture.engine().setTabWidth(4);
        fixture.state().setCursor(new Position(0, 4));

        fixture.engine().ensureCursorVisible(1, 4, true, false);

        assertEquals(List.of(0, 1), fixture.model().wrapStarts(0, 4, false, 4));
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(1, fixture.state().getTopWrapSegment());
    }

    @Test
    void wrapAwareVisibilityTracksWrappedOffsetsAcrossLogicalLines() {
        Fixture fixture = fixture("abcdefghi\n123456");
        fixture.state().setCursor(new Position(1, 6));

        fixture.engine().ensureCursorVisible(2, 4, true, false);

        assertEquals(1, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
    }

    @Test
    void wrapAwareVisibilityUsesAClusterBoundaryWithoutMutatingRawSearchOffsets() {
        Fixture fixture = fixture("A" + FAMILY + "bcdef");
        fixture.state().setTopLine(0);
        fixture.state().setTopWrapSegment(2);
        fixture.state().setCursor(new Position(0, 5));

        fixture.engine().ensureCursorVisible(1, 3, true, false);

        assertEquals(new Position(0, 5), fixture.state().getCursor());
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
    }

    @Test
    void horizontalVisibilityReservesACellForCursorAtEndOfFullLine() {
        Fixture fixture = fixture("abcde");
        fixture.state().setCursor(new Position(0, 5));

        fixture.engine().ensureCursorVisible(2, 5, false, false);

        assertEquals(1, fixture.state().getLeftVisualColumn());
        assertEquals(1, fixture.state().getLeftColumn());
    }

    private static Fixture fixture(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state);
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}

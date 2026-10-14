package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorViewNavigationVisibilityTest {
    private static final String FAMILY = "👨‍👩‍👧‍👦";

    @Test
    void wrapAwareVisibilityScrollsToCursorSegmentWithinOneLine() {
        Fixture fixture = fixture("abcdefghi");
        fixture.state().setCursor(new Position(0, 9));
        fixture.state().setLeftColumn(4);
        fixture.state().setLeftVisualColumn(4);

        EditorViewNavigation.ensureCursorVisible(fixture.engine(), 2, 4, true, false);

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

        EditorViewNavigation.ensureCursorVisible(fixture.engine(), 1, 4, true, false);

        assertEquals(List.of(0, 1, 4), fixture.engine().textLayout().wrapStarts(0, 4, false, 4));
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(2, fixture.state().getTopWrapSegment());
    }

    @Test
    void wrapAwareVisibilityTracksWrappedOffsetsAcrossLogicalLines() {
        Fixture fixture = fixture("abcdefghi\n123456");
        fixture.state().setCursor(new Position(1, 6));

        EditorViewNavigation.ensureCursorVisible(fixture.engine(), 2, 4, true, false);

        assertEquals(1, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
    }

    @Test
    void wrapAwareVisibilityUsesAClusterBoundaryWithoutMutatingRawSearchOffsets() {
        Fixture fixture = fixture("A" + FAMILY + "bcdef");
        fixture.state().setTopLine(0);
        fixture.state().setTopWrapSegment(2);
        fixture.state().setCursor(new Position(0, 5));

        EditorViewNavigation.ensureCursorVisible(fixture.engine(), 1, 3, true, false);

        assertEquals(new Position(0, 5), fixture.state().getCursor());
        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getTopWrapSegment());
    }

    @Test
    void horizontalVisibilityReservesACellForCursorAtEndOfFullLine() {
        Fixture fixture = fixture("abcde");
        fixture.state().setCursor(new Position(0, 5));

        EditorViewNavigation.ensureCursorVisible(fixture.engine(), 2, 5, false, false);

        assertEquals(1, fixture.state().getLeftVisualColumn());
        assertEquals(1, fixture.state().getLeftColumn());
    }

    private static Fixture fixture(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}

package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalEditorViewportIntegrationTest {
    @Test
    void engineUsesAbsoluteTabStopsAndTerminalCellWidthsForWrappedVisibility() {
        TextModel model = model("\t中ab");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, layout);
        engine.setTabWidth(4);
        state.setCursor(new Position(0, 4));

        EditorViewNavigation.ensureCursorVisible(engine, 1, 4, true, false);

        assertEquals(List.of(0, 1, 4), layout.wrapStarts(0, 4, false, 4));
        assertEquals(0, state.getTopLine());
        assertEquals(2, state.getTopWrapSegment());
    }

    @Test
    void pointerMappingReturnsBoundariesForBothCellsOfWideGraphemes() {
        TextModel model = model("A中😀B");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        EditorState state = new EditorState();

        assertEquals(new Position(0, 1), hit(model, layout, state, 4));
        assertEquals(new Position(0, 1), hit(model, layout, state, 5));
        assertEquals(new Position(0, 2), hit(model, layout, state, 6));
        Position emojiRight = hit(model, layout, state, 7);
        assertEquals(new Position(0, 2), emojiRight);
    }

    @Test
    void horizontalViewportInsideTabUsesTheAbsoluteVisualColumn() {
        TextModel model = model("\tX");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);

        Position hit = new EditorViewCoordinates(layout, 10, false, false, 4)
            .positionInViewport(state, 2, 0, 2);

        assertEquals(new Position(0, 1), hit);
    }

    private static Position hit(
        TextModel model,
        TerminalEditorTextLayout layout,
        EditorState state,
        int mouseColumn
    ) {
        return new EditorViewCoordinates(layout, 10, false, false, 4)
            .positionInViewport(state, 2, 0, mouseColumn - 3);
    }

    private static TextModel model(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        return new TextModel(buffer);
    }
}

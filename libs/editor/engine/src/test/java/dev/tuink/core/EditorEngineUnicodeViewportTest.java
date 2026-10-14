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
    void navigationReusesOnlyTheCurrentImmutableTextAcrossModelEditsAndUndo() {
        Fixture fixture = fixture("e\u0301x");
        fixture.engine().moveRight();
        assertEquals(new Position(0, 2), fixture.state().getCursor());

        // Same line length, different grapheme boundaries, without recreating the engine.
        fixture.model().replaceLines(List.of("яab"));
        fixture.engine().moveHome();
        fixture.engine().moveRight();
        assertEquals(new Position(0, 1), fixture.state().getCursor());

        fixture.engine().insertInput("\u0301");
        fixture.engine().moveHome();
        fixture.engine().moveRight();
        assertEquals(new Position(0, 2), fixture.state().getCursor());
        fixture.engine().undo();
        fixture.engine().moveHome();
        fixture.engine().moveRight();
        assertEquals(new Position(0, 1), fixture.state().getCursor());
    }

    private static Fixture fixture(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}

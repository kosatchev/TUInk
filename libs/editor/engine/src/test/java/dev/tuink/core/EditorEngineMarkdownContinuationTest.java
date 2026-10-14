package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineMarkdownContinuationTest {
    @Test
    void invalidClosingMarkersDoNotEnableListsInsideCode() {
        for (List<String> prefix : List.of(
            List.of("```text", "```not-a-close"),
            List.of("~~~text", "~~~not-a-close"),
            List.of("```text", "    ```"),
            List.of("```text", "\t```"),
            List.of("```text", "   \t```"),
            List.of("````text", "```"),
            List.of("~~~~text", "~~~"),
            List.of("```text", "~~~"),
            List.of("~~~text", "```"),
            List.of("   ```text", "code")
        )) {
            for (String item : List.of("1. item", "- item", "- [x] done")) {
                Fixture fixture = fixture(prefix, item);

                fixture.engine.insertNewline();

                assertEquals("", fixture.model.getLine(prefix.size() + 1), prefix + " " + item);
            }
        }
    }

    @Test
    void matchingClosingMarkersRestoreListContinuation() {
        for (List<String> prefix : List.of(
            List.of("```text", "```"),
            List.of("```text", "   ``` \t"),
            List.of("```text", "`````"),
            List.of("  ~~~~text", " ~~~~~\t"),
            List.of("```text", "```not-a-close", "```"),
            List.of("~~~text", "    ~~~", "~~~")
        )) {
            Fixture fixture = fixture(prefix, "1. item");

            fixture.engine.insertNewline();

            assertEquals("2. ", fixture.model.getLine(prefix.size() + 1), prefix.toString());
        }
    }

    @Test
    void markersWithLeadingTabsOrFourSpacesDoNotOpenFences() {
        for (String marker : List.of("    ```text", "\t```text", "   \t~~~text", "    ~~~text", "``text")) {
            Fixture fixture = fixture(List.of(marker), "1. item");

            fixture.engine.insertNewline();

            assertEquals("2. ", fixture.model.getLine(2), marker);
        }
    }

    @Test
    void newlineAndItsContinuationRoundTripAsOneHistoryEntry() {
        for (String closing : List.of("```", "```not-a-close")) {
            Fixture fixture = fixture(List.of("```text", closing), "1. item");
            List<String> before = fixture.model.snapshotLines();
            Position cursorBefore = fixture.state.getCursor();

            fixture.engine.insertNewline();
            List<String> after = fixture.model.snapshotLines();
            Position cursorAfter = fixture.state.getCursor();
            assertTrue(fixture.state.isDirty());

            assertTrue(fixture.engine.undo());
            assertEquals(before, fixture.model.snapshotLines());
            assertEquals(cursorBefore, fixture.state.getCursor());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertEquals(after, fixture.model.snapshotLines());
            assertEquals(cursorAfter, fixture.state.getCursor());
            assertTrue(fixture.state.isDirty());
            assertFalse(fixture.engine.redo());
        }
    }

    private static Fixture fixture(List<String> prefix, String item) {
        List<String> lines = new ArrayList<>(prefix);
        lines.add(item);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of("notes.md"));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setAutoIndentEnabled(false);
        engine.moveCursorTo(new Position(lines.size() - 1, item.length()));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}

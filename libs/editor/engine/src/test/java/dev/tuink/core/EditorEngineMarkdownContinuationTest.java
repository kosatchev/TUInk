package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineMarkdownContinuationTest {
    @Test
    void tabSeparatedItemsAndEveryTaskBulletContinueTerminateAndUndo() {
        for (List<String> example : List.of(
            List.of("-\titem", "-\t"),
            List.of("*\t[X]\titem", "*\t[ ]\t"),
            List.of("+  [x]  item", "+  [ ]  "),
            List.of("009)\titem", "010)\t"),
            List.of("2.\t[x]\titem", "3.\t[ ]\t")
        )) {
            String original = example.get(0);
            String continuation = example.get(1);
            Fixture fixture = fixture(List.of(), original);

            fixture.engine.insertNewline();
            assertEquals(List.of(original, continuation), fixture.model.snapshotLines(), original);
            assertEquals(new Position(1, continuation.length()), fixture.state.getCursor());
            fixture.engine.insertNewline();
            assertEquals(List.of(original, ""), fixture.model.snapshotLines(), original);

            assertTrue(fixture.engine.undo());
            assertEquals(List.of(original, continuation), fixture.model.snapshotLines());
            assertTrue(fixture.engine.undo());
            assertEquals(List.of(original), fixture.model.snapshotLines());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertTrue(fixture.engine.redo());
            assertEquals(List.of(original, ""), fixture.model.snapshotLines());
        }
    }

    @Test
    void maximumOrdinalDoesNotCreateAnInvalidTenDigitListMarker() {
        Fixture fixture = fixture(List.of(), "999999999. item");

        fixture.engine.insertNewline();

        assertEquals(List.of("999999999. item", ""), fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("999999999. item"), fixture.model.snapshotLines());
    }

    @Test
    void thematicBreaksDoNotContinueListsOrCreateListContainers() {
        for (String separator : List.of("- - -", "* * *", "- - - -", "  * * *", "-\t-\t-", "---", "***", "_ _ _")) {
            Fixture fixture = fixture(List.of(), separator);

            fixture.engine.insertNewline();

            assertEquals(List.of(separator, ""), fixture.model.snapshotLines(), separator);
            assertTrue(fixture.engine.undo());
            assertEquals(List.of(separator), fixture.model.snapshotLines());
            assertTrue(fixture.engine.redo());
            assertEquals(List.of(separator, ""), fixture.model.snapshotLines());
        }
        Fixture afterSeparator = fixture(List.of("- - -"), "    - literal code");
        afterSeparator.engine.insertNewline();
        assertEquals("", afterSeparator.model.getLine(2));
    }

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
            for (String item : List.of("1. item", "- item", "- [x] done", "1.\titem", "*\t[x]\tdone")) {
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
        state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of("notes.md").toString()));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setAutoIndentEnabled(false);
        engine.moveCursorTo(new Position(lines.size() - 1, item.length()));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}

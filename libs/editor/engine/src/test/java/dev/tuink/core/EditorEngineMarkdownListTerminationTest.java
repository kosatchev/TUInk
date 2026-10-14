package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineMarkdownListTerminationTest {
    @Test
    void emptyBulletsOrderedItemsAndTasksFinishOnTheSameLine() {
        for (String marker : List.of("- ", "* ", "+ ", "1. ", "42) ", "- [ ] ", "- [x] ",
            "- [X]", "* [ ] ", "+ [x]\t", "2. [ ] ", "-\t", "0002. ")) {
            Fixture fixture = fixture("notes.md", List.of(marker));

            fixture.engine.insertNewline();

            assertEquals(List.of(""), fixture.model.snapshotLines(), marker);
            assertEquals(new Position(0, 0), fixture.state.getCursor(), marker);
            assertTrue(fixture.engine.undo());
            assertEquals(List.of(marker), fixture.model.snapshotLines());
            assertFalse(fixture.state.isDirty());
            assertTrue(fixture.engine.redo());
            assertEquals(List.of(""), fixture.model.snapshotLines());
        }
    }

    @Test
    void continuationTerminationAndOrdinaryEnterAreSeparateUndoSteps() {
        Fixture fixture = fixture("notes.md", List.of("- item"));

        fixture.engine.insertNewline();
        assertEquals(List.of("- item", "- "), fixture.model.snapshotLines());
        fixture.engine.insertNewline();
        assertEquals(List.of("- item", ""), fixture.model.snapshotLines());
        assertEquals(new Position(1, 0), fixture.state.getCursor());
        fixture.engine.insertNewline();
        assertEquals(List.of("- item", "", ""), fixture.model.snapshotLines());

        assertTrue(fixture.engine.undo());
        assertEquals(List.of("- item", ""), fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("- item", "- "), fixture.model.snapshotLines());
        assertEquals(new Position(1, 2), fixture.state.getCursor());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("- item"), fixture.model.snapshotLines());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
        for (int index = 0; index < 3; index++) {
            assertTrue(fixture.engine.redo());
        }
        assertEquals(List.of("- item", "", ""), fixture.model.snapshotLines());
    }

    @Test
    void terminationKeepsIndentIncludingNestedListsAndTrailingWhitespace() {
        for (List<String> lines : List.of(
            List.of("  - \t "), List.of("- parent", "    - "),
            List.of("- parent", "    - child", "        - [ ] "), List.of("- parent", "\t- ")
        )) {
            Fixture fixture = fixture("notes.md", lines);
            String indent = lines.getLast().substring(0, lines.getLast().indexOf('-'));

            fixture.engine.insertNewline();

            assertEquals(indent, fixture.model.getLine(lines.size() - 1), lines.toString());
            assertEquals(lines.size(), fixture.model.lineCount());
            assertEquals(new Position(lines.size() - 1, indent.length()), fixture.state.getCursor());
        }
        Fixture beforeWhitespace = fixture("notes.md", List.of("-    "));
        beforeWhitespace.engine.moveCursorTo(new Position(0, 2));
        beforeWhitespace.engine.insertNewline();
        assertEquals(List.of(""), beforeWhitespace.model.snapshotLines());
    }

    @Test
    void contentSuffixAndExplicitSelectionsUseOrdinaryNewlineEditing() {
        Fixture suffix = fixture("notes.md", List.of("- suffix"));
        suffix.engine.moveCursorTo(new Position(0, 2));
        suffix.engine.insertNewline();
        assertEquals(List.of("- ", "suffix"), suffix.model.snapshotLines());

        Fixture selection = fixture("notes.md", List.of("- ", "next"));
        selection.engine.restoreSelection(new Position(1, 0), new Position(0, 2));
        selection.engine.insertNewline();
        assertEquals(List.of("- ", "next"), selection.model.snapshotLines());
        assertFalse(selection.state.hasSelection());
        assertTrue(selection.engine.undo());
        assertEquals(new Position(0, 2), selection.state.getSelectionAnchor());
        assertEquals(new Position(1, 0), selection.state.getCursor());

        Fixture insideMarker = fixture("notes.md", List.of("- [ ] "));
        insideMarker.engine.moveCursorTo(new Position(0, 3));
        insideMarker.engine.insertNewline();
        assertEquals(List.of("- [", "-  ] "), insideMarker.model.snapshotLines());
    }

    @Test
    void disabledListsOtherLanguagesAndCodeBlocksKeepTheirText() {
        Fixture disabled = fixture("notes.md", List.of("- "));
        disabled.engine.setAutoListEnabled(false);
        disabled.engine.insertNewline();
        assertEquals(List.of("- ", ""), disabled.model.snapshotLines());
        for (String filename : List.of("notes.txt", "config.yaml")) {
            Fixture other = fixture(filename, List.of("- "));
            other.engine.insertNewline();
            assertEquals(List.of("- ", ""), other.model.snapshotLines());
        }
        for (List<String> lines : List.of(
            List.of("```markdown", "- "), List.of("~~~", "- [ ] "),
            List.of("```", "```not-a-close", "1. "), List.of("    - "),
            List.of("\t- "), List.of("paragraph", "", "    - "),
            List.of("- parent", "      - "), List.of("- [ ] parent", "      - "),
            List.of("-     code", "        - "),
            List.of("- parent", "    ```text", "    - "), List.of("- parent", "    ~~~", "    - ")
        )) {
            Fixture code = fixture("notes.md", lines);
            code.engine.insertNewline();
            assertEquals(lines, code.model.snapshotLines().subList(0, lines.size()), lines.toString());
            assertEquals(lines.size() + 1, code.model.lineCount());
        }
        Fixture afterFence = fixture("notes.md", List.of("```", "```", "- "));
        afterFence.engine.insertNewline();
        assertEquals(List.of("```", "```", ""), afterFence.model.snapshotLines());
    }

    @Test
    void markerLikeTextIsNotAnEmptyMarkdownItem() {
        for (String text : List.of("-", "-- ", "1.", "1234567890. ", "1x. ", "- [q] ", "- [ ] suffix")) {
            Fixture fixture = fixture("notes.md", List.of(text));
            fixture.engine.insertNewline();
            assertEquals(text, fixture.model.getLine(0), text);
            assertEquals(2, fixture.model.lineCount());
        }
    }

    @Test
    void terminationPreservesMixedSeparatorsAndSavepointThroughUndoRedo(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("notes.md");
        Files.writeString(file, "- item\r\n- \nend\r\n");
        Fixture fixture = fixture("notes.md", List.of(""));
        fixture.engine.open(file);
        fixture.engine.moveCursorTo(new Position(1, 2));
        DocumentFileMetadata metadata = fixture.model.fileMetadata();

        fixture.engine.insertNewline();

        assertEquals(List.of("- item", "", "end", ""), fixture.model.snapshotLines());
        assertEquals(metadata, fixture.model.fileMetadata());
        fixture.engine.save();
        assertEquals("- item\r\n\nend\r\n", Files.readString(file));
        assertFalse(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("- item", "- ", "end", ""), fixture.model.snapshotLines());
        assertEquals(metadata, fixture.model.fileMetadata());
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertEquals(new Position(1, 0), fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());
    }

    private static Fixture fixture(String filename, List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of(filename));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveCursorTo(new Position(lines.size() - 1, lines.getLast().length()));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}

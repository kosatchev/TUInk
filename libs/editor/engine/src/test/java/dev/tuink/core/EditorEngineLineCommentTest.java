package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EditorEngineLineCommentTest {
    @Test
    void togglesCurrentLineAndKeepsLogicalCursorPosition() {
        Fixture fixture = fixture("  value();", "Demo.java");
        fixture.state.setCursor(new Position(0, 2));

        EditorEngine.LineCommentResult commented = fixture.engine.toggleLineComment();

        assertEquals(EditorEngine.LineCommentAction.COMMENTED, commented.action());
        assertEquals(1, commented.lineCount());
        assertEquals("  // value();", fixture.model.getLine(0));
        assertEquals(new Position(0, 5), fixture.state.getCursor());

        EditorEngine.LineCommentResult uncommented = fixture.engine.toggleLineComment();

        assertEquals(EditorEngine.LineCommentAction.UNCOMMENTED, uncommented.action());
        assertEquals("  value();", fixture.model.getLine(0));
        assertEquals(new Position(0, 2), fixture.state.getCursor());
    }

    @Test
    void commentsSelectedLinesAtCommonIndentAndExcludesZeroColumnEnd() {
        Fixture fixture = fixture("  first();\n    second();\n\nthird();", "Demo.java");
        fixture.state.setSelectionAnchor(new Position(0, 2));
        fixture.state.setCursor(new Position(3, 0));

        EditorEngine.LineCommentResult result = fixture.engine.toggleLineComment();

        assertEquals(EditorEngine.LineCommentAction.COMMENTED, result.action());
        assertEquals(2, result.lineCount());
        assertEquals(
            List.of("  // first();", "  //   second();", "", "third();"),
            fixture.model.snapshotLines()
        );
        assertEquals(new Position(0, 5), fixture.state.getSelectionAnchor());
        assertEquals(new Position(3, 0), fixture.state.getCursor());
        assertTrue(fixture.state.isDirty());

        assertTrue(fixture.engine.undo());
        assertEquals(
            List.of("  first();", "    second();", "", "third();"),
            fixture.model.snapshotLines()
        );
        assertEquals(new Position(0, 2), fixture.state.getSelectionAnchor());
        assertEquals(new Position(3, 0), fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());

        assertTrue(fixture.engine.redo());
        assertEquals(
            List.of("  // first();", "  //   second();", "", "third();"),
            fixture.model.snapshotLines()
        );
        assertEquals(new Position(0, 5), fixture.state.getSelectionAnchor());
    }

    @Test
    void preservesReverseSelectionWhenTogglingRange() {
        Fixture fixture = fixture("one();\n  two();\nthree();", "Demo.java");
        fixture.state.setSelectionAnchor(new Position(2, 0));
        fixture.state.setCursor(new Position(0, 0));

        fixture.engine.toggleLineComment();

        assertEquals(List.of("// one();", "//   two();", "three();"), fixture.model.snapshotLines());
        assertEquals(new Position(2, 0), fixture.state.getSelectionAnchor());
        assertEquals(new Position(0, 3), fixture.state.getCursor());
    }

    @Test
    void mixedCommentStateCommentsEveryNonBlankLine() {
        Fixture fixture = fixture("// one\n  two", "Demo.java");
        fixture.state.setSelectionAnchor(new Position(0, 0));
        fixture.state.setCursor(new Position(1, 5));

        EditorEngine.LineCommentResult result = fixture.engine.toggleLineComment();

        assertEquals(EditorEngine.LineCommentAction.COMMENTED, result.action());
        assertEquals(List.of("// // one", "//   two"), fixture.model.snapshotLines());
    }

    @Test
    void usesLanguageSpecificMarkers() {
        assertCommentMarker("value = 1", "script.py", "# value = 1");
        assertCommentMarker("select 1;", "query.sql", "-- select 1;");
        assertCommentMarker("name=value", "settings.ini", "; name=value");
    }

    @Test
    void reportsUnsupportedSyntaxWithoutEditing() {
        Fixture fixture = fixture("{\"value\": 1}", "data.json");

        EditorEngine.LineCommentResult result = fixture.engine.toggleLineComment();

        assertEquals(EditorEngine.LineCommentAction.UNSUPPORTED, result.action());
        assertEquals("{\"value\": 1}", fixture.model.getLine(0));
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
    }

    @Test
    void unnamedAndExtensionlessDocumentsAreSafelyUnsupported() {
        assertEquals(CommentSyntaxProfile.GENERIC, LanguageProfiles.resolveCommentSyntax(null));
        assertEquals(
            CommentSyntaxProfile.GENERIC,
            LanguageProfiles.resolveCommentSyntax(Path.of("README"))
        );
    }

    @Test
    void leavesBlankOnlyRangeUnchanged() {
        Fixture fixture = fixture("   \n\t", "Demo.java");
        fixture.state.setSelectionAnchor(new Position(0, 0));
        fixture.state.setCursor(new Position(1, 1));

        EditorEngine.LineCommentResult result = fixture.engine.toggleLineComment();

        assertEquals(EditorEngine.LineCommentAction.NO_CONTENT, result.action());
        assertEquals(List.of("   ", "\t"), fixture.model.snapshotLines());
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void commentsShebangInsteadOfMistakingItForToggleMarker() {
        Fixture fixture = fixture("#!/usr/bin/env python3", "script.py");

        fixture.engine.toggleLineComment();
        assertEquals("# #!/usr/bin/env python3", fixture.model.getLine(0));

        fixture.engine.toggleLineComment();
        assertEquals("#!/usr/bin/env python3", fixture.model.getLine(0));
    }

    @Test
    void batchUndoPreservesMixedSeparatorsAndSharedViewsWithoutDocumentSnapshots() {
        SnapshotCountingModel model = new SnapshotCountingModel(new SimpleLineBuffer());
        List<String> original = List.of("// alpha", "//", "// omega");
        DocumentFileMetadata metadata = new DocumentFileMetadata(StandardCharsets.UTF_16LE,
            BomKind.UTF_16_LE, "\r", List.of("\r", "\n"));
        model.replaceLines(original, metadata);
        EditorDocument document = new EditorDocument(model);
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession second = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.state().setCurrentFile(Path.of("Demo.java"));
        first.engine().selectAll();
        EditorViewMemento before = first.state().viewMemento();
        second.state().setCursor(new Position(1, 2));
        model.snapshots = 0;

        assertEquals(EditorEngine.LineCommentAction.UNCOMMENTED, first.engine().toggleLineComment().action());

        assertEquals(List.of("alpha", "", "omega"), model.snapshotLines());
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
        assertEquals(new Position(1, 0), second.state().getCursor());
        assertTrue(second.state().isDirty());
        assertTrue(first.engine().undo());
        assertEquals(original, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(before, first.state().viewMemento());
        assertFalse(second.state().isDirty());
        assertFalse(first.engine().undo(), "A comment batch must occupy one history step");
        assertTrue(first.engine().redo());
        assertEquals(List.of("alpha", "", "omega"), model.snapshotLines());
        // The three explicit assertions above inspect snapshots; the edit and history do not.
        assertEquals(3, model.snapshots);
    }

    @Test
    void stagingAllocationFailureLeavesTheActiveDocumentAndHistoryUntouched() {
        AtomicBoolean fail = new AtomicBoolean();
        TextModel model = new TextModel(new FailingBuffer(fail));
        model.replaceLines(List.of("first", "second", "third"));
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of("Demo.java"));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.insertInput("X");
        engine.insertNewline();
        assertTrue(engine.undo());
        engine.selectAll();
        List<String> before = model.snapshotLines();
        DocumentFileMetadata metadata = model.fileMetadata();
        EditorViewMemento view = state.viewMemento();
        long revision = model.revision();
        long epoch = model.contentEpoch();
        long navigation = engine.navigationRevision();
        boolean dirty = state.isDirty();
        fail.set(true);

        assertThrows(DocumentEditLimitException.class, engine::toggleLineComment);

        fail.set(false);
        assertEquals(before, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(view, state.viewMemento());
        assertEquals(revision, model.revision());
        assertEquals(epoch, model.contentEpoch());
        assertEquals(navigation, engine.navigationRevision());
        assertEquals(dirty, state.isDirty());
        assertTrue(engine.redo(), "Failure must retain the existing redo branch");
        assertTrue(engine.undo());
        assertEquals(before, model.snapshotLines());
        assertTrue(engine.undo());
        assertFalse(state.isDirty());
    }

    private static void assertCommentMarker(String input, String fileName, String expected) {
        Fixture fixture = fixture(input, fileName);
        fixture.engine.toggleLineComment();
        assertEquals(expected, fixture.model.getLine(0));
    }

    private static Fixture fixture(String text, String fileName) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of(fileName));
        return new Fixture(model, state, new EditorEngine(model, state, new TestEditorTextLayout(model)));
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }

    private static final class SnapshotCountingModel extends TextModel {
        private int snapshots;

        private SnapshotCountingModel(TextBuffer buffer) {
            super(buffer);
        }

        @Override public List<String> snapshotLines() {
            snapshots++;
            return super.snapshotLines();
        }
    }

    private static final class FailingBuffer extends SimpleLineBuffer {
        private final AtomicBoolean fail;

        private FailingBuffer(AtomicBoolean fail) {
            this.fail = fail;
        }

        @Override public TextBuffer createEmptySibling() {
            return new FailingBuffer(fail);
        }

        @Override public void insertLine(int index, String text) {
            if (fail.get() && lineCount() == 2) throw new OutOfMemoryError("staging allocation");
            super.insertLine(index, text);
        }
    }
}

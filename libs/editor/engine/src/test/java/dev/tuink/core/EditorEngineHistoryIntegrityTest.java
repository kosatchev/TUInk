package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineHistoryIntegrityTest {
    @TempDir
    Path tempDir;

    @Test
    void dirtyTracksOperationHistoryPositionAcrossSaveUndoAndRedo() throws IOException {
        Fixture fixture = fixture("");

        fixture.engine.insertInput("x");
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertFalse(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertTrue(fixture.state.isDirty());

        fixture.engine.saveAs(tempDir.resolve("operation.txt"));
        assertFalse(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of(""), fixture.model.snapshotLines());
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("x"), fixture.model.snapshotLines());
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void dirtyTracksSnapshotHistoryPositionAcrossSaveUndoAndRedo() throws IOException {
        Fixture fixture = fixture("one");
        fixture.engine.applyTextReplacements(List.of(new TextReplacement(
            new Position(0, 0),
            new Position(0, 3),
            "ONE"
        )));
        fixture.engine.saveAs(tempDir.resolve("snapshot.txt"));

        assertTrue(fixture.engine.undo());
        assertEquals(List.of("one"), fixture.model.snapshotLines());
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("ONE"), fixture.model.snapshotLines());
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void operationHistoryRecordsNormalizedNewlinesAndRejectsMalformedInputBeforeDeletion() {
        Fixture fixture = fixture("base");
        fixture.engine.moveCursorTo(new Position(0, 4));

        fixture.engine.insertInput("x\ry\r\nz");
        assertEquals(List.of("basex", "y", "z"), fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("base"), fixture.model.snapshotLines());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("basex", "y", "z"), fixture.model.snapshotLines());

        fixture.engine.beginSelectionAt(new Position(0, 0));
        fixture.engine.extendSelectionTo(new Position(1, 1));
        List<String> before = fixture.model.snapshotLines();
        long revision = fixture.model.revision();
        assertThrows(IllegalArgumentException.class, () -> fixture.engine.insertInput("bad\uD800"));
        assertEquals(before, fixture.model.snapshotLines());
        assertEquals(revision, fixture.model.revision());
        assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
        assertEquals(new Position(1, 1), fixture.state.getCursor());

        assertThrows(IllegalArgumentException.class, () -> fixture.engine.insertChar('\uD800'));
        assertEquals(before, fixture.model.snapshotLines());
        assertEquals(revision, fixture.model.revision());
    }

    @Test
    void replaceAllSupportsCanonicalMultilineReplacementAsOneUndoStep() {
        Fixture fixture = fixture("a a");

        assertEquals(2, fixture.engine.replaceAll("a", "x\r\ny"));
        assertEquals(List.of("x", "y x", "y"), fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("a a"), fixture.model.snapshotLines());
        assertFalse(fixture.engine.undo());
    }

    @Test
    void identicalReplaceAdvancesWithoutChangingTextRevisionOrHistory() {
        Fixture fixture = fixture("x x");
        fixture.engine.restoreSelection(new Position(0, 1), new Position(0, 0));
        long revision = fixture.model.revision();

        assertTrue(fixture.engine.replaceCurrentOrNext("x", "x"));

        assertEquals(List.of("x x"), fixture.model.snapshotLines());
        assertEquals(revision, fixture.model.revision());
        assertEquals(new Position(0, 2), fixture.state.getSelectionAnchor());
        assertEquals(new Position(0, 3), fixture.state.getCursor());
        assertFalse(fixture.engine.undo());
    }

    @Test
    void replaceUndoAndRedoRestoreTheExactBeforeAndAfterSelections() {
        Fixture fixture = fixture("one two");
        fixture.engine.restoreSelection(new Position(0, 3), new Position(0, 0));

        assertTrue(fixture.engine.replaceCurrentOrNext("one", "1"));
        assertEquals(List.of("1 two"), fixture.model.snapshotLines());
        assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
        assertEquals(new Position(0, 1), fixture.state.getCursor());

        assertTrue(fixture.engine.undo());
        assertEquals(List.of("one two"), fixture.model.snapshotLines());
        assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
        assertEquals(new Position(0, 3), fixture.state.getCursor());

        assertTrue(fixture.engine.redo());
        assertEquals(List.of("1 two"), fixture.model.snapshotLines());
        assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
        assertEquals(new Position(0, 1), fixture.state.getCursor());
    }

    @Test
    void repeatedSmallReplacementsKeepDeltaHistoryInsteadOfWholeLineSnapshots() {
        class SnapshotCountingModel extends TextModel {
            private int snapshots;

            SnapshotCountingModel() {
                super(new SimpleLineBuffer());
            }

            @Override
            public List<String> snapshotLines() {
                snapshots++;
                return super.snapshotLines();
            }
        }
        SnapshotCountingModel model = new SnapshotCountingModel();
        String original = "a0" + "x".repeat(100_000);
        model.insertText(new Position(0, 0), original);
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        model.snapshots = 0;

        for (int index = 0; index < 100; index++) {
            assertTrue(engine.replaceCurrentOrNext(
                index % 2 == 0 ? "a0" : "b0", index % 2 == 0 ? "b0" : "a0"
            ));
        }
        for (int index = 0; index < 100; index++) {
            assertTrue(engine.undo());
        }
        assertEquals(original, model.getLine(0));
        assertFalse(engine.undo());
        for (int index = 0; index < 100; index++) {
            assertTrue(engine.redo());
        }
        assertEquals(original, model.getLine(0));
        assertEquals(0, model.snapshots, "Small replacements must not retain complete line snapshots");
    }

    @Test
    void wrappingASingleLineSelectionKeepsItsOffsetsAndOneUndoStep() {
        for (boolean reverse : List.of(false, true)) {
            Fixture fixture = fixture("L😀e\u0301R");
            Position start = new Position(0, 1);
            Position end = new Position(0, 5);
            fixture.engine.restoreSelection(reverse ? start : end, reverse ? end : start);

            fixture.engine.insertChar('[');
            assertEquals("L[😀e\u0301]R", fixture.model.getLine(0));
            assertEquals(new Position(0, 2), fixture.state.getSelectionAnchor());
            assertEquals(new Position(0, 6), fixture.state.getCursor());
            assertTrue(fixture.engine.undo());
            assertEquals("L😀e\u0301R", fixture.model.getLine(0));
            assertEquals(reverse ? start : end, fixture.state.getCursor());
            assertEquals(reverse ? end : start, fixture.state.getSelectionAnchor());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertEquals("L[😀e\u0301]R", fixture.model.getLine(0));
        }
    }

    private static Fixture fixture(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        if (!text.isEmpty()) {
            model.insertText(new Position(0, 0), text);
        }
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}

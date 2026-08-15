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

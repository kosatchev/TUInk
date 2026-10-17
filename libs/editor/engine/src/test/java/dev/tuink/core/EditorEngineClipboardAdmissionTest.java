package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorEngineClipboardAdmissionTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void extractionFailureKeepsClipboardDocumentViewAndRedo(boolean cut, boolean selection) {
        FailingRangeModel model = new FailingRangeModel();
        model.replaceLines(List.of("first", "last"));
        EditorState state = new EditorState();
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("old clipboard");
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.insertChar('x');
        assertTrue(engine.undo());
        if (selection) engine.restoreSelection(new Position(1, 2), new Position(0, 1));
        state.setViewport(new ViewportState(0, 0, 2, 2));
        var before = state.viewMemento();
        var metadata = model.fileMetadata();
        long revision = model.revision();
        long navigation = engine.navigationRevision();
        model.fail = true;

        assertThrows(ClipboardLimitException.class, () -> {
            if (selection) {
                if (cut) engine.cutSelection(); else engine.copySelection();
            } else {
                if (cut) engine.cutCurrentLine(); else engine.copyCurrentLine();
            }
        });

        assertEquals("old clipboard", clipboard.text());
        assertEquals(List.of("first", "last"), model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(revision, model.revision());
        assertEquals(navigation, engine.navigationRevision());
        assertEquals(before, state.viewMemento());
        assertFalse(state.isDirty());
        model.fail = false;
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals("xfirst", model.getLine(0));
    }

    private static final class FailingRangeModel extends TextModel {
        private boolean fail;

        private FailingRangeModel() { super(new SimpleLineBuffer()); }

        @Override
        public String getTextRange(Position start, Position end) {
            if (fail) throw new OutOfMemoryError("Injected extraction failure");
            return super.getTextRange(start, end);
        }
    }
}

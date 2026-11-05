package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Refuses the real Copy or selection-collapse receipt through the common RMB route. */
class ClipboardPointerAdmissionIntegrationTest {
    @TempDir Path directory;

    @Test void refusedCopyKeepsTheOldClipboardAndSelectionAndLeavesTheWindowUsable() throws Exception {
        exerciseRefusal(1, false);
    }

    @Test void refusedCollapseKeepsTheSuccessfulCopyAndOriginalSelectionAndLeavesTheWindowUsable() throws Exception {
        exerciseRefusal(2, true);
    }

    private void exerciseRefusal(int refuseResult, boolean copyPublished) throws Exception {
        List<String> original = List.of("first row text with room", "second row text with room", "third");
        try (var window = new EditorWindowFixture(directory, false, original)) {
            var model = new TextModel(new SimpleLineBuffer());
            var state = new EditorState();
            var layout = new TerminalEditorTextLayout(model);
            var engine = new RefusingResultEngine(model, state, layout, window.host.clipboard);
            engine.openExisting(JvmDocumentStorage.resource(window.document));
            var session = TuiDocumentSession.adopt(new TabSession(model, state, layout, engine));
            window.context.replace(0, new TerminalEditorApp.EditorTab(session, session.newRenderPlanBuilder(line -> List.of())));
            window.context.activate(0);
            window.ui.rightClickClipboardSetting = true;
            engine.restoreSelection(new Position(1, 6), new Position(1, 0));
            window.frame();
            window.host.clipboard.setText("previous workspace copy");
            long clipboardRevision = window.host.clipboard.revision();
            long documentRevision = model.revision();
            long navigationRevision = engine.navigationRevision();
            var view = state.viewMemento();
            engine.refuseOn(refuseResult);

            // The pointer is over text beyond the selection; RMB must retain the
            // active caret instead of first synthesizing a left-click placement.
            window.event(new PointerEvent(3, 10, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT, Set.of()));

            assertEquals(refuseResult, engine.preparedResults, "The refusal must occur at Copy or its following RESTORE");
            assertTrue(window.controller.running());
            assertNotNull(window.notification.message);
            assertTrue(window.notification.isError());
            assertTrue(window.notification.message.contains("available memory"), window.notification.message);
            assertEquals(original, model.snapshotLines());
            assertEquals(documentRevision, model.revision());
            assertEquals(navigationRevision, engine.navigationRevision());
            assertEquals(view, state.viewMemento());
            assertTrue(state.hasSelection());
            assertFalse(state.isDirty());
            assertFalse(window.editorView.isMouseSelecting());
            assertEquals(copyPublished ? "second" : "previous workspace copy", window.host.clipboard.text());
            assertEquals(clipboardRevision + (copyPublished ? 1 : 0), window.host.clipboard.revision(),
                "Only the successful Copy publishes; refusal and selection rollback never publish again");

            engine.refuseOn(0);
            window.event(new TextInput("Z"));
            assertEquals("Z row text with room", model.getLine(1), "The preserved selection remains editable");
            assertTrue(state.isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(original, model.snapshotLines());
            assertFalse(state.isDirty());
            assertTrue(window.controller.running());
            assertEquals(copyPublished ? "second" : "previous workspace copy", window.host.clipboard.text());
            assertEquals(clipboardRevision + (copyPublished ? 1 : 0), window.host.clipboard.revision());
        }
    }

    private static final class RefusingResultEngine extends EditorEngine {
        private int refuseResult;
        private int preparedResults;

        RefusingResultEngine(TextModel model, EditorState state, TerminalEditorTextLayout layout, EditorClipboard clipboard) {
            super(model, state, layout, clipboard);
        }

        void refuseOn(int index) {
            refuseResult = index;
            preparedResults = 0;
        }

        @Override protected DocumentCommandResult.Applied prepareCommandResult(DocumentCommandTarget target, long revision,
            boolean textChanged, boolean viewChanged, boolean performed, int affectedCount,
            DocumentCommandResult.CommentAction commentAction) {
            if (++preparedResults == refuseResult) throw new OutOfMemoryError("mandatory clipboard result refused");
            return super.prepareCommandResult(target, revision, textChanged, viewChanged, performed, affectedCount, commentAction);
        }
    }
}

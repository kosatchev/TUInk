package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Result admission is refused inside the real command transaction, then handled by the host. */
class PointerCommandRefusalIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(value = PointerEvent.Kind.class, names = {"PRESS", "DRAG", "RELEASE"})
    void refusedPointerCommandsEndTheGestureAndKeepTheEditorUsable(PointerEvent.Kind kind) throws Exception {
        List<String> original = List.of("first row text with room", "second row text with room", "third");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, original)) {
            AtomicBoolean refuse = new AtomicBoolean();
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorState state = new EditorState();
            TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
            RefusingResultEngine engine = new RefusingResultEngine(model, state, layout, window.host.clipboard, refuse);
            engine.openExisting(JvmDocumentStorage.resource(window.document));
            TuiDocumentSession session = TuiDocumentSession.adopt(new TabSession(model, state, layout, engine));
            window.tabs.set(0, new TerminalEditorApp.EditorTab(session, session.newRenderPlanBuilder(line -> List.of())));
            window.context.activate(0);
            window.frame();
            if (kind != PointerEvent.Kind.PRESS) window.pointer(PointerEvent.Kind.PRESS, 3, 8);
            var cursor = state.getCursor();
            var anchor = state.getSelectionAnchor();
            long revision = model.revision();
            refuse.set(true);

            window.pointer(kind, 5, 24);

            assertTrue(window.controller.running());
            assertTrue(window.notification.message.contains("available memory"));
            assertFalse(window.editorView.isMouseSelecting());
            assertEquals(-1L, window.editorView.nanosUntilSelectionScroll(System.nanoTime()));
            assertEquals(cursor, state.getCursor());
            assertEquals(anchor, state.getSelectionAnchor());
            assertEquals(revision, model.revision());
            assertEquals(original, model.snapshotLines());
            assertFalse(state.isDirty());
            refuse.set(false);
            window.pointer(PointerEvent.Kind.DRAG, 4, 23);
            window.pointer(PointerEvent.Kind.RELEASE, 4, 23);
            assertEquals(cursor, state.getCursor(), "the refused gesture must not retain capture");
            assertEquals(anchor, state.getSelectionAnchor());
            window.event(new TextInput("X"));
            assertTrue(state.isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(original, model.snapshotLines());
            assertFalse(state.isDirty());
            assertTrue(window.controller.running());
        }
    }

    private static final class RefusingResultEngine extends EditorEngine {
        private final AtomicBoolean refuse;

        private RefusingResultEngine(TextModel model, EditorState state, TerminalEditorTextLayout layout,
                                     EditorClipboard clipboard, AtomicBoolean refuse) {
            super(model, state, layout, clipboard);
            this.refuse = refuse;
        }

        @Override
        protected DocumentCommandResult.Applied prepareCommandResult(DocumentCommandTarget target, long revision,
            boolean textChanged, boolean viewChanged, boolean performed, int affectedCount,
            DocumentCommandResult.CommentAction commentAction) {
            if (refuse.get()) throw new OutOfMemoryError("mandatory pointer result refused");
            return super.prepareCommandResult(target, revision, textChanged, viewChanged, performed,
                affectedCount, commentAction);
        }
    }
}

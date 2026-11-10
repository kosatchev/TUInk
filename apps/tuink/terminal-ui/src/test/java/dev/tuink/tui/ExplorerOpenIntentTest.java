package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerOpenIntentTest {
    @TempDir Path directory;

    @Test
    void ordinaryPendingOpenActivatesTheDocumentAndTransfersKeyboardInput() throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, false)) {
            var original = window.active();
            startOpen(window, target);

            complete(window, pending);

            assertEquals(2, window.tabs.size());
            assertNotSame(original, window.active());
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.ui.sidebar.focused);
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            window.event(new TextInput("new "));
            assertEquals(List.of("new loaded document"), window.active().tabSession().model().snapshotLines());
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void pendingOpenPreservesANewerExplicitExplorerFocusRequest() throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, false)) {
            var original = window.selected();
            startOpen(window, target);
            window.event(ctrl(KeyStroke.Key.E));
            assertTrue(window.ui.sidebar.focused);

            complete(window, pending);

            assertEquals(2, window.tabs.size(), "The earlier read may finish in a background tab");
            assertSame(original, window.selected());
            assertTrue(window.ui.sidebar.focused,
                "An earlier read must not override a later explicit Ctrl+E focus request");
            assertEquals(ActiveSurface.Focus.CONTROL, window.controller.activeSurface().focus());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingOpenPreservesANewerFindField(boolean preview) throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, preview)) {
            var original = window.selected();
            var source = window.tabs.getFirst();
            startOpen(window, target);
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("original"));
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());

            complete(window, pending);

            assertEquals(2, window.tabs.size());
            assertSame(original, window.selected());
            assertFalse(window.ui.sidebar.focused);
            assertFalse(original.uiState().searchEditorFocused);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(new TextInput(" query"));
            assertEquals("original query", preview ? window.preview().searchQuery : window.query.text());
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingEncodingQuestionDoesNotInterruptANewerExplorerFocus(boolean preview) throws Exception {
        Path target = ambiguousTarget();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, preview)) {
            var original = window.selected();
            startOpen(window, target);
            window.event(ctrl(KeyStroke.Key.E));
            assertTrue(window.ui.sidebar.focused);

            complete(window, pending);

            assertSame(original, window.selected());
            assertEquals(1, window.tabs.size());
            assertTrue(original.uiState().modalState.isNone(),
                "An encoding question from an older read must not interrupt a newer focus request");
            assertTrue(window.ui.sidebar.focused);
            assertEquals(ActiveSurface.Focus.CONTROL, window.controller.activeSurface().focus());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(original.uiState().modalState.isOpenUtf16Confirm(),
                "Returning to content must make the deferred encoding question available");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(original.uiState().modalState.isNone());
            assertFalse(original.uiState().openFileMode,
                "Deferring the encoding question must retain its explorer origin");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingEncodingQuestionDoesNotInterruptANewerFindField(boolean preview) throws Exception {
        Path target = ambiguousTarget();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, preview)) {
            var original = window.selected();
            var source = window.tabs.getFirst();
            startOpen(window, target);
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("original"));

            complete(window, pending);

            assertSame(original, window.selected());
            assertEquals(1, window.tabs.size());
            assertTrue(original.uiState().modalState.isNone(),
                "An encoding question from an older read must not replace the newer Find field");
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(new TextInput(" query"));
            assertEquals("original query", preview ? window.preview().searchQuery : window.query.text());
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(original.uiState().modalState.isOpenUtf16Confirm(),
                "Closing Find must make the deferred encoding question available");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(original.uiState().modalState.isNone());
            assertFalse(original.uiState().openFileMode);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingPickerFailureWaitsForANewerFindFieldToClose(boolean preview) throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, preview)) {
            var original = window.selected();
            var source = window.tabs.getFirst();
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            assertEquals(target, window.tree.state().selectedId().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("original"));
            Files.delete(target);

            complete(window, pending);

            assertSame(original, window.selected());
            assertFalse(original.uiState().openFileMode,
                "A picker error from an older read must not replace the newer Find field");
            assertTrue(original.uiState().modalState.isNone());
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(new TextInput(" query"));
            assertEquals("original query", preview ? window.preview().searchQuery : window.query.text());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertTrue(original.uiState().openFileMode,
                "Closing Find must return to the failed read's picker");
            assertEquals(target, window.tree.state().selectedId().path());
            assertTrue(original.uiState().pickerNotice.summary().contains("no longer exists"));
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNewExplicitOpenCanReplaceAnOlderDeferredEncodingQuestion(boolean preview) throws Exception {
        Path ambiguous = ambiguousTarget();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, preview)) {
            var original = window.selected();
            startOpen(window, ambiguous);
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("original"));
            complete(window, pending);
            assertTrue(original.uiState().modalState.isNone());
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());

            Path latest = Files.writeString(directory.resolve("zzz-latest.txt"), "latest document");
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            assertEquals(latest, window.tree.state().selectedId().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy(),
                "A deferred encoding suggestion must not block a newer explicit Open request");

            complete(window, pending);

            assertEquals(latest, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("latest document"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.TAB_CLOSE);
            assertSame(original, window.selected());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(original.uiState().modalState.isNone(),
                "The replaced encoding suggestion must not reappear after the newer document closes");
            assertFalse(original.uiState().openFileMode);
        }
    }

    @Test
    void pendingOpenPreservesANewerSaveAsField() throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, false)) {
            var original = window.active();
            startOpen(window, target);
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertTrue(original.uiState().modalState.isSaveAs());
            assertFalse(original.uiState().blockingContentFocused);

            complete(window, pending);

            assertEquals(2, window.tabs.size());
            assertSame(original, window.selected());
            assertTrue(original.uiState().modalState.isSaveAs());
            assertFalse(original.uiState().blockingContentFocused,
                "Completing an older read must leave the newer Save As field focused");
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            String filename = window.filename.text();
            window.event(new TextInput("draft"));
            assertTrue(window.filename.text().contains("draft"));
            assertFalse(filename.equals(window.filename.text()));
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void pendingOpenCannotCloseANewerAcceptedFolderChooser() throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, false)) {
            var original = window.selected();
            startOpen(window, target);
            window.command(TuinkCommand.FOLDER_OPEN);
            assertTrue(original.uiState().openFileMode && original.uiState().choosingFolder,
                "The newer folder chooser must be accepted while the read is pending");

            complete(window, pending);

            assertEquals(2, window.tabs.size());
            assertSame(original, window.selected());
            assertTrue(original.uiState().openFileMode && original.uiState().choosingFolder,
                "An older file read must preserve the newer accepted folder chooser");
        }
    }

    @Test
    void switchingAwayAndBackDoesNotReviveAnOlderOpenIntent() throws Exception {
        Path target = target();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(pending, false)) {
            var original = window.selected();
            startOpen(window, target);
            window.command(TuinkCommand.TAB_NEW);
            assertNotSame(original, window.selected());
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertSame(original, window.selected());

            complete(window, pending);

            assertEquals(3, window.tabs.size());
            assertSame(original, window.selected(),
                "Returning to the initiating tab does not authorize an older read to select another tab");
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    private Path target() throws Exception {
        return Files.writeString(directory.resolve("zz-slow.txt"), "loaded document");
    }

    private Path ambiguousTarget() throws Exception {
        return Files.write(directory.resolve("zz-ambiguous.txt"),
            new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0});
    }

    private EditorWindowFixture window(ArrayDeque<Runnable> pending, boolean preview) throws Exception {
        var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"), pending::add);
        try {
            if (preview) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.await(() -> window.preview().ready());
                assertTrue(window.selected() instanceof PreviewTab);
            }
            return window;
        } catch (Exception | AssertionError failure) {
            window.close();
            throw failure;
        }
    }

    private void startOpen(EditorWindowFixture window, Path target) throws Exception {
        window.controller.openFolder(directory);
        window.await(() -> !window.ui.sidebar.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(target, window.ui.sidebar.placement.tree().selectedEntry().orElseThrow().path());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        assertTrue(window.documentIo.busy());
        assertFalse(window.ui.sidebar.focused);
    }

    private static void complete(EditorWindowFixture window, ArrayDeque<Runnable> pending) throws Exception {
        assertEquals(1, pending.size());
        pending.removeFirst().run();
        window.frame();
        assertFalse(window.documentIo.busy());
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

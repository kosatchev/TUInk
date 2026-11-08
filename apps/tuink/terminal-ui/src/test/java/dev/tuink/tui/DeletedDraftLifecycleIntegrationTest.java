package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real resource loss, document ownership and close/save continuations through the window. */
class DeletedDraftLifecycleIntegrationTest {
    private static final List<String> MARKDOWN = List.of("# Header", "", "body  ");
    @TempDir Path directory;

    @Test
    void externalDeletionCreatesADirtyDraftAndOnlyAnAcceptedSaveClearsItsWarning() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original text"))) {
            window.event(new TextInput("draft "));
            var editor = window.active();
            var document = editor.documentSession().document();
            var id = document.documentId();
            var cursor = editor.state().getCursor();
            long revision = editor.tabSession().model().revision();
            openExplorer(window);
            deleteAndRefresh(window, id);

            assertSame(editor.documentSession(), window.active().documentSession());
            assertEquals(id, TerminalEditorApp.documentId(window.selected()));
            assertNull(editor.state().getCurrentResource());
            assertTrue(editor.state().isDirty());
            assertEquals(cursor, editor.state().getCursor());
            assertEquals(revision, editor.tabSession().model().revision());
            assertEquals(List.of("draft original text"), editor.tabSession().model().snapshotLines());
            window.frame();
            assertTrue(screenContains(window, "File deleted or moved"), () -> screen(window));
            assertTrue(screenContains(window, "Dismiss"), () -> screen(window));

            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            submitFilename(window, directory.resolve("missing-parent/cannot-save.txt"));
            window.await(() -> !window.documentIo.busy() && window.selected().uiState().modalState.isSaveAs()
                && window.notification.requiresAttention());
            assertTrue(window.host.deletedNotices.visible(id));
            assertTrue(editor.state().isDirty());
            assertNull(editor.state().getCurrentResource());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.selected().uiState().modalState.isNone());
            assertTrue(window.host.deletedNotices.visible(id));
            assertTrue(window.host.deletedNotices.hasUnsavedRemoval(id));
            assertFalse(Files.exists(window.document));

            Path recovered = directory.resolve("recovered.txt");
            window.command(TuinkCommand.DOCUMENT_SAVE);
            submitFilename(window, recovered);
            window.await(() -> !window.documentIo.busy() && !editor.state().isDirty());
            assertEquals("draft original text", Files.readString(recovered));
            assertEquals(recovered, JvmDocumentStorage.pathOf(editor.state().getCurrentResource()));
            assertFalse(window.host.deletedNotices.visible(id));
            assertFalse(window.host.deletedNotices.hasUnsavedRemoval(id));
            assertFalse(screenContains(window, "File deleted or moved"));
            assertFalse(Files.exists(window.document), "Save As must not recreate the removed path");
        }
    }

    @Test
    void aDismissedDeletedFrozenPreviewStillRequiresCancelOrExplicitDiscardBeforeClosing() throws Exception {
        try (var window = markdownWindow()) {
            PreviewTab preview = frozenDeleted(window);
            FrozenDocumentSession subject = closed(preview);
            var id = subject.documentId();
            dismissByMouse(window);
            assertFalse(window.host.deletedNotices.visible(id));
            assertTrue(window.host.deletedNotices.hasUnsavedRemoval(id));

            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            assertSame(subject, closed((PreviewTab) window.selected()));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            assertTrue(window.selected().uiState().modalState.isNone());
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertEquals(MARKDOWN, subject.source().text().lines());
            assertTrue(window.tabs.isEmpty(), "Cancelling must retain the snapshot without creating a source");

            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            window.event(new TextInput("n"));
            assertNull(window.context.presentation(preview.presentationId()));
            assertTrue(window.controller.running());
            assertEquals(1, window.tabs.size(), "Closing the last preview leaves the usual empty editor");
            assertFalse(Files.exists(window.document));
        }
    }

    @Test
    void savingADeletedFrozenPreviewFromItsCloseQuestionFinishesTheRequestedClose() throws Exception {
        try (var window = markdownWindow()) {
            PreviewTab preview = frozenDeleted(window);
            var id = closed(preview).documentId();
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            window.event(ctrl(KeyStroke.Key.S));
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            assertTrue(window.tabs.isEmpty());

            Path recovered = directory.resolve("close-recovered.md");
            submitFilename(window, recovered);
            window.await(() -> !window.documentIo.busy() && window.context.presentation(preview.presentationId()) == null);
            assertEquals(String.join("\n", MARKDOWN), Files.readString(recovered));
            assertTrue(window.controller.running());
            assertFalse(window.host.deletedNotices.hasUnsavedRemoval(id));
            assertFalse(Files.exists(window.document));
        }
    }

    @Test
    void quitSaveAllSavesADeletedFrozenDraftBeforeExitingWithoutCreatingAnEditor() throws Exception {
        try (var window = markdownWindow()) {
            PreviewTab preview = frozenDeleted(window);
            FrozenDocumentSession subject = closed(preview);
            dismissByMouse(window);
            window.command(TuinkCommand.APP_QUIT);
            assertTrue(window.controller.running());
            assertTrue(window.promptText().contains("Save All"));
            window.event(ctrl(KeyStroke.Key.S));
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            assertTrue(window.tabs.isEmpty());

            Path recovered = directory.resolve("quit-recovered.md");
            submitFilename(window, recovered);
            window.await(() -> !window.documentIo.busy() && !window.controller.running());
            assertEquals(String.join("\n", MARKDOWN), Files.readString(recovered));
            assertEquals(recovered, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertSame(subject, closed((PreviewTab) window.context.presentation(preview.presentationId())));
            assertTrue(window.tabs.isEmpty());
            assertFalse(window.host.deletedNotices.hasUnsavedRemoval(subject.documentId()));
            assertFalse(Files.exists(window.document));
        }
    }

    @Test
    void cancellingQuitSaveAsKeepsTheFrozenDraftAndDoesNotQuitAfterALaterStandaloneSave() throws Exception {
        try (var window = markdownWindow()) {
            PreviewTab preview = frozenDeleted(window);
            FrozenDocumentSession subject = closed(preview);
            var captured = subject.source();
            window.command(TuinkCommand.APP_QUIT);
            window.event(ctrl(KeyStroke.Key.S));
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            Path abandoned = directory.resolve("abandoned.md");
            setFilename(window, abandoned);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertTrue(window.controller.running());
            assertTrue(window.selected().uiState().modalState.isNone());
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertSame(captured, subject.source());
            assertNull(subject.binding().resource());
            assertTrue(window.host.deletedNotices.visible(subject.documentId()));
            assertTrue(window.host.deletedNotices.hasUnsavedRemoval(subject.documentId()));
            assertTrue(window.tabs.isEmpty());
            assertFalse(Files.exists(abandoned));

            Path recovered = directory.resolve("standalone-recovered.md");
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            submitFilename(window, recovered);
            window.await(() -> !window.documentIo.busy() && !window.host.deletedNotices.hasUnsavedRemoval(subject.documentId()));
            assertTrue(window.controller.running(), "A cancelled quit must not be resumed by an unrelated later save");
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertEquals(String.join("\n", MARKDOWN), Files.readString(recovered));
            assertTrue(window.tabs.isEmpty());
        }
    }

    @Test
    void failedQuitSaveAsRetainsItsWarningAndCancelsTheQuitContinuation() throws Exception {
        try (var window = markdownWindow()) {
            PreviewTab preview = frozenDeleted(window);
            FrozenDocumentSession subject = closed(preview);
            window.command(TuinkCommand.APP_QUIT);
            window.event(ctrl(KeyStroke.Key.S));
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            submitFilename(window, directory.resolve("missing-parent/cannot-save.md"));
            window.await(() -> !window.documentIo.busy() && window.selected().uiState().modalState.isSaveAs()
                && window.notification.requiresAttention());
            assertTrue(window.controller.running());
            assertTrue(window.host.deletedNotices.visible(subject.documentId()));
            assertNull(subject.binding().resource());
            assertEquals(MARKDOWN, subject.source().text().lines());

            Path recovered = directory.resolve("retry-recovered.md");
            submitFilename(window, recovered);
            window.await(() -> !window.documentIo.busy() && !window.host.deletedNotices.hasUnsavedRemoval(subject.documentId()));
            assertTrue(window.controller.running(), "A failed quit save cancels exit, even if its filename is then corrected");
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertEquals(String.join("\n", MARKDOWN), Files.readString(recovered));
            assertTrue(window.tabs.isEmpty());
        }
    }

    private EditorWindowFixture markdownWindow() throws Exception {
        return new EditorWindowFixture(directory, "document.md", false, MARKDOWN);
    }

    private static void openExplorer(EditorWindowFixture window) throws Exception {
        window.controller.openFolder(window.document.getParent());
        window.await(() -> window.ui.explorer.tree != null && !window.ui.explorer.tree.loading());
    }

    private static PreviewTab frozenDeleted(EditorWindowFixture window) throws Exception {
        openExplorer(window);
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.selected() instanceof PreviewTab && window.preview().ready());
        window.command(TuinkCommand.TAB_PREVIOUS);
        window.command(TuinkCommand.TAB_CLOSE);
        window.await(() -> window.selected() instanceof PreviewTab preview && preview.source() instanceof PreviewTab.ClosedSnapshot
            && window.preview().ready());
        var preview = (PreviewTab) window.selected();
        assertTrue(window.tabs.isEmpty());
        deleteAndRefresh(window, closed(preview).documentId());
        assertNull(closed(preview).binding().resource());
        assertEquals(MARKDOWN, closed(preview).source().text().lines());
        return preview;
    }

    private static FrozenDocumentSession closed(PreviewTab preview) {
        return assertInstanceOf(PreviewTab.ClosedSnapshot.class, preview.source()).subject();
    }

    private static void deleteAndRefresh(EditorWindowFixture window, DocumentId document) throws Exception {
        Files.delete(window.document);
        window.command(TuinkCommand.EXPLORER_REFRESH);
        window.await(() -> window.host.deletedNotices.hasUnsavedRemoval(document));
        assertTrue(window.host.deletedNotices.visible(document));
        assertEquals(window.document, window.host.deletedNotices.originalPath(document));
    }

    private static void dismissByMouse(EditorWindowFixture window) {
        for (int row = 0; row < window.height; row++) {
            int column = window.screenRow(row).indexOf("Dismiss");
            if (column < 0) continue;
            window.pointer(PointerEvent.Kind.PRESS, row, column + 1);
            window.pointer(PointerEvent.Kind.RELEASE, row, column + 1);
            return;
        }
        fail("The deleted draft must expose its Dismiss button");
    }

    private static void submitFilename(EditorWindowFixture window, Path target) {
        setFilename(window, target);
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private static void setFilename(EditorWindowFixture window, Path target) {
        assertTrue(window.selected().uiState().modalState.isSaveAs());
        window.event(ctrl(KeyStroke.Key.A));
        window.event(new TextInput(target.toString()));
        assertEquals(target.toString(), window.filename.text());
    }

    private static boolean screenContains(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return true;
        return false;
    }

    private static String screen(EditorWindowFixture window) {
        return java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}

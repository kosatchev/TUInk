package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class UiRecipientTest {
    @Test void tabNavigationDoesNotChangeARecipientAndRetirementDoesNotRetargetIt() {
        var host = host();
        var original = host.newEditorTab();
        var other = host.newEditorTab();
        var context = new TerminalEditorApp.SessionContext(List.of(original), 0);
        var recipient = UiRecipient.presentation(original, UiRecipient.Flow.LOCAL);
        context.addNewTabAndActivate(other);
        assertTrue(recipient.current(context, null));
        context.remove(original.documentSession().viewId());
        assertFalse(original.documentSession().document().ownsView(recipient.viewId()));
        assertFalse(recipient.current(context, null));
        assertSame(other, context.activePresentation());
        assertNotSame(other.uiState(), recipient.state());
        original.uiState().close(); other.uiState().close();
    }

    @Test void aFrozenPreviewHasNoEditingViewAndAnUnrelatedRebindInvalidatesItsRecipient() {
        var host = host();
        var editor = host.newEditorTab();
        var context = new TerminalEditorApp.SessionContext(List.of(editor), 0);
        var frozen = new PreviewTab(editor).freeze(editor.documentSession().document().freezeSource());
        context.addPreview(frozen, editor.documentSession().viewId());
        var recipient = UiRecipient.presentation(frozen, UiRecipient.Flow.LOCAL);
        assertNull(recipient.viewId());
        assertTrue(recipient.current(context, null));
        context.updatePreview(frozen.relink(editor));
        assertFalse(recipient.current(context, null));
        frozen.close(); editor.uiState().close();
    }

    @Test void onlyTheAcceptedOperationsReceiptCanAdvanceItsPreviewRecipient() throws Exception {
        var host = host();
        var editor = host.newEditorTab();
        var storage = new MemoryDocumentStorage();
        storage.put("source.md", "source".getBytes(StandardCharsets.UTF_8));
        var loaded = worker(() -> storage.load(storage.resource("source.md"), DocumentOpenMode.DETECT, false));
        editor.documentSession().document().openPrepared(loaded);
        var context = new TerminalEditorApp.SessionContext(List.of(editor), 0);
        var preview = new PreviewTab(editor);
        context.addPreview(preview, editor.documentSession().viewId());
        var recipient = UiRecipient.presentation(preview, UiRecipient.Flow.LOCAL);
        var operation = editor.documentSession().document().io().beginSave();
        var accepted = assertInstanceOf(DocumentIoCoordinator.Saved.class,
            editor.documentSession().document().io().accept(operation, worker(operation::perform)));
        var saved = preview.saved();
        context.updatePreview(saved);
        assertFalse(recipient.current(context, null), "ordinary generation validation must still reject the old address");
        var advanced = recipient.afterAcceptedSaveRebind(saved, operation, accepted);
        assertTrue(advanced.current(context, null));
        var unrelated = new DocumentIoCoordinator.Saved(accepted.documentId(), accepted.operationId() + 1, accepted.receipt());
        assertThrows(IllegalArgumentException.class, () -> recipient.afterAcceptedSaveRebind(saved, operation, unrelated));
        assertThrows(IllegalArgumentException.class, () -> recipient.afterAcceptedSaveRebind(saved.saved(), operation, accepted));
        preview.close(); editor.uiState().close();
    }

    @Test void windowScopeIsIndependentOfPresentationSelectionAndRetiresWithItsOwnState() {
        var host = host();
        var editor = host.newEditorTab();
        var context = new TerminalEditorApp.SessionContext(List.of(editor), 0);
        var windowUi = new TabUiState();
        var recipient = UiRecipient.window(windowUi, null);
        assertTrue(recipient.current(context, windowUi));
        assertFalse(recipient.current(context, new TabUiState()));
        windowUi.close();
        assertFalse(recipient.current(context, windowUi));
        editor.uiState().close();
    }

    private static TerminalEditorApp host() {
        var host = new TerminalEditorApp();
        host.styles = UiStyles.forTheme(UiStyles.Theme.CYAN);
        return host;
    }

    private static <T> T worker(java.util.concurrent.Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread thread = new Thread(task); thread.start(); thread.join(5000);
        assertFalse(thread.isAlive());
        return task.get();
    }
}

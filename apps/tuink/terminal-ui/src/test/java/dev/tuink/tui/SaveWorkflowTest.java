package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static dev.tuink.tui.SaveWorkflowTestSupport.finish;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.UiModalState;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveWorkflowTest {
    @TempDir Path directory;

    @Test
    void encodingFailureOffersExplicitConversionAndRetainsEachSaveContinuation() throws Exception {
        Path source = directory.resolve("legacy.txt");
        Charset charset = Charset.forName("windows-1251");
        Files.write(source, "Привет".getBytes(charset));
        TabSession tab = emptyTab();
        tab.engine().openExisting(JvmDocumentStorage.resource(source));
        tab.engine().insertInput("😀");
        SaveWorkflow.Context context = context(List.of(tab));
        Path target = directory.resolve("new.txt");
        var direct = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForCloseTab(0), target.toString()));
        assertEquals(UiModalState.saveAsForCloseTab(0).offerUtf8(), direct.modalState());
        assertFalse(direct.focusFilename());
        assertFalse(direct.refreshSavedHighlighter());
        assertEquals(SaveFlowController.TextInputEffectKind.NONE, direct.filename().kind());
        assertFalse(Files.exists(target));

        Files.writeString(target, "untouched");
        var inspected = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForQuit(), target.toString()));
        UiModalState overwrite = inspected.modalState();
        var approvedContext = new SaveWorkflow.Context(context.documents(), context.activeTabIndex(),
            context.suggestions(), inspected.overwriteApproval());
        var confirmed = finish(approvedContext, SaveWorkflow.overwrite(approvedContext, overwrite, ConfirmChoice.SAVE));
        assertEquals(overwrite.offerUtf8(), confirmed.modalState());
        assertFalse(confirmed.focusFilename());
        assertFalse(confirmed.refreshSavedHighlighter());
        assertEquals(SaveWorkflow.NoticeKind.ERROR, confirmed.notice().kind());
        assertEquals(direct.notice().message(), confirmed.notice().message());
        assertEquals("untouched", Files.readString(target));
        assertEquals(source, JvmDocumentStorage.pathOf(tab.state().getCurrentResource()));
        assertTrue(tab.state().isDirty());
        assertEquals(charset, tab.state().getFileEncoding());
        assertEquals(direct.modalState().utf8SaveAs(),
            SaveWorkflow.encodingChoice(direct.modalState(), ConfirmChoice.SAVE).modalState());
        assertEquals(confirmed.modalState().utf8SaveAs(),
            SaveWorkflow.encodingChoice(confirmed.modalState(), ConfirmChoice.SAVE).modalState());
        assertTrue(SaveWorkflow.encodingChoice(direct.modalState(), ConfirmChoice.CANCEL).modalState().isNone());
    }

    @Test
    void quitSavesNamedTabsInOrderAndActivatesTheConflictingTabForRetry() throws Exception {
        TabSession first = namedDirtyTab("first.txt");
        TabSession second = namedDirtyTab("second.txt");
        Files.writeString(JvmDocumentStorage.pathOf(second.state().getCurrentResource()), "external");
        var context = context(List.of(first, second));
        var result = finish(context, SaveWorkflow.quit(context, ConfirmChoice.SAVE));
        assertTrue(result.running());
        assertEquals(UiModalState.quitConfirm(), result.modalState());
        assertEquals(1, result.activateTabIndex());
        assertEquals(-1, result.closeTabIndex());
        assertEquals(SaveWorkflow.NoticeKind.ERROR, result.notice().kind());
        assertFalse(first.state().isDirty());
        assertTrue(second.state().isDirty());
        assertEquals("draft original", Files.readString(JvmDocumentStorage.pathOf(first.state().getCurrentResource())));
        assertEquals("external", Files.readString(JvmDocumentStorage.pathOf(second.state().getCurrentResource())));
    }

    @Test
    void successfulQuitSaveAsRequestsTheNextFilenameWithoutDuplicatingModalState() throws Exception {
        TabSession first = emptyTab();
        TabSession second = emptyTab();
        first.engine().insertInput("first");
        second.engine().insertInput("second");
        var context = context(List.of(first, second));
        var result = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForQuit(), directory.resolve("first.txt").toString()));
        assertTrue(result.running());
        assertTrue(result.refreshSavedHighlighter());
        assertEquals(UiModalState.saveAsForQuit(), result.modalState());
        assertEquals(1, result.activateTabIndex());
        assertEquals(SaveFlowController.TextInputEffectKind.SET_TEXT, result.filename().kind());
        assertEquals(SaveWorkflow.NoticeKind.CLEAR, result.notice().kind());
        assertFalse(first.state().isDirty());
        assertTrue(second.state().isDirty());
    }

    private SaveWorkflow.Context context(List<TabSession> tabs) {
        return new SaveWorkflow.Context(new java.util.ArrayList<>(tabs.stream().map(TuiDocumentSession::adopt).toList()), 0, new SaveAsSuggestionStore());
    }

    private TabSession namedDirtyTab(String filename) throws Exception {
        TabSession tab = emptyTab();
        Path file = directory.resolve(filename);
        Files.writeString(file, "original");
        tab.engine().openExisting(JvmDocumentStorage.resource(file));
        tab.engine().insertInput("draft ");
        return tab;
    }

    private static TabSession emptyTab() {
        return TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
    }
}

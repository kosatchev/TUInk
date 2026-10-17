package dev.tuink.tui;

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
    void encodingFailureRetainsEachModalAndItsOriginalFocusPolicy() throws Exception {
        Path source = directory.resolve("legacy.txt");
        Charset charset = Charset.forName("windows-1251");
        Files.write(source, "Привет".getBytes(charset));
        TabSession tab = emptyTab();
        tab.engine().openExisting(source);
        tab.engine().insertInput("😀");
        SaveWorkflow.Context context = context(List.of(tab));
        Path target = directory.resolve("new.txt");
        var direct = SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForCloseTab(0), target.toString());
        assertEquals(UiModalState.saveAsForCloseTab(0), direct.modalState());
        assertTrue(direct.focusFilename());
        assertFalse(direct.refreshSavedHighlighter());
        assertEquals(SaveFlowController.TextInputEffectKind.NONE, direct.filename().kind());
        assertFalse(Files.exists(target));

        Files.writeString(target, "untouched");
        UiModalState overwrite = UiModalState.saveAsForQuit().confirmOverwrite(target);
        var confirmed = SaveWorkflow.overwrite(context, overwrite, ConfirmChoice.SAVE);
        assertEquals(overwrite, confirmed.modalState());
        assertFalse(confirmed.focusFilename());
        assertFalse(confirmed.refreshSavedHighlighter());
        assertEquals(SaveWorkflow.NoticeKind.ERROR, confirmed.notice().kind());
        assertEquals(direct.notice().message(), confirmed.notice().message());
        assertEquals("untouched", Files.readString(target));
        assertEquals(source, tab.state().getCurrentFile());
        assertTrue(tab.state().isDirty());
    }

    @Test
    void quitSavesNamedTabsInOrderAndActivatesTheConflictingTabForRetry() throws Exception {
        TabSession first = namedDirtyTab("first.txt");
        TabSession second = namedDirtyTab("second.txt");
        Files.writeString(second.state().getCurrentFile(), "external");
        var result = SaveWorkflow.quit(context(List.of(first, second)), ConfirmChoice.SAVE);
        assertTrue(result.running());
        assertEquals(UiModalState.quitConfirm(), result.modalState());
        assertEquals(1, result.activateTabIndex());
        assertEquals(-1, result.closeTabIndex());
        assertEquals(SaveWorkflow.NoticeKind.ERROR, result.notice().kind());
        assertFalse(first.state().isDirty());
        assertTrue(second.state().isDirty());
        assertEquals("draft original", Files.readString(first.state().getCurrentFile()));
        assertEquals("external", Files.readString(second.state().getCurrentFile()));
    }

    @Test
    void successfulQuitSaveAsRequestsTheNextFilenameWithoutDuplicatingModalState() throws Exception {
        TabSession first = emptyTab();
        TabSession second = emptyTab();
        first.engine().insertInput("first");
        second.engine().insertInput("second");
        var result = SaveWorkflow.saveAsInput(context(List.of(first, second)), TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForQuit(), directory.resolve("first.txt").toString());
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
        return new SaveWorkflow.Context(tabs, 0, new SaveAsSuggestionStore());
    }

    private TabSession namedDirtyTab(String filename) throws Exception {
        TabSession tab = emptyTab();
        Path file = directory.resolve(filename);
        Files.writeString(file, "original");
        tab.engine().openExisting(file);
        tab.engine().insertInput("draft ");
        return tab;
    }

    private static TabSession emptyTab() {
        return TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
    }
}

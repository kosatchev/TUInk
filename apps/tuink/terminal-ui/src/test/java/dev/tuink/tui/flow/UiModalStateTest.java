package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.editor.document.ViewId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UiModalStateTest {
    @Test
    void unchangedCloseDisplayIndexRetainsTheDialogIdentity() {
        UiModalState close = UiModalState.closeTabConfirm(2).bindTo(new ViewId(7));
        UiModalState saveAs = UiModalState.saveAsForCloseTab(2).bindTo(new ViewId(7));

        assertSame(close, close.withCloseTabIndex(2));
        assertSame(saveAs, saveAs.withCloseTabIndex(2));
    }

    @Test
    void viewIdentitySurvivesDisplayReindexingOverwriteAndEncodingChoice() {
        ViewId owner = new ViewId(7);
        UiModalState bound = UiModalState.saveAsForCloseTab(2).bindTo(owner);

        UiModalState changed = bound.confirmOverwrite(Path.of("existing.txt"))
            .withCloseTabIndex(0).backToSaveAs().offerUtf8().utf8SaveAs();

        assertEquals(owner, changed.targetViewId());
        assertEquals(0, changed.pendingCloseTabIndex());
        assertEquals(SaveContinuation.CLOSE_TAB, changed.saveContinuation());
        assertEquals(DocumentSaveFormat.UTF8, changed.saveFormat());
        assertEquals(owner, changed.bindTo(new ViewId(8)).targetViewId(), "selection cannot rebind a dialog");
        assertEquals(UiModalState.quitConfirm(), UiModalState.quitConfirm().bindTo(owner));
    }

    @Test
    void exactlyOneModeCanBeActive() {
        UiModalState state = UiModalState.closeTabConfirm(3);

        assertEquals(UiModalMode.CLOSE_TAB_CONFIRM, state.mode());
        assertEquals(3, state.pendingCloseTabIndex());
        assertThrows(
            IllegalArgumentException.class,
            () -> new UiModalState(
                UiModalMode.CLOSE_TAB_CONFIRM,
                Path.of("other.txt"),
                3,
                SaveContinuation.NONE
            )
        );
    }

    @Test
    void closeTabSaveContinuationSurvivesOverwriteRoundTrip() {
        UiModalState saveAs = UiModalState.saveAsForCloseTab(2);

        UiModalState overwrite = saveAs.confirmOverwrite(Path.of("existing.txt"));
        UiModalState backToSaveAs = overwrite.backToSaveAs();

        assertEquals(UiModalMode.SAVE_OVERWRITE_CONFIRM, overwrite.mode());
        assertEquals(SaveContinuation.CLOSE_TAB, overwrite.saveContinuation());
        assertEquals(2, overwrite.pendingCloseTabIndex());
        assertEquals(saveAs, backToSaveAs);
    }

    @Test
    void quitSaveContinuationDoesNotCarryTabIndex() {
        UiModalState saveAs = UiModalState.saveAsForQuit();

        assertEquals(SaveContinuation.QUIT, saveAs.saveContinuation());
        assertEquals(-1, saveAs.pendingCloseTabIndex());
    }

    @Test
    void rejectsCloseTabContinuationWithoutTabIndex() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new UiModalState(
                UiModalMode.SAVE_AS,
                null,
                -1,
                SaveContinuation.CLOSE_TAB
            )
        );
    }
}

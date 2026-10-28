package dev.tuink.tui;

import static dev.tuink.tui.SaveWorkflowTestSupport.finish;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorClipboard;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.UiModalState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SaveWorkflowViewIdentityTest {
    @TempDir Path directory;
    private final EditorClipboard clipboard = new EditorClipboard();
    private final SaveAsSuggestionStore suggestions = new SaveAsSuggestionStore();

    @Test
    void boundCloseDialogFollowsItsViewAfterOtherTabsMove() throws Exception {
        var first = named("first.txt");
        var closing = named("closing.txt");
        var replacement = named("replacement.txt");
        var modal = UiModalState.closeTabConfirm(1).bindTo(closing.viewId());

        var moved = SaveWorkflow.closeTab(context(List.of(closing, first, replacement), 2), modal, ConfirmChoice.DISCARD);
        assertEquals(0, moved.closeTabIndex());

        var removed = SaveWorkflow.closeTab(context(List.of(first, replacement), 1), modal, ConfirmChoice.DISCARD);
        assertEquals(-1, removed.closeTabIndex(), "an old display index cannot close its replacement");
        assertNull(removed.pendingSave());
    }

    @Test
    void boundFilenameInputSavesItsViewEvenAfterSelectionChanges() throws Exception {
        var owner = dirty("owner.txt");
        var other = named("other.txt");
        Path target = directory.resolve("saved-copy.txt");
        var modal = UiModalState.saveAs().bindTo(owner.viewId());
        var context = context(List.of(other, owner), 0);

        var saved = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            modal, target.toString()));

        assertEquals(SaveWorkflow.NoticeKind.SUCCESS, saved.notice().kind());
        assertEquals("draft original", Files.readString(target));
        assertEquals(target, JvmDocumentStorage.pathOf(owner.view().state().getCurrentResource()));
        assertEquals(directory.resolve("other.txt"), JvmDocumentStorage.pathOf(other.view().state().getCurrentResource()));
        assertEquals("original", Files.readString(directory.resolve("owner.txt")));
        assertFalse(owner.view().state().isDirty());
    }

    @Test
    void aRemovedFilenameTargetCannotSaveTheCurrentReplacement() throws Exception {
        var owner = dirty("owner.txt");
        var replacement = dirty("replacement.txt");
        Path target = directory.resolve("must-not-exist.txt");

        var result = SaveWorkflow.saveAsInput(context(List.of(replacement), 0), TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForCloseTab(0).bindTo(owner.viewId()), target.toString());

        assertTrue(result.modalState().isNone());
        assertNull(result.pendingSave());
        assertFalse(Files.exists(target));
        assertTrue(replacement.view().state().isDirty());
        assertFalse(replacement.document().io().busy());
    }

    @Test
    void aCloseSaveResolvesTheInitiatingViewAtCompletion() throws Exception {
        var first = named("first.txt");
        var owner = dirty("owner.txt");
        var initial = context(List.of(first, owner), 1);
        var request = SaveWorkflow.closeTab(initial,
            UiModalState.closeTabConfirm(1).bindTo(owner.viewId()), ConfirmChoice.SAVE).pendingSave();
        assertNotNull(request);
        var completed = request.perform();

        var result = SaveWorkflow.complete(context(List.of(owner), 0), request, completed, null);

        assertEquals(0, result.closeTabIndex());
        assertFalse(owner.view().state().isDirty());
        assertEquals("draft original", Files.readString(directory.resolve("owner.txt")));
    }

    @Test
    void aStartedWriteOutlivesItsViewWithoutClosingTheSurvivingView() throws Exception {
        var owner = dirty("owner.txt");
        var surviving = owner.attachView(clipboard);
        var request = SaveWorkflow.closeTab(context(List.of(owner, surviving), 0),
            UiModalState.closeTabConfirm(0).bindTo(owner.viewId()), ConfirmChoice.SAVE).pendingSave();
        assertNotNull(request);
        owner.document().detachView(owner.viewId());

        var result = SaveWorkflow.complete(context(List.of(surviving), 0), request, request.perform(), null);

        assertEquals(-1, result.closeTabIndex());
        assertTrue(result.modalState().isNone());
        assertFalse(surviving.view().state().isDirty());
        assertEquals("draft original", Files.readString(directory.resolve("owner.txt")));
        assertTrue(surviving.document().ownsView(surviving.viewId()));
    }

    @Test
    void anInspectionCanContinueOnASurvivingViewWithoutInheritingClose() throws Exception {
        var owner = dirty("owner.txt");
        var surviving = owner.attachView(clipboard);
        Path target = directory.resolve("copy.txt");
        var initial = context(List.of(owner, surviving), 0);
        var request = SaveWorkflow.saveAsInput(initial, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForCloseTab(0).bindTo(owner.viewId()), target.toString()).pendingSave();
        assertNotNull(request);
        owner.document().detachView(owner.viewId());
        var current = context(List.of(surviving), 0);

        var inspected = SaveWorkflow.complete(current, request, request.perform(), null);
        assertNotNull(inspected.pendingSave());
        assertSame(surviving, inspected.pendingSave().session());
        assertEquals(SaveContinuation.NONE, inspected.pendingSave().origin().saveContinuation());
        assertEquals(surviving.viewId(), inspected.pendingSave().origin().targetViewId());
        var saved = finish(current, inspected);

        assertEquals(-1, saved.closeTabIndex());
        assertEquals("draft original", Files.readString(target));
        assertFalse(surviving.view().state().isDirty());
    }

    @Test
    void overwriteApprovalUsesTheBoundViewAfterSelectionAndOrderChange() throws Exception {
        var owner = dirty("owner.txt");
        var other = named("other.txt");
        Path target = Files.writeString(directory.resolve("target.txt"), "external");
        var initial = context(List.of(owner, other), 0);
        var inspected = finish(initial, SaveWorkflow.saveAsInput(initial, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs().bindTo(owner.viewId()), target.toString()));
        assertNotNull(inspected.overwriteApproval());
        var current = new SaveWorkflow.Context(List.of(other, owner), 0, suggestions, inspected.overwriteApproval());

        var saved = finish(current, SaveWorkflow.overwrite(current, inspected.modalState(), ConfirmChoice.SAVE));

        assertEquals(SaveWorkflow.NoticeKind.SUCCESS, saved.notice().kind());
        assertEquals("draft original", Files.readString(target));
        assertFalse(owner.view().state().isDirty());
        assertEquals(directory.resolve("other.txt"), JvmDocumentStorage.pathOf(other.view().state().getCurrentResource()));
    }

    @ParameterizedTest
    @EnumSource(ConfirmChoice.class)
    void aStaleOverwriteForAnotherPathCannotAffectTheCurrentApproval(ConfirmChoice choice) throws Exception {
        var owner = dirty("owner.txt");
        Path oldTarget = Files.writeString(directory.resolve("old-target.txt"), "old bytes");
        Path newTarget = Files.writeString(directory.resolve("new-target.txt"), "new bytes");
        UiModalState oldDialog = UiModalState.saveAs().bindTo(owner.viewId()).confirmOverwrite(oldTarget);
        var initial = context(List.of(owner), 0);
        var inspected = finish(initial, SaveWorkflow.saveAsInput(initial, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs().bindTo(owner.viewId()), newTarget.toString()));
        var current = new SaveWorkflow.Context(initial.documents(), 0, suggestions, inspected.overwriteApproval());

        var stale = SaveWorkflow.overwrite(current, oldDialog, choice);

        assertSame(inspected.overwriteApproval(), stale.overwriteApproval());
        assertEquals(inspected.modalState(), stale.modalState());
        assertNull(stale.pendingSave());
        assertEquals(DocumentIoCoordinator.State.AWAITING_APPROVAL, owner.document().io().state());
        assertEquals("old bytes", Files.readString(oldTarget));
        assertEquals("new bytes", Files.readString(newTarget));
        var saved = finish(current, SaveWorkflow.overwrite(current, stale.modalState(), ConfirmChoice.SAVE));
        assertEquals(SaveWorkflow.NoticeKind.SUCCESS, saved.notice().kind());
        assertEquals("draft original", Files.readString(newTarget));
        assertEquals("old bytes", Files.readString(oldTarget));
    }

    @ParameterizedTest
    @EnumSource(ConfirmChoice.class)
    void aStaleOverwriteForAnotherViewCannotAffectTheCurrentApproval(ConfirmChoice choice) throws Exception {
        var oldOwner = dirty("old-owner.txt");
        var currentOwner = dirty("current-owner.txt");
        Path target = Files.writeString(directory.resolve("target.txt"), "external bytes");
        UiModalState oldDialog = UiModalState.saveAs().bindTo(oldOwner.viewId()).confirmOverwrite(target);
        var initial = context(List.of(oldOwner, currentOwner), 1);
        var inspected = finish(initial, SaveWorkflow.saveAsInput(initial, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs().bindTo(currentOwner.viewId()), target.toString()));
        var current = new SaveWorkflow.Context(initial.documents(), 1, suggestions, inspected.overwriteApproval());

        var stale = SaveWorkflow.overwrite(current, oldDialog, choice);

        assertSame(inspected.overwriteApproval(), stale.overwriteApproval());
        assertEquals(inspected.modalState(), stale.modalState());
        assertNull(stale.pendingSave());
        assertEquals(DocumentIoCoordinator.State.AWAITING_APPROVAL, currentOwner.document().io().state());
        assertEquals("external bytes", Files.readString(target));
        assertTrue(oldOwner.view().state().isDirty());
        var saved = finish(current, SaveWorkflow.overwrite(current, stale.modalState(), ConfirmChoice.SAVE));
        assertEquals(SaveWorkflow.NoticeKind.SUCCESS, saved.notice().kind());
        assertFalse(currentOwner.view().state().isDirty());
        assertTrue(oldOwner.view().state().isDirty());
    }

    private SaveWorkflow.Context context(List<TuiDocumentSession> documents, int active) {
        return new SaveWorkflow.Context(documents, active, suggestions);
    }

    private TuiDocumentSession named(String name) throws Exception {
        Path path = Files.writeString(directory.resolve(name), "original");
        var session = TuiDocumentSession.create(clipboard);
        session.view().engine().openExisting(JvmDocumentStorage.resource(path));
        return session;
    }

    private TuiDocumentSession dirty(String name) throws Exception {
        var session = named(name);
        session.execute(new DocumentCommand.Input("draft "));
        return session;
    }
}

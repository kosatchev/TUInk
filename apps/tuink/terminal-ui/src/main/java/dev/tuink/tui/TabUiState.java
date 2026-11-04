package dev.tuink.tui;

import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.HelpPage;
import dev.tuiframe.widget.DialogAttention;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.TextField;
import java.util.Objects;

/** Retained controls, dialog state, and Help reflow anchor for one terminal view. */
final class TabUiState implements AutoCloseable {
    final MarkdownPreviewState preview = new MarkdownPreviewState();
    TextField searchQueryInput;
    TextField searchReplaceInput;
    TextField saveAsInput;
    SearchController.SearchUiOptions searchUi = SearchController.SearchUiOptions.DEFAULT;
    boolean searchMode;
    boolean searchEditorFocused;
    SearchFocus pendingSearchFocus;
    UiModalState modalState = UiModalState.none();
    SaveWorkflow.OverwriteApproval overwriteApproval;
    SnapshotSaveWorkflow.OverwriteApproval snapshotOverwriteApproval;
    long filenameRevision;
    boolean openFileMode;
    DocumentFormatDialogView.State formatDialog;
    dev.tuink.editor.storage.DocumentOpenMode pickerOpenMode = dev.tuink.editor.storage.DocumentOpenMode.DETECT;
    FilePickerOpenWorkflow.Notice pickerNotice;
    FileTreeModel.DirectoryFailure lastPickerDirectoryFailure;
    final OpenFileDialogView.FocusState openFileFocusState = new OpenFileDialogView.FocusState();
    OpenFileTreeController openFileTree;
    FilePickerOpenWorkflow.OpenRequest pendingOpen;
    FilePickerOpenWorkflow.Result deferredOpenResult;
    final SearchPresentation searchPresentation = new SearchPresentation();
    String reportedSearchError = "";
    Object blockingFocusOwner;
    boolean blockingContentFocused;
    UiModalState confirmPromptState;
    Object confirmScopeId;
    final FocusManager<SearchFocus> searchFocusManager = new FocusManager<>();
    final FocusManager<ConfirmChoice> confirmFocusManager = new FocusManager<>();
    final FocusManager<SaveAsBarView.Target> saveAsFocusManager = new FocusManager<>();
    final FocusManager<HelpOverlayInputRouter.Target> helpFocusManager = new FocusManager<>();
    HelpPage helpScrollPage;
    ListView.State helpScrollState = ListView.State.initial();
    ListView.WrappedText helpText;
    HelpPage helpTextPage;
    TerminalEditorApp.HelpOverlayPlacement helpOverlayCache;
    final DialogAttention modalAttention = new DialogAttention();
    TerminalEditorApp.NotificationState notification;
    boolean closed;

    TabUiState() {
        this(new TextField(TextField.PastePolicy.PRESERVE_TABS),
            new TextField(TextField.PastePolicy.PRESERVE_TABS),
            new TextField(TextField.PastePolicy.PRESERVE_TABS));
    }

    TabUiState(TextField searchQueryInput, TextField searchReplaceInput, TextField saveAsInput) {
        bindInputs(searchQueryInput, searchReplaceInput, saveAsInput);
    }

    /** Initial composition may supply existing controls used by its host and fixtures. */
    void bindInputs(TextField searchQueryInput, TextField searchReplaceInput, TextField saveAsInput) {
        this.searchQueryInput = Objects.requireNonNull(searchQueryInput, "searchQueryInput");
        this.searchReplaceInput = Objects.requireNonNull(searchReplaceInput, "searchReplaceInput");
        this.saveAsInput = Objects.requireNonNull(saveAsInput, "saveAsInput");
    }

    boolean hasSearch() { return searchMode || preview.searchVisible || !preview.searchQuery.isEmpty(); }

    boolean awaitingAnswer() { return openFileMode || !modalState.isNone(); }

    /** Retiring a view abandons its reads and prompts while document writes keep their own lifetime. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        preview.close();
        try {
            if (pendingOpen != null) pendingOpen.cancel();
            if (overwriteApproval != null) {
                overwriteApproval.request().session().document().io().cancel(overwriteApproval.inspection());
            }
            if (snapshotOverwriteApproval != null) {
                snapshotOverwriteApproval.request().owner().subject().io().cancel(snapshotOverwriteApproval.inspection());
            }
        } finally {
            pendingOpen = null;
            overwriteApproval = null;
            snapshotOverwriteApproval = null;
            deferredOpenResult = null;
            if (openFileTree != null) {
                openFileTree.close();
                openFileTree = null;
            }
        }
    }
}

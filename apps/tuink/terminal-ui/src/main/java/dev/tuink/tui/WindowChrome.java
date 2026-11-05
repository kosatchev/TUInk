package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.TabBar;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.tui.flow.DocumentSaveFormat;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.ConfirmPromptText;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.PathPresentation;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import java.util.List;
import java.util.Objects;

/** Owner-thread preparation of the window around either content surface. Paint receives only its result. */
final class WindowChrome {
    record Input(TerminalEditorApp host, TerminalEditorApp.SessionContext context,
        UiSessionState preferences, ActiveSurface surface, TabUiState tab, TabUiState focus, UiModalState modal,
        TerminalEditorApp.NotificationState notice, int recoveryEntryCount, int rows, int cols,
        TopMenuController menu, CommandRouter commands, TabStrip tabs) {
        Input {
            Objects.requireNonNull(host); Objects.requireNonNull(context); Objects.requireNonNull(preferences);
            Objects.requireNonNull(surface); Objects.requireNonNull(tab); Objects.requireNonNull(focus); Objects.requireNonNull(modal);
            Objects.requireNonNull(notice); Objects.requireNonNull(menu); Objects.requireNonNull(commands);
            Objects.requireNonNull(tabs);
            if (rows < 2 || cols < 1 || recoveryEntryCount < 0) throw new IllegalArgumentException("Invalid window viewport");
        }
    }

    /** Source/Preview supply only content decisions, never an editor backend to the shared preparation. */
    record Content(ActiveSurface surface, Rect bounds, String status, boolean[] tabMatchFlags) {
        Content { Objects.requireNonNull(surface); Objects.requireNonNull(bounds); Objects.requireNonNull(status); }
    }

    record Result(FrameComposition.Surface surface, FrameComposition.Layers layers,
        SearchBarView.Placement search) {}

    record Prepared(Input input, boolean showTabs, int contentTopRow, Rect dialogViewport,
        InlinePromptBar prompt, NotificationBar notification, SaveAsBarView.Placement saveAs,
        ConfirmBarView.Placement confirm, SearchBarView.Placement search) {
        Rect contentBounds(boolean reserveNotification) {
            return new Rect(0, contentTopRow, input.cols(), Math.max(0,
                input.rows() - 1 - contentTopRow - prompt.height() - (reserveNotification ? notification.height() : 0)));
        }
        Result finish(Content content) { return complete(this, content); }
    }

    /** Shared strip lifetime uses presentation identity, never a retained editing state. */
    static final class TabStrip {
        private TabBar.State state;
        private PresentationId active;
        private int activeIndex = -1;
        private int columns = -1;
        private TabBarView.Placement previous;

        TabStrip() { this(TabBar.State.initial()); }
        TabStrip(TabBar.State state) { this.state = Objects.requireNonNull(state); }
        void adopt(TabBar.State next) { state = Objects.requireNonNull(next); }
        void invalidateLayout() { columns = -1; }

        private TabBarView.Placement place(Input input, List<TabBarView.TabItem> items, boolean show) {
            if (!show) { previous = null; active = null; return null; }
            int nextIndex = input.context().activePresentationIndex();
            PresentationId nextActive = input.context().activePresentation().presentationId();
            var view = TabBarView.build(items, nextIndex, state);
            Rect bounds = new Rect(0, 1, input.cols(), 1), clip = new Rect(0, 0, input.cols(), input.rows());
            var placement = !Objects.equals(active, nextActive) || activeIndex != nextIndex || columns != input.cols()
                ? view.placeRevealingActive(bounds, clip) : view.placeKeepingActiveVisible(bounds, clip, previous);
            active = nextActive; activeIndex = nextIndex; columns = input.cols();
            state = placement.state(); previous = placement;
            return placement;
        }
    }

    private WindowChrome() {}

    static Prepared prepare(Input input, SearchBarView searchBar) {
        var host = input.host(); var tab = input.tab(); var focus = input.focus(); var modal = input.modal();
        boolean showTabs = input.preferences().showTabBarSetting && input.context().presentations().size() > 1;
        int top = showTabs ? 2 : 1;
        int availableRows = Math.max(1, input.rows() - top - 1);
        var prompt = InlinePromptBar.hidden();
        SaveAsBarView saveAs = null;
        ConfirmBarView confirm = null;
        SearchBarView activeSearch = TerminalEditorApp.searchOwnsInteractiveRow(modal) && !tab.openFileMode ? searchBar : null;
        if (modal.isSaveAs()) {
            saveAs = SaveAsBarView.build(modal.saveFormat() == DocumentSaveFormat.UTF8
                ? "Save UTF-8: " : SaveAsBarView.DEFAULT_LABEL, tab.saveAsInput, input.cols());
            prompt = InlinePromptBar.of(saveAs.row(), EditorStyleAdapter.toEditor(host.styles.windows().promptAlert()), 1);
        } else {
            confirm = confirmation(input, availableRows);
            if (confirm != null) prompt = InlinePromptBar.of(confirm.row(), EditorStyleAdapter.toEditor(host.styles.windows().promptAlert()), 1);
            else if (activeSearch != null) prompt = InlinePromptBar.of(activeSearch.text(), EditorStyleAdapter.toEditor(host.styles.windows().searchBar()), 1);
        }
        Rect clip = new Rect(0, top, input.cols(), Math.max(0, input.rows() - top));
        int promptTop = input.rows() - 1 - prompt.height();
        SaveAsBarView.Placement placedSave = null;
        ConfirmBarView.Placement placedConfirm = null;
        SearchBarView.Placement placedSearch = null;
        if (saveAs != null) {
            placedSave = saveAs.place(TerminalEditorApp.SAVE_AS_SCOPE_ID, promptTop, clip);
            placedSave.activate(focus.saveAsFocusManager);
        } else focus.saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
        if (confirm != null) {
            if (focus.confirmScopeId == null || !sameDialog(focus.confirmPromptState, modal)) {
                focus.confirmFocusManager.activeScopeId().ifPresent(focus.confirmFocusManager::deactivate);
                focus.confirmScopeId = new Object();
            }
            focus.confirmPromptState = modal;
            placedConfirm = confirm.place(focus.confirmScopeId, promptTop, clip);
            placedConfirm.activate(focus.confirmFocusManager);
        } else {
            focus.confirmFocusManager.activeScopeId().ifPresent(focus.confirmFocusManager::deactivate);
            focus.confirmPromptState = null; focus.confirmScopeId = null;
        }
        if (activeSearch != null && saveAs == null && confirm == null) {
            placedSearch = activeSearch.place(TerminalEditorApp.SEARCH_SCOPE_ID, promptTop, clip);
            placedSearch.activate(tab.searchFocusManager);
            if (tab.pendingSearchFocus != null) {
                tab.searchFocusManager.focus(placedSearch.focusScope(), tab.pendingSearchFocus);
                tab.pendingSearchFocus = null;
            }
            if (tab.searchEditorFocused) tab.searchFocusManager.clear(placedSearch.focusScope());
        } else if (!tab.searchMode) tab.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
        NotificationBar notification = NotificationBar.of(input.notice().message, EditorStyleAdapter.toEditor(input.notice().style()));
        int inset = showTabs ? top : 0;
        return new Prepared(input, showTabs, top, new Rect(0, inset, input.cols(), Math.max(0, input.rows() - inset)),
            prompt, notification, placedSave, placedConfirm, placedSearch);
    }

    private static Result complete(Prepared prepared, Content content) {
        var input = prepared.input(); var host = input.host(); var tab = input.tab(); var focus = input.focus();
        var tabItems = host.toPresentationTabItems(input.context(), content.tabMatchFlags());
        var selected = tabItems.get(input.context().activePresentationIndex());
        var tabs = input.tabs().place(input, tabItems, prepared.showTabs());
        var menuItems = tabItems.stream().map(item -> new TopMenuView.TabItem(item.file(), item.dirty(),
            item.hasMatch(), item.searching(), item.awaitingAnswer(), item.qualifier())).toList();
        var menu = TopMenuView.build(input.preferences(), menuItems,
            input.context().activePresentationIndex(), host.theme.label(), input.commands(), input.menu().state(), content.surface());
        var menuPlacement = menu.place(new Rect(0, 0, input.cols(), input.rows()), content.surface().reading(), selected.dirty());
        input.menu().adopt(menuPlacement.state());
        var topBar = menu.topBar(selected.file(), selected.dirty(), input.cols(), content.surface().reading());
        var picker = picker(prepared);
        var page = host.activeHelpPage(input.modal().isHelpHints(), input.modal().isHelpLicenses(), input.modal().isHelpAuthor());
        var help = host.helpOverlayPlacement(input.rows(), input.cols(), page, prepared.dialogViewport().y());
        var format = input.modal().isDocumentFormat() && tab.formatDialog != null
            ? DocumentFormatDialogView.place(tab.formatDialog, prepared.dialogViewport(),
                DocumentFormatPolicy.apply(tab.formatDialog.purpose, content.surface()).allowed()) : null;
        Rect blocking = FrameComposition.Layers.prepareBlockingBounds(input.rows(), input.cols(),
            prepared.contentTopRow(), prepared.prompt().height(), prepared.saveAs() != null || prepared.confirm() != null,
            picker, help, format);
        if (focus.blockingContentFocused) {
            boolean available = DialogCommandPolicy.mode(TerminalEditorApp.modalInputKind(input.modal(), tab.openFileMode)).permitsContentFocus()
                && content.bounds().width() > 0 && content.bounds().height() > 0 && !blocking.contains(content.bounds());
            if (available) {
                if (prepared.saveAs() != null) focus.saveAsFocusManager.clear(prepared.saveAs().focusScope());
                if (prepared.confirm() != null) focus.confirmFocusManager.clear(prepared.confirm().focusScope());
                if (help != null) focus.helpFocusManager.clear(help.focusScope());
            } else {
                focus.blockingContentFocused = false;
                if (prepared.saveAs() != null) DialogFocusTraversal.focusFirst(focus.saveAsFocusManager, prepared.saveAs().focusScope());
                else if (prepared.confirm() != null) DialogFocusTraversal.focusFirst(focus.confirmFocusManager, prepared.confirm().focusScope());
                else if (picker.visible()) {
                    tab.openFileTree.adopt(tab.openFileTree.state().cancelPointerInteraction());
                    picker.dialog().focusFirst(tab.openFileFocusState);
                } else if (help != null) DialogFocusTraversal.focusFirst(focus.helpFocusManager, help.focusScope());
            }
        }
        return new Result(new FrameComposition.Surface(topBar, content.status(), prepared.prompt(), prepared.notification()),
            new FrameComposition.Layers(tabs, menuPlacement, prepared.saveAs() != null, prepared.saveAs(), prepared.confirm(),
                picker, help, format, focus.modalAttention.highlighted(), focus.blockingContentFocused, blocking), prepared.search());
    }

    private static ConfirmBarView confirmation(Input input, int rows) {
        var modal = input.modal(); var host = input.host(); int cols = input.cols();
        if (modal.isSaveOverwriteConfirm()) return host.buildOverwriteConfirmBar(modal.pendingOverwritePath(), cols, rows);
        if (modal.isQuitConfirm()) return host.buildQuitConfirmBar(input.context().tabs(), cols, rows);
        if (modal.isRecoveryConfirm()) return ConfirmBarView.responsive(host.buildRecoveryPrompt(input.recoveryEntryCount()), cols, rows,
            "Restore", "Delete", true, "Later", host.confirmPolicy(modal).shortcuts());
        if (modal.isCloseTabConfirm()) return host.buildCloseTabConfirmBar(input.context().tabs(), modal.pendingCloseTabIndex(), cols, rows);
        if (modal.isSaveUtf8Confirm() || modal.isOpenUtf16Confirm()) {
            boolean opening = modal.isOpenUtf16Confirm();
            String question = opening ? ConfirmPromptText.wrappingFileQuestion("Ambiguous: ", modal.pendingOpenPath(), " as UTF-16LE?",
                cols * Math.max(1, Math.min(8, rows - 4))) : "Save as UTF-8 without BOM?";
            return ConfirmBarView.responsive(question, cols, rows, opening ? "Open UTF-16LE" : "Save UTF-8", "Cancel", false, "Cancel", host.confirmPolicy(modal).shortcuts());
        }
        return null;
    }

    private static FrameComposition.OpenFile picker(Prepared prepared) {
        var input = prepared.input(); var tab = input.tab();
        if (!tab.openFileMode || !input.modal().isNone()) return FrameComposition.OpenFile.hidden();
        var tree = Objects.requireNonNull(tab.openFileTree, "Open-file mode requires an admitted tree controller");
        var viewport = prepared.dialogViewport();
        var layout = OpenFileDialogController.layoutForTerminal(input.rows(), input.cols(), viewport.y());
        int left = layout.left() + 2;
        var placedTree = tree.place(new Rect(left, layout.listStartRow(), Math.max(0, layout.right() - left), layout.listRows()), viewport,
            tab.openFileFocusState.focus() == OpenFileInputRouter.Focus.LIST && !input.focus().blockingContentFocused,
            !CompactDialogView.required(viewport.height(), viewport.width()));
        tab.openFileFocusState.synchronizeRoot(tree.root());
        var dialog = OpenFileDialogView.place(TerminalEditorApp.OPEN_FILE_SCOPE_ID, layout, placedTree,
            tree.showHiddenFiles(), viewport, tab.pickerNotice, tab.openFileFocusState.location());
        dialog.activate(tab.openFileFocusState);
        return new FrameComposition.OpenFile(PathPresentation.full(tree.root()), dialog, tab.openFileFocusState.focus());
    }

    private static boolean sameDialog(UiModalState a, UiModalState b) {
        return a == b || a != null && a.pendingCloseTabIndex() != b.pendingCloseTabIndex()
            && a.mode() == b.mode() && Objects.equals(a.targetViewId(), b.targetViewId())
            && Objects.equals(a.pendingOverwritePath(), b.pendingOverwritePath())
            && a.saveContinuation() == b.saveContinuation() && a.saveFormat() == b.saveFormat()
            && Objects.equals(a.pendingOpenPath(), b.pendingOpenPath());
    }
}

package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.SplitPane;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.SurfaceContext;
import dev.tuiframe.widget.NoticeBanner;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.tui.flow.DocumentSaveFormat;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.render.WindowTextPainter;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.ConfirmPromptText;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Owner-thread preparation of the window around either content surface. Paint receives only its result. */
final class WindowChrome {
    record Input(TerminalEditorApp host, TerminalEditorApp.SessionContext context,
        UiSessionState preferences, Supplier<ActiveSurface> surfaceSource, TabUiState tab, TabUiState focus, UiModalState modal,
        TerminalEditorApp.NotificationState notice, int recoveryEntryCount, int rows, int cols,
        TopMenuController menu, CommandRouter commands, TabStrip tabs) {
        Input {
            Objects.requireNonNull(host); Objects.requireNonNull(context); Objects.requireNonNull(preferences);
            Objects.requireNonNull(surfaceSource); Objects.requireNonNull(tab); Objects.requireNonNull(focus); Objects.requireNonNull(modal);
            Objects.requireNonNull(notice); Objects.requireNonNull(menu); Objects.requireNonNull(commands);
            Objects.requireNonNull(tabs);
            if (rows < 2 || cols < 1 || recoveryEntryCount < 0) throw new IllegalArgumentException("Invalid window viewport");
        }

        Input(TerminalEditorApp host, TerminalEditorApp.SessionContext context,
            UiSessionState preferences, ActiveSurface surface, TabUiState tab, TabUiState focus, UiModalState modal,
            TerminalEditorApp.NotificationState notice, int recoveryEntryCount, int rows, int cols,
            TopMenuController menu, CommandRouter commands, TabStrip tabs) {
            this(host, context, preferences, () -> surface, tab, focus, modal, notice, recoveryEntryCount,
                rows, cols, menu, commands, tabs);
            Objects.requireNonNull(surface);
        }

        ActiveSurface surface() { return Objects.requireNonNull(surfaceSource.get()); }
    }

    /** Source/Preview supply only content decisions, never an editor backend to the shared preparation. */
    record Content(ActiveSurface surface, Rect bounds, String status, boolean[] tabMatchFlags) {
        Content { Objects.requireNonNull(surface); Objects.requireNonNull(bounds); Objects.requireNonNull(status); }
    }

    record Result(FrameComposition.Surface surface, FrameComposition.Layers layers,
        SearchBarView.Placement search) {}

    record Prepared(Input input, boolean showTabs, int contentTopRow, Rect dialogViewport,
        SplitPane explorerSplit, Rect documentPane,
        InlinePromptBar prompt, NotificationBar notification, SaveAsBarView.Placement saveAs,
        ConfirmBarView.Placement confirm, SearchBarView.Placement search, WindowTextPainter.Layout textLayout,
        NoticeBanner.Placement deletedNotice, NoticeBanner.Placement externalNotice) {
        Rect contentBounds(boolean reserveNotification) {
            return new Rect(documentPane.x(), contentTopRow, documentPane.width(), Math.max(0,
                input.rows() - 1 - contentTopRow - prompt.height() - (reserveNotification ? notification.height() : 0)
                    - (deletedNotice == null ? 0 : deletedNotice.bounds().height())
                    - (externalNotice == null ? 0 : externalNotice.bounds().height())));
        }
        Result finish(Content content) { return complete(this, content); }
    }

    /** Shared strip lifetime uses presentation identity, never a retained editing state. */
    static final class TabStrip {
        private TabBar.State state;
        private PresentationId active;
        private int activeIndex = -1;
        private Rect placedBounds;
        private TabBarView.Placement previous;

        TabStrip() { this(TabBar.State.initial()); }
        TabStrip(TabBar.State state) { this.state = Objects.requireNonNull(state); }
        void adopt(TabBar.State next) { state = Objects.requireNonNull(next); }
        void invalidateLayout() { placedBounds = null; }

        private TabBarView.Placement place(Input input, List<TabBarView.TabItem> items, boolean show, Rect pane) {
            if (!show) { previous = null; active = null; return null; }
            int nextIndex = input.context().activePresentationIndex();
            PresentationId nextActive = input.context().activePresentation().presentationId();
            var view = TabBarView.build(items, nextIndex, state);
            Rect bounds = new Rect(pane.x(), 1, pane.width(), 1), clip = new Rect(0, 0, input.cols(), input.rows());
            var placement = !Objects.equals(active, nextActive) || activeIndex != nextIndex || !bounds.equals(placedBounds)
                ? view.placeRevealingActive(bounds, clip) : view.placeKeepingActiveVisible(bounds, clip, previous);
            active = nextActive; activeIndex = nextIndex; placedBounds = bounds;
            state = placement.state(); previous = placement;
            return placement;
        }
    }

    private WindowChrome() {}

    /** The document container width is independent of its local footer height. */
    static Rect documentPane(Input input) {
        Rect workspace = new Rect(0, 1, input.cols(), Math.max(0, input.rows() - 1));
        if (!input.preferences().explorer.visible) return workspace;
        return SplitPane.sideBySide(workspace, input.preferences().explorer.preferredWidth, 16, 24).secondBounds();
    }

    static Prepared prepare(Input input, SearchBarView searchBar) {
        var host = input.host(); var tab = input.tab(); var focus = input.focus(); var modal = input.modal();
        boolean showTabs = input.preferences().showTabBarSetting && input.context().presentations().size() > 1;
        int top = showTabs ? 2 : 1;
        int availableRows = Math.max(1, input.rows() - top - 1);
        boolean windowPrompt = modal.isQuitConfirm() || modal.isRecoveryConfirm() || modal.isSettingsConfirm();
        int promptWidth = windowPrompt ? input.cols() : documentPane(input).width();
        NotificationBar notification = NotificationBar.of(input.notice().message, EditorStyleAdapter.toEditor(input.notice().style()));
        var prompt = InlinePromptBar.hidden();
        SaveAsBarView saveAs = null;
        ConfirmBarView confirm = null;
        SearchBarView activeSearch = TerminalEditorApp.searchOwnsInteractiveRow(modal) && !tab.openFileMode ? searchBar : null;
        if (modal.isSaveAs()) {
            saveAs = SaveAsBarView.build(modal.saveFormat() == DocumentSaveFormat.UTF8
                ? "Save UTF-8: " : SaveAsBarView.DEFAULT_LABEL, tab.saveAsInput, promptWidth);
            prompt = InlinePromptBar.of(saveAs.row(), EditorStyleAdapter.toEditor(host.styles.windows().promptAlert()), 1);
        } else {
            confirm = confirmation(input, availableRows, promptWidth);
            if (confirm != null) prompt = InlinePromptBar.of(confirm.row(), EditorStyleAdapter.toEditor(host.styles.windows().promptAlert()), 1);
            else if (activeSearch != null) prompt = InlinePromptBar.of(activeSearch.text(), EditorStyleAdapter.toEditor(host.styles.windows().searchBar()), 1);
        }
        var window = SurfaceContext.root(new Rect(0, 0, input.cols(), input.rows()), host.explorerWidgetTheme(true));
        var workspace = window.child(new Rect(0, 1, input.cols(), Math.max(0, input.rows() - 1)));
        Rect body = new Rect(0, 1, input.cols(), Math.max(0, input.rows() - (windowPrompt ? 2 : 1)
            - (windowPrompt ? prompt.height() + notification.height() : 0)));
        var explorerSplit = input.preferences().explorer.prepareLayout(body);
        Rect allocated = explorerSplit == null ? body : explorerSplit.secondBounds();
        Rect documentPane = new Rect(allocated.x(), 1, allocated.width(), Math.max(0, input.rows() - 1));
        var document = workspace.child(new Rect(documentPane.x(), top - 1, documentPane.width(), Math.max(0, input.rows() - top)));
        var footerParent = windowPrompt
            ? window.child(new Rect(0, top, input.cols(), Math.max(0, input.rows() - top))) : document;
        Rect clip = footerParent.clip();
        int promptTop = input.rows() - 1 - prompt.height();
        var promptSurface = footerParent.child(new Rect(0, promptTop - footerParent.bounds().y(),
            footerParent.bounds().width(), prompt.height()));
        dev.tuiframe.widget.Theme controls = saveAs != null ? host::resolveSaveAsWidgetStyle
            : confirm != null ? host::resolveConfirmWidgetStyle : host::resolveSearchWidgetStyle;
        var promptBody = saveAs != null || confirm != null ? host.styles.windows().promptAlert() : host.styles.windows().searchBar();
        promptSurface = promptSurface.withTheme(controls.withSurface(promptBody, promptBody));
        var noticeSurface = footerParent.child(new Rect(0, promptTop - notification.height() - footerParent.bounds().y(),
            footerParent.bounds().width(), notification.height()));
        var statusSurface = document.child(new Rect(0, input.rows() - 1 - document.bounds().y(), document.bounds().width(), 1));
        var textLayout = new WindowTextPainter.Layout(statusSurface, promptSurface, noticeSurface);
        var documentId = TerminalEditorApp.documentId(input.context().activePresentation());
        var deletedBanner = host.deletedNotices.banner(documentId);
        NoticeBanner.Placement deletedNotice = null;
        NoticeBanner.Placement externalNotice = null;
        int bannerRows = Math.max(0, input.rows() - 1 - top - prompt.height() - notification.height());
        int bannerBottom = promptTop - notification.height();
        boolean bannerEnabled = modal.isNone() && !tab.openFileMode && !input.menu().state().isOpen();
        if (deletedBanner != null && !windowPrompt) {
            int height = Math.min(deletedBanner.preferredHeight(documentPane.width()), bannerRows);
            var bannerSurface = document.child(new Rect(0,
                bannerBottom - height - document.bounds().y(), documentPane.width(), height));
            deletedNotice = deletedBanner.place(host.deletedNotices.scopeId(documentId), bannerSurface.bounds(),
                bannerSurface.clip(), bannerEnabled);
            bannerRows -= height;
            bannerBottom -= height;
        }

        SaveAsBarView.Placement placedSave = null;
        ConfirmBarView.Placement placedConfirm = null;
        SearchBarView.Placement placedSearch = null;
        if (saveAs != null) {
            placedSave = saveAs.place(TerminalEditorApp.SAVE_AS_SCOPE_ID, promptSurface.bounds(), clip);
            placedSave.activate(focus.saveAsFocusManager);
        } else focus.saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
        if (confirm != null) {
            if (focus.confirmScopeId == null || !sameDialog(focus.confirmPromptState, modal)) {
                focus.confirmFocusManager.activeScopeId().ifPresent(focus.confirmFocusManager::deactivate);
                focus.confirmScopeId = new Object();
            }
            focus.confirmPromptState = modal;
            placedConfirm = confirm.place(focus.confirmScopeId, promptSurface.bounds(), clip);
            placedConfirm.activate(focus.confirmFocusManager);
        } else {
            focus.confirmFocusManager.activeScopeId().ifPresent(focus.confirmFocusManager::deactivate);
            focus.confirmPromptState = null; focus.confirmScopeId = null;
        }
        if (activeSearch != null && saveAs == null && confirm == null) {
            placedSearch = activeSearch.place(TerminalEditorApp.SEARCH_SCOPE_ID, promptSurface.bounds(), clip);
            placedSearch.activate(tab.searchFocusManager);
            if (tab.pendingSearchFocus != null) {
                tab.searchFocusManager.focus(placedSearch.focusScope(), tab.pendingSearchFocus);
                tab.pendingSearchFocus = null;
            }
            if (tab.searchEditorFocused) tab.searchFocusManager.clear(placedSearch.focusScope());
        } else if (!tab.searchMode && !tab.preview.searchVisible) tab.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
        int inset = showTabs ? top : 0;
        return new Prepared(input, showTabs, top, new Rect(0, inset, input.cols(), Math.max(0, input.rows() - inset)),
            explorerSplit, documentPane, prompt, notification, placedSave, placedConfirm, placedSearch, textLayout, deletedNotice, externalNotice);
    }

    private static Result complete(Prepared prepared, Content content) {
        var input = prepared.input(); var host = input.host(); var tab = input.tab(); var focus = input.focus();
        var tabItems = host.toPresentationTabItems(input.context(), content.tabMatchFlags());
        var selected = tabItems.get(input.context().activePresentationIndex());
        var tabs = input.tabs().place(input, tabItems, prepared.showTabs(), prepared.documentPane());
        var menuItems = tabItems.stream().map(item -> new TopMenuView.TabItem(item.file(), item.dirty(),
            item.hasMatch(), item.searching(), item.awaitingAnswer(), item.qualifier())).toList();
        var menu = TopMenuView.build(input.preferences(), menuItems,
            input.context().activePresentationIndex(), host.theme.label(), input.commands(), input.menu().state(), content.surface());
        var menuPlacement = menu.place(new Rect(0, 0, input.cols(), input.rows()), content.surface().reading(), selected.dirty());
        input.menu().adopt(menuPlacement.state());
        var topBar = menu.topBar(selected.file(), selected.dirty(), input.cols(), content.surface().reading());
        var picker = picker(prepared);
        var explorer = input.preferences().explorer.place(prepared.explorerSplit(),
            new Rect(0, 0, input.cols(), input.rows()), input.modal().isNone() && !tab.openFileMode && !input.menu().state().isOpen());
        var page = host.activeHelpPage(input.modal().isHelpHints(), input.modal().isHelpLicenses(), input.modal().isHelpAuthor());
        var help = host.helpOverlayPlacement(input.rows(), input.cols(), page, prepared.dialogViewport().y());
        var format = input.modal().isDocumentFormat() && tab.formatDialog != null
            ? DocumentFormatDialogView.place(tab.formatDialog, prepared.dialogViewport(),
                DocumentFormatPolicy.apply(tab.formatDialog.purpose, content.surface()).allowed()) : null;
        var fileDialog = input.modal().isExplorerFiles() && focus.explorerDialog != null
            ? ExplorerFileDialogView.place(focus.explorerDialog, prepared.dialogViewport()) : null;
        Rect blocking = FrameComposition.Layers.prepareBlockingBounds(input.rows(), input.cols(),
            prepared.contentTopRow(), prepared.prompt().height(), prepared.saveAs() != null || prepared.confirm() != null,
            picker, help, format, fileDialog, prepared.textLayout().prompt().clip());
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
        return new Result(new FrameComposition.Surface(topBar, content.status(), prepared.prompt(), prepared.notification(), prepared.textLayout(), prepared.deletedNotice(), prepared.externalNotice()),
            new FrameComposition.Layers(tabs, menuPlacement, prepared.saveAs() != null, prepared.saveAs(), prepared.confirm(),
                picker, help, format, focus.modalAttention.highlighted(), focus.blockingContentFocused, blocking, explorer, fileDialog), prepared.search());
    }

    private static ConfirmBarView confirmation(Input input, int rows, int cols) {
        var modal = input.modal(); var host = input.host();
        if (modal.isSaveOverwriteConfirm()) return host.buildOverwriteConfirmBar(modal.pendingOverwritePath(), cols, rows);
        if (modal.isQuitConfirm()) {
            long snapshots = input.context().presentations().stream().filter(tab -> tab instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.ClosedSnapshot frozen
                && (host.deletedNotices.hasUnsavedRemoval(TerminalEditorApp.documentId(tab))
                    || frozen.subject().unsavedExternalChanges())).count();
            if (snapshots > 0) return ConfirmBarView.responsive("Save unsaved documents, including " + snapshots + " preview draft(s)?",
                cols, rows, "Save All", "Discard All", true, "Cancel", host.confirmPolicy(modal).shortcuts());
            return host.buildQuitConfirmBar(input.context().tabs(), cols, rows);
        }
        if (modal.isSettingsConfirm()) return ConfirmBarView.responsive(
            "Unsupported settings. Replace invalid values with defaults?", cols, rows,
            "Fix", "Delete", true, "Later", host.confirmPolicy(modal).shortcuts());
        if (modal.isRecoveryConfirm()) return ConfirmBarView.responsive(host.buildRecoveryPrompt(input.recoveryEntryCount()), cols, rows,
            "Restore", "Delete", true, "Later", host.confirmPolicy(modal).shortcuts());
        if (modal.isCloseTabConfirm()) {
            if (input.context().activePresentation() instanceof PreviewTab preview && preview.source() instanceof PreviewTab.ClosedSnapshot frozen) {
                var file = dev.tuink.storage.jvm.JvmDocumentStorage.pathOf(frozen.subject().binding().resource());
                var caption = host.deletedDraftCaption(preview);
                if (caption == null) caption = file;
                return ConfirmBarView.responsive(ConfirmPromptText.wrappingFileQuestion("Close draft: ", caption,
                    " ?", cols * Math.max(1, Math.min(8, rows - 4))), cols, rows, file == null ? "Save As" : "Save", "Discard", true, "Cancel",
                    host.confirmPolicy(modal).shortcuts());
            }
            return host.buildCloseTabConfirmBar(input.context().tabs(), modal.pendingCloseTabIndex(), cols, rows);
        }
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
            tree.showHiddenFiles(), viewport, tab.pickerNotice, tab.openFileFocusState.location(),
            tab.choosingFolder ? tree.root() : null);
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

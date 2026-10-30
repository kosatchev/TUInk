package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.core.EditorEngine;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.ViewId;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.core.EditorState;
import dev.tuink.core.LanguageProfiles;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.input.EditorInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.cell.Canvas;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuiframe.runtime.TuiApplicationRunner;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.DialogInputPolicy;
import dev.tuiframe.widget.DialogMode;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.ConfirmPromptText;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.ViewportController;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Owns frame preparation and event handling for one terminal editor window. */
final class EditorWindowController implements TuiApplicationRunner.Frontend<TuiInputEvent> {

    private static final long SYNTAX_RETRY_DELAY_NANOS = 16_000_000L;
    private static final Object DOCUMENT_POINTER_SCOPE = new Object();
    private static final Object HELP_CONTENT_POINTER_SCOPE = new Object();
    private final WindowPointerCapture pointerCapture = new WindowPointerCapture();
    private final TerminalEditorApp host;
    private final CommandRouter commandRouter;
    private final EditorView editorView;
    private final TuiEventLoop<TuiInputEvent> eventLoop;
    private final TerminalEditorApp.RecoveryAutosaveManager recoveryAutosave;
    private final TerminalEditorApp.RenderFrameController renderFrameController;
    private final RenderRasterizer renderer;
    private final SaveAsSuggestionStore saveAsSuggestions;
    private final ScreenPresenter screenPresenter;
    private final TerminalEditorApp.SessionContext sessionContext;
    private final List<RecoveryStore.PendingSnapshot> startupRecoveryEntries;
    private final List<TerminalEditorApp.EditorTab> tabs;
    private final TerminalAccess terminal;
    private final TopMenuController topMenu;
    private final UiSessionState uiSessionState;
    private final AppSettingsSession settingsSession;
    private final DocumentIoController documentIo;
    private SaveWorkflow.SaveRequest pendingSave;
    private FilePickerOpenWorkflow.OpenRequest pendingOpen;
    private boolean quitAfterDocumentIo;
    private boolean closingDocumentIo;

    EditorWindowController(TerminalEditorApp host, Resources resources) {
        this.host = Objects.requireNonNull(host, "host");
        this.commandRouter = resources.commandRouter();
        this.editorView = resources.editorView();
        this.eventLoop = resources.eventLoop();
        this.recoveryAutosave = resources.recoveryAutosave();
        this.renderFrameController = resources.renderFrameController();
        this.renderer = resources.renderer();
        this.saveAsSuggestions = resources.saveAsSuggestions();
        this.screenPresenter = resources.screenPresenter();
        this.sessionContext = resources.sessionContext();
        this.startupRecoveryEntries = resources.startupRecoveryEntries();
        this.tabs = sessionContext.tabs();
        this.editorView.bindCommands(command -> activeDocument().execute(command));
        this.terminal = resources.terminal();
        this.topMenu = resources.topMenu();
        this.uiSessionState = resources.uiSessionState();
        this.settingsSession = resources.settingsSession();
        this.documentIo = Objects.requireNonNull(resources.documentIo(), "documentIo");
        TabUiState initial = sessionContext.active().uiState();
        initial.searchQueryInput = resources.searchQueryInput();
        initial.searchReplaceInput = resources.searchReplaceInput();
        initial.saveAsInput = resources.saveAsInput();
        initial.searchUi = resources.initialSearchUi();
        initial.searchMode = uiSessionState.searchMode;
        initial.searchEditorFocused = uiSessionState.searchEditorFocused;
        initial.notification = resources.notification();
        initial.openFileTree = resources.openFileTree();
        setModal(resources.initialModalState());
        host.bindTabUi(this::focusUi);
    }

    record Resources(
        CommandRouter commandRouter,
        EditorView editorView,
        TuiEventLoop<TuiInputEvent> eventLoop,
        UiModalState initialModalState,
        SearchController.SearchUiOptions initialSearchUi,
        TerminalEditorApp.NotificationState notification,
        OpenFileTreeController openFileTree,
        TerminalEditorApp.RecoveryAutosaveManager recoveryAutosave,
        TerminalEditorApp.RenderFrameController renderFrameController,
        RenderRasterizer renderer,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        ScreenPresenter screenPresenter,
        TextField searchQueryInput,
        TextField searchReplaceInput,
        TerminalEditorApp.SessionContext sessionContext,
        List<RecoveryStore.PendingSnapshot> startupRecoveryEntries,
        TerminalAccess terminal,
        TopMenuController topMenu,
        UiSessionState uiSessionState,
        AppSettingsSession settingsSession,
        DocumentIoController documentIo
    ) {}

    record TerminalAccess(IntSupplier widthSource, IntSupplier heightSource,
                          Consumer<Boolean> mouseTracking) {
        int width() { return widthSource.getAsInt(); }
        int height() { return heightSource.getAsInt(); }
        void setMouseTracking(boolean enabled) { mouseTracking.accept(enabled); }
    }
    private boolean running = true;
    private EditorView.ScrollAxis editorScrollDrag = EditorView.ScrollAxis.NONE;
    private int editorScrollDragThumbOffset;

    private int activeTabIndex;
    private TextModel model;
    private EditorState state;
    private EditorEngine engine;
    private RenderPlanBuilder renderPlanBuilder;
    private ViewportController viewportController;
    private EditorViewNavigation navigation;

    private RenderPlan plan;
    private SearchBarView.Placement searchBarPlacement;
    private ConfirmBarView.Placement confirmBarPlacement;
    private SaveAsBarView.Placement saveAsBarPlacement;
    private Rect modalInputBounds = new Rect(0, 0, 0, 0);
    private int contentTopRow = 1;
    private int contentRows;
    private int contentCols;
    private int contentLeft;
    private int editorPointerRows;
    private TabBar.State tabBarState = TabBar.State.initial();
    private EditorState tabBarActiveState;
    private int tabBarActiveIndex = -1;
    private int tabBarColumns = -1;
    private TabBarView.Placement tabBarPlacement;
    private TopMenuView.Placement topMenuPlacement;
    private FileTreeView.Placement openFileTreePlacement;
    private OpenFileDialogView.Placement openFileDialogPlacement;
    private TerminalEditorApp.HelpOverlayPlacement helpOverlayPlacement;

    private int rows;
    private int cols;
    private boolean saveAsMode;
    private boolean saveOverwriteConfirmMode;
    private boolean recoveryConfirmMode;
    private boolean quitConfirmMode;
    private boolean closeTabConfirmMode;
    private boolean helpHintsMode;
    private boolean helpLicensesMode;
    private boolean helpAuthorMode;
    private Path pendingOverwritePath;
    private int pendingCloseTabIndex;
    private boolean confirmMode;
    private long syntaxRetryAtNanos = Long.MIN_VALUE;

    private final TabUiState globalUi = new TabUiState();
    private UiModalState windowModal = UiModalState.none();
    private TabUiState uiOverride;
    private ViewId interactionViewId;
    private boolean globalInteraction;
    private final java.util.Set<TabUiState> knownUiStates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private TabUiState ui() { return uiOverride != null ? uiOverride : sessionContext.active().uiState(); }

    private TabUiState focusUi() {
        return uiOverride != null ? uiOverride : !windowModal.isNone() ? globalUi : ui();
    }

    private UiModalState modalState() {
        return uiOverride == null && !windowModal.isNone() ? windowModal : ui().modalState;
    }

    private static boolean globalModal(UiModalState modal) {
        return modal.isQuitConfirm() || modal.isRecoveryConfirm();
    }

    private void setModal(UiModalState modal) {
        Objects.requireNonNull(modal, "modal");
        if (modal.isNone() && !windowModal.isNone() && (uiOverride == null || globalInteraction)) {
            var message = globalUi.notification;
            if (message != null && message.message != null) {
                var replacement = host.new NotificationState();
                sessionContext.active().uiState().notification = message;
                globalUi.notification = replacement;
            }
            clearWindowModal();
            return;
        }
        if (globalModal(modal)) {
            if (uiOverride == null || globalInteraction) windowModal = modal;
        } else {
            if (uiOverride == null || globalInteraction) clearWindowModal();
            ui().modalState = modal;
        }
    }

    private void clearWindowModal() {
        if (windowModal.isNone()) return;
        globalUi.confirmFocusManager.activeScopeId().ifPresent(globalUi.confirmFocusManager::deactivate);
        globalUi.confirmPromptState = null;
        globalUi.confirmScopeId = null;
        globalUi.blockingFocusOwner = null;
        globalUi.blockingContentFocused = false;
        windowModal = UiModalState.none();
    }

    private TerminalEditorApp.NotificationState notice() {
        TabUiState owner = focusUi();
        if (owner.notification == null) owner.notification = host.new NotificationState();
        return owner.notification;
    }

    TerminalEditorApp.NotificationState notificationState() { return notice(); }

    private OpenFileTreeController openFileTree() {
        if (ui().openFileTree == null) {
            Path file = JvmDocumentStorage.pathOf(tabs.get(interactionIndex()).state().getCurrentResource());
            ui().openFileTree = new OpenFileTreeController(FilePickerOpenWorkflow.initialRoot(file), true, true);
        }
        return ui().openFileTree;
    }

    private TabUiState stateFor(ViewId viewId) {
        int index = sessionContext.indexOf(viewId);
        return index < 0 ? null : tabs.get(index).uiState();
    }

    private int interactionIndex() {
        int index = interactionViewId == null ? -1 : sessionContext.indexOf(interactionViewId);
        return index >= 0 ? index : sessionContext.activeTabIndex();
    }

    private boolean backgroundInteraction() {
        return uiOverride != null && !globalInteraction && !sessionContext.activeViewId().equals(interactionViewId);
    }

    private void withUi(ViewId viewId, TabUiState owner, boolean globalFlow, Runnable action) {
        TabUiState previous = uiOverride;
        ViewId previousView = interactionViewId;
        boolean previousGlobal = globalInteraction;
        uiOverride = owner;
        interactionViewId = viewId;
        globalInteraction = globalFlow;
        try { action.run(); }
        finally {
            uiOverride = previous;
            interactionViewId = previousView;
            globalInteraction = previousGlobal;
        }
    }

    private void withUi(ViewId viewId, TabUiState owner, Runnable action) { withUi(viewId, owner, false, action); }

    private void syncSearchProjection() {
        uiSessionState.searchMode = ui().searchMode;
        uiSessionState.searchEditorFocused = ui().searchEditorFocused;
    }

    private void synchronizeUiLifetime() {
        var live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TabUiState, Boolean>());
        for (var tab : tabs) live.add(tab.uiState());
        var removed = knownUiStates.iterator();
        while (removed.hasNext()) {
            TabUiState previous = removed.next();
            if (!live.contains(previous)) { previous.close(); removed.remove(); }
        }
        knownUiStates.addAll(live);
    }

    private void activateTab(int index) {
        if (index < 0 || index >= tabs.size() || index == sessionContext.activeTabIndex()) return;
        cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
        cancelDocumentPointerInteraction();
        sessionContext.activate(index);
        searchBarPlacement = null;
        saveAsBarPlacement = null;
        confirmBarPlacement = null;
        openFileDialogPlacement = null;
        openFileTreePlacement = null;
        helpOverlayPlacement = null;
        topMenu.close();
        syncSearchProjection();
        eventLoop.invalidate();
    }

    private void closeView(int index) {
        var tab = tabs.get(index);
        ViewId viewId = tab.documentSession().viewId();
        if (!sessionContext.canClose(viewId)) {
            withUi(viewId, tab.uiState(), () -> notice().info("Saving this document; wait before closing its tab"));
        } else if (tab.uiState().awaitingAnswer()) {
            activateTab(index);
            tab.uiState().modalAttention.request(TerminalEditorApp.modalInputOwner(tab.uiState().modalState,
                tab.uiState().openFileMode), System.nanoTime());
        } else if (tab.state().isDirty() && uiSessionState.confirmOnCloseSetting && !sessionContext.hasOtherViews(viewId)) {
            tab.uiState().modalState = UiModalState.closeTabConfirm(index).bindTo(viewId);
        } else {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
            host.closeTab(sessionContext, viewId, saveAsSuggestions);
            searchBarPlacement = null;
            saveAsBarPlacement = null;
            confirmBarPlacement = null;
        }
        syncSearchProjection();
        eventLoop.invalidate();
    }

    /** Window navigation remains reachable while a view owns an input dialog. */
    private boolean routeTabNavigation(TuiInputEvent event, TuinkCommand command) {
        if (!windowModal.isNone()) return false;
        if (command == TuinkCommand.TAB_NEXT || command == TuinkCommand.TAB_PREVIOUS) {
            activateTab(Math.floorMod(sessionContext.activeTabIndex() + (command == TuinkCommand.TAB_NEXT ? 1 : -1), tabs.size()));
            return true;
        }
        if (command == TuinkCommand.TAB_NEW) {
            sessionContext.addAll(List.of(host.newEditorTab()));
            activateTab(tabs.size() - 1);
            return true;
        }
        if (command == TuinkCommand.TAB_CLOSE) {
            closeView(sessionContext.activeTabIndex());
            return true;
        }
        if (event instanceof PointerEvent pointer && tabBarPlacement != null
                && uiSessionState.mouseModeSetting && uiSessionState.mouseModeAvailable && !topMenu.isOpen()) {
            var result = TabBarInputRouter.route(tabBarPlacement, TabBarInputRouter.Policy.NORMAL, pointer);
            tabBarState = result.state();
            if (result.activateIndex() != null && result.activateIndex() < tabs.size()) activateTab(result.activateIndex());
            if (result.closeIndex() != null && result.closeIndex() < tabs.size()) closeView(result.closeIndex());
            if (result.consumed()) { eventLoop.invalidate(); return true; }
        }
        return false;
    }

    private boolean finishPendingDialogsBeforeQuit() {
        for (int index = 0; index < tabs.size(); index++) {
            if (!tabs.get(index).uiState().awaitingAnswer()) continue;
            setModal(UiModalState.none());
            activateTab(index);
            notice().info("Finish this document dialog before saving all documents and quitting");
            return true;
        }
        return false;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public void prepareTurn() {
        synchronizeUiLifetime();
        syncSearchProjection();
        synchronizeModalTarget();
        while (documentIo.collect()) {
            eventLoop.invalidate();
        }
        syncSearchProjection();
        if (ui().deferredOpenResult != null && modalState().isNone() && !documentIo.busy() && !closingDocumentIo) {
            FilePickerOpenWorkflow.Result ready = ui().deferredOpenResult;
            ui().deferredOpenResult = null;
            applyOpenFileResult(ready);
            eventLoop.invalidate();
        }
        uiSessionState.syntaxHighlightingAvailable = host.syntaxHighlightingAvailable();
        if (state != sessionContext.state()) {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        activeTabIndex = sessionContext.activeTabIndex();
        model = sessionContext.model();
        state = sessionContext.state();
        engine = sessionContext.engine();
        renderPlanBuilder = sessionContext.renderPlanBuilder();
        if (syntaxRetryAtNanos != Long.MIN_VALUE) {
            if (!renderPlanBuilder.hasPendingSyntaxWork()) {
                syntaxRetryAtNanos = Long.MIN_VALUE;
            } else if (System.nanoTime() - syntaxRetryAtNanos >= 0L) {
                syntaxRetryAtNanos = Long.MIN_VALUE;
                eventLoop.invalidate();
            }
        }
        viewportController = tabs.get(activeTabIndex).viewportController();
        navigation = tabs.get(activeTabIndex).navigation();
        engine.setGutterDiffMode(uiSessionState.gutterDiffModeSetting);
        if (engine.refreshGutterDiffIfDue()) {
            eventLoop.invalidate();
        }
        Object nextBlockingOwner = TerminalEditorApp.modalInputOwner(modalState(), ui().openFileMode);
        if (!Objects.equals(focusUi().blockingFocusOwner, nextBlockingOwner)) {
            focusUi().blockingFocusOwner = nextBlockingOwner;
            focusUi().blockingContentFocused = false;
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        if (host.modalAttention().synchronize(TerminalEditorApp.modalInputOwner(modalState(), ui().openFileMode), System.nanoTime())) {
            eventLoop.invalidate();
        }
        List<TuiDocumentSession> recoveryTabSessions = host.toDocumentSessions(tabs);
        recoveryAutosave.syncTabs(recoveryTabSessions);
        String recoveryWarning = modalState().isRecoveryConfirm() ? null
            : recoveryAutosave.autosaveDirtyTabs(recoveryTabSessions, System.nanoTime());
        if (recoveryWarning != null) {
            notice().warning(recoveryWarning);
            eventLoop.invalidate();
        }
        if (notice().expireIfNeeded()) {
            eventLoop.invalidate();
        }
        if (ui().openFileMode && openFileTree().consumeMetadataChanged()) {
            var failure = openFileTree().directoryFailure();
            if (failure != null && !failure.equals(ui().lastPickerDirectoryFailure)) {
                ui().pickerNotice = new FilePickerOpenWorkflow.Notice(failure.reason(), failure.directory());
            }
            ui().lastPickerDirectoryFailure = failure;
            eventLoop.invalidate();
        }
        int nextRows = Math.max(2, terminal.height());
        int nextCols = Math.max(1, terminal.width());
        if (rows != nextRows || cols != nextCols || !uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable) {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        rows = nextRows;
        cols = nextCols;
        saveAsMode = modalState().isSaveAs();
        saveOverwriteConfirmMode = modalState().isSaveOverwriteConfirm();
        recoveryConfirmMode = modalState().isRecoveryConfirm();
        quitConfirmMode = modalState().isQuitConfirm();
        closeTabConfirmMode = modalState().isCloseTabConfirm();
        helpHintsMode = modalState().isHelpHints();
        helpLicensesMode = modalState().isHelpLicenses();
        helpAuthorMode = modalState().isHelpAuthor();
        if (!helpHintsMode && !helpLicensesMode && !helpAuthorMode) {
            host.resetHelpScroll();
        }
        pendingOverwritePath = modalState().pendingOverwritePath();
        pendingCloseTabIndex = modalState().pendingCloseTabIndex();
        confirmMode = recoveryConfirmMode
            || modalState().isSaveUtf8Confirm()
            || modalState().isOpenUtf16Confirm()
            || saveOverwriteConfirmMode
            || quitConfirmMode
            || closeTabConfirmMode;
        if (!ui().searchMode) {
            ui().searchEditorFocused = false;
            host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            searchBarPlacement = null;
        }
        if (!saveAsMode) {
            host.saveAsFocusManager().deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
            saveAsBarPlacement = null;
        }
        if (confirmMode) {
            if (!sameDialog(focusUi().confirmPromptState, modalState())) {
                host.deactivateConfirmFocus();
                focusUi().confirmPromptState = modalState();
                focusUi().confirmScopeId = new Object();
                confirmBarPlacement = null;
            }
        } else {
            host.deactivateConfirmFocus();
            focusUi().confirmPromptState = null;
            focusUi().confirmScopeId = null;
            confirmBarPlacement = null;
        }
        focusUi().confirmPromptState = confirmMode ? modalState() : null;
        if (plan != null) runDocumentPointerAction(() -> {
            if (editorView.scrollSelectionIfDue(
                model, state, plan, contentRows, engine, contentTopRow, System.nanoTime()
            )) {
                viewportController.onEditorTextInteraction();
                eventLoop.invalidate();
            }
        });
    }

    private static boolean sameDialog(UiModalState a, UiModalState b) {
        return a == b || a != null && a.pendingCloseTabIndex() != b.pendingCloseTabIndex()
            && a.mode() == b.mode() && Objects.equals(a.targetViewId(), b.targetViewId())
            && Objects.equals(a.pendingOverwritePath(), b.pendingOverwritePath())
            && a.saveContinuation() == b.saveContinuation() && a.saveFormat() == b.saveFormat()
            && Objects.equals(a.pendingOpenPath(), b.pendingOpenPath());
    }

    @Override
    public boolean renderFrame(TuiEventLoop.FrameTicket frameTicket) {
        if (frameTicket.fullRedraw()) {
            screenPresenter.invalidate();
        }
        if (host.profileEnabled) {
            host.frameProfiler.beginFrame();
        }
        FrameComposition composition = prepareComposition(frameTicket.fullRedraw());
        TerminalEditorApp.RenderFrameResult frameResult = renderFrameController.renderFrameIfNeeded(composition);
        plan = frameResult.plan();
        syntaxRetryAtNanos = renderPlanBuilder.hasPendingSyntaxWork()
            ? System.nanoTime() + SYNTAX_RETRY_DELAY_NANOS : Long.MIN_VALUE;
        return frameResult.rendered();
    }

    private FrameComposition prepareComposition(boolean fullRedraw) {
        SearchPresentation.Snapshot searchSnapshot = ui().searchPresentation.resolve(
            ui().searchMode, ui().searchQueryInput.text(), ui().searchUi, host.toTabSessions(tabs), activeTabIndex);
        if (!searchSnapshot.error().equals(ui().reportedSearchError)) {
            ui().reportedSearchError = searchSnapshot.error();
            if (!ui().reportedSearchError.isEmpty()) notice().error(ui().reportedSearchError);
        }
        String searchQuery = searchSnapshot.query();
        SearchOptions searchOptions = searchSnapshot.options();
        if (host.profileEnabled) {
            host.frameProfiler.searchNanos = host.frameProfiler.stopwatchNanos();
        }
        SearchBarView searchBar = ui().searchMode
            ? SearchBarView.build(
                ui().searchQueryInput,
                ui().searchReplaceInput,
                cols,
                ui().searchUi.caseSensitive(),
                ui().searchUi.matchMode(),
                ui().searchUi.allTabs()
            )
            : null;
        String searchSummary = searchSnapshot.summary();
        boolean showTabBar = uiSessionState.showTabBarSetting && tabs.size() > 1;
        contentTopRow = showTabBar ? 2 : 1;
        // Keep the search model for resume-after-modal, but a
        // modal prompt exclusively owns this frame's row,
        // painter, and cursor.
        SearchBarView activeSearchBar = TerminalEditorApp.searchOwnsInteractiveRow(modalState())
                && !ui().openFileMode
            ? searchBar
            : null;
        ConfirmBarView confirmBar = null;
        SaveAsBarView saveAsBar = null;
        InlinePromptBar inlinePromptBar = InlinePromptBar.hidden();
        if (saveAsMode) {
            saveAsBar = SaveAsBarView.build(
                modalState().saveFormat() == dev.tuink.tui.flow.DocumentSaveFormat.UTF8 ? "Save UTF-8: " : SaveAsBarView.DEFAULT_LABEL,
                ui().saveAsInput, cols);
            inlinePromptBar = InlinePromptBar.of(
                saveAsBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1
            );
        } else if (modalState().isSaveUtf8Confirm() || modalState().isOpenUtf16Confirm()) {
            boolean opening = modalState().isOpenUtf16Confirm();
            String primaryLabel = opening ? "Open UTF-16LE" : "Save UTF-8";
            String question = opening
                ? ConfirmPromptText.fileQuestion("Ambiguous: ", modalState().pendingOpenPath(), " as UTF-16LE?",
                    ConfirmBarView.promptWidth(cols, primaryLabel, "Cancel", false, "Cancel"))
                : "Save as UTF-8 without BOM?";
            confirmBar = ConfirmBarView.buildAlternative(
                question, cols, primaryLabel);
            inlinePromptBar = InlinePromptBar.of(confirmBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()), 1, 1);
        } else if (saveOverwriteConfirmMode) {
            confirmBar = ConfirmBarView.build(
                host.buildOverwritePrompt(pendingOverwritePath, cols),
                cols
            );
            inlinePromptBar = InlinePromptBar.of(
                confirmBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1,
                1
            );
        } else if (recoveryConfirmMode) {
            confirmBar = ConfirmBarView.buildRecovery(
                host.buildRecoveryPrompt(startupRecoveryEntries.size()),
                cols
            );
            inlinePromptBar = InlinePromptBar.of(
                confirmBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1,
                1
            );
        } else if (quitConfirmMode) {
            confirmBar = host.buildQuitConfirmBar(tabs, cols);
            inlinePromptBar = InlinePromptBar.of(
                confirmBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1,
                1
            );
        } else if (closeTabConfirmMode) {
            confirmBar = host.buildCloseTabConfirmBar(
                tabs,
                pendingCloseTabIndex,
                cols
            );
            inlinePromptBar = InlinePromptBar.of(
                confirmBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1,
                1
            );
        } else if (activeSearchBar != null) {
            inlinePromptBar = InlinePromptBar.of(
                activeSearchBar.text(),
                EditorStyleAdapter.toEditor(host.styles.searchBar()),
                1,
                1
            );
        }
        Rect promptInteractionClip = new Rect(
            0,
            contentTopRow,
            cols,
            Math.max(0, rows - contentTopRow)
        );
        if (saveAsBar != null) {
            saveAsBarPlacement = saveAsBar.place(
                TerminalEditorApp.SAVE_AS_SCOPE_ID,
                rows - 1 - inlinePromptBar.height(),
                promptInteractionClip
            );
            saveAsBarPlacement.activate(host.saveAsFocusManager());
        }
        if (confirmBar != null) {
            confirmBarPlacement = confirmBar.place(
                focusUi().confirmScopeId,
                rows - 1 - inlinePromptBar.height(),
                promptInteractionClip
            );
            confirmBarPlacement.activate(host.confirmFocusManager());
        }
        if (activeSearchBar != null) {
            searchBarPlacement = activeSearchBar.place(
                TerminalEditorApp.SEARCH_SCOPE_ID,
                rows - 1 - inlinePromptBar.height(),
                promptInteractionClip
            );
            searchBarPlacement.activate(host.searchFocusManager());
            if (ui().pendingSearchFocus != null) {
                host.searchFocusManager().focus(searchBarPlacement.focusScope(), ui().pendingSearchFocus);
                ui().pendingSearchFocus = null;
            }
            if (ui().searchEditorFocused) {
                host.searchFocusManager().clear(searchBarPlacement.focusScope());
            }
        } else {
            searchBarPlacement = null;
        }
        NotificationBar notificationBar = NotificationBar.of(
            notice().message,
            EditorStyleAdapter.toEditor(notice().style())
        );
        int overlayRows = inlinePromptBar.height();
        engine.setTabWidth(uiSessionState.tabWidthSetting);
        EditorLayout editorLayout = null;
        for (int i = 0; i < 3; i++) {
            editorLayout = EditorLayout.resolve(
                model,
                engine.textLayout(),
                state,
                rows,
                cols,
                contentTopRow,
                overlayRows,
                uiSessionState.wrapLinesSetting,
                uiSessionState.wrapWordsSetting,
                uiSessionState.tabWidthSetting
            );
            int topBefore = state.getTopLine();
            int segmentBefore = state.getTopWrapSegment();
            int leftBefore = state.getLeftVisualColumn();
            viewportController.ensureCursorVisible(
                engine,
                editorLayout.contentRows(),
                editorLayout.contentColumns(),
                uiSessionState.wrapLinesSetting,
                uiSessionState.wrapWordsSetting,
                uiSessionState.tabWidthSetting
            );
            if (topBefore == state.getTopLine()
                && segmentBefore == state.getTopWrapSegment()
                && leftBefore == state.getLeftVisualColumn()) {
                break;
            }
        }
        editorLayout = EditorLayout.resolve(
            model,
            engine.textLayout(),
            state,
            rows,
            cols,
            contentTopRow,
            overlayRows,
            uiSessionState.wrapLinesSetting,
            uiSessionState.wrapWordsSetting,
            uiSessionState.tabWidthSetting
        );
        contentRows = editorLayout.contentRows();
        contentCols = editorLayout.contentColumns();
        contentLeft = editorLayout.gutterWidth();
        editorPointerRows = contentRows + editorLayout.horizontalScrollbarHeight();
        if (ui().searchEditorFocused && searchBarPlacement != null
            && (contentRows == 0 || contentCols == 0)) {
            ui().searchEditorFocused = false;
            host.searchFocusManager().next(searchBarPlacement.focusScope());
        }
        boolean[] tabMatchFlags = searchSnapshot.tabMatchFlags();
        List<TabBarView.TabItem> tabItems = host.toTabItems(tabs, tabMatchFlags);
        if (showTabBar) {
            TabBarView tabBarView = TabBarView.build(
                tabItems,
                activeTabIndex,
                tabBarState
            );
            Rect tabBounds = new Rect(0, 1, cols, 1);
            Rect tabClip = new Rect(0, 0, cols, rows);
            tabBarPlacement = tabBarActiveState != state || tabBarActiveIndex != activeTabIndex
                || tabBarColumns != cols
                ? tabBarView.placeRevealingActive(tabBounds, tabClip)
                : tabBarView.placeKeepingActiveVisible(tabBounds, tabClip, tabBarPlacement);
            tabBarActiveState = state;
            tabBarActiveIndex = activeTabIndex;
            tabBarColumns = cols;
            tabBarState = tabBarPlacement.state();
        } else {
            tabBarPlacement = null;
            tabBarActiveState = null;
        }
        TopMenuView topMenuView = TopMenuView.build(
            uiSessionState,
            host.toTopMenuTabs(tabs, tabMatchFlags),
            activeTabIndex,
            host.theme.label(),
            commandRouter,
            topMenu.state()
        );
        topMenuPlacement = topMenuView.place(
            new Rect(0, 0, cols, rows), uiSessionState.readingModeSetting, state.isDirty()
        );
        topMenu.adopt(topMenuPlacement.state());
        OpenDialogLayout openFileLayout = null;
        int dialogTopInset = showTabBar ? contentTopRow : 0;
        Rect dialogViewport = new Rect(0, dialogTopInset, cols, Math.max(0, rows - dialogTopInset));
        if (ui().openFileMode) {
            openFileLayout = OpenFileDialogController.layoutForTerminal(rows, cols, dialogTopInset);
            boolean treeVisible = !CompactDialogView.required(dialogViewport.height(), cols);
            int treeLeft = openFileLayout.left() + 2;
            Rect treeBounds = new Rect(
                treeLeft,
                openFileLayout.listStartRow(),
                Math.max(0, openFileLayout.right() - treeLeft),
                openFileLayout.listRows()
            );
            openFileTreePlacement = openFileTree().place(
                treeBounds,
                dialogViewport,
                ui().openFileFocusState.focus() == OpenFileInputRouter.Focus.LIST && !focusUi().blockingContentFocused,
                treeVisible
            );
            openFileDialogPlacement = OpenFileDialogView.place(
                TerminalEditorApp.OPEN_FILE_SCOPE_ID, openFileLayout, openFileTreePlacement,
                openFileTree().showHiddenFiles(), dialogViewport, ui().pickerNotice
            );
            openFileDialogPlacement.activate(ui().openFileFocusState);
        } else {
            openFileTreePlacement = null;
            openFileDialogPlacement = null;
        }
        renderer.setHighlightActiveGutter(uiSessionState.highlightActiveGutterSetting);
        renderer.setVisibleWhitespace(uiSessionState.visibleWhitespaceSetting);
        renderer.setTabWidth(uiSessionState.tabWidthSetting);
        renderPlanBuilder.setSyntaxHighlightingEnabled(
            host.effectiveSyntaxHighlighting(uiSessionState.syntaxHighlightingSetting)
        );
        renderPlanBuilder.setCommentSyntaxProfile(
            LanguageProfiles.resolveCommentSyntax(state.getCurrentResource() == null ? null : state.getCurrentResource().nameHint())
        );
        renderPlanBuilder.setRainbowBracketsEnabled(uiSessionState.rainbowBracketsSetting);
        renderPlanBuilder.setTabWidth(uiSessionState.tabWidthSetting);
        renderPlanBuilder.setDecorationStyles(
            host.styles.renderSelection(),
            host.styles.renderSearchMatch(),
            host.styles.renderCurrentSearchMatch(),
            host.styles.renderBracketMatch(),
            host.styles.renderQuoteMatch(),
            host.styles.renderUnmatchedBracket(),
            host.styles.renderRainbowBrackets()
        );
        renderPlanBuilder.setGutterDiffResolver(engine.gutterDiffMarkers());
        editorView.setTabWidth(uiSessionState.tabWidthSetting);
        engine.setTabInsertMode(uiSessionState.tabInsertModeSetting);
        engine.setAutoIndentEnabled(uiSessionState.autoIndentSetting);
        engine.setAutoPairsEnabled(uiSessionState.autoPairsSetting);
        engine.setAutoListEnabled(uiSessionState.autoListSetting);

        TopMenuView.TopBar topMenuBar = topMenuView.topBar(
            JvmDocumentStorage.pathOf(state.getCurrentResource()),
            state.isDirty(),
            cols,
            uiSessionState.readingModeSetting
        );
        String bottomBar = host.buildBottomBar(
            state,
            model,
            cols,
            uiSessionState.readingModeSetting,
            activeTabIndex,
            tabs.size(),
            searchSummary
        );
        HelpPage helpPage = host.activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode);
        helpOverlayPlacement = helpPage == null ? null : host.helpOverlayPlacement(rows, cols, helpPage, dialogTopInset);
        modalInputBounds = helpOverlayPlacement != null ? helpOverlayPlacement.frame().bounds()
            : openFileDialogPlacement != null ? openFileDialogPlacement.frame().bounds()
            : host.blockingSurface(rows, cols, contentTopRow, inlinePromptBar.height(), saveAsMode || confirmMode);
        if (focusUi().blockingContentFocused) {
            if (blockingContentAvailable()) {
                clearBlockingControlFocus();
            } else {
                focusFirstBlockingControl();
            }
        }
        syncSearchProjection();
        FrameComposition composition = new FrameComposition(
            new FrameComposition.Document(
                renderPlanBuilder,
                model,
                state,
                editorLayout,
                plan
            ),
            new FrameComposition.Surface(
                topMenuBar,
                bottomBar,
                inlinePromptBar,
                notificationBar
            ),
            new FrameComposition.Search(
                ui().searchMode,
                searchQuery,
                searchOptions,
                uiSessionState.autoSelectionHighlightSetting,
                searchBarPlacement,
                ui().searchEditorFocused
            ),
            new FrameComposition.Layers(
                tabBarPlacement,
                topMenuPlacement,
                saveAsMode,
                saveAsBarPlacement,
                confirmBarPlacement,
                ui().openFileMode
                    ? new FrameComposition.OpenFile(
                        dev.tuink.tui.ui.PathPresentation.full(openFileTree().root()),
                        openFileDialogPlacement,
                        ui().openFileFocusState.focus()
                    )
                    : FrameComposition.OpenFile.hidden(),
                helpOverlayPlacement,
                host.modalAttention().highlighted(),
                focusUi().blockingContentFocused
            ),
            uiSessionState.wrapLinesSetting,
            uiSessionState.wrapWordsSetting,
            fullRedraw
        );
        modalInputBounds = host.blockingSurface(composition);
        return composition;
    }

    @Override
    public void frameEnded(boolean rendered) {
        if (host.profileEnabled) {
            host.frameProfiler.endFrame(rendered);
        }
    }

    @Override
    public long nanosUntilWakeup(long nowNanos) {
        long presentationDeadline = TerminalEditorApp.earlierDeadline(
            TerminalEditorApp.earlierDeadline(notice().nanosUntilExpire(nowNanos),
                host.modalAttention().nanosUntilChange(nowNanos)),
            TerminalEditorApp.earlierDeadline(editorView.nanosUntilSelectionScroll(nowNanos),
                TerminalEditorApp.earlierDeadline(engine.nanosUntilGutterDiffRefresh(nowNanos),
                    syntaxRetryAtNanos == Long.MIN_VALUE ? -1L : Math.max(0L, syntaxRetryAtNanos - nowNanos)))
        );
        return TerminalEditorApp.earlierDeadline(presentationDeadline,
            documentIo.busy() ? SYNTAX_RETRY_DELAY_NANOS : -1L);
    }

    private TerminalEditorApp.HelpOverlayPlacement currentHelpOverlay() {
        return helpHintsMode || helpLicensesMode || helpAuthorMode ? helpOverlayPlacement : null;
    }

    private void cancelDocumentPointerInteraction() {
        editorView.cancelMouseSelection();
        editorScrollDrag = EditorView.ScrollAxis.NONE;
        editorScrollDragThumbOffset = 0;
    }

    private void cancelPointerCapture(PointerCaptureCancellation reason) {
        pointerCapture.cancel(pointerFrame(), reason);
    }

    static FocusScope<String, ListView.Action> helpContentScope(TerminalEditorApp.HelpOverlayPlacement help) {
        return FocusScope.single(HELP_CONTENT_POINTER_SCOPE, FocusResetPolicy.CLEARED,
            new RoutedTarget<>("content", help.content(), FocusSpace.root(help.viewport()), false,
                true));
    }

    private FocusScope<String, Void> documentPointerScope() {
        Rect bounds = new Rect(0, contentTopRow, cols, Math.max(0, editorPointerRows));
        return FocusScope.single(DOCUMENT_POINTER_SCOPE, FocusResetPolicy.CLEARED,
            new RoutedTarget<>("document", new DocumentPointerTarget(bounds),
                FocusSpace.root(new Rect(0, 0, cols, rows)), false, plan != null));
    }

    private WindowPointerCapture.Frame pointerFrame() {
        WindowPointerCapture.Frame frame = WindowPointerCapture.frame(this);
        if (searchBarPlacement != null) {
            frame.addScope(searchBarPlacement.focusScope(), host.searchFocusManager(), ignored -> {});
        }
        if (saveAsBarPlacement != null) {
            frame.addScope(saveAsBarPlacement.focusScope(), host.saveAsFocusManager(), ignored -> {});
        }
        if (topMenuPlacement != null) {
            frame.addScope(topMenuPlacement.captureScope(), action -> {
                if (action instanceof MenuBar.StateChanged<CommandInvocation> changed) {
                    topMenu.adopt(changed.state());
                }
            });
        }
        if (openFileDialogPlacement != null) {
            frame.addScope(openFileDialogPlacement.captureScope(), action -> {
                if (action instanceof FileTreeView.StateChanged changed) {
                    openFileTree().adopt(changed.state());
                }
            });
        }
        TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
        if (help != null) {
            frame.addScope(helpContentScope(help), action -> {
                if (action instanceof ListView.StateChanged changed) {
                    host.setHelpScrollState(changed.state());
                }
            });
        }
        if (plan != null) {
            frame.addScope(documentPointerScope(), ignored -> {});
        }
        return frame;
    }

    /** A refused pointer command ends its gesture without terminating the editor loop. */
    private void runDocumentPointerAction(Runnable action) {
        try {
            action.run();
        } catch (DocumentEditLimitException refused) {
            cancelPointerCapture(PointerCaptureCancellation.RELEASE);
            cancelDocumentPointerInteraction();
            notice().error(refused.getMessage());
            eventLoop.invalidate();
        }
    }

    private void routeDocumentPointer(PointerEvent event) {
        var scope = documentPointerScope();
        var target = scope.target("document").orElseThrow();
        InteractionResult<Void> result = target.placement().handle(event,
            new FocusContext(false, target.interactive(), target.space()));
        pointerCapture.observe(this, scope, "document", result);
    }

    private final class DocumentPointerTarget implements PlacedPointerCapture<Void> {
        private final Rect bounds;

        private DocumentPointerTarget(Rect bounds) { this.bounds = bounds; }
        @Override public Rect bounds() { return bounds; }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {}
        @Override public boolean pointerCaptureActive() {
            return editorView.isMouseSelecting() || editorScrollDrag != EditorView.ScrollAxis.NONE;
        }
        @Override public InteractionResult<Void> cancelPointerCapture(PointerCaptureCancellation reason) {
            cancelDocumentPointerInteraction();
            return InteractionResult.<Void>handled().withPointerCapture(PointerCaptureRequest.RELEASE);
        }
        @Override public InteractionResult<Void> handle(TuiInputEvent event, FocusContext context) {
            if (!(event instanceof PointerEvent pointer) || !context.enabled()) {
                return InteractionResult.ignored();
            }
            runDocumentPointerAction(() -> handleDocumentPointer(pointer));
            return InteractionResult.<Void>handled().withPointerCapture(pointerCaptureActive()
                ? PointerCaptureRequest.ACQUIRE : PointerCaptureRequest.RELEASE);
        }
    }

    private boolean documentPointerHit(PointerEvent mouse) {
        return new Rect(0, contentTopRow, cols, editorPointerRows)
            .contains(mouse.column(), mouse.row())
            && (TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) == ModalInputPolicy.Kind.NONE
                || blockingContentAvailable() && !modalInputBounds.contains(mouse.column(), mouse.row()));
    }

    private boolean blockingContentAvailable() {
        return DialogCommandPolicy.mode(TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode)).permitsContentFocus()
            && contentRows > 0 && contentCols > 0
            && !modalInputBounds.contains(new Rect(contentLeft, contentTopRow, contentCols, contentRows));
    }

    private void clearBlockingControlFocus() {
        if (saveAsBarPlacement != null) {
            host.saveAsFocusManager().clear(saveAsBarPlacement.focusScope());
        }
        if (confirmBarPlacement != null) {
            host.confirmFocusManager().clear(confirmBarPlacement.focusScope());
        }
        TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
        if (help != null) {
            host.helpFocusManager().clear(help.focusScope());
        }
    }

    private void focusDocument() {
        if (TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) != ModalInputPolicy.Kind.NONE) {
            focusUi().blockingContentFocused = true;
            clearBlockingControlFocus();
        } else if (ui().searchMode) {
            ui().searchEditorFocused = true;
            if (searchBarPlacement != null) {
                host.searchFocusManager().clear(searchBarPlacement.focusScope());
            }
        }
    }

    private void focusFirstBlockingControl() {
        focusUi().blockingContentFocused = false;
        if (saveAsMode && saveAsBarPlacement != null) {
            DialogFocusTraversal.focusFirst(host.saveAsFocusManager(), saveAsBarPlacement.focusScope());
        } else if (confirmMode && confirmBarPlacement != null) {
            DialogFocusTraversal.focusFirst(host.confirmFocusManager(), confirmBarPlacement.focusScope());
        } else if (ui().openFileMode) {
            if (openFileDialogPlacement != null) {
                openFileTree().adopt(openFileTree().state().cancelPointerInteraction());
                openFileDialogPlacement.focusFirst(ui().openFileFocusState);
            }
        } else {
            TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
            if (help != null) {
                DialogFocusTraversal.focusFirst(host.helpFocusManager(), help.focusScope());
            }
        }
    }

    private void advanceBlockingFocus(KeyStroke stroke) {
        boolean available = blockingContentAvailable();
        if (saveAsMode && saveAsBarPlacement != null) {
            focusUi().blockingContentFocused = DialogFocusTraversal.advance(host.saveAsFocusManager(),
                saveAsBarPlacement.focusScope(), focusUi().blockingContentFocused, available, stroke);
        } else if (confirmMode && confirmBarPlacement != null) {
            focusUi().blockingContentFocused = DialogFocusTraversal.advance(host.confirmFocusManager(),
                confirmBarPlacement.focusScope(), focusUi().blockingContentFocused, available, stroke);
        } else if (ui().openFileMode) {
            if (openFileDialogPlacement != null) {
                OpenFileInputRouter.Result result = OpenFileInputRouter.route(
                    openFileDialogPlacement, ui().openFileFocusState, stroke, System.nanoTime(),
                    TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS);
                openFileTree().adopt(result.state());
            }
            focusUi().blockingContentFocused = false;
        } else {
            TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
            if (help != null) {
                focusUi().blockingContentFocused = DialogFocusTraversal.advance(host.helpFocusManager(),
                    help.focusScope(), focusUi().blockingContentFocused, available, stroke);
            }
        }
    }

    private void rejectDialogInput() {
        DialogInputPolicy.apply(DialogInputPolicy.Decision.BLOCK, this::focusFirstBlockingControl,
            () -> host.modalAttention().request(TerminalEditorApp.modalInputOwner(modalState(), ui().openFileMode), System.nanoTime()));
        editorView.cancelMouseSelection();
        editorScrollDrag = EditorView.ScrollAxis.NONE;
        eventLoop.invalidate();
    }

    private boolean routeBlockingInput(TuiInputEvent event, TuinkCommand command, ModalInputPolicy.Kind kind) {
        if (kind == ModalInputPolicy.Kind.NONE) {
            return false;
        }
        DialogMode mode = DialogCommandPolicy.mode(kind);
        if (event instanceof PointerEvent mouse) {
            if (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable) {
                return true;
            }
            boolean drag = mouse.kind() == PointerEvent.Kind.DRAG
                || mouse.kind() == PointerEvent.Kind.MOVE && mouse.button() == PointerEvent.Button.LEFT;
            if (mode.permitsContentFocus()
                && (documentPointerHit(mouse)
                    || (drag || mouse.kind() == PointerEvent.Kind.RELEASE)
                        && (focusUi().blockingContentFocused || editorScrollDrag != EditorView.ScrollAxis.NONE))) {
                routeDocumentPointer(mouse);
                eventLoop.invalidate();
                return true;
            }
            if (modalInputBounds.contains(mouse.column(), mouse.row())) {
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() == PointerEvent.Button.LEFT
                    && focusUi().blockingContentFocused) {
                    focusFirstBlockingControl();
                }
                return false;
            }
            if (!mode.permitsContentFocus()) {
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() != PointerEvent.Button.NONE
                    || mouse.kind() == PointerEvent.Kind.SCROLL_UP
                    || mouse.kind() == PointerEvent.Kind.SCROLL_DOWN
                    || mouse.kind() == PointerEvent.Kind.SCROLL_LEFT
                    || mouse.kind() == PointerEvent.Kind.SCROLL_RIGHT) {
                    rejectDialogInput();
                }
                return true;
            }
            if (tabBarPlacement != null && tabBarPlacement.enabled()
                && TerminalEditorApp.belongsToTabChrome(event, tabBarPlacement.bounds())) {
                if (modalState().isCloseTabConfirm()) {
                    return false;
                }
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() == PointerEvent.Button.LEFT) {
                    rejectDialogInput();
                }
            }
            return true;
        }
        TuiInputEvent focusEvent = command == TuinkCommand.EDIT_INSERT_TAB
            ? KeyStroke.of(KeyStroke.Key.TAB) : event;
        if (DialogFocusTraversal.isTraversal(focusEvent)) {
            advanceBlockingFocus((KeyStroke) focusEvent);
            editorView.cancelMouseSelection();
            eventLoop.invalidate();
            return true;
        }
        if (focusUi().blockingContentFocused && DialogCommandPolicy.cancels(kind, command)) {
            focusFirstBlockingControl();
            return false;
        }
        DialogInputPolicy.Action action = DialogCommandPolicy.action(event, command);
        DialogInputPolicy.Decision decision = DialogCommandPolicy.decide(mode,
            DialogCommandPolicy.target(kind, ui().openFileFocusState.focus(),
                host.saveAsFocusManager().focused().orElse(null), focusUi().blockingContentFocused), action);
        if (decision == DialogInputPolicy.Decision.BLOCK) {
            rejectDialogInput();
            return true;
        }
        if (decision.consumed()) {
            return true;
        }
        if (focusUi().blockingContentFocused) {
            EditorInput input = TerminalEditorApp.resolveEditorInput(commandRouter, event,
                event instanceof KeyStroke stroke ? commandRouter.invocationFor(stroke).orElse(null) : null,
                null, false, SearchFocus.FIND);
            if (input != null && input.command() != null) {
                InputActionDispatcher.DispatchResult result = dispatchInputAction(input.command());
                viewportController.onCommandResult(result.viewportPolicy());
            }
            eventLoop.invalidate();
            return true;
        }
        if (saveAsMode && ControlCommandRouter.owns(command)) {
            return false;
        }
        if (ModalInputPolicy.blocked(kind, event, command)) {
            rejectDialogInput();
            return true;
        }
        return false;
    }

    private void advanceSearchFocus(KeyStroke event) {
        ui().searchEditorFocused = DialogFocusTraversal.advance(host.searchFocusManager(),
            searchBarPlacement.focusScope(), ui().searchEditorFocused,
            contentRows > 0 && contentCols > 0, event);
    }

    private boolean consumeSearchControlAction(SearchBarView.Action action) {
        if (action == SearchBarView.Action.CLOSE_REQUESTED) {
            ui().searchMode = false;
            host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return true;
        }
        if (action == SearchBarView.Action.INPUT_REJECTED) {
            notice().error("Text is too large; nothing changed");
            return true;
        }
        SearchFocus toggle = TerminalEditorApp.toggleTarget(action);
        if (toggle != null) {
            ui().searchUi = SearchController.toggleFocusedSearchOption(ui().searchUi, toggle);
            return true;
        }
        return action == SearchBarView.Action.QUERY_CHANGED || action == SearchBarView.Action.REPLACEMENT_CHANGED;
    }

    private void executeSearchDocumentCommand(TuinkCommand command) {
        TabUiState source = ui();
        if (command.editsDocumentText() && rejectSavingDocumentEdit()) {
            return;
        }
        if ((command == TuinkCommand.SEARCH_REPLACE || command == TuinkCommand.SEARCH_REPLACE_ALL)
            && (searchBarPlacement == null || !searchBarPlacement.replacementContextVisible())) {
            notice().warning("Enlarge terminal to show Find and Replace fields before replacing");
            return;
        }
        if (command == TuinkCommand.CURSOR_PAGE_UP || command == TuinkCommand.CURSOR_PAGE_DOWN) {
            TerminalEditorApp.scrollDocumentViewport(model, engine.textLayout(), state, contentRows, contentCols,
                uiSessionState.wrapLinesSetting, uiSessionState.wrapWordsSetting,
                (command == TuinkCommand.CURSOR_PAGE_UP ? -1 : 1) * Math.max(1, contentRows),
                uiSessionState.tabWidthSetting);
            viewportController.onCommandResult(ViewportController.CursorPolicy.PAGE_SCROLL);
            return;
        }
        if (SearchController.isSearchActionFocus(host.searchFocusManager().focused().orElse(null))) {
            host.focusSearch(searchBarPlacement, SearchController.focusForAction(command));
        }
        SearchCommandHandler.Result result = SearchCommandHandler.execute(command,
            new SearchCommandHandler.Context(tabs.stream().map(TerminalEditorApp.EditorTab::documentSession).toList(), activeTabIndex,
                uiSessionState.readingModeSetting, ui().searchUi, ui().searchQueryInput.text(), ui().searchReplaceInput.text()),
            this::revealCurrentSelection);
        if (result.activeTabIndex() != activeTabIndex
                && !prepareSearchNavigation(source, tabs.get(result.activeTabIndex()).uiState())) {
            return;
        }
        tabs.get(result.activeTabIndex()).viewportController().onCommandResult(result.cursorPolicy());
        if (result.activeTabIndex() != activeTabIndex) activateTab(result.activeTabIndex());
        if (result.status() != null && !result.status().isBlank()) {
            switch (result.statusLevel()) {
                case ERROR -> notice().error(result.status());
                case WARNING -> notice().warning(result.status());
                case INFO -> notice().info(result.status());
            }
        }
    }

    /** Explicit cross-tab search carries values into independent destination controls. */
    private boolean prepareSearchNavigation(TabUiState source, TabUiState destination) {
        try {
            TextField query = new TextField(TextField.PastePolicy.PRESERVE_TABS);
            TextField replacement = new TextField(TextField.PastePolicy.PRESERVE_TABS);
            if (!query.set(source.searchQueryInput.text()) || !replacement.set(source.searchReplaceInput.text())) {
                notice().error("Not enough memory to continue this search in another tab");
                return false;
            }
            SearchFocus focus = source.searchFocusManager.focused().orElse(SearchFocus.FIND);
            // The destination keeps its dialog and filename state. Only this
            // explicit search result replaces its search session; tab navigation does not.
            destination.searchQueryInput = query;
            destination.searchReplaceInput = replacement;
            destination.searchUi = source.searchUi;
            destination.searchMode = source.searchMode;
            destination.searchEditorFocused = source.searchEditorFocused;
            destination.pendingSearchFocus = focus;
            return true;
        } catch (OutOfMemoryError memory) {
            notice().error("Not enough memory to continue this search in another tab");
            return false;
        }
    }

    private boolean revealCurrentSelection() {
        int topBefore = state.getTopLine();
        int wrapBefore = state.getTopWrapSegment();
        int leftBefore = state.getLeftVisualColumn();
        EditorViewNavigation.ensureCursorVisible(engine, contentRows, contentCols,
            uiSessionState.wrapLinesSetting, uiSessionState.wrapWordsSetting);
        return topBefore != state.getTopLine() || wrapBefore != state.getTopWrapSegment()
            || leftBefore != state.getLeftVisualColumn();
    }

    private void handleDocumentPointer(PointerEvent mouse) {
        if (mouse.kind() == PointerEvent.Kind.RELEASE) {
            editorScrollDrag = EditorView.ScrollAxis.NONE;
            editorScrollDragThumbOffset = 0;
            editorView.handleMouseRelease(engine);
            return;
        }
        if (mouse.button() == PointerEvent.Button.OTHER
            || !uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable) {
            return;
        }
        switch (mouse.kind()) {
            case SCROLL_UP -> {
                int topBefore = state.getTopLine();
                int topWrapBefore = state.getTopWrapSegment();
                TerminalEditorApp.applyWheelVerticalScroll(
                    model,
                    engine.textLayout(),
                    state,
                    plan,
                    -3,
                    uiSessionState.tabWidthSetting
                );
                if (state.getTopLine() != topBefore || state.getTopWrapSegment() != topWrapBefore) {
                    viewportController.onManualVerticalPan();
                }
            }
            case SCROLL_DOWN -> {
                int topBefore = state.getTopLine();
                int topWrapBefore = state.getTopWrapSegment();
                TerminalEditorApp.applyWheelVerticalScroll(
                    model,
                    engine.textLayout(),
                    state,
                    plan,
                    3,
                    uiSessionState.tabWidthSetting
                );
                if (state.getTopLine() != topBefore || state.getTopWrapSegment() != topWrapBefore) {
                    viewportController.onManualVerticalPan();
                }
            }
            case SCROLL_LEFT, SCROLL_RIGHT -> {
                if (!plan.wrapLines()) {
                    int previousLeft = state.getLeftVisualColumn();
                    int visibleCols = Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth());
                    int nextLeft = ScrollInteraction.scrollBy(
                        previousLeft, mouse.kind() == PointerEvent.Kind.SCROLL_LEFT ? -3 : 3,
                        visibleCols, plan.totalColumns()
                    );
                    new EditorViewCoordinates(engine.textLayout(), visibleCols, false, false,
                        uiSessionState.tabWidthSetting).applyHorizontalOffset(state, nextLeft);
                    viewportController.onScrollbarInteraction(
                        new EditorView.ScrollPointerResult(true, EditorView.ScrollAxis.NONE, 0),
                        previousLeft, state.getLeftVisualColumn(), state.getTopLine(), state.getTopLine()
                    );
                }
            }
            case PRESS -> {
                if (mouse.button() == PointerEvent.Button.LEFT) {
                    editorScrollDrag = EditorView.ScrollAxis.NONE;
                    editorScrollDragThumbOffset = 0;
                    int leftBeforeScrollPointer = state.getLeftVisualColumn();
                    int topBeforeScrollPointer = state.getTopLine();
                    int topWrapBeforeScrollPointer = state.getTopWrapSegment();
                    EditorView.ScrollPointerResult scrollPointer = editorView.handleScrollbarPointer(
                        mouse.row(),
                        mouse.column(),
                        rows,
                        cols,
                        contentTopRow,
                        plan,
                        model,
                        engine.textLayout(),
                        state
                    );
                    if (scrollPointer.consumed()) {
                        editorScrollDrag = scrollPointer.dragAxis();
                        editorScrollDragThumbOffset = scrollPointer.dragThumbOffset();
                        viewportController.onScrollbarInteraction(
                            scrollPointer,
                            leftBeforeScrollPointer,
                            state.getLeftVisualColumn(),
                            topBeforeScrollPointer,
                            state.getTopLine()
                        );
                        if (state.getTopWrapSegment() != topWrapBeforeScrollPointer) {
                            viewportController.onManualVerticalPan();
                        }
                        editorView.cancelMouseSelection();
                        return;
                    }
                    if (!documentPointerHit(mouse)) {
                        editorView.cancelMouseSelection();
                        return;
                    }
                    focusDocument();
                    editorView.handleMousePress(
                        mouse,
                        model,
                        state,
                        plan,
                        contentRows,
                        engine,
                        contentTopRow,
                        TerminalEditorApp.shouldExtendEditorSelection(mouse)
                    );
                    viewportController.onEditorTextInteraction();
                }
            }
            case MOVE, DRAG -> {
                if (mouse.button() == PointerEvent.Button.LEFT
                    && editorScrollDrag != EditorView.ScrollAxis.NONE) {
                    int leftBeforeDragScroll = state.getLeftVisualColumn();
                    int topBeforeDragScroll = state.getTopLine();
                    int topWrapBeforeDragScroll = state.getTopWrapSegment();
                    EditorView.ScrollPointerResult dragScrollPointer = editorView.handleScrollbarPointer(
                        mouse.row(),
                        mouse.column(),
                        rows,
                        cols,
                        contentTopRow,
                        plan,
                        model,
                        engine.textLayout(),
                        state,
                        editorScrollDrag,
                        editorScrollDragThumbOffset
                    );
                    if (!dragScrollPointer.consumed()) {
                        editorScrollDrag = EditorView.ScrollAxis.NONE;
                        editorScrollDragThumbOffset = 0;
                    }
                    viewportController.onScrollbarInteraction(
                        dragScrollPointer,
                        leftBeforeDragScroll,
                        state.getLeftVisualColumn(),
                        topBeforeDragScroll,
                        state.getTopLine()
                    );
                    if (state.getTopWrapSegment() != topWrapBeforeDragScroll) {
                        viewportController.onManualVerticalPan();
                    }
                    return;
                }
                if (!editorView.isMouseSelecting() || mouse.button() != PointerEvent.Button.LEFT) {
                    return;
                }
                editorView.handleMouseDrag(
                    mouse,
                    model,
                    state,
                    plan,
                    contentRows,
                    engine,
                    contentTopRow
                );
                viewportController.onEditorTextInteraction();
            }
            default -> {
                // Unknown pointer events have no editor action.
            }
        }
    }

    @Override
    public void handlePoll(TuiEventLoop.Poll<TuiInputEvent> pollResult) {
        TuiInputEvent inputEvent = pollResult.event();
        if (inputEvent == null) {
            return;
        }
        TuinkCommand navigationCommand = inputEvent instanceof KeyStroke key
            ? commandRouter.invocationFor(key).flatMap(invocation -> TuinkCommand.fromId(invocation.id())).orElse(null)
            : null;
        boolean cancelledCapture = pointerCapture.beforeInput(pointerFrame(), inputEvent);
        if (cancelledCapture && plan != null) {
            prepareComposition(false);
            eventLoop.invalidate();
        }
        if (routeTabNavigation(inputEvent, navigationCommand)) return;
        if (inputEvent instanceof ResizeEvent) {
            tabBarColumns = -1;
            host.setHelpScrollState(new ListView.State(-1, host.helpScrollState().firstVisibleIndex()));
            if (ui().openFileTree != null) ui().openFileTree.adopt(ui().openFileTree.state().cancelPointerInteraction());
            topMenu.adopt(topMenu.state().cancelPointerInteraction());
            return;
        }
        if (inputEvent instanceof PointerEvent pointer && pointerCapture.active()) {
            if (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable) {
                cancelPointerCapture(PointerCaptureCancellation.DISABLED);
                return;
            }
            if (pointerCapture.dispatch(pointerFrame(), pointer)) {
                eventLoop.invalidate();
                return;
            }
        }
        ModalInputPolicy.Kind blocker = TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode);
        TuinkCommand physicalCommand = inputEvent instanceof KeyStroke stroke
            ? commandRouter.invocationFor(stroke)
                .flatMap(invocation -> TuinkCommand.fromId(invocation.id())).orElse(null)
            : null;
        if (routeBlockingInput(inputEvent, physicalCommand, blocker)) {
            return;
        }
        if (TerminalEditorApp.searchOwnsRawInput(ui().searchMode, modalState(), ui().openFileMode, topMenu.isOpen())
            && !ui().searchEditorFocused
            && DialogCommandPolicy.decide(DialogMode.NON_BLOCKING,
                SearchController.isSearchFieldFocus(host.searchFocusManager().focused().orElse(null))
                    ? DialogInputPolicy.Target.TEXT_INPUT : DialogInputPolicy.Target.BUTTON,
                DialogCommandPolicy.action(inputEvent, physicalCommand)).consumed()) {
            return;
        }
        boolean startsAction = !(inputEvent instanceof PointerEvent pointer) || switch (pointer.kind()) {
            case PRESS, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT -> true;
            default -> false;
        };
        if (!saveAsMode && !confirmMode && startsAction) {
            notice().clearErrorOnUserAction();
        }
        eventLoop.invalidate();
        if (modalState().isHelpHints() || modalState().isHelpLicenses() || modalState().isHelpAuthor()) {
            boolean pointerAllowed = !(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && uiSessionState.mouseModeAvailable;
            if (pointerAllowed) {
                HelpOverlayInputRouter.Result helpInput = host.routeHelpOverlayInput(
                    currentHelpOverlay(), inputEvent, pointerCapture, this
                );
                if (helpInput.closeRequested()) {
                    host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                    setModal(UiModalState.none());
                    return;
                }
                if (helpInput.consumed()) {
                    return;
                }
            }
        }
        TuinkCommand routedSaveAsCommand = null;
        if (saveAsMode) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable)) {
                return;
            }
            if (saveAsBarPlacement == null) {
                // Entering the modal always schedules a frame; keep
                // a malformed/custom runtime from leaking input.
                eventLoop.invalidate();
                return;
            }
            String filenameBefore = ui().saveAsInput.text();
            SaveAsInputRouter.Result saveAsInputResult = SaveAsInputRouter.route(
                host.saveAsFocusManager(),
                saveAsBarPlacement,
                inputEvent, pointerCapture, this
            );
            if (!saveAsInputResult.consumed() && physicalCommand != null) {
                saveAsInputResult = SaveAsInputRouter.routeCommand(host.saveAsFocusManager(),
                    saveAsBarPlacement, physicalCommand, host.clipboard);
            }
            SaveAsBarView.Action fieldAction = saveAsInputResult.action();
            if (fieldAction == SaveAsBarView.Action.SUBMIT_REJECTED) {
                // Keep a pending save failure visible until its filename is edited.
                if (notice().message == null) {
                    notice().warning("Enlarge terminal to show the filename before saving");
                }
                return;
            }
            if (fieldAction == SaveAsBarView.Action.INPUT_REJECTED) {
                notice().error("Text is too large; nothing changed");
                return;
            }
            if (fieldAction == SaveAsBarView.Action.VALUE_CHANGED) {
                if (!filenameBefore.equals(ui().saveAsInput.text())) {
                    ui().filenameRevision++;
                    notice().clear();
                }
                saveAsSuggestions.rememberDraft(
                    tabs.get(activeTabIndex).tabSession(),
                    ui().saveAsInput.text()
                );
                return;
            }
            if (fieldAction == SaveAsBarView.Action.SUBMITTED) {
                routedSaveAsCommand = TuinkCommand.EDIT_INSERT_NEWLINE;
            } else if (fieldAction == SaveAsBarView.Action.CANCELLED) {
                routedSaveAsCommand = TuinkCommand.UI_ESCAPE;
            }
            if (inputEvent instanceof PointerEvent && saveAsInputResult.consumed()) {
                editorView.cancelMouseSelection();
            }
            if (routedSaveAsCommand == null && saveAsInputResult.consumed()) {
                return;
            }
        }
        ConfirmChoice routedConfirmDecision = null;
        if (confirmMode) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable)) {
                return;
            }
            if (confirmBarPlacement == null) {
                // A modal transition always schedules a frame before
                // input. Capture defensively if a custom runtime
                // violates that ordering.
                eventLoop.invalidate();
                return;
            }
            ConfirmBarInputRouter.Result confirmInput = ConfirmBarInputRouter.route(
                host.confirmFocusManager(),
                confirmBarPlacement,
                host.confirmPolicy(modalState()),
                inputEvent
            );
            routedConfirmDecision = confirmInput.decision();
            if (routedConfirmDecision != null) {
                notice().clearErrorOnUserAction();
            }
            if (routedConfirmDecision != null && inputEvent instanceof PointerEvent) {
                editorView.cancelMouseSelection();
            }
            if (routedConfirmDecision == null && confirmInput.consumed()) {
                return;
            }
        }
        if (ui().openFileMode) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable)) {
                return;
            }
            if (openFileDialogPlacement == null) {
                eventLoop.invalidate();
                return;
            }
            OpenFileInputRouter.Result openInput = OpenFileInputRouter.route(
                openFileDialogPlacement,
                ui().openFileFocusState,
                inputEvent,
                System.nanoTime(),
                TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS, pointerCapture, this
            );
            openFileTree().adopt(openInput.state());
            if (inputEvent instanceof PointerEvent && openInput.consumed()) {
                editorView.cancelMouseSelection();
            }
            if (openInput.intent() != null) {
                applyOpenFileResult(FilePickerOpenWorkflow.apply(openInput.intent(), openFileTree(),
                    host.toTabSessions(tabs), sessionContext.activeTabIndex(),
                    () -> TuiDocumentSession.create(host.clipboard)));
                return;
            }
            if (openInput.consumed()) {
                return;
            }
            // The open-file dialog is modal even when a pointer
            // misses every current-frame control.
            editorView.cancelMouseSelection();
            return;
        }
        CommandInvocation routedMenuCommand = null;
        CommandRouter.RoutedCommand routedMenuResult = null;
        if (modalState().isNone() && !ui().openFileMode) {
            boolean pointerAllowed = !(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && uiSessionState.mouseModeAvailable;
            if (!pointerAllowed && topMenu.isOpen()) {
                return;
            }
            if (pointerAllowed) {
                if (topMenuPlacement == null) {
                    if (topMenu.isOpen()) {
                        eventLoop.invalidate();
                        return;
                    }
                } else {
                    TopMenuInputRouter.Result menuInput = TopMenuInputRouter.route(
                        topMenu,
                        topMenuPlacement,
                        commandRouter,
                        inputEvent, pointerCapture, this
                    );
                    if (inputEvent instanceof PointerEvent && menuInput.consumed()) {
                        editorView.cancelMouseSelection();
                    }
                    routedMenuCommand = menuInput.command();
                    if (routedMenuCommand != null) {
                        routedMenuResult = commandRouter.dispatch(routedMenuCommand)
                            .orElse(null);
                        if (routedMenuResult instanceof CommandRouter.ActivateTab tab
                            && tab.tabIndex() >= 0
                            && tab.tabIndex() < tabs.size()) {
                            activateTab(tab.tabIndex());
                            return;
                        }
                    } else if (menuInput.consumed()) {
                        return;
                    }
                }
            }
        }
        SearchBarView.Action routedSearchAction = null;
        if (TerminalEditorApp.searchOwnsRawInput(
            ui().searchMode,
            modalState(),
            ui().openFileMode,
            topMenu.isOpen(),
            routedMenuCommand != null
        )) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable)) {
                return;
            }
            if (searchBarPlacement == null) {
                // Search activation schedules a frame before input;
                // capture defensively if a custom runtime violates it.
                eventLoop.invalidate();
                return;
            }
            if (DialogFocusTraversal.isTraversal(inputEvent)) {
                advanceSearchFocus((KeyStroke) inputEvent);
                editorView.cancelMouseSelection();
                return;
            }
            SearchInputRouter.Result searchInput = !ui().searchEditorFocused
                || inputEvent instanceof PointerEvent
                ? SearchInputRouter.route(host.searchFocusManager(), searchBarPlacement, inputEvent, pointerCapture, this)
                : new SearchInputRouter.Result(false, null);
            if (inputEvent instanceof PointerEvent pointer
                && pointer.kind() == PointerEvent.Kind.PRESS
                && pointer.button() == PointerEvent.Button.LEFT
                && searchInput.consumed()
                && host.searchFocusManager().focused().isPresent()) {
                ui().searchEditorFocused = false;
            }
            if (inputEvent instanceof PointerEvent && searchInput.consumed()) {
                editorView.cancelMouseSelection();
            }
            SearchBarView.Action searchAction = searchInput.action();
            if (consumeSearchControlAction(searchAction)) {
                return;
            }
            routedSearchAction = searchAction;
            if (routedSearchAction == null && searchInput.consumed()) {
                return;
            }
        }
        TabBarInputRouter.Policy tabPolicy = routedMenuCommand == null ? TerminalEditorApp.tabBarInputPolicy(
            tabBarPlacement != null,
            uiSessionState.mouseModeSetting && uiSessionState.mouseModeAvailable,
            modalState(),
            ui().openFileMode,
            topMenu.isOpen(),
            ui().searchMode
        ) : null;
        if (inputEvent instanceof PointerEvent pointer && tabPolicy != null) {
            TabBarInputRouter.Result tabInput = TabBarInputRouter.route(
                tabBarPlacement,
                tabPolicy,
                pointer
            );
            if (modalState().isCloseTabConfirm() && tabInput.blocked()) {
                rejectDialogInput();
            }
            tabBarState = tabInput.state();
            Integer activateIndex = tabInput.activateIndex();
            if (activateIndex != null && activateIndex < tabs.size()) {
                CommandInvocation activation = commandRouter.tabActivationInvocation(
                    activateIndex
                );
                int resolvedTab = commandRouter.invokeTabActivation(activation).orElse(-1);
                if (resolvedTab >= 0 && resolvedTab < tabs.size()) {
                    activateTab(resolvedTab);
                }
                topMenu.close();
                editorView.cancelMouseSelection();
                return;
            }
            Integer closeIndex = tabInput.closeIndex();
            if (closeIndex != null && closeIndex < tabs.size()) {
                ViewId closeView = tabs.get(closeIndex).documentSession().viewId();
                if (!sessionContext.canClose(closeView)) {
                    notice().info("Saving this document; wait before closing its tab");
                    return;
                }
                if (tabs.get(closeIndex).state().isDirty() && !sessionContext.hasOtherViews(closeView)
                    && uiSessionState.confirmOnCloseSetting) {
                    setModal(UiModalState.closeTabConfirm(closeIndex)
                        .bindTo(tabs.get(closeIndex).documentSession().viewId()));
                } else {
                    host.closeTab(sessionContext, tabs.get(closeIndex).documentSession().viewId(), saveAsSuggestions);
                }
                topMenu.close();
                editorView.cancelMouseSelection();
                return;
            }
            if (tabInput.consumed()) {
                editorView.cancelMouseSelection();
                return;
            }
        }
        TuinkCommand routedSearchCommand = TerminalEditorApp.searchCommand(routedSearchAction);
        CommandInvocation pendingCommand = routedMenuCommand != null
            ? routedMenuCommand
            : routedSearchCommand != null
            ? CommandInvocation.of(routedSearchCommand.id())
            : routedConfirmDecision == null
            && routedSaveAsCommand == null
            && inputEvent instanceof KeyStroke stroke
            ? commandRouter.invocationFor(stroke).orElse(null)
            : null;

        if (routedMenuCommand == null && inputEvent instanceof PasteEvent paste) {
            if (paste.truncated()) {
                notice().error("Paste is too large and was not inserted");
                return;
            }
            if (!modalState().isNone() || ui().openFileMode || topMenu.isOpen()) {
                // The focused overlay owns input; paste cannot leak through it.
            } else {
                if (rejectSavingDocumentEdit()) {
                    return;
                } else if (uiSessionState.readingModeSetting) {
                    notice().warning("Reading mode: editing is disabled");
                } else {
                    long revisionBefore = model.revision();
                    Position cursorBefore = state.getCursor();
                    try {
                        activeDocument().execute(new DocumentCommand.Paste(paste.text()));
                    } catch (DocumentEditLimitException limit) {
                        notice().error(limit.getMessage());
                        return;
                    }
                    if (revisionBefore != model.revision() || !cursorBefore.equals(state.getCursor())) {
                        viewportController.onEditorTextInteraction();
                    }
                }
            }
            return;
        }

        if (routedMenuCommand != null
            && !(routedMenuResult instanceof CommandRouter.RoutedAction)) {
            return;
        }
        EditorInput key = routedSaveAsCommand != null
            ? EditorInput.command(routedSaveAsCommand)
            : routedConfirmDecision != null
            ? EditorInput.command(TuinkCommand.EDIT_INSERT_NEWLINE)
            : TerminalEditorApp.resolveEditorInput(
                commandRouter,
                inputEvent,
                pendingCommand,
                routedMenuResult,
                TerminalEditorApp.searchOwnsRawInput(
                    ui().searchMode,
                    modalState(),
                    ui().openFileMode,
                    topMenu.isOpen(),
                    routedMenuCommand != null
                ) && !ui().searchEditorFocused,
                host.currentSearchFocus()
            );
        if (key == null) {
            return;
        }
        if (TerminalEditorApp.searchOwnsRawInput(ui().searchMode, modalState(), ui().openFileMode, topMenu.isOpen())
            && ui().searchEditorFocused
            && key.command() == TuinkCommand.UI_ESCAPE) {
            ui().searchMode = false;
            host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return;
        }
        if (key.command() == null && key.event() instanceof PointerEvent mouse) {
            if (TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) == ModalInputPolicy.Kind.NONE) {
                routeDocumentPointer(mouse);
            }
            return;
        }

        if (modalState().isHelpHints()) {
            HelpHintsController.Result helpHintsResult = HelpHintsController.handle(
                key,
                true
            );
            if (helpHintsResult.consumed()) {
                if (!helpHintsResult.helpHintsMode()) {
                    host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                }
                setModal(helpHintsResult.helpHintsMode() ? UiModalState.helpHints() : UiModalState.none());
                return;
            }
        }

        if (modalState().isHelpLicenses()) {
            if (key.command() == null && key.event() instanceof TextInput) {
                return;
            }
            TuinkCommand action = key.command();
            if (action == TuinkCommand.UI_ESCAPE
                || action == TuinkCommand.EDIT_INSERT_NEWLINE
                || action == TuinkCommand.HELP_LICENSES) {
                host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                setModal(UiModalState.none());
            }
            return;
        }

        if (modalState().isHelpAuthor()) {
            if (key.command() == null && key.event() instanceof TextInput) {
                return;
            }
            TuinkCommand action = key.command();
            if (action == TuinkCommand.UI_ESCAPE
                || action == TuinkCommand.EDIT_INSERT_NEWLINE
                || action == TuinkCommand.HELP_AUTHOR) {
                host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                setModal(UiModalState.none());
            }
            return;
        }

        if (modalState().isRecoveryConfirm()) {
            if (routedConfirmDecision != null) {
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    var restored = host.restoreRecoveredTabs(
                        startupRecoveryEntries,
                        sessionContext,
                        recoveryAutosave,
                        System.nanoTime()
                    );
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(restored.failedEntries());
                    int restoredCount = restored.restoredCount();
                    if (restoredCount > 0) {
                        activeTabIndex = sessionContext.activeTabIndex();
                        state = sessionContext.state();
                        openFileTree().setRoot(FilePickerOpenWorkflow.initialRoot(JvmDocumentStorage.pathOf(state.getCurrentResource())));
                        notice().info("Recovered " + restoredCount + " tab(s)");
                    } else {
                        notice().info("No recovery snapshots restored");
                    }
                    if (!restored.failedEntries().isEmpty()) {
                        notice().error("Could not restore " + restored.failedEntries().size()
                            + " snapshot(s); data kept. Choose Later or Delete. "
                            + String.join("; ", restored.failureDetails()));
                        return;
                    }
                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                    TerminalEditorApp.RecoveryAutosaveManager.DeletionResult deletion =
                        recoveryAutosave.deleteEntries(startupRecoveryEntries);
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(deletion.failedEntries());
                    if (!deletion.successful()) {
                        notice().error(deletion.failureMessage());
                        return;
                    }
                    notice().info("Recovery snapshots discarded");
                } else {
                    var release = recoveryAutosave.releaseEntries(startupRecoveryEntries);
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(release.failedEntries());
                    if (!release.successful()) {
                        notice().error(release.failureMessage());
                        return;
                    }
                    notice().info("Recovery postponed; snapshots kept for next launch");
                }
                setModal(UiModalState.none());
                return;
            }
        }

        if (modalState().isSaveAs()) {
            applySaveResult(SaveWorkflow.saveAsInput(saveContext(), key.command(), modalState(), ui().saveAsInput.text()));
            return;
        }
        if (routedConfirmDecision != null) {
            if (modalState().isOpenUtf16Confirm()) {
                Path target = modalState().pendingOpenPath();
                setModal(UiModalState.none());
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    applyOpenFileResult(FilePickerOpenWorkflow.openAsUtf16(target,
                        host.toTabSessions(tabs), sessionContext.activeTabIndex(),
                        () -> TuiDocumentSession.create(host.clipboard)));
                } else {
                    ui().openFileMode = true;
                }
                return;
            }
            if (modalState().isSaveUtf8Confirm()) {
                applySaveResult(SaveWorkflow.encodingChoice(modalState(), routedConfirmDecision));
                return;
            }
            if (modalState().isSaveOverwriteConfirm()) {
                applySaveResult(SaveWorkflow.overwrite(saveContext(), modalState(), routedConfirmDecision));
                return;
            }
            if (modalState().isQuitConfirm()) {
                if (routedConfirmDecision == ConfirmChoice.SAVE && finishPendingDialogsBeforeQuit()) return;
                applySaveResult(SaveWorkflow.quit(saveContext(), routedConfirmDecision));
                return;
            }
            if (modalState().isCloseTabConfirm()) {
                applySaveResult(SaveWorkflow.closeTab(saveContext(), modalState(), routedConfirmDecision));
                return;
            }
        }

        CommandRecipient recipient = CommandRecipient.resolve(key.command(),
            ui().searchMode, ui().searchEditorFocused);
        if (recipient == CommandRecipient.SEARCH_CONTROL) {
            if (searchBarPlacement != null) {
                if (key.command() == TuinkCommand.EDIT_INSERT_TAB || key.command() == TuinkCommand.SEARCH_FOCUS_NEXT) {
                    advanceSearchFocus(KeyStroke.of(KeyStroke.Key.TAB));
                    return;
                }
                SearchInputRouter.Result result = SearchInputRouter.routeCommand(host.searchFocusManager(),
                    searchBarPlacement, key.command(), host.clipboard);
                if (!consumeSearchControlAction(result.action())) {
                    TuinkCommand intent = TerminalEditorApp.searchCommand(result.action());
                    if (intent != null) executeSearchDocumentCommand(intent);
                }
            }
            return;
        }
        if (recipient == CommandRecipient.SEARCH_DOCUMENT) {
            executeSearchDocumentCommand(key.command());
            return;
        }

        if (key.command() == null && key.event() instanceof TextInput charInput) {
            if (rejectSavingDocumentEdit()) {
                return;
            } else if (uiSessionState.readingModeSetting) {
                notice().warning("Reading mode: editing is disabled");
            } else {
                long revisionBefore = model.revision();
                Position cursorBefore = state.getCursor();
                try {
                    activeDocument().execute(new DocumentCommand.Input(charInput.text()));
                } catch (DocumentEditLimitException limit) {
                    notice().error(limit.getMessage());
                    return;
                }
                if (revisionBefore != model.revision() || !cursorBefore.equals(state.getCursor())) {
                    viewportController.onEditorTextInteraction();
                }
            }
            return;
        }

        TuinkCommand action = key.command();
        if (action.editsDocumentText() && rejectSavingDocumentEdit()) {
            return;
        }
        if (action == TuinkCommand.APP_QUIT && documentIo.busy()) {
            boolean alreadyQuitting = pendingSave != null && (pendingSave.purpose() == SaveWorkflow.SavePurpose.QUIT
                || pendingSave.origin().saveContinuation() == SaveContinuation.QUIT);
            if (!alreadyQuitting) {
                quitAfterDocumentIo = true;
            }
            notice().info(alreadyQuitting ? "Saving documents before quitting"
                : "Waiting for the file operation before quitting");
            return;
        }
        if (action == TuinkCommand.TAB_CLOSE && !sessionContext.canClose(sessionContext.activeViewId())) {
            notice().info("Saving this document; wait before closing its tab");
            return;
        }
        if (activeDocument().document().io().busy()
                && (action == TuinkCommand.DOCUMENT_SAVE || action == TuinkCommand.DOCUMENT_SAVE_AS)
            || documentIo.busy() && action == TuinkCommand.DOCUMENT_SAVE && state.getCurrentResource() != null
            || action == TuinkCommand.DOCUMENT_OPEN && (ui().pendingOpen != null || ui().deferredOpenResult != null)) {
            notice().info("A file operation is already in progress");
            return;
        }
        InputActionDispatcher.DispatchResult dispatchResult = dispatchInputAction(action);
        if (dispatchResult.handled()) {
            viewportController.onCommandResult(dispatchResult.viewportPolicy());
            if (action == TuinkCommand.SEARCH_OPEN) {
                String selectedQuery = SearchSelection.prefill(model, state);
                if (selectedQuery != null && !ui().searchQueryInput.set(selectedQuery)) {
                    ui().searchMode = false;
                    ui().searchEditorFocused = false;
                    host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
                    searchBarPlacement = null;
                    notice().error("Not enough memory to open Find with the selected text");
                    return;
                }
                host.focusSearch(searchBarPlacement, SearchFocus.FIND);
            }
            setModal(TerminalEditorApp.consumeRequestedHelpModal(uiSessionState, modalState()));
            if (dispatchResult.statusMessage() != null && !dispatchResult.statusMessage().isBlank()) {
                if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.ERROR) {
                    notice().error(dispatchResult.statusMessage());
                } else if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.WARNING) {
                    notice().warning(dispatchResult.statusMessage());
                } else {
                    notice().info(dispatchResult.statusMessage());
                }
            }
            return;
        }
        EditorActionController.Result editorAction = EditorActionController.decide(
            action,
            running,
            modalState(),
            uiSessionState.confirmOnCloseSetting,
            host.hasDirtyTabs(tabs),
            state.isDirty() && !sessionContext.hasOtherViews(sessionContext.activeViewId()),
            activeTabIndex,
            JvmDocumentStorage.pathOf(state.getCurrentResource()),
            (action == TuinkCommand.DOCUMENT_SAVE || action == TuinkCommand.DOCUMENT_SAVE_AS)
                && JvmDocumentStorage.pathOf(state.getCurrentResource()) != null
                && SaveTargetPolicy.isReadOnlyFile(JvmDocumentStorage.pathOf(state.getCurrentResource()))
        );
        var themeBefore = host.theme;
        host.applyEditorActionResult(editorAction, sessionContext, tabs, activeTabIndex,
            notice(), saveAsSuggestions, renderer);
        if (host.theme != themeBefore) {
            settingsSession.themeChanged();
        }
        running = editorAction.running();
        setModal(editorAction.modalState().bindTo(sessionContext.activeViewId()));
        if (editorAction.requestOpenFile()) {
            topMenu.close();
            ui().openFileMode = true;
            ui().pickerNotice = null;
            ui().lastPickerDirectoryFailure = null;
            openFileTree().setRoot(FilePickerOpenWorkflow.initialRoot(JvmDocumentStorage.pathOf(state.getCurrentResource())));
            ui().openFileFocusState.focus(OpenFileInputRouter.Focus.LIST);
        }
        applyFilenameEffect(SaveWorkflow.initialFilename(saveContext(), editorAction.saveAsPreset()));
        if (editorAction.requestSaveCurrent()) {
            applySaveResult(SaveWorkflow.saveCurrent(saveContext()));
        }
    }

    private void applyOpenFileResult(FilePickerOpenWorkflow.Result result) {
        ViewId ownerId = interactionView();
        TabUiState owner = ui();
        if (owner.closed || stateFor(ownerId) != owner) return;
        withUi(ownerId, owner, () -> applyOpenFileResultOnOwner(result));
    }

    private void applyOpenFileResultOnOwner(FilePickerOpenWorkflow.Result result) {
        boolean publishToActivatedView = !backgroundInteraction() && windowModal.isNone();
        if (result.pendingOpen() != null) {
            if (documentIo.busy() || ui().deferredOpenResult != null) {
                notice().info("A file operation is already in progress");
                return;
            }
            var request = result.pendingOpen();
            ViewId ownerId = interactionView();
            TabUiState owner = ui();
            pendingOpen = request;
            owner.pendingOpen = request;
            owner.openFileMode = false;
            owner.pickerNotice = null;
            notice().info("Opening document…");
            documentIo.submit(request::read, completion -> {
                pendingOpen = null;
                owner.pendingOpen = null;
                if (!closingDocumentIo && !owner.closed && stateFor(ownerId) == owner) {
                    withUi(ownerId, owner, () -> applyOpenFileResultOnOwner(FilePickerOpenWorkflow.complete(
                        request, completion.value(), completion.failure(), host.toTabSessions(tabs), interactionIndex(),
                        () -> TuiDocumentSession.create(host.clipboard))));
                }
                afterDocumentIo();
            });
            return;
        }
        boolean documentPromptActive = modalState().isSaveAs() || modalState().isSaveOverwriteConfirm()
            || modalState().isSaveUtf8Confirm() || modalState().isCloseTabConfirm();
        if (documentPromptActive && (result.openFileMode() || result.pendingExplicitOpenPath() != null)) {
            ui().deferredOpenResult = result;
            if (result.noticeKind() == FilePickerOpenWorkflow.NoticeKind.ERROR) {
                notice().error(result.message());
            } else {
                notice().info("Finish the current dialog to choose the file encoding");
            }
            return;
        }
        if (result.openedDocument() != null) {
            TuiDocumentSession document = result.openedDocument();
            RenderPlanBuilder builder = document.newRenderPlanBuilder(
                host.resolveHighlighter(JvmDocumentStorage.pathOf(document.view().state().getCurrentResource())));
            sessionContext.addAll(List.of(new TerminalEditorApp.EditorTab(document, builder)));
            if (!documentPromptActive && !backgroundInteraction() && windowModal.isNone()) {
                activateTab(sessionContext.indexOf(document.viewId()));
            }
        } else if (result.activateViewId() != null && !documentPromptActive
                && !backgroundInteraction() && windowModal.isNone()) {
            activateTab(sessionContext.indexOf(result.activateViewId()));
        }
        ui().openFileMode = result.openFileMode();
        if (result.pendingExplicitOpenPath() != null) {
            setModal(UiModalState.openUtf16Confirm(result.pendingExplicitOpenPath()));
        }
        if (!ui().openFileMode) ui().pickerNotice = null;
        switch (result.noticeKind()) {
            case UNCHANGED -> { }
            case SUCCESS -> {
                if (documentPromptActive && result.activateTabIndex() >= 0) {
                    notice().info("File is already open in tab " + (result.activateTabIndex() + 1));
                } else {
                    notice().success(result.message());
                }
            }
            case WARNING -> {
                if (ui().openFileMode) ui().pickerNotice = result.notice();
                else notice().warning(result.message());
            }
            case ERROR -> {
                if (ui().openFileMode) ui().pickerNotice = result.notice();
                else notice().error(result.message());
            }
        }
        if (result.noticeKind() == FilePickerOpenWorkflow.NoticeKind.SUCCESS && publishToActivatedView
                && !sessionContext.activeViewId().equals(interactionView())) {
            TabUiState destination = sessionContext.active().uiState();
            if (destination.notification == null) destination.notification = host.new NotificationState();
            destination.notification.success(result.message());
        }
    }

    private TuiDocumentSession activeDocument() {
        return tabs.get(interactionIndex()).documentSession();
    }

    private ViewId interactionView() {
        return interactionViewId == null ? sessionContext.activeViewId() : interactionViewId;
    }

    private SaveWorkflow.Context saveContext() {
        return new SaveWorkflow.Context(tabs.stream().map(TerminalEditorApp.EditorTab::documentSession).toList(),
            interactionIndex(), saveAsSuggestions, ui().overwriteApproval);
    }

    private boolean applyFilenameEffect(SaveFlowController.TextInputEffect effect) {
        switch (effect.kind()) {
            case NONE -> { }
            case SET_TEXT -> {
                if (!ui().saveAsInput.set(effect.text())) {
                    // Never leave a previous tab's filename active after a refused preset.
                    // Cancelling this continuation keeps every unsaved document open.
                    setModal(UiModalState.none());
                    if (!backgroundInteraction()) running = true;
                    host.saveAsFocusManager().deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
                    if (!backgroundInteraction()) saveAsBarPlacement = null;
                    notice().error("Not enough memory to open Save As; the filename was not changed");
                    return false;
                }
                ui().filenameRevision++;
            }
            case CLEAR -> {
                ui().saveAsInput.clear();
                ui().filenameRevision++;
            }
        }
        return true;
    }

    private void applySaveResult(SaveWorkflow.Result result) {
        applySaveResult(result, null);
    }

    private void applySaveResult(SaveWorkflow.Result result, TabSession savedTab) {
        ViewId ownerId = result.modalState().targetViewId();
        if (ownerId == null && result.pendingSave() != null) ownerId = result.pendingSave().session().viewId();
        if (ownerId == null && result.activateTabIndex() >= 0 && result.activateTabIndex() < tabs.size()) {
            ownerId = tabs.get(result.activateTabIndex()).documentSession().viewId();
        }
        if (ownerId == null) ownerId = interactionView();
        TabUiState owner = stateFor(ownerId);
        if (owner == null) {
            ownerId = interactionView();
            owner = ui();
        }
        boolean globalFlow = globalInteraction || globalModal(modalState())
            || result.modalState().saveContinuation() == SaveContinuation.QUIT
            || result.pendingSave() != null && isGlobalSave(result.pendingSave());
        if (globalModal(modalState()) && result.modalState().isNone()
                && result.activateTabIndex() < 0 && result.closeTabIndex() < 0 && result.pendingSave() == null
                && result.filename().kind() == SaveFlowController.TextInputEffectKind.NONE) {
            // Dismissing a window question reveals the tab's retained dialog underneath.
            owner = globalUi;
        }
        ViewId destinationId = ownerId;
        TabUiState destination = owner;
        withUi(destinationId, destination, globalFlow, () -> applySaveResultOnOwner(result, savedTab));
    }

    private void applySaveResultOnOwner(SaveWorkflow.Result result, TabSession savedTab) {
        // Refresh the saved tab before a continuation activates or closes another tab.
        if (result.refreshSavedHighlighter()) {
            refreshSavedHighlighter(savedTab == null ? tabs.get(interactionIndex()).tabSession() : savedTab);
        }
        if (result.activateTabIndex() >= 0 && !backgroundInteraction()) {
            activateTab(result.activateTabIndex());
        }
        if (result.closeTabIndex() >= 0) {
            boolean closingActive = result.closeTabIndex() == sessionContext.activeTabIndex();
            if (closingActive) {
                cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
                cancelDocumentPointerInteraction();
            }
            host.closeTab(sessionContext, tabs.get(result.closeTabIndex()).documentSession().viewId(), saveAsSuggestions);
            if (closingActive) {
                searchBarPlacement = null;
                saveAsBarPlacement = null;
                confirmBarPlacement = null;
                openFileDialogPlacement = null;
                openFileTreePlacement = null;
                helpOverlayPlacement = null;
            }
        }
        if (!applyFilenameEffect(result.filename())) {
            return;
        }
        if (globalInteraction && globalModal(result.modalState())) {
            withUi(interactionView(), globalUi, true, () -> applySaveNotice(result.notice()));
        } else {
            applySaveNotice(result.notice());
        }
        if (!backgroundInteraction()) running = result.running();
        setModal(result.modalState().bindTo(interactionView()));
        ui().overwriteApproval = result.overwriteApproval();
        if (result.focusFilename()) {
            focusUi().blockingContentFocused = false;
            // Re-enter against the next frame's filename placement, including after a
            // continuation changes tabs without changing the Save As scope identity.
            host.saveAsFocusManager().deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
        }
        if (result.pendingSave() != null && !closingDocumentIo) {
            startSave(result.pendingSave());
        }
    }

    private void refreshSavedHighlighter(TabSession savedTab) {
        for (int index = 0; index < tabs.size(); index++) {
            TerminalEditorApp.EditorTab tab = tabs.get(index);
            if (tab.tabSession().engine().document() == savedTab.engine().document()) {
                RenderPlanBuilder builder = tab.documentSession().newRenderPlanBuilder(
                    host.resolveHighlighter(JvmDocumentStorage.pathOf(savedTab.state().getCurrentResource())));
                sessionContext.update(new TerminalEditorApp.EditorTab(tab.documentSession(), builder,
                    tab.viewportController(), tab.navigation(), tab.uiState()));
                if (index == sessionContext.activeTabIndex()) {
                    sessionContext.activate(index);
                    renderPlanBuilder = builder;
                }
            }
        }
    }

    /** Resolve retained dialog identities before painting or consuming their next input. */
    private void synchronizeModalTarget() {
        setModal(modalState().bindTo(interactionView()));
        ViewId target = modalState().targetViewId();
        if (target == null) return;
        int index = sessionContext.indexOf(target);
        if (index < 0) {
            if (ui().overwriteApproval != null && target.equals(ui().overwriteApproval.request().session().viewId())) {
                ui().overwriteApproval.request().session().document().io().cancel(ui().overwriteApproval.inspection());
                ui().overwriteApproval = null;
            }
            setModal(UiModalState.none());
            notice().info("The document view for this dialog was closed");
        } else if (modalState().isCloseTabConfirm()
                || modalState().saveContinuation() == SaveContinuation.CLOSE_TAB) {
            setModal(modalState().withCloseTabIndex(index));
        }
    }

    private void startSave(SaveWorkflow.SaveRequest unsubmitted) {
        // Completing a read-only inspection during close must not start a new write.
        if (closingDocumentIo) {
            unsubmitted.session().document().io().stop();
            return;
        }
        ViewId ownerId = unsubmitted.session().viewId();
        TabUiState owner = stateFor(ownerId);
        if (owner == null || owner.closed) {
            unsubmitted.session().document().io().cancel(unsubmitted.operation());
            return;
        }
        boolean globalFlow = globalInteraction || isGlobalSave(unsubmitted);
        withUi(ownerId, owner, globalFlow, () -> startSaveOnOwner(unsubmitted, owner, globalFlow));
    }

    private static boolean isGlobalSave(SaveWorkflow.SaveRequest request) {
        return request.purpose() == SaveWorkflow.SavePurpose.QUIT
            || request.origin().saveContinuation() == SaveContinuation.QUIT;
    }

    private void startSaveOnOwner(SaveWorkflow.SaveRequest unsubmitted, TabUiState owner, boolean globalFlow) {
        SaveWorkflow.SaveRequest request = unsubmitted.submission() == null
            ? unsubmitted.submitted(owner.saveAsInput.text(), owner.filenameRevision) : unsubmitted;
        if (documentIo.busy()) {
            request.session().document().io().cancel(request.operation());
            notice().info("A file operation is already in progress; retry saving after it finishes");
            setModal(request.origin());
            return;
        }
        pendingSave = request;
        long submittedFilenameRevision = request.submission().filenameRevision();
        String submittedFilename = request.submission().filename();
        notice().info(request.inspecting() ? "Checking save target…" : "Saving document…");
        documentIo.submit(request::perform, completion -> {
            pendingSave = null;
            var transition = request.accept(completion.value(), completion.failure());
            if (transition instanceof DocumentIoCoordinator.Ignored) {
                afterDocumentIo();
                return;
            }
            if (closingDocumentIo) {
                // Publish a started write before final recovery, without
                // starting a quit/close continuation during shutdown.
                if (transition instanceof DocumentIoCoordinator.Failed failed) {
                    if (owner.notification == null) owner.notification = host.new NotificationState();
                    owner.notification.error(SaveTargetPolicy.saveErrorMessage(failed.failure()));
                }
                return;
            }
            ViewId ownerId = liveSaveView(request);
            TabUiState liveOwner = ownerId == null ? null : stateFor(ownerId);
            if (liveOwner != null) {
                boolean performed = completion.succeeded() && completion.value().succeeded();
                withUi(ownerId, liveOwner, globalFlow, () -> completeSaveOnOwner(request, transition, performed,
                    owner, submittedFilenameRevision, submittedFilename));
            } else if (transition instanceof DocumentIoCoordinator.Failed) {
                quitAfterDocumentIo = false;
            }
            afterDocumentIo();
        });
    }

    private ViewId liveSaveView(SaveWorkflow.SaveRequest request) {
        TabUiState origin = stateFor(request.session().viewId());
        if (origin != null && !origin.closed) return request.session().viewId();
        for (var tab : tabs) {
            if (tab.documentSession().document() == request.session().document() && !tab.uiState().closed) {
                return tab.documentSession().viewId();
            }
        }
        return null;
    }

    private void completeSaveOnOwner(SaveWorkflow.SaveRequest request, DocumentIoCoordinator.Transition transition,
                                     boolean performed, TabUiState submittedUi, long submittedFilenameRevision,
                                     String submittedFilename) {
        SaveWorkflow.Result result = SaveWorkflow.complete(saveContext(), request, transition);
        boolean succeeded = performed && result.notice().kind() != SaveWorkflow.NoticeKind.ERROR;
        if (!succeeded || request.inspecting() && result.pendingSave() == null) {
            // A failed write or target question needs an owner decision before any deferred exit.
            quitAfterDocumentIo = false;
        }
        boolean standalone = request.purpose() == SaveWorkflow.SavePurpose.CURRENT
            || request.purpose() == SaveWorkflow.SavePurpose.SAVE_AS
                && request.origin().saveContinuation() == SaveContinuation.NONE;
        if (request.inspecting() && succeeded && result.pendingSave() != null && standalone) {
            startSave(result.pendingSave());
        } else if (!request.inspecting() && succeeded && standalone) {
            // A later prompt and filename in this view remain independent of the completed write.
            if (result.refreshSavedHighlighter()) refreshSavedHighlighter(request.tab());
            if (ui() == submittedUi && ui().filenameRevision == submittedFilenameRevision) {
                applyFilenameEffect(result.filename());
            }
            applySaveNotice(result.notice());
        } else {
            if (request.purpose() == SaveWorkflow.SavePurpose.SAVE_AS
                && (!succeeded || request.inspecting() && result.pendingSave() == null)
                && (ui() != submittedUi || ui().filenameRevision != submittedFilenameRevision)
                && (result.modalState().isSaveAs() || result.modalState().isSaveOverwriteConfirm()
                    || result.modalState().isSaveUtf8Confirm())
                && !applyFilenameEffect(SaveFlowController.TextInputEffect.setText(submittedFilename))) return;
            applySaveResult(result, request.tab());
        }
    }

    private void applySaveNotice(SaveWorkflow.Notice notice) {
        switch (notice.kind()) {
            case UNCHANGED -> { }
            case CLEAR -> notice().clear();
            case SUCCESS -> notice().success(notice.message());
            case ERROR -> notice().error(notice.message());
        }
    }

    private boolean rejectSavingDocumentEdit() {
        if (!activeDocument().document().io().busy()) {
            return false;
        }
        notice().info("Saving this document; editing will resume when the write finishes");
        return true;
    }

    private void afterDocumentIo() {
        if (quitAfterDocumentIo && !documentIo.busy() && !closingDocumentIo) {
            quitAfterDocumentIo = false;
            if (uiSessionState.confirmOnCloseSetting && host.hasDirtyTabs(tabs)) {
                withUi(sessionContext.activeViewId(), globalUi, true, () -> setModal(UiModalState.quitConfirm()));
            } else {
                running = false;
            }
        }
        eventLoop.invalidate();
    }

    /** Finish a started write before recovery captures its final owner-thread state. */
    void closeDocumentIo() {
        closingDocumentIo = true;
        for (TabUiState owner : knownUiStates) owner.close();
        for (TerminalEditorApp.EditorTab tab : tabs) tab.uiState().close();
        globalUi.close();
        if (pendingOpen != null) pendingOpen.cancel();
        for (TerminalEditorApp.EditorTab tab : tabs) tab.documentSession().document().io().stop();
        documentIo.close();
    }

    private InputActionDispatcher.DispatchResult dispatchInputAction(TuinkCommand command) {
        syncSearchProjection();
        boolean readingBefore = uiSessionState.readingModeSetting;
        boolean mouseBefore = uiSessionState.mouseModeSetting;
        TerminalEditorApp.EditorTab target = tabs.get(interactionIndex());
        TabSession view = target.tabSession();
        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(command, uiSessionState,
            new InputActionDispatcher.DispatchContext(view.engine(), view.state(), topMenu,
                terminal::setMouseTracking, contentRows, contentCols, target.navigation(), target.documentSession()));
        ui().searchMode = uiSessionState.searchMode;
        ui().searchEditorFocused = uiSessionState.searchEditorFocused;
        if (uiSessionState.readingModeSetting != readingBefore) {
            settingsSession.readingModeChanged();
        }
        if (uiSessionState.mouseModeSetting != mouseBefore) {
            settingsSession.mouseModeChanged();
        }
        return result;
    }
}

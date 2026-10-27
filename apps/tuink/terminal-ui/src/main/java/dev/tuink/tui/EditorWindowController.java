package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.core.EditorEngine;
import dev.tuink.editor.command.DocumentCommand;
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
    private final TerminalEditorApp.NotificationState notification;
    private final OpenFileTreeController openFileTree;
    private final TerminalEditorApp.RecoveryAutosaveManager recoveryAutosave;
    private final TerminalEditorApp.RenderFrameController renderFrameController;
    private final RenderRasterizer renderer;
    private final TextField saveAsInput;
    private final SaveAsSuggestionStore saveAsSuggestions;
    private final ScreenPresenter screenPresenter;
    private final TextField searchQueryInput;
    private final TextField searchReplaceInput;
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
    private SaveWorkflow.OverwriteApproval overwriteApproval;
    private FilePickerOpenWorkflow.Result deferredOpenResult;
    private boolean quitAfterDocumentIo;
    private boolean closingDocumentIo;
    private long filenameRevision;

    EditorWindowController(TerminalEditorApp host, Resources resources) {
        this.host = Objects.requireNonNull(host, "host");
        this.commandRouter = resources.commandRouter();
        this.editorView = resources.editorView();
        this.eventLoop = resources.eventLoop();
        this.notification = resources.notification();
        this.openFileTree = resources.openFileTree();
        this.recoveryAutosave = resources.recoveryAutosave();
        this.renderFrameController = resources.renderFrameController();
        this.renderer = resources.renderer();
        this.saveAsInput = resources.saveAsInput();
        this.saveAsSuggestions = resources.saveAsSuggestions();
        this.screenPresenter = resources.screenPresenter();
        this.searchQueryInput = resources.searchQueryInput();
        this.searchReplaceInput = resources.searchReplaceInput();
        this.sessionContext = resources.sessionContext();
        this.startupRecoveryEntries = resources.startupRecoveryEntries();
        this.tabs = resources.tabs();
        this.editorView.bindCommands(command -> activeDocument().execute(command));
        this.terminal = resources.terminal();
        this.topMenu = resources.topMenu();
        this.uiSessionState = resources.uiSessionState();
        this.settingsSession = resources.settingsSession();
        this.documentIo = Objects.requireNonNull(resources.documentIo(), "documentIo");
        this.modalState = resources.initialModalState();
        this.searchUi = resources.initialSearchUi();
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
        List<TerminalEditorApp.EditorTab> tabs,
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
    private UiModalState modalState;
    private SearchController.SearchUiOptions searchUi;
    private boolean openFileMode;
    private FilePickerOpenWorkflow.Notice pickerNotice;
    private FileTreeModel.DirectoryFailure lastPickerDirectoryFailure;
    private final OpenFileDialogView.FocusState openFileFocusState = new OpenFileDialogView.FocusState();
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
    private Object blockingFocusOwner;
    private boolean blockingContentFocused;
    private UiModalState confirmPromptState;
    private Object confirmScopeId;
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
    private final SearchPresentation searchPresentation = new SearchPresentation();
    private String reportedSearchError = "";
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

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public void prepareTurn() {
        while (documentIo.collect()) {
            eventLoop.invalidate();
        }
        if (deferredOpenResult != null && modalState.isNone() && !documentIo.busy() && !closingDocumentIo) {
            FilePickerOpenWorkflow.Result ready = deferredOpenResult;
            deferredOpenResult = null;
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
        Object nextBlockingOwner = TerminalEditorApp.modalInputOwner(modalState, openFileMode);
        if (!Objects.equals(blockingFocusOwner, nextBlockingOwner)) {
            blockingFocusOwner = nextBlockingOwner;
            blockingContentFocused = false;
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        if (host.modalAttention.synchronize(TerminalEditorApp.modalInputOwner(modalState, openFileMode), System.nanoTime())) {
            eventLoop.invalidate();
        }
        List<TuiDocumentSession> recoveryTabSessions = host.toDocumentSessions(tabs);
        recoveryAutosave.syncTabs(recoveryTabSessions);
        String recoveryWarning = modalState.isRecoveryConfirm() ? null
            : recoveryAutosave.autosaveDirtyTabs(recoveryTabSessions, System.nanoTime());
        if (recoveryWarning != null) {
            notification.warning(recoveryWarning);
            eventLoop.invalidate();
        }
        if (notification.expireIfNeeded()) {
            eventLoop.invalidate();
        }
        if (openFileMode && openFileTree.consumeMetadataChanged()) {
            var failure = openFileTree.directoryFailure();
            if (failure != null && !failure.equals(lastPickerDirectoryFailure)) {
                pickerNotice = new FilePickerOpenWorkflow.Notice(failure.reason(), failure.directory());
            }
            lastPickerDirectoryFailure = failure;
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
        saveAsMode = modalState.isSaveAs();
        saveOverwriteConfirmMode = modalState.isSaveOverwriteConfirm();
        recoveryConfirmMode = modalState.isRecoveryConfirm();
        quitConfirmMode = modalState.isQuitConfirm();
        closeTabConfirmMode = modalState.isCloseTabConfirm();
        helpHintsMode = modalState.isHelpHints();
        helpLicensesMode = modalState.isHelpLicenses();
        helpAuthorMode = modalState.isHelpAuthor();
        if (!helpHintsMode && !helpLicensesMode && !helpAuthorMode) {
            host.resetHelpScroll();
        }
        pendingOverwritePath = modalState.pendingOverwritePath();
        pendingCloseTabIndex = modalState.pendingCloseTabIndex();
        confirmMode = recoveryConfirmMode
            || modalState.isSaveUtf8Confirm()
            || modalState.isOpenUtf16Confirm()
            || saveOverwriteConfirmMode
            || quitConfirmMode
            || closeTabConfirmMode;
        if (!uiSessionState.searchMode) {
            uiSessionState.searchEditorFocused = false;
            host.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            searchBarPlacement = null;
        }
        if (!saveAsMode) {
            host.saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
            saveAsBarPlacement = null;
        }
        if (confirmMode) {
            if (confirmPromptState != modalState) {
                host.deactivateConfirmFocus();
                confirmPromptState = modalState;
                confirmScopeId = new Object();
                confirmBarPlacement = null;
            }
        } else {
            host.deactivateConfirmFocus();
            confirmPromptState = null;
            confirmScopeId = null;
            confirmBarPlacement = null;
        }
        if (plan != null) runDocumentPointerAction(() -> {
            if (editorView.scrollSelectionIfDue(
                model, state, plan, contentRows, engine, contentTopRow, System.nanoTime()
            )) {
                viewportController.onEditorTextInteraction();
                eventLoop.invalidate();
            }
        });
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
        SearchPresentation.Snapshot searchSnapshot = searchPresentation.resolve(
            uiSessionState.searchMode, searchQueryInput.text(), searchUi, host.toTabSessions(tabs), activeTabIndex);
        if (!searchSnapshot.error().equals(reportedSearchError)) {
            reportedSearchError = searchSnapshot.error();
            if (!reportedSearchError.isEmpty()) notification.error(reportedSearchError);
        }
        String searchQuery = searchSnapshot.query();
        SearchOptions searchOptions = searchSnapshot.options();
        if (host.profileEnabled) {
            host.frameProfiler.searchNanos = host.frameProfiler.stopwatchNanos();
        }
        SearchBarView searchBar = uiSessionState.searchMode
            ? SearchBarView.build(
                searchQueryInput,
                searchReplaceInput,
                cols,
                searchUi.caseSensitive(),
                searchUi.matchMode(),
                searchUi.allTabs()
            )
            : null;
        String searchSummary = searchSnapshot.summary();
        boolean showTabBar = uiSessionState.showTabBarSetting && tabs.size() > 1;
        contentTopRow = showTabBar ? 2 : 1;
        // Keep the search model for resume-after-modal, but a
        // modal prompt exclusively owns this frame's row,
        // painter, and cursor.
        SearchBarView activeSearchBar = TerminalEditorApp.searchOwnsInteractiveRow(modalState)
                && !openFileMode
            ? searchBar
            : null;
        ConfirmBarView confirmBar = null;
        SaveAsBarView saveAsBar = null;
        InlinePromptBar inlinePromptBar = InlinePromptBar.hidden();
        if (saveAsMode) {
            saveAsBar = SaveAsBarView.build(
                modalState.saveFormat() == dev.tuink.tui.flow.DocumentSaveFormat.UTF8 ? "Save UTF-8: " : SaveAsBarView.DEFAULT_LABEL,
                saveAsInput, cols);
            inlinePromptBar = InlinePromptBar.of(
                saveAsBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1
            );
        } else if (modalState.isSaveUtf8Confirm() || modalState.isOpenUtf16Confirm()) {
            boolean opening = modalState.isOpenUtf16Confirm();
            String primaryLabel = opening ? "Open UTF-16LE" : "Save UTF-8";
            String question = opening
                ? ("Ambiguous: " + dev.tuink.tui.ui.PathPresentation.name(modalState.pendingOpenPath()) + " as UTF-16LE?")
                : "Save as UTF-8 without BOM?";
            confirmBar = ConfirmBarView.buildAlternative(
                question, cols, primaryLabel);
            inlinePromptBar = InlinePromptBar.of(confirmBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()), 1, 1);
        } else if (saveOverwriteConfirmMode) {
            confirmBar = ConfirmBarView.build(
                host.buildOverwritePrompt(pendingOverwritePath),
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
            saveAsBarPlacement.activate(host.saveAsFocusManager);
        }
        if (confirmBar != null) {
            confirmBarPlacement = confirmBar.place(
                confirmScopeId,
                rows - 1 - inlinePromptBar.height(),
                promptInteractionClip
            );
            confirmBarPlacement.activate(host.confirmFocusManager);
        }
        if (activeSearchBar != null) {
            searchBarPlacement = activeSearchBar.place(
                TerminalEditorApp.SEARCH_SCOPE_ID,
                rows - 1 - inlinePromptBar.height(),
                promptInteractionClip
            );
            searchBarPlacement.activate(host.searchFocusManager);
            if (uiSessionState.searchEditorFocused) {
                host.searchFocusManager.clear(searchBarPlacement.focusScope());
            }
        } else {
            searchBarPlacement = null;
        }
        NotificationBar notificationBar = NotificationBar.of(
            notification.message,
            EditorStyleAdapter.toEditor(notification.style())
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
        if (uiSessionState.searchEditorFocused && searchBarPlacement != null
            && (contentRows == 0 || contentCols == 0)) {
            uiSessionState.searchEditorFocused = false;
            host.searchFocusManager.next(searchBarPlacement.focusScope());
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
        if (openFileMode) {
            openFileLayout = OpenFileDialogController.layoutForTerminal(rows, cols);
            boolean treeVisible = rows >= 8 && cols >= 24;
            int treeLeft = openFileLayout.left() + 2;
            Rect treeBounds = new Rect(
                treeLeft,
                openFileLayout.listStartRow(),
                Math.max(0, openFileLayout.right() - treeLeft),
                openFileLayout.listRows()
            );
            openFileTreePlacement = openFileTree.place(
                treeBounds,
                new Rect(0, 0, cols, rows),
                openFileFocusState.focus() == OpenFileInputRouter.Focus.LIST && !blockingContentFocused,
                treeVisible
            );
            openFileDialogPlacement = OpenFileDialogView.place(
                TerminalEditorApp.OPEN_FILE_SCOPE_ID, openFileLayout, openFileTreePlacement,
                openFileTree.showHiddenFiles(), new Rect(0, 0, cols, rows), pickerNotice
            );
            openFileDialogPlacement.activate(openFileFocusState);
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
        helpOverlayPlacement = helpPage == null ? null : host.helpOverlayPlacement(rows, cols, helpPage);
        modalInputBounds = helpOverlayPlacement != null ? helpOverlayPlacement.frame().bounds()
            : openFileDialogPlacement != null ? openFileDialogPlacement.frame().bounds()
            : host.blockingSurface(rows, cols, contentTopRow, inlinePromptBar.height(), saveAsMode || confirmMode);
        if (blockingContentFocused) {
            if (blockingContentAvailable()) {
                clearBlockingControlFocus();
            } else {
                focusFirstBlockingControl();
            }
        }
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
                uiSessionState.searchMode,
                searchQuery,
                searchOptions,
                uiSessionState.autoSelectionHighlightSetting,
                searchBarPlacement,
                uiSessionState.searchEditorFocused
            ),
            new FrameComposition.Layers(
                tabBarPlacement,
                topMenuPlacement,
                saveAsMode,
                saveAsBarPlacement,
                confirmBarPlacement,
                openFileMode
                    ? new FrameComposition.OpenFile(
                        dev.tuink.tui.ui.PathPresentation.full(openFileTree.root()),
                        openFileDialogPlacement,
                        openFileFocusState.focus()
                    )
                    : FrameComposition.OpenFile.hidden(),
                helpOverlayPlacement,
                host.modalAttention.highlighted(),
                blockingContentFocused
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
            TerminalEditorApp.earlierDeadline(notification.nanosUntilExpire(nowNanos),
                host.modalAttention.nanosUntilChange(nowNanos)),
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
                !help.content().bounds().isEmpty()));
    }

    private FocusScope<String, Void> documentPointerScope() {
        Rect bounds = new Rect(0, contentTopRow, cols, Math.max(0, editorPointerRows));
        return FocusScope.single(DOCUMENT_POINTER_SCOPE, FocusResetPolicy.CLEARED,
            new RoutedTarget<>("document", new DocumentPointerTarget(bounds),
                FocusSpace.root(new Rect(0, 0, cols, rows)), false, plan != null && !bounds.isEmpty()));
    }

    private WindowPointerCapture.Frame pointerFrame() {
        WindowPointerCapture.Frame frame = WindowPointerCapture.frame(this);
        if (searchBarPlacement != null) {
            frame.addScope(searchBarPlacement.focusScope(), host.searchFocusManager, ignored -> {});
        }
        if (saveAsBarPlacement != null) {
            frame.addScope(saveAsBarPlacement.focusScope(), host.saveAsFocusManager, ignored -> {});
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
                    openFileTree.adopt(changed.state());
                }
            });
        }
        TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
        if (help != null) {
            frame.addScope(helpContentScope(help), action -> {
                if (action instanceof ListView.StateChanged changed) {
                    host.helpScrollState = changed.state();
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
            notification.error(refused.getMessage());
            eventLoop.invalidate();
        }
    }

    private void routeDocumentPointer(PointerEvent event) {
        var scope = documentPointerScope();
        var target = scope.target("document").orElseThrow();
        InteractionResult<Void> result = target.placement().handle(event,
            new FocusContext(false, target.enabled(), target.space()));
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
            && (TerminalEditorApp.modalInputKind(modalState, openFileMode) == ModalInputPolicy.Kind.NONE
                || blockingContentAvailable() && !modalInputBounds.contains(mouse.column(), mouse.row()));
    }

    private boolean blockingContentAvailable() {
        return DialogCommandPolicy.mode(TerminalEditorApp.modalInputKind(modalState, openFileMode)).permitsContentFocus()
            && contentRows > 0 && contentCols > 0
            && !modalInputBounds.contains(new Rect(contentLeft, contentTopRow, contentCols, contentRows));
    }

    private void clearBlockingControlFocus() {
        if (saveAsBarPlacement != null) {
            host.saveAsFocusManager.clear(saveAsBarPlacement.focusScope());
        }
        if (confirmBarPlacement != null) {
            host.confirmFocusManager.clear(confirmBarPlacement.focusScope());
        }
        TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
        if (help != null) {
            host.helpFocusManager.clear(help.focusScope());
        }
    }

    private void focusDocument() {
        if (TerminalEditorApp.modalInputKind(modalState, openFileMode) != ModalInputPolicy.Kind.NONE) {
            blockingContentFocused = true;
            clearBlockingControlFocus();
        } else if (uiSessionState.searchMode) {
            uiSessionState.searchEditorFocused = true;
            if (searchBarPlacement != null) {
                host.searchFocusManager.clear(searchBarPlacement.focusScope());
            }
        }
    }

    private void focusFirstBlockingControl() {
        blockingContentFocused = false;
        if (saveAsMode && saveAsBarPlacement != null) {
            DialogFocusTraversal.focusFirst(host.saveAsFocusManager, saveAsBarPlacement.focusScope());
        } else if (confirmMode && confirmBarPlacement != null) {
            DialogFocusTraversal.focusFirst(host.confirmFocusManager, confirmBarPlacement.focusScope());
        } else if (openFileMode) {
            if (openFileDialogPlacement != null) {
                openFileTree.adopt(openFileTree.state().cancelPointerInteraction());
                openFileDialogPlacement.focusFirst(openFileFocusState);
            }
        } else {
            TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
            if (help != null) {
                DialogFocusTraversal.focusFirst(host.helpFocusManager, help.focusScope());
            }
        }
    }

    private void advanceBlockingFocus(KeyStroke stroke) {
        boolean available = blockingContentAvailable();
        if (saveAsMode && saveAsBarPlacement != null) {
            blockingContentFocused = DialogFocusTraversal.advance(host.saveAsFocusManager,
                saveAsBarPlacement.focusScope(), blockingContentFocused, available, stroke);
        } else if (confirmMode && confirmBarPlacement != null) {
            blockingContentFocused = DialogFocusTraversal.advance(host.confirmFocusManager,
                confirmBarPlacement.focusScope(), blockingContentFocused, available, stroke);
        } else if (openFileMode) {
            if (openFileDialogPlacement != null) {
                OpenFileInputRouter.Result result = OpenFileInputRouter.route(
                    openFileDialogPlacement, openFileFocusState, stroke, System.nanoTime(),
                    TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS);
                openFileTree.adopt(result.state());
            }
            blockingContentFocused = false;
        } else {
            TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
            if (help != null) {
                blockingContentFocused = DialogFocusTraversal.advance(host.helpFocusManager,
                    help.focusScope(), blockingContentFocused, available, stroke);
            }
        }
    }

    private void rejectDialogInput() {
        DialogInputPolicy.apply(DialogInputPolicy.Decision.BLOCK, this::focusFirstBlockingControl,
            () -> host.modalAttention.request(TerminalEditorApp.modalInputOwner(modalState, openFileMode), System.nanoTime()));
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
                        && (blockingContentFocused || editorScrollDrag != EditorView.ScrollAxis.NONE))) {
                routeDocumentPointer(mouse);
                eventLoop.invalidate();
                return true;
            }
            if (modalInputBounds.contains(mouse.column(), mouse.row())) {
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() == PointerEvent.Button.LEFT
                    && blockingContentFocused) {
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
                if (modalState.isCloseTabConfirm()) {
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
        if (blockingContentFocused && DialogCommandPolicy.cancels(kind, command)) {
            focusFirstBlockingControl();
            return false;
        }
        DialogInputPolicy.Action action = DialogCommandPolicy.action(event, command);
        DialogInputPolicy.Decision decision = DialogCommandPolicy.decide(mode,
            DialogCommandPolicy.target(kind, openFileFocusState.focus(),
                host.saveAsFocusManager.focused().orElse(null), blockingContentFocused), action);
        if (decision == DialogInputPolicy.Decision.BLOCK) {
            rejectDialogInput();
            return true;
        }
        if (decision.consumed()) {
            return true;
        }
        if (blockingContentFocused) {
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
        uiSessionState.searchEditorFocused = DialogFocusTraversal.advance(host.searchFocusManager,
            searchBarPlacement.focusScope(), uiSessionState.searchEditorFocused,
            contentRows > 0 && contentCols > 0, event);
    }

    private boolean consumeSearchControlAction(SearchBarView.Action action) {
        if (action == SearchBarView.Action.CLOSE_REQUESTED) {
            uiSessionState.searchMode = false;
            host.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return true;
        }
        if (action == SearchBarView.Action.INPUT_REJECTED) {
            notification.error("Text is too large; nothing changed");
            return true;
        }
        SearchFocus toggle = TerminalEditorApp.toggleTarget(action);
        if (toggle != null) {
            searchUi = SearchController.toggleFocusedSearchOption(searchUi, toggle);
            return true;
        }
        return action == SearchBarView.Action.QUERY_CHANGED || action == SearchBarView.Action.REPLACEMENT_CHANGED;
    }

    private void executeSearchDocumentCommand(TuinkCommand command) {
        if (command.editsDocumentText() && rejectSavingDocumentEdit()) {
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
        if (SearchController.isSearchActionFocus(host.searchFocusManager.focused().orElse(null))) {
            host.focusSearch(searchBarPlacement, SearchController.focusForAction(command));
        }
        SearchCommandHandler.Result result = SearchCommandHandler.execute(command,
            new SearchCommandHandler.Context(tabs.stream().map(TerminalEditorApp.EditorTab::documentSession).toList(), activeTabIndex,
                uiSessionState.readingModeSetting, searchUi, searchQueryInput.text(), searchReplaceInput.text()),
            this::revealCurrentSelection);
        tabs.get(result.activeTabIndex()).viewportController().onCommandResult(result.cursorPolicy());
        if (result.activeTabIndex() != activeTabIndex) sessionContext.activate(result.activeTabIndex());
        if (result.status() != null && !result.status().isBlank()) {
            switch (result.statusLevel()) {
                case ERROR -> notification.error(result.status());
                case WARNING -> notification.warning(result.status());
                case INFO -> notification.info(result.status());
            }
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
        boolean cancelledCapture = pointerCapture.beforeInput(pointerFrame(), inputEvent);
        if (cancelledCapture && plan != null) {
            prepareComposition(false);
            eventLoop.invalidate();
        }
        if (inputEvent instanceof ResizeEvent) {
            tabBarColumns = -1;
            host.helpScrollState = new ListView.State(-1, host.helpScrollState.firstVisibleIndex());
            openFileTree.adopt(openFileTree.state().cancelPointerInteraction());
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
        ModalInputPolicy.Kind blocker = TerminalEditorApp.modalInputKind(modalState, openFileMode);
        TuinkCommand physicalCommand = inputEvent instanceof KeyStroke stroke
            ? commandRouter.invocationFor(stroke)
                .flatMap(invocation -> TuinkCommand.fromId(invocation.id())).orElse(null)
            : null;
        if (routeBlockingInput(inputEvent, physicalCommand, blocker)) {
            return;
        }
        if (TerminalEditorApp.searchOwnsRawInput(uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen())
            && !uiSessionState.searchEditorFocused
            && DialogCommandPolicy.decide(DialogMode.NON_BLOCKING,
                SearchController.isSearchFieldFocus(host.searchFocusManager.focused().orElse(null))
                    ? DialogInputPolicy.Target.TEXT_INPUT : DialogInputPolicy.Target.BUTTON,
                DialogCommandPolicy.action(inputEvent, physicalCommand)).consumed()) {
            return;
        }
        boolean startsAction = !(inputEvent instanceof PointerEvent pointer) || switch (pointer.kind()) {
            case PRESS, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT -> true;
            default -> false;
        };
        if (!saveAsMode && !confirmMode && startsAction) {
            notification.clearErrorOnUserAction();
        }
        eventLoop.invalidate();
        if (modalState.isHelpHints() || modalState.isHelpLicenses() || modalState.isHelpAuthor()) {
            boolean pointerAllowed = !(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && uiSessionState.mouseModeAvailable;
            if (pointerAllowed) {
                HelpOverlayInputRouter.Result helpInput = host.routeHelpOverlayInput(
                    currentHelpOverlay(), inputEvent, pointerCapture, this
                );
                if (helpInput.closeRequested()) {
                    host.helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
                    modalState = UiModalState.none();
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
            String filenameBefore = saveAsInput.text();
            SaveAsInputRouter.Result saveAsInputResult = SaveAsInputRouter.route(
                host.saveAsFocusManager,
                saveAsBarPlacement,
                inputEvent, pointerCapture, this
            );
            if (!saveAsInputResult.consumed() && physicalCommand != null) {
                saveAsInputResult = SaveAsInputRouter.routeCommand(host.saveAsFocusManager,
                    saveAsBarPlacement, physicalCommand, host.clipboard);
            }
            SaveAsBarView.Action fieldAction = saveAsInputResult.action();
            if (fieldAction == SaveAsBarView.Action.INPUT_REJECTED) {
                notification.error("Text is too large; nothing changed");
                return;
            }
            if (fieldAction == SaveAsBarView.Action.VALUE_CHANGED) {
                if (!filenameBefore.equals(saveAsInput.text())) {
                    filenameRevision++;
                    notification.clear();
                }
                saveAsSuggestions.rememberDraft(
                    tabs.get(activeTabIndex).tabSession(),
                    saveAsInput.text()
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
                host.confirmFocusManager,
                confirmBarPlacement,
                host.confirmPolicy(modalState),
                inputEvent
            );
            routedConfirmDecision = confirmInput.decision();
            if (routedConfirmDecision != null) {
                notification.clearErrorOnUserAction();
            }
            if (routedConfirmDecision != null && inputEvent instanceof PointerEvent) {
                editorView.cancelMouseSelection();
            }
            if (routedConfirmDecision == null && confirmInput.consumed()) {
                return;
            }
        }
        if (openFileMode) {
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
                openFileFocusState,
                inputEvent,
                System.nanoTime(),
                TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS, pointerCapture, this
            );
            openFileTree.adopt(openInput.state());
            if (inputEvent instanceof PointerEvent && openInput.consumed()) {
                editorView.cancelMouseSelection();
            }
            if (openInput.intent() != null) {
                applyOpenFileResult(FilePickerOpenWorkflow.apply(openInput.intent(), openFileTree,
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
        if (modalState.isNone() && !openFileMode) {
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
                            sessionContext.activate(tab.tabIndex());
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
            uiSessionState.searchMode,
            modalState,
            openFileMode,
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
            SearchInputRouter.Result searchInput = !uiSessionState.searchEditorFocused
                || inputEvent instanceof PointerEvent
                ? SearchInputRouter.route(host.searchFocusManager, searchBarPlacement, inputEvent, pointerCapture, this)
                : new SearchInputRouter.Result(false, null);
            if (inputEvent instanceof PointerEvent pointer
                && pointer.kind() == PointerEvent.Kind.PRESS
                && pointer.button() == PointerEvent.Button.LEFT
                && searchInput.consumed()
                && host.searchFocusManager.focused().isPresent()) {
                uiSessionState.searchEditorFocused = false;
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
            modalState,
            openFileMode,
            topMenu.isOpen(),
            uiSessionState.searchMode
        ) : null;
        if (inputEvent instanceof PointerEvent pointer && tabPolicy != null) {
            TabBarInputRouter.Result tabInput = TabBarInputRouter.route(
                tabBarPlacement,
                tabPolicy,
                pointer
            );
            if (modalState.isCloseTabConfirm() && tabInput.blocked()) {
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
                    sessionContext.activate(resolvedTab);
                }
                topMenu.close();
                editorView.cancelMouseSelection();
                return;
            }
            Integer closeIndex = tabInput.closeIndex();
            if (closeIndex != null && closeIndex < tabs.size()) {
                if (tabs.get(closeIndex).documentSession().document().io().busy()) {
                    notification.info("Saving this document; wait before closing its tab");
                    return;
                }
                if (tabs.get(closeIndex).state().isDirty()
                    && uiSessionState.confirmOnCloseSetting) {
                    modalState = UiModalState.closeTabConfirm(closeIndex);
                } else {
                    sessionContext.activate(
                        host.closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions)
                    );
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
                notification.error("Paste is too large and was not inserted");
                return;
            }
            if (!modalState.isNone() || openFileMode || topMenu.isOpen()) {
                // The focused overlay owns input; paste cannot leak through it.
            } else {
                if (rejectSavingDocumentEdit()) {
                    return;
                } else if (uiSessionState.readingModeSetting) {
                    notification.warning("Reading mode: editing is disabled");
                } else {
                    long revisionBefore = model.revision();
                    Position cursorBefore = state.getCursor();
                    try {
                        activeDocument().execute(new DocumentCommand.Paste(paste.text()));
                    } catch (DocumentEditLimitException limit) {
                        notification.error(limit.getMessage());
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
                    uiSessionState.searchMode,
                    modalState,
                    openFileMode,
                    topMenu.isOpen(),
                    routedMenuCommand != null
                ) && !uiSessionState.searchEditorFocused,
                host.currentSearchFocus()
            );
        if (key == null) {
            return;
        }
        if (TerminalEditorApp.searchOwnsRawInput(uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen())
            && uiSessionState.searchEditorFocused
            && key.command() == TuinkCommand.UI_ESCAPE) {
            uiSessionState.searchMode = false;
            host.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return;
        }
        if (key.command() == null && key.event() instanceof PointerEvent mouse) {
            if (TerminalEditorApp.modalInputKind(modalState, openFileMode) == ModalInputPolicy.Kind.NONE) {
                routeDocumentPointer(mouse);
            }
            return;
        }

        if (modalState.isHelpHints()) {
            HelpHintsController.Result helpHintsResult = HelpHintsController.handle(
                key,
                true
            );
            if (helpHintsResult.consumed()) {
                if (!helpHintsResult.helpHintsMode()) {
                    host.helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
                }
                modalState = helpHintsResult.helpHintsMode() ? UiModalState.helpHints() : UiModalState.none();
                return;
            }
        }

        if (modalState.isHelpLicenses()) {
            if (key.command() == null && key.event() instanceof TextInput) {
                return;
            }
            TuinkCommand action = key.command();
            if (action == TuinkCommand.UI_ESCAPE
                || action == TuinkCommand.EDIT_INSERT_NEWLINE
                || action == TuinkCommand.HELP_LICENSES) {
                host.helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
                modalState = UiModalState.none();
            }
            return;
        }

        if (modalState.isHelpAuthor()) {
            if (key.command() == null && key.event() instanceof TextInput) {
                return;
            }
            TuinkCommand action = key.command();
            if (action == TuinkCommand.UI_ESCAPE
                || action == TuinkCommand.EDIT_INSERT_NEWLINE
                || action == TuinkCommand.HELP_AUTHOR) {
                host.helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
                modalState = UiModalState.none();
            }
            return;
        }

        if (modalState.isRecoveryConfirm()) {
            if (routedConfirmDecision != null) {
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    var restored = host.restoreRecoveredTabs(
                        startupRecoveryEntries,
                        tabs,
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
                        openFileTree.setRoot(FilePickerOpenWorkflow.initialRoot(JvmDocumentStorage.pathOf(state.getCurrentResource())));
                        notification.info("Recovered " + restoredCount + " tab(s)");
                    } else {
                        notification.info("No recovery snapshots restored");
                    }
                    if (!restored.failedEntries().isEmpty()) {
                        notification.error("Could not restore " + restored.failedEntries().size()
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
                        notification.error(deletion.failureMessage());
                        return;
                    }
                    notification.info("Recovery snapshots discarded");
                } else {
                    var release = recoveryAutosave.releaseEntries(startupRecoveryEntries);
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(release.failedEntries());
                    if (!release.successful()) {
                        notification.error(release.failureMessage());
                        return;
                    }
                    notification.info("Recovery postponed; snapshots kept for next launch");
                }
                modalState = UiModalState.none();
                return;
            }
        }

        if (modalState.isSaveAs()) {
            applySaveResult(SaveWorkflow.saveAsInput(saveContext(), key.command(), modalState, saveAsInput.text()));
            return;
        }
        if (routedConfirmDecision != null) {
            if (modalState.isOpenUtf16Confirm()) {
                Path target = modalState.pendingOpenPath();
                modalState = UiModalState.none();
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    applyOpenFileResult(FilePickerOpenWorkflow.openAsUtf16(target,
                        host.toTabSessions(tabs), sessionContext.activeTabIndex(),
                        () -> TuiDocumentSession.create(host.clipboard)));
                } else {
                    openFileMode = true;
                }
                return;
            }
            if (modalState.isSaveUtf8Confirm()) {
                applySaveResult(SaveWorkflow.encodingChoice(modalState, routedConfirmDecision));
                return;
            }
            if (modalState.isSaveOverwriteConfirm()) {
                applySaveResult(SaveWorkflow.overwrite(saveContext(), modalState, routedConfirmDecision));
                return;
            }
            if (modalState.isQuitConfirm()) {
                applySaveResult(SaveWorkflow.quit(saveContext(), routedConfirmDecision));
                return;
            }
            if (modalState.isCloseTabConfirm()) {
                applySaveResult(SaveWorkflow.closeTab(saveContext(), modalState, routedConfirmDecision));
                return;
            }
        }

        CommandRecipient recipient = CommandRecipient.resolve(key.command(),
            uiSessionState.searchMode, uiSessionState.searchEditorFocused);
        if (recipient == CommandRecipient.SEARCH_CONTROL) {
            if (searchBarPlacement != null) {
                if (key.command() == TuinkCommand.EDIT_INSERT_TAB || key.command() == TuinkCommand.SEARCH_FOCUS_NEXT) {
                    advanceSearchFocus(KeyStroke.of(KeyStroke.Key.TAB));
                    return;
                }
                SearchInputRouter.Result result = SearchInputRouter.routeCommand(host.searchFocusManager,
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
                notification.warning("Reading mode: editing is disabled");
            } else {
                long revisionBefore = model.revision();
                Position cursorBefore = state.getCursor();
                try {
                    activeDocument().execute(new DocumentCommand.Input(charInput.text()));
                } catch (DocumentEditLimitException limit) {
                    notification.error(limit.getMessage());
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
            notification.info(alreadyQuitting ? "Saving documents before quitting"
                : "Waiting for the file operation before quitting");
            return;
        }
        if (action == TuinkCommand.TAB_CLOSE && activeDocument().document().io().busy()) {
            notification.info("Saving this document; wait before closing its tab");
            return;
        }
        if ((documentIo.busy() || activeDocument().document().io().busy())
                && (action == TuinkCommand.DOCUMENT_SAVE || action == TuinkCommand.DOCUMENT_SAVE_AS)
            || documentIo.busy() && action == TuinkCommand.DOCUMENT_OPEN
            || deferredOpenResult != null && action == TuinkCommand.DOCUMENT_OPEN) {
            notification.info("A file operation is already in progress");
            return;
        }
        InputActionDispatcher.DispatchResult dispatchResult = dispatchInputAction(action);
        if (dispatchResult.handled()) {
            viewportController.onCommandResult(dispatchResult.viewportPolicy());
            if (action == TuinkCommand.SEARCH_OPEN) {
                String selectedQuery = SearchSelection.prefill(model, state);
                if (selectedQuery != null && !searchQueryInput.set(selectedQuery)) {
                    uiSessionState.searchMode = false;
                    uiSessionState.searchEditorFocused = false;
                    host.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
                    searchBarPlacement = null;
                    notification.error("Not enough memory to open Find with the selected text");
                    return;
                }
                host.focusSearch(searchBarPlacement, SearchFocus.FIND);
            }
            modalState = TerminalEditorApp.consumeRequestedHelpModal(uiSessionState, modalState);
            if (dispatchResult.statusMessage() != null && !dispatchResult.statusMessage().isBlank()) {
                if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.ERROR) {
                    notification.error(dispatchResult.statusMessage());
                } else if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.WARNING) {
                    notification.warning(dispatchResult.statusMessage());
                } else {
                    notification.info(dispatchResult.statusMessage());
                }
            }
            return;
        }
        EditorActionController.Result editorAction = EditorActionController.decide(
            action,
            running,
            modalState,
            uiSessionState.confirmOnCloseSetting,
            host.hasDirtyTabs(tabs),
            state.isDirty(),
            activeTabIndex,
            JvmDocumentStorage.pathOf(state.getCurrentResource()),
            (action == TuinkCommand.DOCUMENT_SAVE || action == TuinkCommand.DOCUMENT_SAVE_AS)
                && JvmDocumentStorage.pathOf(state.getCurrentResource()) != null
                && SaveTargetPolicy.isReadOnlyFile(JvmDocumentStorage.pathOf(state.getCurrentResource()))
        );
        var themeBefore = host.theme;
        host.applyEditorActionResult(editorAction, sessionContext, tabs, activeTabIndex,
            notification, saveAsSuggestions, renderer);
        if (host.theme != themeBefore) {
            settingsSession.themeChanged();
        }
        if (editorAction.tabCommand() != EditorActionController.TabCommand.NONE
            || editorAction.requestOpenFile() || !editorAction.modalState().isNone()) {
            uiSessionState.searchMode = false;
        }
        running = editorAction.running();
        modalState = editorAction.modalState();
        if (editorAction.requestOpenFile()) {
            topMenu.close();
            openFileMode = true;
            pickerNotice = null;
            lastPickerDirectoryFailure = null;
            openFileTree.setRoot(FilePickerOpenWorkflow.initialRoot(JvmDocumentStorage.pathOf(state.getCurrentResource())));
            openFileFocusState.focus(OpenFileInputRouter.Focus.LIST);
        }
        applyFilenameEffect(SaveWorkflow.initialFilename(saveContext(), editorAction.saveAsPreset()));
        if (editorAction.requestSaveCurrent()) {
            applySaveResult(SaveWorkflow.saveCurrent(saveContext()));
        }
    }

    private void applyOpenFileResult(FilePickerOpenWorkflow.Result result) {
        if (result.pendingOpen() != null) {
            if (documentIo.busy() || deferredOpenResult != null) {
                notification.info("A file operation is already in progress");
                return;
            }
            var request = result.pendingOpen();
            pendingOpen = request;
            openFileMode = false;
            pickerNotice = null;
            notification.info("Opening document…");
            documentIo.submit(request::read, completion -> {
                pendingOpen = null;
                if (closingDocumentIo) {
                    return;
                }
                applyOpenFileResult(FilePickerOpenWorkflow.complete(request, completion.value(), completion.failure(),
                    host.toTabSessions(tabs), sessionContext.activeTabIndex(),
                    () -> TuiDocumentSession.create(host.clipboard)));
                afterDocumentIo();
            });
            return;
        }
        boolean documentPromptActive = modalState.isSaveAs() || modalState.isSaveOverwriteConfirm()
            || modalState.isSaveUtf8Confirm() || modalState.isCloseTabConfirm();
        if (documentPromptActive && (result.openFileMode() || result.pendingExplicitOpenPath() != null)) {
            deferredOpenResult = result;
            if (result.noticeKind() == FilePickerOpenWorkflow.NoticeKind.ERROR) {
                notification.error(result.message());
            } else {
                notification.info("Finish the current dialog to choose the file encoding");
            }
            return;
        }
        if (result.openedDocument() != null) {
            TuiDocumentSession document = result.openedDocument();
            RenderPlanBuilder builder = document.newRenderPlanBuilder(
                host.resolveHighlighter(JvmDocumentStorage.pathOf(document.view().state().getCurrentResource())));
            tabs.add(new TerminalEditorApp.EditorTab(document, builder));
            if (!documentPromptActive) {
                sessionContext.activate(tabs.size() - 1);
            }
        } else if (result.activateTabIndex() >= 0 && !documentPromptActive) {
            sessionContext.activate(result.activateTabIndex());
        }
        openFileMode = result.openFileMode();
        if (result.pendingExplicitOpenPath() != null) {
            modalState = UiModalState.openUtf16Confirm(result.pendingExplicitOpenPath());
        }
        if (!openFileMode) pickerNotice = null;
        switch (result.noticeKind()) {
            case UNCHANGED -> { }
            case SUCCESS -> {
                if (documentPromptActive && result.activateTabIndex() >= 0) {
                    notification.info("File is already open in tab " + (result.activateTabIndex() + 1));
                } else {
                    notification.success(result.message());
                }
            }
            case WARNING -> {
                if (openFileMode) pickerNotice = result.notice();
                else notification.warning(result.message());
            }
            case ERROR -> {
                if (openFileMode) pickerNotice = result.notice();
                else notification.error(result.message());
            }
        }
    }

    private TuiDocumentSession activeDocument() {
        return tabs.get(sessionContext.activeTabIndex()).documentSession();
    }

    private SaveWorkflow.Context saveContext() {
        return new SaveWorkflow.Context(tabs.stream().map(TerminalEditorApp.EditorTab::documentSession).toList(),
            sessionContext.activeTabIndex(), saveAsSuggestions, overwriteApproval);
    }

    private boolean applyFilenameEffect(SaveFlowController.TextInputEffect effect) {
        switch (effect.kind()) {
            case NONE -> { }
            case SET_TEXT -> {
                if (!saveAsInput.set(effect.text())) {
                    // Never leave a previous tab's filename active after a refused preset.
                    // Cancelling this continuation keeps every unsaved document open.
                    modalState = UiModalState.none();
                    running = true;
                    host.saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
                    saveAsBarPlacement = null;
                    notification.error("Not enough memory to open Save As; the filename was not changed");
                    return false;
                }
                filenameRevision++;
            }
            case CLEAR -> {
                saveAsInput.clear();
                filenameRevision++;
            }
        }
        return true;
    }

    private void applySaveResult(SaveWorkflow.Result result) {
        applySaveResult(result, null);
    }

    private void applySaveResult(SaveWorkflow.Result result, TabSession savedTab) {
        // Refresh the saved tab before a continuation activates or closes another tab.
        if (result.refreshSavedHighlighter()) {
            refreshSavedHighlighter(savedTab == null ? tabs.get(sessionContext.activeTabIndex()).tabSession() : savedTab);
        }
        if (result.activateTabIndex() >= 0) {
            sessionContext.activate(result.activateTabIndex());
        }
        if (result.closeTabIndex() >= 0) {
            sessionContext.activate(host.closeTab(tabs, sessionContext.activeTabIndex(), result.closeTabIndex(), saveAsSuggestions));
        }
        if (!applyFilenameEffect(result.filename())) {
            return;
        }
        applySaveNotice(result.notice());
        running = result.running();
        modalState = result.modalState();
        overwriteApproval = result.overwriteApproval();
        if (result.focusFilename()) {
            blockingContentFocused = false;
            // Re-enter against the next frame's filename placement, including after a
            // continuation changes tabs without changing the Save As scope identity.
            host.saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
        }
        if (result.pendingSave() != null && !closingDocumentIo) {
            startSave(result.pendingSave());
        }
    }

    private void refreshSavedHighlighter(TabSession savedTab) {
        for (int index = 0; index < tabs.size(); index++) {
            TerminalEditorApp.EditorTab tab = tabs.get(index);
            if (tab.tabSession() == savedTab) {
                RenderPlanBuilder builder = tab.documentSession().newRenderPlanBuilder(
                    host.resolveHighlighter(JvmDocumentStorage.pathOf(savedTab.state().getCurrentResource())));
                tabs.set(index, new TerminalEditorApp.EditorTab(tab.documentSession(), builder,
                    tab.viewportController(), tab.navigation()));
                if (index == sessionContext.activeTabIndex()) {
                    sessionContext.activate(index);
                    renderPlanBuilder = builder;
                }
                return;
            }
        }
    }

    private void startSave(SaveWorkflow.SaveRequest unsubmitted) {
        // Completing a read-only inspection during close must not start a new write.
        if (closingDocumentIo) {
            unsubmitted.session().document().io().stop();
            return;
        }
        SaveWorkflow.SaveRequest request = unsubmitted.submission() == null
            ? unsubmitted.submitted(saveAsInput.text(), filenameRevision) : unsubmitted;
        if (documentIo.busy()) {
            request.session().document().io().cancel(request.operation());
            notification.info("A file operation is already in progress; retry saving after it finishes");
            modalState = request.origin();
            return;
        }
        pendingSave = request;
        long submittedFilenameRevision = request.submission().filenameRevision();
        String submittedFilename = request.submission().filename();
        notification.info(request.inspecting() ? "Checking save target…" : "Saving document…");
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
                    notification.error(SaveTargetPolicy.saveErrorMessage(failed.failure()));
                }
                return;
            }
            SaveWorkflow.Result result = SaveWorkflow.complete(saveContext(), request, transition);
            boolean succeeded = completion.succeeded() && completion.value().succeeded()
                && result.notice().kind() != SaveWorkflow.NoticeKind.ERROR;
            if (!succeeded) {
                // The deferred exit depended on this write succeeding. Keep the
                // failed draft visible even when ordinary close confirmation is off.
                quitAfterDocumentIo = false;
            }
            if (request.inspecting() && result.pendingSave() == null) {
                // A target prompt or inspection failure needs an explicit owner decision.
                quitAfterDocumentIo = false;
            }
            boolean standalone = request.purpose() == SaveWorkflow.SavePurpose.CURRENT
                    || request.purpose() == SaveWorkflow.SavePurpose.SAVE_AS
                        && request.origin().saveContinuation() == SaveContinuation.NONE;
            if (request.inspecting() && succeeded && result.pendingSave() != null && standalone) {
                // Advancing inspection to writing is still the original operation.
                // Keep any independent modal/filename opened while it was pending.
                startSave(result.pendingSave());
            } else if (!request.inspecting() && succeeded && standalone) {
                // The original save prompt was dismissed when the write started. A modal or
                // filename opened since then belongs to the user's independent interaction.
                if (result.refreshSavedHighlighter()) {
                    refreshSavedHighlighter(request.tab());
                }
                if (filenameRevision == submittedFilenameRevision) {
                    applyFilenameEffect(result.filename());
                }
                applySaveNotice(result.notice());
            } else {
                if (request.purpose() == SaveWorkflow.SavePurpose.SAVE_AS
                    && (!succeeded || request.inspecting() && result.pendingSave() == null)
                    && filenameRevision != submittedFilenameRevision
                    && (result.modalState().isSaveAs() || result.modalState().isSaveOverwriteConfirm()
                        || result.modalState().isSaveUtf8Confirm())
                    && !applyFilenameEffect(SaveFlowController.TextInputEffect.setText(submittedFilename))) {
                    afterDocumentIo();
                    return;
                }
                applySaveResult(result, request.tab());
            }
            afterDocumentIo();
        });
    }

    private void applySaveNotice(SaveWorkflow.Notice notice) {
        switch (notice.kind()) {
            case UNCHANGED -> { }
            case CLEAR -> notification.clear();
            case SUCCESS -> notification.success(notice.message());
            case ERROR -> notification.error(notice.message());
        }
    }

    private boolean rejectSavingDocumentEdit() {
        if (!activeDocument().document().io().busy()) {
            return false;
        }
        notification.info("Saving this document; editing will resume when the write finishes");
        return true;
    }

    private void afterDocumentIo() {
        if (quitAfterDocumentIo && !documentIo.busy() && !closingDocumentIo) {
            quitAfterDocumentIo = false;
            if (uiSessionState.confirmOnCloseSetting && host.hasDirtyTabs(tabs)) {
                modalState = UiModalState.quitConfirm();
            } else {
                running = false;
            }
        }
        eventLoop.invalidate();
    }

    /** Finish a started write before recovery captures its final owner-thread state. */
    void closeDocumentIo() {
        closingDocumentIo = true;
        deferredOpenResult = null;
        if (pendingOpen != null) pendingOpen.cancel();
        for (TerminalEditorApp.EditorTab tab : tabs) tab.documentSession().document().io().stop();
        documentIo.close();
    }

    private InputActionDispatcher.DispatchResult dispatchInputAction(TuinkCommand command) {
        boolean readingBefore = uiSessionState.readingModeSetting;
        boolean mouseBefore = uiSessionState.mouseModeSetting;
        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(command, uiSessionState,
            new InputActionDispatcher.DispatchContext(engine, state, topMenu,
                terminal::setMouseTracking, contentRows, contentCols, navigation,
                tabs.get(sessionContext.activeTabIndex()).documentSession()));
        if (uiSessionState.readingModeSetting != readingBefore) {
            settingsSession.readingModeChanged();
        }
        if (uiSessionState.mouseModeSetting != mouseBefore) {
            settingsSession.mouseModeChanged();
        }
        return result;
    }
}

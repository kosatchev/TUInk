package dev.tuink.tui;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.EditorViewportBehavior;
import dev.tuink.core.LanguageProfiles;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import dev.tuink.core.SearchMatcher;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSessions;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.AppSettingsStore;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.SearchNavigation;
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
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuiframe.runtime.TuiApplicationRunner;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScreenCursor;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.style.CellColor;
import dev.tuink.tui.style.UiStyles;
import dev.tuiframe.jline.TerminalSession;
import dev.tuiframe.jline.TerminalRuntime;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.DialogAttention;
import dev.tuiframe.widget.DialogAttentionPainter;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.DialogInputPolicy;
import dev.tuiframe.widget.DialogMode;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.PlacedWidget;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.WidgetStyleRole;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.DirtyTabSummary;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.HelpPages;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.CursorPolicy;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.ViewportController;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Owns frame preparation and event handling for one terminal editor window. */
final class EditorWindowController implements TuiApplicationRunner.Frontend<TuiInputEvent> {

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
    private final List<RecoveryStore.Entry> startupRecoveryEntries;
    private final List<TerminalEditorApp.EditorTab> tabs;
    private final TerminalAccess terminal;
    private final TopMenuController topMenu;
    private final UiSessionState uiSessionState;

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
        this.terminal = resources.terminal();
        this.topMenu = resources.topMenu();
        this.uiSessionState = resources.uiSessionState();
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
        List<RecoveryStore.Entry> startupRecoveryEntries,
        List<TerminalEditorApp.EditorTab> tabs,
        TerminalAccess terminal,
        TopMenuController topMenu,
        UiSessionState uiSessionState
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
    private final OpenFileDialogView.FocusState openFileFocusState = new OpenFileDialogView.FocusState();
    private EditorView.ScrollAxis editorScrollDrag = EditorView.ScrollAxis.NONE;
    private int editorScrollDragThumbOffset;

    private int activeTabIndex;
    private TextModel model;
    private EditorState state;
    private EditorEngine engine;
    private RenderPlanBuilder renderPlanBuilder;
    private ViewportController viewportController;

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
    private final TerminalEditorApp.TabMatchCache tabMatchCache = new TerminalEditorApp.TabMatchCache();
    private final SearchMatchCountCache searchMatchCountCache = new SearchMatchCountCache();

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

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public void prepareTurn() {
        uiSessionState.syntaxHighlightingAvailable = host.syntaxHighlightingAvailable();
        if (state != sessionContext.state()) {
            cancelDocumentPointerInteraction();
            cancelFieldMouseSelection();
        }
        activeTabIndex = sessionContext.activeTabIndex();
        model = sessionContext.model();
        state = sessionContext.state();
        engine = sessionContext.engine();
        renderPlanBuilder = sessionContext.renderPlanBuilder();
        viewportController = tabs.get(activeTabIndex).viewportController();
        Object nextBlockingOwner = TerminalEditorApp.modalInputOwner(modalState, openFileMode);
        if (!Objects.equals(blockingFocusOwner, nextBlockingOwner)) {
            blockingFocusOwner = nextBlockingOwner;
            blockingContentFocused = false;
            cancelDocumentPointerInteraction();
            cancelFieldMouseSelection();
        }
        if (host.modalAttention.synchronize(TerminalEditorApp.modalInputOwner(modalState, openFileMode), System.nanoTime())) {
            eventLoop.invalidate();
        }
        List<TabSession> recoveryTabSessions = host.toTabSessions(tabs);
        recoveryAutosave.syncTabs(recoveryTabSessions);
        String recoveryWarning = recoveryAutosave.autosaveDirtyTabs(
            recoveryTabSessions,
            System.nanoTime()
        );
        if (recoveryWarning != null) {
            notification.warning(recoveryWarning);
            eventLoop.invalidate();
        }
        if (notification.expireIfNeeded()) {
            eventLoop.invalidate();
        }
        if (openFileMode && openFileTree.consumeMetadataChanged()) {
            eventLoop.invalidate();
        }
        rows = Math.max(2, terminal.height());
        cols = Math.max(1, terminal.width());
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
    }

    @Override
    public boolean renderFrame(TuiEventLoop.FrameTicket frameTicket) {
        if (frameTicket.fullRedraw()) {
            screenPresenter.invalidate();
        }
        if (host.profileEnabled) {
            host.frameProfiler.beginFrame();
        }
        String searchQuery = "";
        SearchOptions searchOptions = SearchOptions.DEFAULT;
        int searchMatchCount = 0;
        if (uiSessionState.searchMode) {
            searchQuery = searchQueryInput.text();
            searchOptions = SearchController.toSearchOptions(searchUi);
            if (!searchQuery.isEmpty()) {
                boolean searchAllTabs = searchUi.allTabs();
                TextModel searchCountModel = searchAllTabs ? null : model;
                List<TerminalEditorApp.EditorTab> searchCountTabs = searchAllTabs ? tabs : null;
                long searchSourceIdentity = host.searchSourceIdentity(
                    model,
                    tabs,
                    searchAllTabs
                );
                long searchSourceRevision = host.searchSourceRevision(
                    model,
                    tabs,
                    searchAllTabs
                );
                searchMatchCount = searchMatchCountCache.lookup(
                    true,
                    searchQuery,
                    searchOptions,
                    searchAllTabs,
                    searchSourceIdentity,
                    searchSourceRevision
                );
                if (searchMatchCount < 0) {
                    searchMatchCount = host.countSearchMatches(
                        searchCountModel,
                        searchCountTabs,
                        searchQuery,
                        searchOptions
                    );
                    searchMatchCountCache.store(
                        searchQuery,
                        searchOptions,
                        searchAllTabs,
                        searchSourceIdentity,
                        searchSourceRevision,
                        searchMatchCount
                    );
                }
            }
        }
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
        String searchSummary = host.buildSearchSummary(
            uiSessionState.searchMode,
            searchQuery,
            searchUi,
            searchMatchCount,
            tabs.size()
        );
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
            saveAsBar = SaveAsBarView.build(saveAsInput, cols);
            inlinePromptBar = InlinePromptBar.of(
                saveAsBar.row(),
                EditorStyleAdapter.toEditor(host.styles.promptAlert()),
                1
            );
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
        boolean[] tabMatchFlags = host.resolveTabMatchFlags(
            tabs,
            uiSessionState.searchMode,
            searchQuery,
            searchOptions,
            tabMatchCache
        );
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
            new Rect(0, 0, cols, rows), uiSessionState.readingModeSetting
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
                openFileTree.showHiddenFiles(), new Rect(0, 0, cols, rows)
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
            LanguageProfiles.resolveCommentSyntax(state.getCurrentFile())
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
        engine.setGutterDiffMode(uiSessionState.gutterDiffModeSetting);
        renderPlanBuilder.setGutterDiffResolver(engine::gutterDiffMarkerForLine);
        editorView.setTabWidth(uiSessionState.tabWidthSetting);
        engine.setTabInsertMode(uiSessionState.tabInsertModeSetting);
        engine.setAutoIndentEnabled(uiSessionState.autoIndentSetting);
        engine.setAutoPairsEnabled(uiSessionState.autoPairsSetting);
        engine.setAutoListEnabled(uiSessionState.autoListSetting);

        TopMenuView.TopBar topMenuBar = topMenuView.topBar(
            state.getCurrentFile(),
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
        modalInputBounds = host.blockingSurface(rows, cols, contentTopRow, inlinePromptBar.height(),
            saveAsMode || confirmMode, host.activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode),
            openFileMode ? openFileLayout : null);
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
                        true,
                        openFileLayout,
                        openFileTree.root().toString(),
                        openFileTree.showHiddenFiles(),
                        openFileTreePlacement,
                        openFileFocusState.focus()
                    )
                    : FrameComposition.OpenFile.hidden(),
                host.activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode),
                host.modalAttention.highlighted(),
                blockingContentFocused
            ),
            uiSessionState.wrapLinesSetting,
            uiSessionState.wrapWordsSetting,
            frameTicket.fullRedraw()
        );
        modalInputBounds = host.blockingSurface(composition);
        TerminalEditorApp.RenderFrameResult frameResult = renderFrameController.renderFrameIfNeeded(composition);
        plan = frameResult.plan();
        return frameResult.rendered();
    }

    @Override
    public void frameEnded(boolean rendered) {
        if (host.profileEnabled) {
            host.frameProfiler.endFrame(rendered);
        }
    }

    @Override
    public long nanosUntilWakeup(long nowNanos) {
        return TerminalEditorApp.earlierDeadline(
            notification.nanosUntilExpire(nowNanos),
            host.modalAttention.nanosUntilChange(nowNanos)
        );
    }

    private TerminalEditorApp.HelpOverlayPlacement currentHelpOverlay() {
        HelpPage page = host.activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode);
        return page == null ? null : host.helpOverlayPlacement(rows, cols, page);
    }

    private void cancelFieldMouseSelection() {
        searchQueryInput.cancelMouseSelection();
        searchReplaceInput.cancelMouseSelection();
        saveAsInput.cancelMouseSelection();
    }

    private void cancelDocumentPointerInteraction() {
        editorView.cancelMouseSelection();
        editorScrollDrag = EditorView.ScrollAxis.NONE;
        editorScrollDragThumbOffset = 0;
    }

    private boolean handleCapturedFieldPointer(PointerEvent pointer) {
        boolean saveAsCapture = saveAsMode && saveAsBarPlacement != null
            && saveAsBarPlacement.captures(pointer);
        boolean searchCapture = TerminalEditorApp.searchOwnsRawInput(
            uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen()
        ) && searchBarPlacement != null && searchBarPlacement.captures(pointer);
        if (!saveAsCapture && !searchCapture) {
            return false;
        }
        if (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable) {
            cancelFieldMouseSelection();
        } else if (saveAsCapture) {
            SaveAsInputRouter.route(host.saveAsFocusManager, saveAsBarPlacement, pointer);
        } else {
            SearchInputRouter.route(host.searchFocusManager, searchBarPlacement, pointer);
        }
        eventLoop.invalidate();
        return true;
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
            Scrollbar.Drag dialogDrag = switch (kind) {
                case HELP -> host.helpScrollState.drag();
                case OPEN_FILE -> openFileTree.state().widgetState().drag();
                default -> null;
            };
            if (Scrollbar.captures(mouse, dialogDrag)) {
                return false;
            }
            boolean drag = mouse.kind() == PointerEvent.Kind.DRAG
                || mouse.kind() == PointerEvent.Kind.MOVE && mouse.button() == PointerEvent.Button.LEFT;
            if (mode.permitsContentFocus()
                && (documentPointerHit(mouse)
                    || (drag || mouse.kind() == PointerEvent.Kind.RELEASE)
                        && (blockingContentFocused || editorScrollDrag != EditorView.ScrollAxis.NONE))) {
                handleDocumentPointer(mouse);
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
        if (DialogFocusTraversal.isTraversal(event)) {
            advanceBlockingFocus((KeyStroke) event);
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
                viewportController.onInput(input, engine.hasSelection(), !uiSessionState.readingModeSetting);
                InputActionDispatcher.dispatch(input.command(), uiSessionState,
                    new InputActionDispatcher.DispatchContext(engine, state, topMenu,
                        terminal::setMouseTracking, contentRows, contentCols));
            }
            eventLoop.invalidate();
            return true;
        }
        if (saveAsMode && (action == DialogInputPolicy.Action.COPY
            || action == DialogInputPolicy.Action.CUT || action == DialogInputPolicy.Action.PASTE)
            && event instanceof KeyStroke) {
            if (host.saveAsFocusManager.focused().filter(SaveAsBarView.Target.FIELD::equals).isPresent()) {
                String filenameBefore = saveAsInput.text();
                FieldClipboard.handle(command, saveAsInput, host.clipboard);
                if (!filenameBefore.equals(saveAsInput.text())) {
                    notification.clear();
                }
                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsInput.text());
            }
            eventLoop.invalidate();
            return true;
        }
        if (ModalInputPolicy.blocked(kind, event, command)) {
            rejectDialogInput();
            return true;
        }
        return false;
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
                    EditorViewportBehavior.applyHorizontalScrollVisual(
                        state, model, engine.textLayout(), nextLeft, uiSessionState.tabWidthSetting
                    );
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
        if (inputEvent instanceof ResizeEvent) {
            cancelDocumentPointerInteraction();
            cancelFieldMouseSelection();
            tabBarColumns = -1;
            host.helpScrollState = new ListView.State(-1, host.helpScrollState.firstVisibleIndex());
            openFileTree.adopt(openFileTree.state().cancelPointerInteraction());
            topMenu.adopt(topMenu.state().cancelPointerInteraction());
            return;
        }
        if (inputEvent instanceof PointerEvent pointer && handleCapturedFieldPointer(pointer)) {
            return;
        }
        if (!(inputEvent instanceof PointerEvent pointer)
            || pointer.kind() == PointerEvent.Kind.PRESS) {
            cancelFieldMouseSelection();
            cancelDocumentPointerInteraction();
        }
        if (inputEvent instanceof PointerEvent pointer
            && (editorScrollDrag != EditorView.ScrollAxis.NONE
                && Scrollbar.captures(pointer, new Scrollbar.Drag(
                    editorScrollDrag == EditorView.ScrollAxis.VERTICAL
                        ? Scrollbar.Axis.VERTICAL : Scrollbar.Axis.HORIZONTAL,
                    editorScrollDragThumbOffset
                ))
                || editorView.isMouseSelecting()
                    && (pointer.kind() == PointerEvent.Kind.RELEASE
                        || pointer.kind() == PointerEvent.Kind.DRAG
                            && pointer.button() == PointerEvent.Button.LEFT))) {
            handleDocumentPointer(pointer);
            eventLoop.invalidate();
            return;
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
        if (!saveAsMode && !confirmMode) {
            notification.clearErrorOnUserAction();
        }
        eventLoop.invalidate();
        if (modalState.isHelpHints() || modalState.isHelpLicenses() || modalState.isHelpAuthor()) {
            boolean pointerAllowed = !(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && uiSessionState.mouseModeAvailable;
            if (pointerAllowed) {
                HelpPage helpPage = host.activeHelpPage(
                    modalState.isHelpHints(),
                    modalState.isHelpLicenses(),
                    modalState.isHelpAuthor()
                );
                HelpOverlayInputRouter.Result helpInput = host.routeHelpOverlayInput(
                    rows, cols, helpPage, inputEvent
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
                inputEvent
            );
            SaveAsBarView.Action fieldAction = saveAsInputResult.action();
            if (fieldAction == SaveAsBarView.Action.PASTE_REJECTED) {
                notification.error("Paste is too large and was not inserted");
                return;
            }
            if (fieldAction == SaveAsBarView.Action.VALUE_CHANGED) {
                if (!filenameBefore.equals(saveAsInput.text())) {
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
                TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS
            );
            openFileTree.adopt(openInput.state());
            if (inputEvent instanceof PointerEvent && openInput.consumed()) {
                editorView.cancelMouseSelection();
            }
            if (openInput.intent() != null) {
                TerminalEditorApp.OpenFileApplyResult applied = host.applyOpenFileIntent(
                    openInput.intent(),
                    openFileTree,
                    tabs,
                    activeTabIndex,
                    notification
                );
                openFileMode = applied.openFileMode();
                if (applied.activeTabIndex() != activeTabIndex) {
                    sessionContext.activate(applied.activeTabIndex());
                }
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
                        inputEvent
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
                uiSessionState.searchEditorFocused = DialogFocusTraversal.advance(
                    host.searchFocusManager, searchBarPlacement.focusScope(),
                    uiSessionState.searchEditorFocused, contentRows > 0 && contentCols > 0,
                    (KeyStroke) inputEvent
                );
                editorView.cancelMouseSelection();
                return;
            }
            SearchInputRouter.Result searchInput = !uiSessionState.searchEditorFocused
                || inputEvent instanceof PointerEvent
                ? SearchInputRouter.route(host.searchFocusManager, searchBarPlacement, inputEvent)
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
            if (searchAction == SearchBarView.Action.CLOSE_REQUESTED) {
                uiSessionState.searchMode = false;
                host.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
                return;
            }
            if (searchAction == SearchBarView.Action.PASTE_REJECTED) {
                notification.error("Paste is too large and was not inserted");
                return;
            }
            SearchFocus toggleTarget = TerminalEditorApp.toggleTarget(searchAction);
            if (toggleTarget != null) {
                searchUi = SearchController.toggleFocusedSearchOption(searchUi, toggleTarget);
                return;
            }
            if (searchAction == SearchBarView.Action.QUERY_CHANGED
                || searchAction == SearchBarView.Action.REPLACEMENT_CHANGED) {
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
                if (uiSessionState.readingModeSetting) {
                    notification.warning("Reading mode: editing is disabled");
                } else {
                    engine.insertInput(paste.text());
                    viewportController.onEditorTextInteraction();
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
        if (uiSessionState.searchMode && !uiSessionState.searchEditorFocused
            && TerminalEditorApp.isHistoryCommand(key.command())) {
            return;
        }
        if (TerminalEditorApp.searchOwnsRawInput(uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen())
            && uiSessionState.searchEditorFocused
            && key.command() == TuinkCommand.UI_ESCAPE) {
            uiSessionState.searchMode = false;
            host.searchFocusManager.deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return;
        }
        boolean searchPanelFocused = uiSessionState.searchMode && !uiSessionState.searchEditorFocused;
        boolean searchOperation = TerminalEditorApp.isSearchOperation(key.command()) && TerminalEditorApp.searchOwnsCommand(
            uiSessionState.searchMode, uiSessionState.searchEditorFocused,
            routedMenuCommand != null, key.command()
        );
        if (!searchOperation && (!searchPanelFocused || !TerminalEditorApp.isLocalSearchCommand(key.command()))) {
            viewportController.onInput(key, engine.hasSelection(), !uiSessionState.readingModeSetting);
        }
        if (searchPanelFocused && (key.command() == TuinkCommand.CURSOR_PAGE_UP
            || key.command() == TuinkCommand.CURSOR_PAGE_DOWN)) {
            viewportController.onManualVerticalPan();
        }

        if (key.command() == null && key.event() instanceof PointerEvent mouse) {
            if (TerminalEditorApp.modalInputKind(modalState, openFileMode) == ModalInputPolicy.Kind.NONE) {
                handleDocumentPointer(mouse);
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
                    int restoredCount = host.restoreRecoveredTabs(
                        startupRecoveryEntries,
                        tabs,
                        sessionContext,
                        recoveryAutosave,
                        System.nanoTime()
                    );
                    startupRecoveryEntries.clear();
                    if (restoredCount > 0) {
                        activeTabIndex = sessionContext.activeTabIndex();
                        state = sessionContext.state();
                        openFileTree.setRoot(host.initialOpenRoot(state));
                        notification.info("Recovered " + restoredCount + " tab(s)");
                    } else {
                        notification.info("No recovery snapshots restored");
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
                }
                modalState = UiModalState.none();
                return;
            }
        }

        if (modalState.isSaveAs()) {
            UiModalState saveAsState = modalState;
            String saveAsDraftBefore = saveAsInput.text();
            SaveFlowController.SaveAsResult saveAsResult = SaveFlowController.handleSaveAsInput(
                key,
                modalState,
                saveAsInput.text(),
                state.getCurrentFile(),
                state.getSaveDisposition(),
                host::validateSaveTarget,
                path -> Files.exists(path),
                host::isReadOnlyFile,
                host::isSameFilePath,
                host::suggestWritableName
            );
            if (saveAsResult.consumed()) {
                modalState = saveAsResult.modalState();
                if (saveAsResult.clearNotification()) {
                    notification.clear();
                }
                if (saveAsResult.errorMessage() != null) {
                    notification.error(saveAsResult.errorMessage());
                    focusFirstBlockingControl();
                }
                host.applySaveAsTextInputEffect(saveAsInput, saveAsResult.textInputEffect());
                if (saveAsResult.modalState().isSaveAs()) {
                    saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsInput.text());
                } else {
                    saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsDraftBefore);
                }
                if (saveAsResult.saveNowPath() != null) {
                    try {
                        engine.saveAs(
                            saveAsResult.saveNowPath(),
                            saveAsResult.saveDisposition()
                        );
                        renderPlanBuilder = new RenderPlanBuilder(
                            host.resolveHighlighter(state.getCurrentFile()),
                            engine.textLayout()
                        );
                        sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
                        saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                        notification.success(host.savedMessage(state));
                        saveAsInput.clear();
                        TerminalEditorApp.SaveFlowProgress progress = host.continueAfterSuccessfulSave(
                            saveAsState,
                            sessionContext,
                            tabs,
                            saveAsInput,
                            saveAsSuggestions,
                            notification
                        );
                        running = progress.running();
                        modalState = progress.modalState();
                    } catch (IOException e) {
                        modalState = saveAsState;
                        notification.error(host.saveErrorMessage(e));
                        focusFirstBlockingControl();
                    }
                }
                return;
            }
        }

        if (modalState.isSaveOverwriteConfirm()) {
            UiModalState overwriteState = modalState;
            if (routedConfirmDecision != null) {
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    try {
                        engine.saveAs(
                            modalState.pendingOverwritePath(),
                            SaveDisposition.REPLACE_EXISTING
                        );
                        renderPlanBuilder = new RenderPlanBuilder(
                            host.resolveHighlighter(state.getCurrentFile()),
                            engine.textLayout()
                        );
                        sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
                        saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                        notification.success(host.savedMessage(state));
                        saveAsInput.clear();
                        TerminalEditorApp.SaveFlowProgress progress = host.continueAfterSuccessfulSave(
                            overwriteState,
                            sessionContext,
                            tabs,
                            saveAsInput,
                            saveAsSuggestions,
                            notification
                        );
                        running = progress.running();
                        modalState = progress.modalState();
                    } catch (IOException e) {
                        notification.error(host.saveErrorMessage(e));
                    }
                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                    modalState = modalState.backToSaveAs();
                    notification.clear();
                } else {
                    modalState = UiModalState.none();
                    notification.clear();
                }
                return;
            }
        }

        if (modalState.isQuitConfirm()) {
            if (routedConfirmDecision != null) {
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    TerminalEditorApp.SaveFlowProgress progress = host.continueQuitSaveFlow(
                        sessionContext,
                        tabs,
                        saveAsInput,
                        saveAsSuggestions,
                        notification
                    );
                    running = progress.running();
                    modalState = progress.modalState();
                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                    running = false;
                    modalState = UiModalState.none();
                } else {
                    modalState = UiModalState.none();
                }
                return;
            }
        }

        if (modalState.isCloseTabConfirm()) {
            if (routedConfirmDecision != null) {
                int closeIndex = modalState.pendingCloseTabIndex();
                if (closeIndex < 0 || closeIndex >= tabs.size()) {
                    modalState = UiModalState.none();
                    return;
                }
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    TerminalEditorApp.EditorTab pendingTab = tabs.get(closeIndex);
                    if (host.requiresSaveAs(pendingTab)) {
                        sessionContext.activate(closeIndex);
                        host.prepareSaveAs(pendingTab, saveAsInput, saveAsSuggestions, notification);
                        modalState = UiModalState.saveAsForCloseTab(closeIndex);
                    } else if (host.saveDirtyTab(pendingTab, notification, saveAsSuggestions)) {
                        sessionContext.activate(host.closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions));
                        modalState = UiModalState.none();
                    }
                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                    sessionContext.activate(host.closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions));
                    modalState = UiModalState.none();
                } else {
                    modalState = UiModalState.none();
                }
                return;
            }
        }

        if (TerminalEditorApp.searchOwnsCommand(
            uiSessionState.searchMode,
            uiSessionState.searchEditorFocused,
            routedMenuCommand != null,
            key.command()
        )) {
            TerminalEditorApp.SearchModeResult searchModeResult = host.handleSearchModeInput(
                key,
                uiSessionState,
                searchUi,
                searchQueryInput,
                searchReplaceInput,
                searchBarPlacement,
                tabs,
                activeTabIndex,
                model,
                state,
                engine,
                renderPlanBuilder,
                contentRows,
                contentCols,
                running,
                modalState,
                saveAsInput,
                saveAsSuggestions,
                notification,
                openFileMode,
                openFileTree,
                openFileFocusState.focus(),
                topMenu,
                terminal::setMouseTracking,
                renderer
            );
            if (searchModeResult.consumed()) {
                searchUi = searchModeResult.searchUi();
                if (TerminalEditorApp.isSearchOperation(key.command())
                    && (!uiSessionState.readingModeSetting || !key.command().editsDocumentText())) {
                    tabs.get(searchModeResult.activeTabIndex()).viewportController().onEditorTextInteraction();
                }
                if (searchModeResult.activeTabIndex() != activeTabIndex) {
                    sessionContext.activate(searchModeResult.activeTabIndex());
                }
                running = searchModeResult.running();
                modalState = searchModeResult.modalState();
                openFileMode = searchModeResult.openFileMode();
                openFileFocusState.focus(searchModeResult.openFileFocus());
                modalState = TerminalEditorApp.consumeRequestedHelpModal(uiSessionState, modalState);
                return;
            }
        }

        if (key.command() == null && key.event() instanceof TextInput charInput) {
            if (uiSessionState.readingModeSetting) {
                notification.warning("Reading mode: editing is disabled");
            } else {
                engine.insertInput(charInput.text());
            }
            return;
        }

        TuinkCommand action = key.command();
        InputActionDispatcher.DispatchResult dispatchResult = InputActionDispatcher.dispatch(
            action,
            uiSessionState,
            new InputActionDispatcher.DispatchContext(
                engine,
                state,
                topMenu,
                terminal::setMouseTracking,
                contentRows,
                contentCols
            )
        );
        if (dispatchResult.handled()) {
            if (action == TuinkCommand.SEARCH_OPEN) {
                String selectedQuery = host.selectionQueryForCtrlF(model, state);
                if (selectedQuery != null) {
                    searchQueryInput.set(selectedQuery);
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
            state.getCurrentFile(),
            state.getCurrentFile() != null && host.isReadOnlyFile(state.getCurrentFile())
        );
        TerminalEditorApp.EditorActionApplyResult editorActionApplyResult = host.applyEditorActionResult(
            editorAction,
            sessionContext,
            tabs,
            activeTabIndex,
            state,
            engine,
            notification,
            topMenu,
            openFileTree,
            openFileMode,
            openFileFocusState.focus(),
            saveAsInput,
            saveAsSuggestions,
            renderer
        );
        running = editorActionApplyResult.running();
        modalState = editorActionApplyResult.modalState();
        openFileMode = editorActionApplyResult.openFileMode();
        openFileFocusState.focus(editorActionApplyResult.openFileFocus());
    }
}

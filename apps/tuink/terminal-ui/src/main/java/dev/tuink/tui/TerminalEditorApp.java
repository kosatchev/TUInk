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
import java.util.function.Supplier;

/**
 * JLine terminal application wiring core + render + highlighter.
 */
public class TerminalEditorApp {
    private static final String DEFAULT_FILE_NAME = "untitled.txt";
    private static final Object SAVE_AS_SCOPE_ID = new Object();
    private static final Object SEARCH_SCOPE_ID = new Object();
    private static final Object OPEN_FILE_SCOPE_ID = new Object();
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    private static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final int PROFILE_LOG_EVERY_FRAMES = 30;
    private static final int AUTO_SELECTION_HIGHLIGHT_MIN_CHARS = 2;
    private static final int AUTO_SELECTION_HIGHLIGHT_MAX_CHARS = 128;
    private static final int CTRL_F_SELECTION_MAX_CHARS = 256;
    private static final long RECOVERY_AUTOSAVE_DEBOUNCE_NANOS = 2_000_000_000L;
    private static final long RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS = 5_000_000_000L;
    private final EditorClipboard clipboard = new EditorClipboard();
    private final DialogAttention modalAttention = new DialogAttention();
    private UiStyles.Theme theme = UiStyles.Theme.CYAN;
    private UiStyles.Palette styles = UiStyles.forTheme(theme);
    private final Theme colorOverlayWidgetTheme = key -> resolveOverlayWidgetStyle(key, true);
    private final Theme monoOverlayWidgetTheme = key -> resolveOverlayWidgetStyle(key, false);
    private final FocusManager<HelpOverlayInputRouter.Target> helpFocusManager = new FocusManager<>();
    private final Theme confirmWidgetTheme = this::resolveConfirmWidgetStyle;
    private final FocusManager<ConfirmChoice> confirmFocusManager = new FocusManager<>();
    private final Theme saveAsWidgetTheme = this::resolveSaveAsWidgetStyle;
    private final FocusManager<SaveAsBarView.Target> saveAsFocusManager = new FocusManager<>();
    private final Theme searchWidgetTheme = this::resolveSearchWidgetStyle;
    private final FocusManager<SearchFocus> searchFocusManager = new FocusManager<>();
    private final Theme colorTabBarWidgetTheme = key -> resolveTabBarWidgetStyle(key, true);
    private final Theme monoTabBarWidgetTheme = key -> resolveTabBarWidgetStyle(key, false);
    private final Theme colorTopMenuWidgetTheme = key -> resolveTopMenuWidgetStyle(key, true);
    private final Theme monoTopMenuWidgetTheme = key -> resolveTopMenuWidgetStyle(key, false);
    private final Theme colorFileTreeWidgetTheme = key -> resolveFileTreeWidgetStyle(key, true);
    private final Theme monoFileTreeWidgetTheme = key -> resolveFileTreeWidgetStyle(key, false);
    private HelpOverlayPlacement helpOverlayCache;
    private boolean profileEnabled = false;
    private final FrameProfiler frameProfiler = new FrameProfiler();
    private FrameComposition.OpenTreeDiffKey lastOpenTreeDiffKey;

    public static void main(String[] args) throws Exception {
        int exitCode = launch(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Runs the frontend and returns zero on success or two for invalid CLI arguments. */
    public static int launch(String[] args) throws Exception {
        return new TerminalEditorApp().run(args);
    }

    private int run(String[] args) throws Exception {
        TuiCliOptions cli;
        try {
            cli = TuiCliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            return 2;
        }
        if (cli.showHelp()) {
            printUsage(System.out);
            return 0;
        }
        try (RecoveryStore recoveryStore = new RecoveryStore()) {
            run(cli, recoveryStore);
        } finally {
            frameProfiler.close();
        }
        return 0;
    }

    private void run(TuiCliOptions cli, RecoveryStore recoveryStore) throws Exception {
        profileEnabled = cli.profileEnabled();
        if (cli.profileFile() != null) {
            PrintWriter profileWriter = ProfileLog.open(cli.profileFile(), cli.file());
            frameProfiler.setOutput(profileWriter, true);
        }

        AppSettings loadedSettings = AppSettings.defaults();
        boolean settingsFileAccepted = true;
        try {
            loadedSettings = AppSettingsStore.load();
        } catch (IOException e) {
            settingsFileAccepted = false;
            System.err.println("Warning: failed to load settings from " + AppSettingsStore.path() + ": " + e.getMessage());
        }

        UiStyles.Theme initialTheme = cli.themeOverride() != null ? cli.themeOverride() : loadedSettings.theme();
        applyTheme(initialTheme);
        List<EditorTab> tabs = new ArrayList<>();
        tabs.add(newEditorTab());
        SessionContext sessionContext = new SessionContext(tabs, 0);
        int activeTabIndex = sessionContext.activeTabIndex();
        TextModel model = sessionContext.model();
        EditorState state = sessionContext.state();
        EditorEngine engine = sessionContext.engine();
        RenderPlanBuilder renderPlanBuilder = sessionContext.renderPlanBuilder();
        RenderRasterizer renderer = new RenderRasterizer(1, theme);
        EditorView editorView = new EditorView();

        NotificationState notification = new NotificationState();
        UiModalState modalState = UiModalState.none();
        RecoveryAutosaveManager recoveryAutosave = new RecoveryAutosaveManager(recoveryStore);
        List<RecoveryStore.Entry> startupRecoveryEntries = new ArrayList<>();
        TextField saveAsInput = new TextField();
        SaveAsSuggestionStore saveAsSuggestions = new SaveAsSuggestionStore();
        UiSessionState uiSessionState = UiSessionState.from(
            false,
            loadedSettings.showTabBar(),
            loadedSettings.wrapLines(),
            loadedSettings.wrapWords(),
            cli.readModeOverride() != null ? cli.readModeOverride() : loadedSettings.readingMode(),
            loadedSettings.confirmOnClose(),
            cli.mouseEnabledOverride() != null ? cli.mouseEnabledOverride() : loadedSettings.mouseMode(),
            loadedSettings.highlightActiveGutter(),
            loadedSettings.smartHomeEnd(),
            loadedSettings.autoIndent(),
            loadedSettings.autoPairs(),
            loadedSettings.autoList(),
            loadedSettings.visibleWhitespace(),
            loadedSettings.syntaxHighlighting(),
            loadedSettings.rainbowBrackets(),
            loadedSettings.autoSelectionHighlight(),
            loadedSettings.gutterDiffMode(),
            loadedSettings.tabWidth(),
            loadedSettings.tabInsertMode(),
            false,
            false,
            false
        );
        TextField searchQueryInput = new TextField();
        TextField searchReplaceInput = new TextField();
        SearchController.SearchUiOptions searchUi = SearchController.SearchUiOptions.DEFAULT;
        TopMenuController topMenu = new TopMenuController();
        Path initialOpenFileRoot = initialOpenRoot(state);
        try {
            RecoveryStore.LoadResult recoveryLoad = recoveryStore.loadAll();
            startupRecoveryEntries.addAll(recoveryLoad.entries());
            if (!recoveryLoad.skipped().isEmpty()) {
                String ids = recoveryLoad.skipped().stream()
                    .map(RecoveryStore.SkippedSnapshot::id)
                    .collect(java.util.stream.Collectors.joining(", "));
                notification.warning(
                    "Skipped " + recoveryLoad.skipped().size()
                        + " incompatible recovery snapshot(s): " + ids
                );
                System.err.println(
                    "Warning: incompatible recovery snapshots were left unchanged in "
                        + recoveryStore.directory() + ": " + recoveryLoad.skipped()
                );
            }
            if (!startupRecoveryEntries.isEmpty()) {
                modalState = UiModalState.recoveryConfirm();
            }
        } catch (IOException e) {
            System.err.println(
                "Warning: failed to load recovery snapshots from " + recoveryStore.directory() + ": " + e.getMessage()
            );
        }

        if (cli.file() != null) {
            Path file = cli.file();
            try {
                engine.open(file);
                renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(file), engine.textLayout());
                sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
            } catch (IOException e) {
                notification.error("Open failed: " + e.getMessage());
            }
        }

        try (
            OpenFileTreeController openFileTree = new OpenFileTreeController(
                initialOpenFileRoot,
                true,
                true
            );
            TerminalRuntime terminal = TerminalRuntime.open(
                new TerminalSession.Options(
                    cli.altScreenEnabled(),
                    uiSessionState.mouseModeSetting,
                    shouldForceExecTerminalProvider()
                ),
                cli.colorEnabled()
            )
        ) {
            TerminalCapabilities effectiveCapabilities = terminal.capabilities();
            boolean colorEnabled = effectiveCapabilities.colorEnabled();
            uiSessionState.mouseModeAvailable = effectiveCapabilities.mouseTracking();

            ScreenPresenter screenPresenter = terminal.screenPresenter();
            try {
                CommandRouter commandRouter = new CommandRouter();
                TuiEventLoop<TuiInputEvent> eventLoop = terminal.eventLoop();
                RenderFrameController renderFrameController = new RenderFrameController(
                    renderer,
                    editorView,
                    colorEnabled,
                    screenPresenter
                );
                UiModalState initialModalState = modalState;
                SearchController.SearchUiOptions initialSearchUi = searchUi;

                final class FrontendSession implements TuiApplicationRunner.Frontend<TuiInputEvent> {
                    private boolean running = true;
                    private UiModalState modalState = initialModalState;
                    private SearchController.SearchUiOptions searchUi = initialSearchUi;
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
                    private final TabMatchCache tabMatchCache = new TabMatchCache();
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
                        Object nextBlockingOwner = modalInputOwner(modalState, openFileMode);
                        if (!Objects.equals(blockingFocusOwner, nextBlockingOwner)) {
                            blockingFocusOwner = nextBlockingOwner;
                            blockingContentFocused = false;
                            cancelDocumentPointerInteraction();
                            cancelFieldMouseSelection();
                        }
                        if (modalAttention.synchronize(modalInputOwner(modalState, openFileMode), System.nanoTime())) {
                            eventLoop.invalidate();
                        }
                        List<TabSession> recoveryTabSessions = toTabSessions(tabs);
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
                        pendingOverwritePath = modalState.pendingOverwritePath();
                        pendingCloseTabIndex = modalState.pendingCloseTabIndex();
                        confirmMode = recoveryConfirmMode
                            || saveOverwriteConfirmMode
                            || quitConfirmMode
                            || closeTabConfirmMode;
                        if (!uiSessionState.searchMode) {
                            uiSessionState.searchEditorFocused = false;
                            searchFocusManager.deactivate(SEARCH_SCOPE_ID);
                            searchBarPlacement = null;
                        }
                        if (!saveAsMode) {
                            saveAsFocusManager.deactivate(SAVE_AS_SCOPE_ID);
                            saveAsBarPlacement = null;
                        }
                        if (confirmMode) {
                            if (confirmPromptState != modalState) {
                                deactivateConfirmFocus();
                                confirmPromptState = modalState;
                                confirmScopeId = new Object();
                                confirmBarPlacement = null;
                            }
                        } else {
                            deactivateConfirmFocus();
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
                        if (profileEnabled) {
                            frameProfiler.beginFrame();
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
                                List<EditorTab> searchCountTabs = searchAllTabs ? tabs : null;
                                long searchSourceIdentity = searchSourceIdentity(
                                    model,
                                    tabs,
                                    searchAllTabs
                                );
                                long searchSourceRevision = searchSourceRevision(
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
                                    searchMatchCount = countSearchMatches(
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
                        if (profileEnabled) {
                            frameProfiler.searchNanos = frameProfiler.stopwatchNanos();
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
                        String searchSummary = buildSearchSummary(
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
                        SearchBarView activeSearchBar = searchOwnsInteractiveRow(modalState)
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
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1
                            );
                        } else if (saveOverwriteConfirmMode) {
                            confirmBar = ConfirmBarView.build(
                                buildOverwritePrompt(pendingOverwritePath),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                1
                            );
                        } else if (recoveryConfirmMode) {
                            confirmBar = ConfirmBarView.buildRecovery(
                                buildRecoveryPrompt(startupRecoveryEntries.size()),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                1
                            );
                        } else if (quitConfirmMode) {
                            confirmBar = buildQuitConfirmBar(tabs, cols);
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                1
                            );
                        } else if (closeTabConfirmMode) {
                            confirmBar = buildCloseTabConfirmBar(
                                tabs,
                                pendingCloseTabIndex,
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                1
                            );
                        } else if (activeSearchBar != null) {
                            inlinePromptBar = InlinePromptBar.of(
                                activeSearchBar.text(),
                                EditorStyleAdapter.toEditor(styles.searchBar()),
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
                                SAVE_AS_SCOPE_ID,
                                rows - 1 - inlinePromptBar.height(),
                                promptInteractionClip
                            );
                            saveAsBarPlacement.activate(saveAsFocusManager);
                        }
                        if (confirmBar != null) {
                            confirmBarPlacement = confirmBar.place(
                                confirmScopeId,
                                rows - 1 - inlinePromptBar.height(),
                                promptInteractionClip
                            );
                            confirmBarPlacement.activate(confirmFocusManager);
                        }
                        if (activeSearchBar != null) {
                            searchBarPlacement = activeSearchBar.place(
                                SEARCH_SCOPE_ID,
                                rows - 1 - inlinePromptBar.height(),
                                promptInteractionClip
                            );
                            searchBarPlacement.activate(searchFocusManager);
                            if (uiSessionState.searchEditorFocused) {
                                searchFocusManager.clear(searchBarPlacement.focusScope());
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
                            searchFocusManager.next(searchBarPlacement.focusScope());
                        }
                        boolean[] tabMatchFlags = resolveTabMatchFlags(
                            tabs,
                            uiSessionState.searchMode,
                            searchQuery,
                            searchOptions,
                            tabMatchCache
                        );
                        List<TabBarView.TabItem> tabItems = toTabItems(tabs, tabMatchFlags);
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
                            toTopMenuTabs(tabs, tabMatchFlags),
                            activeTabIndex,
                            theme.label(),
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
                                OPEN_FILE_SCOPE_ID, openFileLayout, openFileTreePlacement,
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
                            effectiveSyntaxHighlighting(uiSessionState.syntaxHighlightingSetting)
                        );
                        renderPlanBuilder.setCommentSyntaxProfile(
                            LanguageProfiles.resolveCommentSyntax(state.getCurrentFile())
                        );
                        renderPlanBuilder.setRainbowBracketsEnabled(uiSessionState.rainbowBracketsSetting);
                        renderPlanBuilder.setTabWidth(uiSessionState.tabWidthSetting);
                        renderPlanBuilder.setDecorationStyles(
                            styles.renderSelection(),
                            styles.renderSearchMatch(),
                            styles.renderCurrentSearchMatch(),
                            styles.renderBracketMatch(),
                            styles.renderQuoteMatch(),
                            styles.renderUnmatchedBracket(),
                            styles.renderRainbowBrackets()
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
                        String bottomBar = buildBottomBar(
                            state,
                            model,
                            cols,
                            uiSessionState.readingModeSetting,
                            activeTabIndex,
                            tabs.size(),
                            searchSummary
                        );
                        modalInputBounds = blockingSurface(rows, cols, contentTopRow, inlinePromptBar.height(),
                            saveAsMode || confirmMode, activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode),
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
                                activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode),
                                modalAttention.highlighted(),
                                blockingContentFocused
                            ),
                            uiSessionState.wrapLinesSetting,
                            uiSessionState.wrapWordsSetting,
                            frameTicket.fullRedraw()
                        );
                        modalInputBounds = blockingSurface(composition);
                        RenderFrameResult frameResult = renderFrameController.renderFrameIfNeeded(composition);
                        plan = frameResult.plan();
                        return frameResult.rendered();
                    }

                    @Override
                    public void frameEnded(boolean rendered) {
                        if (profileEnabled) {
                            frameProfiler.endFrame(rendered);
                        }
                    }

                    @Override
                    public long nanosUntilWakeup(long nowNanos) {
                        return earlierDeadline(
                            notification.nanosUntilExpire(nowNanos),
                            modalAttention.nanosUntilChange(nowNanos)
                        );
                    }

                    private HelpOverlayPlacement currentHelpOverlay() {
                        HelpPage page = activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode);
                        return page == null ? null : helpOverlayPlacement(rows, cols, page);
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
                        boolean searchCapture = searchOwnsRawInput(
                            uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen()
                        ) && searchBarPlacement != null && searchBarPlacement.captures(pointer);
                        if (!saveAsCapture && !searchCapture) {
                            return false;
                        }
                        if (!uiSessionState.mouseModeSetting || !uiSessionState.mouseModeAvailable) {
                            cancelFieldMouseSelection();
                        } else if (saveAsCapture) {
                            SaveAsInputRouter.route(saveAsFocusManager, saveAsBarPlacement, pointer);
                        } else {
                            SearchInputRouter.route(searchFocusManager, searchBarPlacement, pointer);
                        }
                        eventLoop.invalidate();
                        return true;
                    }

                    private boolean documentPointerHit(PointerEvent mouse) {
                        return new Rect(0, contentTopRow, cols, editorPointerRows)
                            .contains(mouse.column(), mouse.row())
                            && (modalInputKind(modalState, openFileMode) == ModalInputPolicy.Kind.NONE
                                || blockingContentAvailable() && !modalInputBounds.contains(mouse.column(), mouse.row()));
                    }

                    private boolean blockingContentAvailable() {
                        return DialogCommandPolicy.mode(modalInputKind(modalState, openFileMode)).permitsContentFocus()
                            && contentRows > 0 && contentCols > 0
                            && !modalInputBounds.contains(new Rect(contentLeft, contentTopRow, contentCols, contentRows));
                    }

                    private void clearBlockingControlFocus() {
                        if (saveAsBarPlacement != null) {
                            saveAsFocusManager.clear(saveAsBarPlacement.focusScope());
                        }
                        if (confirmBarPlacement != null) {
                            confirmFocusManager.clear(confirmBarPlacement.focusScope());
                        }
                        HelpOverlayPlacement help = currentHelpOverlay();
                        if (help != null) {
                            helpFocusManager.clear(help.focusScope());
                        }
                    }

                    private void focusDocument() {
                        if (modalInputKind(modalState, openFileMode) != ModalInputPolicy.Kind.NONE) {
                            blockingContentFocused = true;
                            clearBlockingControlFocus();
                        } else if (uiSessionState.searchMode) {
                            uiSessionState.searchEditorFocused = true;
                            if (searchBarPlacement != null) {
                                searchFocusManager.clear(searchBarPlacement.focusScope());
                            }
                        }
                    }

                    private void focusFirstBlockingControl() {
                        blockingContentFocused = false;
                        if (saveAsMode && saveAsBarPlacement != null) {
                            DialogFocusTraversal.focusFirst(saveAsFocusManager, saveAsBarPlacement.focusScope());
                        } else if (confirmMode && confirmBarPlacement != null) {
                            DialogFocusTraversal.focusFirst(confirmFocusManager, confirmBarPlacement.focusScope());
                        } else if (openFileMode) {
                            if (openFileDialogPlacement != null) {
                                openFileTree.adopt(openFileTree.state().cancelPointerInteraction());
                                openFileDialogPlacement.focusFirst(openFileFocusState);
                            }
                        } else {
                            HelpOverlayPlacement help = currentHelpOverlay();
                            if (help != null) {
                                DialogFocusTraversal.focusFirst(helpFocusManager, help.focusScope());
                            }
                        }
                    }

                    private void advanceBlockingFocus(KeyStroke stroke) {
                        boolean available = blockingContentAvailable();
                        if (saveAsMode && saveAsBarPlacement != null) {
                            blockingContentFocused = DialogFocusTraversal.advance(saveAsFocusManager,
                                saveAsBarPlacement.focusScope(), blockingContentFocused, available, stroke);
                        } else if (confirmMode && confirmBarPlacement != null) {
                            blockingContentFocused = DialogFocusTraversal.advance(confirmFocusManager,
                                confirmBarPlacement.focusScope(), blockingContentFocused, available, stroke);
                        } else if (openFileMode) {
                            if (openFileDialogPlacement != null) {
                                OpenFileInputRouter.Result result = OpenFileInputRouter.route(
                                    openFileDialogPlacement, openFileFocusState, stroke, System.nanoTime(),
                                    OPEN_DIALOG_DOUBLE_CLICK_NANOS);
                                openFileTree.adopt(result.state());
                            }
                            blockingContentFocused = false;
                        } else {
                            HelpOverlayPlacement help = currentHelpOverlay();
                            if (help != null) {
                                blockingContentFocused = DialogFocusTraversal.advance(helpFocusManager,
                                    help.focusScope(), blockingContentFocused, available, stroke);
                            }
                        }
                    }

                    private void rejectDialogInput() {
                        DialogInputPolicy.apply(DialogInputPolicy.Decision.BLOCK, this::focusFirstBlockingControl,
                            () -> modalAttention.request(modalInputOwner(modalState, openFileMode), System.nanoTime()));
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
                            Scrollbar.Drag dialogDrag = kind == ModalInputPolicy.Kind.OPEN_FILE
                                ? openFileTree.state().widgetState().drag() : null;
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
                                && belongsToTabChrome(event, tabBarPlacement.bounds())) {
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
                        DialogInputPolicy.Decision decision = DialogInputPolicy.decide(mode,
                            DialogCommandPolicy.target(kind, openFileFocusState.focus(),
                                saveAsFocusManager.focused().orElse(null), blockingContentFocused), action);
                        if (decision == DialogInputPolicy.Decision.BLOCK) {
                            rejectDialogInput();
                            return true;
                        }
                        if (decision.consumed()) {
                            return true;
                        }
                        if (blockingContentFocused) {
                            EditorInput input = resolveEditorInput(commandRouter, event,
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
                            if (saveAsFocusManager.focused().filter(SaveAsBarView.Target.FIELD::equals).isPresent()) {
                                String filenameBefore = saveAsInput.text();
                                FieldClipboard.handle(command, saveAsInput, clipboard);
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
                                applyWheelVerticalScroll(
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
                                applyWheelVerticalScroll(
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
                                        shouldExtendEditorSelection(mouse)
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
                        ModalInputPolicy.Kind blocker = modalInputKind(modalState, openFileMode);
                        TuinkCommand physicalCommand = inputEvent instanceof KeyStroke stroke
                            ? commandRouter.invocationFor(stroke)
                                .flatMap(invocation -> TuinkCommand.fromId(invocation.id())).orElse(null)
                            : null;
                        if (routeBlockingInput(inputEvent, physicalCommand, blocker)) {
                            return;
                        }
                        if (searchOwnsRawInput(uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen())
                            && !uiSessionState.searchEditorFocused
                            && DialogInputPolicy.decide(DialogMode.NON_BLOCKING,
                                SearchController.isSearchFieldFocus(searchFocusManager.focused().orElse(null))
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
                                HelpPage helpPage = activeHelpPage(
                                    modalState.isHelpHints(),
                                    modalState.isHelpLicenses(),
                                    modalState.isHelpAuthor()
                                );
                                HelpOverlayPlacement overlay = helpOverlayPlacement(rows, cols, helpPage);
                                HelpOverlayInputRouter.Result helpInput = HelpOverlayInputRouter.route(
                                    helpFocusManager,
                                    overlay.focusScope(),
                                    overlay.frame(),
                                    inputEvent
                                );
                                if (helpInput.closeRequested()) {
                                    helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
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
                                saveAsFocusManager,
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
                                confirmFocusManager,
                                confirmBarPlacement,
                                confirmPolicy(modalState),
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
                                OPEN_DIALOG_DOUBLE_CLICK_NANOS
                            );
                            openFileTree.adopt(openInput.state());
                            if (inputEvent instanceof PointerEvent && openInput.consumed()) {
                                editorView.cancelMouseSelection();
                            }
                            if (openInput.intent() != null) {
                                OpenFileApplyResult applied = applyOpenFileIntent(
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
                        if (searchOwnsRawInput(
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
                                    searchFocusManager, searchBarPlacement.focusScope(),
                                    uiSessionState.searchEditorFocused, contentRows > 0 && contentCols > 0,
                                    (KeyStroke) inputEvent
                                );
                                editorView.cancelMouseSelection();
                                return;
                            }
                            SearchInputRouter.Result searchInput = !uiSessionState.searchEditorFocused
                                || inputEvent instanceof PointerEvent
                                ? SearchInputRouter.route(searchFocusManager, searchBarPlacement, inputEvent)
                                : new SearchInputRouter.Result(false, null);
                            if (inputEvent instanceof PointerEvent pointer
                                && pointer.kind() == PointerEvent.Kind.PRESS
                                && pointer.button() == PointerEvent.Button.LEFT
                                && searchInput.consumed()
                                && searchFocusManager.focused().isPresent()) {
                                uiSessionState.searchEditorFocused = false;
                            }
                            if (inputEvent instanceof PointerEvent && searchInput.consumed()) {
                                editorView.cancelMouseSelection();
                            }
                            SearchBarView.Action searchAction = searchInput.action();
                            if (searchAction == SearchBarView.Action.CLOSE_REQUESTED) {
                                uiSessionState.searchMode = false;
                                searchFocusManager.deactivate(SEARCH_SCOPE_ID);
                                return;
                            }
                            if (searchAction == SearchBarView.Action.PASTE_REJECTED) {
                                notification.error("Paste is too large and was not inserted");
                                return;
                            }
                            SearchFocus toggleTarget = toggleTarget(searchAction);
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
                        TabBarInputRouter.Policy tabPolicy = routedMenuCommand == null ? tabBarInputPolicy(
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
                                        closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions)
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
                        TuinkCommand routedSearchCommand = searchCommand(routedSearchAction);
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
                            : resolveEditorInput(
                                commandRouter,
                                inputEvent,
                                pendingCommand,
                                routedMenuResult,
                                searchOwnsRawInput(
                                    uiSessionState.searchMode,
                                    modalState,
                                    openFileMode,
                                    topMenu.isOpen(),
                                    routedMenuCommand != null
                                ) && !uiSessionState.searchEditorFocused,
                                currentSearchFocus()
                            );
                        if (key == null) {
                            return;
                        }
                        if (uiSessionState.searchMode && !uiSessionState.searchEditorFocused
                            && isHistoryCommand(key.command())) {
                            return;
                        }
                        if (searchOwnsRawInput(uiSessionState.searchMode, modalState, openFileMode, topMenu.isOpen())
                            && uiSessionState.searchEditorFocused
                            && key.command() == TuinkCommand.UI_ESCAPE) {
                            uiSessionState.searchMode = false;
                            searchFocusManager.deactivate(SEARCH_SCOPE_ID);
                            return;
                        }
                        boolean searchPanelFocused = uiSessionState.searchMode && !uiSessionState.searchEditorFocused;
                        boolean searchOperation = isSearchOperation(key.command()) && searchOwnsCommand(
                            uiSessionState.searchMode, uiSessionState.searchEditorFocused,
                            routedMenuCommand != null, key.command()
                        );
                        if (!searchOperation && (!searchPanelFocused || !isLocalSearchCommand(key.command()))) {
                            viewportController.onInput(key, engine.hasSelection(), !uiSessionState.readingModeSetting);
                        }
                        if (searchPanelFocused && (key.command() == TuinkCommand.CURSOR_PAGE_UP
                            || key.command() == TuinkCommand.CURSOR_PAGE_DOWN)) {
                            viewportController.onManualVerticalPan();
                        }

                        if (key.command() == null && key.event() instanceof PointerEvent mouse) {
                            if (modalInputKind(modalState, openFileMode) == ModalInputPolicy.Kind.NONE) {
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
                                    helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
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
                                helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
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
                                helpFocusManager.deactivate(HelpOverlayInputRouter.Scope.HELP);
                                modalState = UiModalState.none();
                            }
                            return;
                        }

                        if (modalState.isRecoveryConfirm()) {
                            if (routedConfirmDecision != null) {
                                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                                    int restoredCount = restoreRecoveredTabs(
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
                                        openFileTree.setRoot(initialOpenRoot(state));
                                        notification.info("Recovered " + restoredCount + " tab(s)");
                                    } else {
                                        notification.info("No recovery snapshots restored");
                                    }
                                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                                    RecoveryAutosaveManager.DeletionResult deletion =
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
                                TerminalEditorApp.this::validateSaveTarget,
                                path -> Files.exists(path),
                                TerminalEditorApp.this::isReadOnlyFile,
                                TerminalEditorApp.this::isSameFilePath,
                                TerminalEditorApp.this::suggestWritableName
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
                                applySaveAsTextInputEffect(saveAsInput, saveAsResult.textInputEffect());
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
                                            resolveHighlighter(state.getCurrentFile()),
                                            engine.textLayout()
                                        );
                                        sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
                                        saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                                        notification.success(savedMessage(state));
                                        saveAsInput.clear();
                                        SaveFlowProgress progress = continueAfterSuccessfulSave(
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
                                        notification.error(saveErrorMessage(e));
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
                                            resolveHighlighter(state.getCurrentFile()),
                                            engine.textLayout()
                                        );
                                        sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
                                        saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                                        notification.success(savedMessage(state));
                                        saveAsInput.clear();
                                        SaveFlowProgress progress = continueAfterSuccessfulSave(
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
                                        notification.error(saveErrorMessage(e));
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
                                    SaveFlowProgress progress = continueQuitSaveFlow(
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
                                    EditorTab pendingTab = tabs.get(closeIndex);
                                    if (requiresSaveAs(pendingTab)) {
                                        sessionContext.activate(closeIndex);
                                        prepareSaveAs(pendingTab, saveAsInput, saveAsSuggestions, notification);
                                        modalState = UiModalState.saveAsForCloseTab(closeIndex);
                                    } else if (saveDirtyTab(pendingTab, notification, saveAsSuggestions)) {
                                        sessionContext.activate(closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions));
                                        modalState = UiModalState.none();
                                    }
                                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                                    sessionContext.activate(closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions));
                                    modalState = UiModalState.none();
                                } else {
                                    modalState = UiModalState.none();
                                }
                                return;
                            }
                        }

                        if (searchOwnsCommand(
                            uiSessionState.searchMode,
                            uiSessionState.searchEditorFocused,
                            routedMenuCommand != null,
                            key.command()
                        )) {
                            SearchModeResult searchModeResult = handleSearchModeInput(
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
                                if (isSearchOperation(key.command())
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
                                modalState = consumeRequestedHelpModal(uiSessionState, modalState);
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
                                String selectedQuery = selectionQueryForCtrlF(model, state);
                                if (selectedQuery != null) {
                                    searchQueryInput.set(selectedQuery);
                                }
                                focusSearch(searchBarPlacement, SearchFocus.FIND);
                            }
                            modalState = consumeRequestedHelpModal(uiSessionState, modalState);
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
                            hasDirtyTabs(tabs),
                            state.isDirty(),
                            activeTabIndex,
                            state.getCurrentFile(),
                            state.getCurrentFile() != null && isReadOnlyFile(state.getCurrentFile())
                        );
                        EditorActionApplyResult editorActionApplyResult = applyEditorActionResult(
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

                runWithRecovery(
                    eventLoop,
                    new FrontendSession(),
                    recoveryAutosave,
                    () -> toTabSessions(tabs),
                    warning -> System.err.println("Warning: " + warning)
                );
            } finally {
                frameProfiler.close();
            }
        }

        AppSettings effectiveSettings = new AppSettings(
            uiSessionState.showTabBarSetting,
            uiSessionState.wrapLinesSetting,
            uiSessionState.wrapWordsSetting,
            uiSessionState.readingModeSetting,
            uiSessionState.confirmOnCloseSetting,
            uiSessionState.mouseModeSetting,
            uiSessionState.highlightActiveGutterSetting,
            uiSessionState.smartHomeEndSetting,
            uiSessionState.autoIndentSetting,
            uiSessionState.autoPairsSetting,
            uiSessionState.autoListSetting,
            uiSessionState.visibleWhitespaceSetting,
            uiSessionState.syntaxHighlightingSetting,
            uiSessionState.rainbowBracketsSetting,
            uiSessionState.autoSelectionHighlightSetting,
            uiSessionState.gutterDiffModeSetting,
            uiSessionState.tabWidthSetting,
            uiSessionState.tabInsertModeSetting,
            theme
        );
        if (settingsFileAccepted && !effectiveSettings.equals(loadedSettings)) {
            try {
                AppSettingsStore.save(effectiveSettings);
            } catch (IOException e) {
                System.err.println("Warning: failed to save settings to " + AppSettingsStore.path() + ": " + e.getMessage());
            }
        }
    }

    private EditorActionApplyResult applyEditorActionResult(
        EditorActionController.Result editorAction,
        SessionContext sessionContext,
        List<EditorTab> tabs,
        int activeTabIndex,
        EditorState state,
        EditorEngine engine,
        NotificationState notification,
        TopMenuController topMenu,
        OpenFileTreeController openFileTree,
        boolean openFileMode,
        OpenFileInputRouter.Focus openFileFocus,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        RenderRasterizer renderer
    ) {
        boolean running = editorAction.running();
        UiModalState modalState = editorAction.modalState();
        boolean nextOpenFileMode = openFileMode;
        OpenFileInputRouter.Focus nextOpenFileFocus = openFileFocus;

        if (editorAction.clearNotification()) {
            notification.clear();
        }
        if (editorAction.notificationError() != null) {
            notification.error(editorAction.notificationError());
        }
        if (editorAction.requestSaveCurrent()) {
            try {
                engine.save();
                saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                notification.success(savedMessage(state));
            } catch (IOException e) {
                notification.error(saveErrorMessage(e));
            }
        }
        if (editorAction.requestOpenFile()) {
            topMenu.close();
            nextOpenFileMode = true;
            openFileTree.setRoot(initialOpenRoot(state));
            nextOpenFileFocus = OpenFileInputRouter.Focus.LIST;
        }
        if (editorAction.requestToggleTheme()) {
            applyTheme(theme.next());
            renderer.setTheme(theme);
            notification.info("Theme: " + theme.label());
        }

        switch (editorAction.saveAsPreset()) {
            case NONE -> {
            }
            case DEFAULT_FILE_NAME -> saveAsInput.set(
                saveAsSuggestions.suggestionFor(
                    tabs.get(activeTabIndex).tabSession(),
                    state.getCurrentFile(),
                    DEFAULT_FILE_NAME
                )
            );
            case FROM_CURRENT_OR_DEFAULT -> {
                saveAsInput.set(
                    saveAsSuggestions.suggestionFor(
                        tabs.get(activeTabIndex).tabSession(),
                        state.getCurrentFile(),
                        DEFAULT_FILE_NAME
                    )
                );
            }
            case SUGGESTED_FROM_CURRENT -> {
                String suggestion = saveAsSuggestions.suggestionFor(
                    tabs.get(activeTabIndex).tabSession(),
                    null,
                    suggestWritableName(state.getCurrentFile())
                );
                saveAsInput.set(suggestion);
                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), suggestion);
            }
        }

        switch (editorAction.tabCommand()) {
            case NONE -> {
            }
            case NEW_TAB -> sessionContext.addNewTabAndActivate(newEditorTab());
            case NEXT_TAB -> {
                int nextIndex = switchTab(tabs, activeTabIndex, 1);
                sessionContext.activate(nextIndex);
            }
            case PREV_TAB -> {
                int nextIndex = switchTab(tabs, activeTabIndex, -1);
                sessionContext.activate(nextIndex);
            }
            case CLOSE_TAB -> sessionContext.activate(closeCurrentTab(tabs, activeTabIndex, saveAsSuggestions));
        }

        return new EditorActionApplyResult(
            running,
            modalState,
            nextOpenFileMode,
            nextOpenFileFocus
        );
    }

    private void applySaveAsTextInputEffect(TextField saveAsInput, SaveFlowController.TextInputEffect effect) {
        if (effect == null) {
            return;
        }
        switch (effect.kind()) {
            case NONE -> {
            }
            case INSERT -> saveAsInput.insert(effect.text());
            case SET_TEXT -> saveAsInput.set(effect.text());
            case BACKSPACE -> saveAsInput.backspace();
            case MOVE_LEFT -> saveAsInput.moveLeft();
            case MOVE_RIGHT -> saveAsInput.moveRight();
            case MOVE_HOME -> saveAsInput.moveHome();
            case MOVE_END -> saveAsInput.moveEnd();
            case DELETE_FORWARD -> saveAsInput.deleteForward();
            case CLEAR -> saveAsInput.clear();
        }
    }

    private static void applyWheelVerticalScroll(
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        RenderPlan plan,
        int deltaRows,
        int tabWidth
    ) {
        scrollDocumentViewport(
            model, textLayout, state, plan == null ? 1 : plan.contentRows(),
            plan == null ? 0 : Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth()),
            plan != null && plan.wrapLines(), plan != null && plan.wrapByWords(), deltaRows, tabWidth
        );
    }

    private static void scrollDocumentViewport(
        TextModel model,
        EditorTextLayout layout,
        EditorState state,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords,
        int delta,
        int tabWidth
    ) {
        if (wrapLines && contentCols > 0) {
            EditorViewportBehavior.WrapTop top = EditorViewportBehavior.normalizeWrapTop(
                model, layout, state.getTopLine(), state.getTopWrapSegment(),
                contentCols, wrapByWords, tabWidth
            );
            int offset = EditorViewportBehavior.wrappedOffsetForWrapTop(
                model, layout, top, contentCols, wrapByWords, tabWidth
            );
            int total = layout.totalWrappedRows(contentCols, wrapByWords, tabWidth);
            int target = ScrollInteraction.scrollBy(offset, delta, Math.max(1, contentRows), total);
            EditorViewportBehavior.applyWrapTop(state, EditorViewportBehavior.wrapTopFromWrappedOffset(
                model, layout, target, contentCols, wrapByWords, tabWidth
            ));
        } else {
            state.setTopWrapSegment(0);
            EditorViewportBehavior.applyVerticalScrollKeepCursor(state, model, ScrollInteraction.scrollBy(
                state.getTopLine(), delta, Math.max(1, contentRows), model.lineCount()
            ));
        }
    }

    private static boolean isLocalSearchCommand(TuinkCommand action) {
        if (action == null) {
            return true;
        }
        return switch (action) {
            case EDIT_COPY, EDIT_CUT, EDIT_PASTE, EDIT_UNDO, EDIT_REDO, EDIT_SELECT_ALL,
                EDIT_BACKSPACE, EDIT_DELETE_FORWARD, EDIT_TOGGLE_LINE_COMMENT, EDIT_INSERT_TAB,
                CURSOR_LEFT, CURSOR_RIGHT, CURSOR_UP, CURSOR_DOWN,
                CURSOR_SELECT_LEFT, CURSOR_SELECT_RIGHT, CURSOR_SELECT_UP, CURSOR_SELECT_DOWN,
                CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT, CURSOR_HOME, CURSOR_END, CURSOR_PAGE_UP, CURSOR_PAGE_DOWN,
                SEARCH_FIELD_BACKSPACE, SEARCH_FIELD_DELETE, SEARCH_FIELD_HOME, SEARCH_FIELD_END,
                SEARCH_NAVIGATE_LEFT, SEARCH_NAVIGATE_RIGHT, SEARCH_NAVIGATE_UP, SEARCH_NAVIGATE_DOWN,
                SEARCH_FOCUS_NEXT -> true;
            default -> false;
        };
    }

    private SearchModeResult handleSearchModeInput(
        EditorInput key,
        UiSessionState uiSessionState,
        SearchController.SearchUiOptions searchUi,
        TextField searchQueryInput,
        TextField searchReplaceInput,
        SearchBarView.Placement searchBarPlacement,
        List<EditorTab> tabs,
        int activeTabIndex,
        TextModel model,
        EditorState state,
        EditorEngine engine,
        RenderPlanBuilder renderPlanBuilder,
        int contentRows,
        int contentCols,
        boolean running,
        UiModalState modalState,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification,
        boolean openFileMode,
        OpenFileTreeController openFileTree,
        OpenFileInputRouter.Focus openFileFocus,
        TopMenuController topMenu,
        Consumer<Boolean> mouseTrackingController,
        RenderRasterizer renderer
    ) {
        String statusMessage = null;
        boolean statusError = false;
        boolean statusWarning = false;
        int nextActiveTabIndex = activeTabIndex;
        TextModel nextModel = model;
        EditorState nextState = state;
        EditorEngine nextEngine = engine;
        RenderPlanBuilder nextRenderPlanBuilder = renderPlanBuilder;
        SearchController.SearchUiOptions nextSearchUi = searchUi;
        boolean nextRunning = running;
        UiModalState nextModalState = modalState;
        boolean nextOpenFileMode = openFileMode;
        OpenFileInputRouter.Focus nextOpenFileFocus = openFileFocus;

        SearchFocus searchFocus = searchFocusManager.focused().orElse(null);
        if (key.command() == null && key.event() instanceof TextInput charInput
            && !(" ".equals(charInput.text()) && SearchController.isSearchActionFocus(searchFocus))) {
            if (charInput.text().equals(" ") && SearchController.isToggleFocus(searchFocus)) {
                SearchController.SearchUiOptions before = nextSearchUi;
                nextSearchUi = SearchController.toggleFocusedSearchOption(nextSearchUi, searchFocus);
                if (!nextSearchUi.equals(before)) {
                    statusMessage = "";
                }
            } else if (searchFocus == SearchFocus.REPLACE) {
                searchReplaceInput.insert(charInput.text());
                statusMessage = "";
            } else if (searchFocus == SearchFocus.FIND) {
                searchQueryInput.insert(charInput.text());
                statusMessage = "";
            }
            return new SearchModeResult(
                true,
                nextSearchUi,
                nextActiveTabIndex,
                nextModel,
                nextState,
                nextEngine,
                nextRenderPlanBuilder,
                nextRunning,
                nextModalState,
                nextOpenFileMode,
                nextOpenFileFocus
            );
        }

        TuinkCommand action = key.command();
        if (action == null && key.event() instanceof TextInput charInput
            && " ".equals(charInput.text()) && SearchController.isSearchActionFocus(searchFocus)) {
            action = SearchController.actionForSearchFocus(searchFocus);
        }
        if (action == TuinkCommand.EDIT_INSERT_NEWLINE) {
            action = searchFocus == null ? null : SearchController.actionForSearchFocus(searchFocus);
        }
        if (action == null || isHistoryCommand(action) || SearchController.handleFieldClipboard(
            action,
            searchFocusManager.focused().orElse(null),
            searchQueryInput,
            searchReplaceInput,
            clipboard
        )) {
            return new SearchModeResult(
                true,
                nextSearchUi,
                nextActiveTabIndex,
                nextModel,
                nextState,
                nextEngine,
                nextRenderPlanBuilder,
                nextRunning,
                nextModalState,
                nextOpenFileMode,
                nextOpenFileFocus
            );
        }
        switch (action) {
            case SEARCH_CLOSE -> {
                uiSessionState.searchMode = false;
                statusMessage = "";
            }
            case SEARCH_FOCUS_NEXT, EDIT_INSERT_TAB -> {
                if (searchBarPlacement != null) {
                    searchFocusManager.next(searchBarPlacement.focusScope());
                }
            }
            case SEARCH_FIELD_BACKSPACE, EDIT_BACKSPACE -> {
                if (SearchController.isSearchFieldFocus(searchFocus)) {
                    TextField active = searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                    active.backspace();
                }
            }
            case SEARCH_FIELD_DELETE, EDIT_DELETE_FORWARD -> {
                if (SearchController.isSearchFieldFocus(searchFocus)) {
                    TextField active = searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                    active.deleteForward();
                }
            }
            case SEARCH_NAVIGATE_LEFT, CURSOR_LEFT -> {
                if (searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveLeft();
                } else if (searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveLeft();
                } else {
                    moveSearchFocus(searchBarPlacement, false);
                }
            }
            case SEARCH_NAVIGATE_RIGHT, CURSOR_RIGHT -> {
                if (searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveRight();
                } else if (searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveRight();
                } else {
                    moveSearchFocus(searchBarPlacement, true);
                }
            }
            case SEARCH_NAVIGATE_UP, CURSOR_UP -> {
                if (searchFocus != null && !SearchController.isSearchFieldFocus(searchFocus)) {
                    focusSearch(searchBarPlacement, SearchNavigation.vertical(searchFocus, true));
                }
            }
            case SEARCH_NAVIGATE_DOWN, CURSOR_DOWN -> {
                if (searchFocus != null && !SearchController.isSearchFieldFocus(searchFocus)) {
                    focusSearch(searchBarPlacement, SearchNavigation.vertical(searchFocus, false));
                }
            }
            case CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT, CURSOR_SELECT_LEFT, CURSOR_SELECT_RIGHT -> {
                if (SearchController.isSearchFieldFocus(searchFocus)) {
                    TextField active = searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                    active.navigate(action == TuinkCommand.CURSOR_WORD_LEFT || action == TuinkCommand.CURSOR_SELECT_LEFT
                            ? KeyStroke.Key.ARROW_LEFT : KeyStroke.Key.ARROW_RIGHT,
                        action == TuinkCommand.CURSOR_WORD_LEFT || action == TuinkCommand.CURSOR_WORD_RIGHT,
                        action == TuinkCommand.CURSOR_SELECT_LEFT || action == TuinkCommand.CURSOR_SELECT_RIGHT);
                }
            }
            case EDIT_SELECT_ALL -> {
                if (SearchController.isSearchFieldFocus(searchFocus)) {
                    (searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput).selectAll();
                }
            }
            case CURSOR_SELECT_UP, CURSOR_SELECT_DOWN, EDIT_TOGGLE_LINE_COMMENT -> {
                // Single-line field controls own these commands even without a local action.
            }
            case CURSOR_PAGE_UP, CURSOR_PAGE_DOWN -> scrollDocumentViewport(
                nextModel, nextEngine.textLayout(), nextState, contentRows, contentCols,
                uiSessionState.wrapLinesSetting, uiSessionState.wrapWordsSetting,
                (action == TuinkCommand.CURSOR_PAGE_UP ? -1 : 1) * Math.max(1, contentRows),
                uiSessionState.tabWidthSetting
            );
            case SEARCH_FIELD_HOME, CURSOR_HOME -> {
                if (searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveHome();
                } else if (searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveHome();
                }
            }
            case SEARCH_FIELD_END, CURSOR_END -> {
                if (searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveEnd();
                } else if (searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveEnd();
                }
            }
            case SEARCH_NEXT -> {
                if (SearchController.isSearchActionFocus(searchFocus)) {
                    focusSearch(searchBarPlacement, SearchFocus.NEXT_BUTTON);
                }
                boolean found;
                SearchOptions searchOptions = SearchController.toSearchOptions(nextSearchUi);
                boolean focusedCurrent = focusCurrentMatchIfOffscreen(
                    nextModel,
                    nextState,
                    nextEngine,
                    contentRows,
                    contentCols,
                    uiSessionState.wrapLinesSetting,
                    uiSessionState.wrapWordsSetting,
                    searchQueryInput.text(),
                    searchOptions
                );
                if (focusedCurrent) {
                    found = true;
                } else
                if (nextSearchUi.allTabs()) {
                    int foundTab = TabSearch.findNextAcrossTabs(
                        toTabSessions(tabs),
                        nextActiveTabIndex,
                        searchQueryInput.text(),
                        searchOptions
                    );
                    found = foundTab >= 0;
                    if (found) {
                        nextActiveTabIndex = foundTab;
                        nextModel = tabs.get(nextActiveTabIndex).model();
                        nextState = tabs.get(nextActiveTabIndex).state();
                        nextEngine = tabs.get(nextActiveTabIndex).engine();
                        nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                    }
                } else {
                    found = nextEngine.findNext(
                        searchQueryInput.text(),
                        searchOptions
                    );
                }
                if (found) {
                    nextEngine.ensureCursorVisible(
                        contentRows,
                        contentCols,
                        uiSessionState.wrapLinesSetting,
                        uiSessionState.wrapWordsSetting
                    );
                }
                statusMessage = found ? "Found next match" : "Not found";
            }
            case SEARCH_PREVIOUS -> {
                if (SearchController.isSearchActionFocus(searchFocus)) {
                    focusSearch(searchBarPlacement, SearchFocus.PREV_BUTTON);
                }
                boolean found;
                if (nextSearchUi.allTabs()) {
                    int foundTab = TabSearch.findPreviousAcrossTabs(
                        toTabSessions(tabs),
                        nextActiveTabIndex,
                        searchQueryInput.text(),
                        SearchController.toSearchOptions(nextSearchUi)
                    );
                    found = foundTab >= 0;
                    if (found) {
                        nextActiveTabIndex = foundTab;
                        nextModel = tabs.get(nextActiveTabIndex).model();
                        nextState = tabs.get(nextActiveTabIndex).state();
                        nextEngine = tabs.get(nextActiveTabIndex).engine();
                        nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                    }
                } else {
                    found = nextEngine.findPrevious(
                        searchQueryInput.text(),
                        SearchController.toSearchOptions(nextSearchUi)
                    );
                }
                if (found) {
                    nextEngine.ensureCursorVisible(
                        contentRows,
                        contentCols,
                        uiSessionState.wrapLinesSetting,
                        uiSessionState.wrapWordsSetting
                    );
                }
                statusMessage = found ? "Found previous match" : "Not found";
            }
            case SEARCH_REPLACE -> {
                if (SearchController.isSearchActionFocus(searchFocus)) {
                    focusSearch(searchBarPlacement, SearchFocus.REPLACE_BUTTON);
                }
                if (uiSessionState.readingModeSetting) {
                    statusMessage = "Reading mode: replace is disabled";
                } else {
                    boolean replaced = nextEngine.replaceCurrentOrNext(
                        searchQueryInput.text(),
                        searchReplaceInput.text(),
                        SearchController.toSearchOptions(nextSearchUi)
                    );
                    statusMessage = replaced ? "Replaced match" : "Nothing to replace";
                }
            }
            case SEARCH_REPLACE_ALL -> {
                if (SearchController.isSearchActionFocus(searchFocus)) {
                    focusSearch(searchBarPlacement, SearchFocus.ALL_BUTTON);
                }
                if (uiSessionState.readingModeSetting) {
                    statusMessage = "Reading mode: replace is disabled";
                } else {
                    int replaced = nextEngine.replaceAll(
                        searchQueryInput.text(),
                        searchReplaceInput.text(),
                        SearchController.toSearchOptions(nextSearchUi)
                    );
                    statusMessage = replaced > 0
                        ? "Replaced " + replaced + " occurrence(s). Undo available: Ctrl+Z"
                        : "Nothing to replace";
                    statusWarning = true;
                }
            }
            case APP_QUIT -> {
                if (uiSessionState.confirmOnCloseSetting && hasDirtyTabs(tabs)) {
                    uiSessionState.searchMode = false;
                    nextModalState = UiModalState.quitConfirm();
                } else {
                    nextRunning = false;
                }
            }
            case DOCUMENT_SAVE -> {
                if (nextState.getCurrentFile() == null) {
                    uiSessionState.searchMode = false;
                    nextModalState = UiModalState.saveAs();
                    saveAsInput.set(
                        saveAsSuggestions.suggestionFor(
                            tabs.get(nextActiveTabIndex).tabSession(),
                            nextState.getCurrentFile(),
                            DEFAULT_FILE_NAME
                        )
                    );
                    notification.clear();
                } else if (isReadOnlyFile(nextState.getCurrentFile())) {
                    uiSessionState.searchMode = false;
                    nextModalState = UiModalState.saveAs();
                    String suggestion = saveAsSuggestions.suggestionFor(
                        tabs.get(nextActiveTabIndex).tabSession(),
                        null,
                        suggestWritableName(nextState.getCurrentFile())
                    );
                    saveAsInput.set(suggestion);
                    saveAsSuggestions.rememberDraft(tabs.get(nextActiveTabIndex).tabSession(), suggestion);
                    notification.error(readOnlySaveAsMessage(nextState.getCurrentFile()));
                } else {
                    try {
                        nextEngine.save();
                        saveAsSuggestions.rememberSavedPath(tabs.get(nextActiveTabIndex).tabSession(), nextState.getCurrentFile());
                        notification.success(savedMessage(nextState));
                    } catch (IOException e) {
                        notification.error(saveErrorMessage(e));
                    }
                }
            }
            case DOCUMENT_OPEN -> {
                uiSessionState.searchMode = false;
                topMenu.close();
                nextOpenFileMode = true;
                openFileTree.setRoot(initialOpenRoot(nextState));
                nextOpenFileFocus = OpenFileInputRouter.Focus.LIST;
                statusMessage = "Open file";
            }
            case VIEW_CYCLE_THEME -> {
                applyTheme(theme.next());
                renderer.setTheme(theme);
                statusMessage = "Theme: " + theme.label();
            }
            case TAB_NEW -> {
                tabs.add(newEditorTab());
                nextActiveTabIndex = tabs.size() - 1;
                nextModel = tabs.get(nextActiveTabIndex).model();
                nextState = tabs.get(nextActiveTabIndex).state();
                nextEngine = tabs.get(nextActiveTabIndex).engine();
                nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                uiSessionState.searchMode = false;
                statusMessage = "New tab";
            }
            case TAB_NEXT -> {
                nextActiveTabIndex = switchTab(tabs, nextActiveTabIndex, 1);
                nextModel = tabs.get(nextActiveTabIndex).model();
                nextState = tabs.get(nextActiveTabIndex).state();
                nextEngine = tabs.get(nextActiveTabIndex).engine();
                nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                uiSessionState.searchMode = false;
            }
            case TAB_PREVIOUS -> {
                nextActiveTabIndex = switchTab(tabs, nextActiveTabIndex, -1);
                nextModel = tabs.get(nextActiveTabIndex).model();
                nextState = tabs.get(nextActiveTabIndex).state();
                nextEngine = tabs.get(nextActiveTabIndex).engine();
                nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                uiSessionState.searchMode = false;
            }
            case TAB_CLOSE -> {
                if (nextState.isDirty() && uiSessionState.confirmOnCloseSetting) {
                    nextModalState = UiModalState.closeTabConfirm(nextActiveTabIndex);
                } else {
                    nextActiveTabIndex = closeCurrentTab(tabs, nextActiveTabIndex, saveAsSuggestions);
                    nextModel = tabs.get(nextActiveTabIndex).model();
                    nextState = tabs.get(nextActiveTabIndex).state();
                    nextEngine = tabs.get(nextActiveTabIndex).engine();
                    nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                    uiSessionState.searchMode = false;
                }
            }
            case DOCUMENT_SAVE_AS -> {
                uiSessionState.searchMode = false;
                nextModalState = UiModalState.saveAs();
                saveAsInput.set(
                    saveAsSuggestions.suggestionFor(
                        tabs.get(nextActiveTabIndex).tabSession(),
                        nextState.getCurrentFile(),
                        DEFAULT_FILE_NAME
                    )
                );
                notification.clear();
            }
            default -> {
                InputActionDispatcher.DispatchResult dispatchResult = InputActionDispatcher.dispatch(
                    action,
                    uiSessionState,
                    new InputActionDispatcher.DispatchContext(
                        nextEngine,
                        nextState,
                        topMenu,
                        mouseTrackingController,
                        contentRows,
                        contentCols
                    )
                );
                if (dispatchResult.handled()) {
                    statusMessage = dispatchResult.statusMessage();
                    statusError = dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.ERROR;
                    if (!statusError) {
                        statusWarning = statusWarning
                            || dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.WARNING;
                    }
                }
            }
        }

        if (statusMessage != null && !statusMessage.isBlank()) {
            if (statusError) {
                notification.error(statusMessage);
            } else if (statusWarning) {
                notification.warning(statusMessage);
            } else {
                notification.info(statusMessage);
            }
        }

        return new SearchModeResult(
            true,
            nextSearchUi,
            nextActiveTabIndex,
            nextModel,
            nextState,
            nextEngine,
            nextRenderPlanBuilder,
            nextRunning,
            nextModalState,
            nextOpenFileMode,
            nextOpenFileFocus
        );
    }

    private record SearchModeResult(
        boolean consumed,
        SearchController.SearchUiOptions searchUi,
        int activeTabIndex,
        TextModel model,
        EditorState state,
        EditorEngine engine,
        RenderPlanBuilder renderPlanBuilder,
        boolean running,
        UiModalState modalState,
        boolean openFileMode,
        OpenFileInputRouter.Focus openFileFocus
    ) {
    }

    private record EditorActionApplyResult(
        boolean running,
        UiModalState modalState,
        boolean openFileMode,
        OpenFileInputRouter.Focus openFileFocus
    ) {
    }

    private record OpenFileApplyResult(boolean openFileMode, int activeTabIndex) {
    }

    private record SaveFlowProgress(boolean running, UiModalState modalState) {
    }

    private void drawTopFileNameBold(
        CellBuffer buffer,
        Canvas canvas,
        int cols,
        EditorState state,
        int startCol
    ) {
        if (buffer.rows() < 1 || cols <= startCol || startCol < 0) {
            return;
        }
        String file = state.getCurrentFile() == null ? "--no name--" : state.getCurrentFile().toString();
        int endCol = Math.min(cols, startCol + CellText.width(file));
        for (int col = startCol; col < endCol;) {
            var cell = buffer.get(0, col);
            if (cell.continuation()) {
                col++;
                continue;
            }
            canvas.put(col, 0, cell.grapheme(), styles.topbarFilenameHighlight());
            col += Math.max(1, cell.width());
        }
    }

    private void drawMenuLine(Canvas canvas, int row, int startCol, String text, CellStyle style) {
        CellText.draw(canvas, startCol, row, text, style);
    }

    private Canvas frameCanvas(CellBuffer buffer, int rows, int cols) {
        Canvas canvas = new Canvas(buffer);
        canvas.clip(0, 0, Math.max(0, cols), Math.max(0, rows));
        return canvas;
    }

    private void paintConfirmButtons(Canvas canvas, ConfirmBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, confirmWidgetTheme, confirmFocusManager);
    }

    private void paintSaveAsField(Canvas canvas, SaveAsBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, saveAsWidgetTheme, saveAsFocusManager);
    }

    private void paintSearchBar(Canvas canvas, SearchBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, searchWidgetTheme, searchFocusManager);
    }

    private SearchFocus currentSearchFocus() {
        return searchFocusManager.focused().orElse(SearchFocus.FIND);
    }

    private static boolean isHistoryCommand(TuinkCommand command) {
        return command == TuinkCommand.EDIT_UNDO || command == TuinkCommand.EDIT_REDO;
    }

    private static boolean isSearchOperation(TuinkCommand command) {
        return command == TuinkCommand.SEARCH_NEXT || command == TuinkCommand.SEARCH_PREVIOUS
            || command == TuinkCommand.SEARCH_REPLACE || command == TuinkCommand.SEARCH_REPLACE_ALL;
    }

    private void focusSearch(SearchBarView.Placement placement, SearchFocus target) {
        if (placement != null && target != null) {
            searchFocusManager.focus(placement.focusScope(), target);
        }
    }

    private void moveSearchFocus(SearchBarView.Placement placement, boolean forward) {
        if (placement == null) {
            return;
        }
        if (forward) {
            searchFocusManager.next(placement.focusScope());
        } else {
            searchFocusManager.previous(placement.focusScope());
        }
    }

    static SearchFocus toggleTarget(SearchBarView.Action action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case TOGGLE_CASE -> SearchFocus.CASE;
            case TOGGLE_WORD -> SearchFocus.WORD;
            case TOGGLE_WILDCARD -> SearchFocus.WILDCARD;
            case TOGGLE_ALL_TABS -> SearchFocus.ALL;
            default -> null;
        };
    }

    static TuinkCommand searchCommand(SearchBarView.Action action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case FIND_NEXT -> TuinkCommand.SEARCH_NEXT;
            case FIND_PREVIOUS -> TuinkCommand.SEARCH_PREVIOUS;
            case REPLACE -> TuinkCommand.SEARCH_REPLACE;
            case REPLACE_ALL -> TuinkCommand.SEARCH_REPLACE_ALL;
            default -> null;
        };
    }

    static boolean searchOwnsInteractiveRow(UiModalState modalState) {
        return modalState != null && modalState.isNone();
    }

    /** A menu borrows raw input, while local edit commands retain the underlying focus. */
    static boolean searchOwnsCommand(
        boolean searchMode,
        boolean searchEditorFocused,
        boolean menuCommandSelected,
        TuinkCommand command
    ) {
        return searchMode
            && (!menuCommandSelected || isLocalSearchCommand(command))
            && (!searchEditorFocused || isSearchOperation(command));
    }

    static boolean shouldExtendEditorSelection(TuiInputEvent event) {
        return event instanceof PointerEvent pointer
            && pointer.kind() == PointerEvent.Kind.PRESS
            && pointer.button() == PointerEvent.Button.LEFT
            && pointer.modifiers().contains(KeyStroke.Modifier.SHIFT);
    }

    static EditorInput resolveEditorInput(
        CommandRouter commandRouter,
        TuiInputEvent event,
        CommandInvocation pendingCommand,
        CommandRouter.RoutedCommand routedMenuResult,
        boolean searchOwnsInput,
        SearchFocus searchFocus
    ) {
        Objects.requireNonNull(commandRouter, "commandRouter");
        Objects.requireNonNull(event, "event");
        if (routedMenuResult instanceof CommandRouter.RoutedAction selected) {
            return new EditorInput(event, selected.action());
        }
        if (pendingCommand != null) {
            if (searchOwnsInput) {
                TuinkCommand command = TuinkCommand.fromId(pendingCommand.id()).orElse(null);
                if (command == null) {
                    return null;
                }
                pendingCommand = commandRouter.invocationForSearch(command, searchFocus, pendingCommand);
            }
            TuinkCommand command = commandRouter.invoke(pendingCommand).orElse(null);
            return command == null ? null : new EditorInput(event, command);
        }
        return event instanceof TextInput || event instanceof PointerEvent
            ? new EditorInput(event, null)
            : null;
    }

    static boolean searchOwnsRawInput(
        boolean searchMode,
        UiModalState modalState,
        boolean openFileMode,
        boolean topMenuOpen
    ) {
        return searchOwnsRawInput(
            searchMode,
            modalState,
            openFileMode,
            topMenuOpen,
            false
        );
    }

    static boolean searchOwnsRawInput(
        boolean searchMode,
        UiModalState modalState,
        boolean openFileMode,
        boolean topMenuOpen,
        boolean typedMenuCommandSelected
    ) {
        return searchMode
            && searchOwnsInteractiveRow(modalState)
            && !openFileMode
            && !topMenuOpen
            && !typedMenuCommandSelected;
    }

    static TabBarInputRouter.Policy tabBarInputPolicy(
        boolean placementAvailable,
        boolean mouseEnabled,
        UiModalState modalState,
        boolean openFileMode,
        boolean topMenuOpen,
        boolean searchMode
    ) {
        if (!placementAvailable || !mouseEnabled || modalState == null
            || openFileMode || topMenuOpen) {
            return null;
        }
        if (modalState.isCloseTabConfirm()) {
            return TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM;
        }
        if (!modalState.isNone()) {
            return null;
        }
        return searchMode ? TabBarInputRouter.Policy.SEARCH : TabBarInputRouter.Policy.NORMAL;
    }

    static ModalInputPolicy.Kind modalInputKind(UiModalState modal, boolean openFileMode) {
        if (modal.isNone()) {
            return openFileMode ? ModalInputPolicy.Kind.OPEN_FILE : ModalInputPolicy.Kind.NONE;
        }
        if (modal.isSaveAs()) {
            return ModalInputPolicy.Kind.SAVE_AS;
        }
        if (modal.isHelpHints() || modal.isHelpLicenses() || modal.isHelpAuthor()) {
            return ModalInputPolicy.Kind.HELP;
        }
        return ModalInputPolicy.Kind.CONFIRM;
    }

    static Object modalInputOwner(UiModalState modal, boolean openFileMode) {
        return modal.isNone() ? openFileMode ? ModalInputPolicy.Kind.OPEN_FILE : null : modal;
    }

    private static boolean belongsToTabChrome(TuiInputEvent event, Rect bounds) {
        if (!(event instanceof PointerEvent pointer)) {
            return false;
        }
        boolean verticalWheel = pointer.kind() == PointerEvent.Kind.SCROLL_UP
            || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN;
        return bounds.contains(pointer.column(), pointer.row())
            || verticalWheel && bounds.y() > 0 && pointer.row() == bounds.y() - 1
                && pointer.column() >= bounds.x() && pointer.column() < bounds.rightExclusive();
    }

    static long earlierDeadline(long first, long second) {
        return first < 0L ? second : second < 0L ? first : Math.min(first, second);
    }

    /** Geometry of the visible owner, shared by blocked-pointer feedback and painting. */
    Rect blockingSurface(FrameComposition composition) {
        FrameComposition.Layers layers = composition.layers();
        int rows = composition.document().layout().rows();
        int cols = composition.document().layout().cols();
        return blockingSurface(rows, cols, composition.document().layout().contentTopRow(),
            composition.surface().inlinePrompt().height(), layers.saveAsMode() || layers.confirm() != null,
            layers.helpPage(), layers.openFile().visible() ? layers.openFile().layout() : null);
    }

    private Rect blockingSurface(int rows, int cols, int contentTop, int promptHeight,
                                boolean inline, HelpPage help, OpenDialogLayout open) {
        if (help != null || open != null) {
            if (rows < 8 || cols < 24) {
                return new Rect(0, 0, 0, 0);
            }
            if (help != null) {
                HelpDialogLayout layout = helpDialogLayout(rows, cols, help.rows().size());
                return new Rect(layout.left(), layout.top(), layout.width(), layout.height());
            }
            return new Rect(open.left(), open.top(), open.width(), open.height());
        }
        if (inline) {
            Rect prompt = new Rect(0, rows - 1 - promptHeight, cols, promptHeight);
            return prompt.intersection(new Rect(0, contentTop, cols, Math.max(0, rows - 1 - contentTop)));
        }
        return new Rect(0, 0, 0, 0);
    }

    void paintBlockingAttention(CellBuffer buffer, FrameComposition composition, boolean colorEnabled) {
        if (!composition.layers().blockedInputHighlight()) {
            return;
        }
        CellStyle accent = blockingAccent(colorEnabled);
        DialogAttentionPainter.paint(
            buffer,
            blockingSurface(composition),
            composition.layers().helpPage() != null || composition.layers().openFile().visible(),
            accent
        );
    }

    private CellStyle blockingAccent(boolean colorEnabled) {
        return colorEnabled ? styles.notifyWarning()
            : new CellStyle(CellColor.DEFAULT, CellColor.DEFAULT, true, true);
    }

    static UiModalState consumeRequestedHelpModal(UiSessionState ui, UiModalState current) {
        if (ui.helpHintsMode) {
            ui.helpHintsMode = false;
            return UiModalState.helpHints();
        }
        if (ui.helpLicensesMode) {
            ui.helpLicensesMode = false;
            return UiModalState.helpLicenses();
        }
        if (ui.helpAuthorMode) {
            ui.helpAuthorMode = false;
            return UiModalState.helpAuthor();
        }
        return current;
    }

    private CellStyle focusedButtonStyle(CellStyle parentStyle) {
        if (parentStyle == null) {
            return styles.menuSelected();
        }
        return new CellStyle(parentStyle.bg(), parentStyle.fg(), true, parentStyle.underline());
    }

    private String padRight(String text, int width) {
        return CellText.fit(text, width);
    }

    private Path initialOpenRoot(EditorState state) {
        if (state.getCurrentFile() != null) {
            Path parent = state.getCurrentFile().toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private void drawOpenFileOverlay(
        Canvas canvas,
        int rows,
        int cols,
        FrameComposition.OpenFile openFile,
        boolean colorEnabled
    ) {
        drawOpenFileOverlay(canvas, rows, cols, openFile, colorEnabled, false);
    }

    private void drawOpenFileOverlay(
        Canvas canvas,
        int rows,
        int cols,
        FrameComposition.OpenFile openFile,
        boolean colorEnabled,
        boolean highlighted
    ) {
        drawOpenFileOverlay(canvas, rows, cols, openFile, colorEnabled, highlighted, false);
    }

    private void drawOpenFileOverlay(
        Canvas canvas, int rows, int cols, FrameComposition.OpenFile openFile,
        boolean colorEnabled, boolean highlighted, boolean contentFocused
    ) {
        if (!openFile.visible() || rows < 8 || cols < 24) {
            return;
        }
        OpenDialogLayout layout = openFile.layout();
        Modal frame = openFileFrame(
            layout, openFile.placement() != null && openFile.placement().loading(),
            highlighted ? blockingAccent(colorEnabled) : styles.dialogBorder()
        );
        Theme childTheme = frame.childTheme(colorEnabled ? colorFileTreeWidgetTheme : monoFileTreeWidgetTheme);
        frame.layout(new Rect(layout.left(), layout.top(), layout.width(), layout.height())).paint(canvas, childTheme);
        String pathLine = " " + openFile.rootLabel();
        drawMenuLine(
            canvas,
            layout.top() + 1,
            layout.left() + 1,
            padRight(pathLine, Math.max(0, layout.width() - 2)),
            styles.dialog()
        );
        FileTreeView.Placement treePlacement = openFile.placement();
        if (treePlacement != null) {
            treePlacement.paint(
                canvas,
                childTheme
            );
            OpenFileDialogView.place(
                OPEN_FILE_SCOPE_ID, layout, treePlacement, openFile.showHiddenFiles(), new Rect(0, 0, cols, rows)
            ).paintControls(canvas, childTheme, openFile.focus(), contentFocused);
        }
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout) {
        return openFileFrame(layout, false);
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout, boolean loading) {
        return openFileFrame(layout, loading, styles.dialogBorder());
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout, boolean loading, CellStyle border) {
        return new Modal(
            layout.top(),
            layout.left(),
            layout.width(),
            layout.height(),
            loading ? " Open File (Loading...) " : " Open File ",
            styles.dialog(),
            border,
            styles.dialogTitle()
        );
    }

    private HelpDialogLayout helpDialogLayout(int rows, int cols, int contentRows) {
        int preferredWidth = 68;
        // +2 = top/bottom frame borders; content fills the interior without an extra blank row.
        int preferredHeight = contentRows + 2;
        int width = Math.max(24, Math.min(preferredWidth, cols - 4));
        int height = Math.max(8, Math.min(preferredHeight, rows - 4));
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        return new HelpDialogLayout(top, left, width, height);
    }

    private Modal infoDialogFrame(HelpDialogLayout layout, String title) {
        return new Modal(layout.width(), layout.height(), title);
    }

    private Button helpCloseButton(HelpDialogLayout layout) {
        String closeText = " Close ";
        int col = layout.left() + Math.max(1, (layout.width() - (closeText.length() + 2)) / 2);
        int row = layout.top() + layout.height() - 1;
        return new Button(row, col, closeText);
    }

    private HelpOverlayPlacement helpOverlayPlacement(int rows, int cols, HelpPage page) {
        HelpDialogLayout layout = helpDialogLayout(rows, cols, page.rows().size());
        HelpOverlayPlacement cached = helpOverlayCache;
        Rect viewport = new Rect(0, 0, cols, rows);
        if (cached != null
            && cached.page() == page
            && cached.layout().equals(layout)
            && cached.viewport().equals(viewport)) {
            return cached;
        }
        Modal frame = infoDialogFrame(layout, page.title());
        Button close = helpCloseButton(layout);
        RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> closeTarget = new RoutedTarget<>(
            HelpOverlayInputRouter.Target.CLOSE,
            close.layout(
                new Rect(close.col(), close.row(), close.width(), 1)
            ),
            FocusSpace.root(viewport),
            rows >= 8 && cols >= 24,
            rows >= 8 && cols >= 24
        );
        HelpOverlayPlacement prepared = new HelpOverlayPlacement(
            page,
            layout,
            viewport,
            frame.layout(new Rect(layout.left(), layout.top(), layout.width(), layout.height())),
            close,
            FocusScope.single(
                HelpOverlayInputRouter.Scope.HELP,
                FocusResetPolicy.FIRST_FOCUSABLE,
                closeTarget
            )
        );
        helpOverlayCache = prepared;
        return prepared;
    }

    void paintHelpOverlay(Canvas canvas, int rows, int cols, HelpPage page, boolean colorEnabled) {
        paintHelpOverlay(canvas, rows, cols, page, colorEnabled, false);
    }

    void paintHelpOverlay(
        Canvas canvas, int rows, int cols, HelpPage page, boolean colorEnabled, boolean highlighted
    ) {
        if (page == null) {
            helpOverlayCache = null;
            return;
        }
        drawInfoOverlay(canvas, helpOverlayPlacement(rows, cols, page), colorEnabled, highlighted);
    }

    private void drawInfoOverlay(
        Canvas canvas,
        HelpOverlayPlacement overlay,
        boolean colorEnabled,
        boolean highlighted
    ) {
        if (overlay == null || overlay.viewport().height() < 8 || overlay.viewport().width() < 24) {
            return;
        }
        HelpPage page = overlay.page();
        HelpDialogLayout layout = overlay.layout();
        Theme widgetTheme = colorEnabled ? colorOverlayWidgetTheme : monoOverlayWidgetTheme;
        if (highlighted) {
            widgetTheme = widgetTheme.withSurface(styles.dialog(), blockingAccent(colorEnabled));
        }
        overlay.frame().paint(canvas, widgetTheme);
        int textRow = layout.top() + 1;
        int textCol = layout.left() + 2;
        int textWidth = Math.max(0, layout.width() - 4);
        int maxRows = Math.max(0, layout.height() - 2);
        for (int i = 0; i < page.rows().size() && i < maxRows; i++) {
            drawMenuLine(
                canvas,
                textRow + i,
                textCol,
                clipText(page.rows().get(i), textWidth),
                styles.dialog()
            );
        }
        RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> closeTarget = overlay.focusScope()
            .target(HelpOverlayInputRouter.Target.CLOSE)
            .orElseThrow();
        closeTarget.placement().paint(
            canvas,
            widgetTheme,
            helpFocusManager.context(overlay.focusScope(), closeTarget.id())
                .orElseThrow()
                .widgetState()
        );
    }

    CellStyle resolveOverlayWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MODAL_BODY.equals(key) || WidgetThemeKeys.BUTTON.equals(key)) {
            return styles.dialog();
        }
        if (WidgetThemeKeys.MODAL_BORDER.equals(key)) {
            return styles.dialogBorder();
        }
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return styles.dialogTitle();
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono();
        }
        if (WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.dialog();
        }
        return null;
    }

    CellStyle resolveConfirmWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return focusedButtonStyle(styles.promptAlert());
        }
        if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.promptAlert();
        }
        return null;
    }

    CellStyle resolveSaveAsWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key) || WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(key)) {
            return focusedButtonStyle(styles.promptAlert());
        }
        if (WidgetThemeKeys.TEXT_FIELD.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(key)
            || WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.promptAlert();
        }
        return null;
    }

    CellStyle resolveSearchWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key) || WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(key)) {
            return focusedButtonStyle(styles.searchBar());
        }
        if (WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(key)) {
            return styles.searchBar();
        }
        return null;
    }

    CellStyle resolveTabBarWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.TAB_ITEM_ACTIVE.equals(key)) {
            return colorEnabled ? styles.tabbarActive() : styles.tabbarActiveMono();
        }
        if (WidgetThemeKeys.TAB_BAR.equals(key)
            || WidgetThemeKeys.TAB_ITEM.equals(key)
            || WidgetThemeKeys.TAB_ITEM_DISABLED.equals(key)) {
            return colorEnabled ? styles.tabbar() : styles.tabbarMono();
        }
        return null;
    }

    CellStyle resolveTopMenuWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MENU_BAR_ACTIVE.equals(key)) {
            return colorEnabled ? styles.menuTopSelected() : styles.menuSelectedMono();
        }
        if (WidgetThemeKeys.MENU_BAR.equals(key)
            || WidgetThemeKeys.MENU_BAR_DISABLED.equals(key)) {
            return styles.renderTopbar();
        }
        if (WidgetThemeKeys.MENU_ITEM_SELECTED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.menuSelectedMono();
        }
        if (WidgetThemeKeys.MENU_ITEM_DISABLED.equals(key)) {
            return colorEnabled ? styles.contextMenuDisabledItem() : styles.menuPopup();
        }
        if (WidgetThemeKeys.MENU_POPUP.equals(key)
            || WidgetThemeKeys.MENU_ITEM.equals(key)) {
            return styles.menuPopup();
        }
        return null;
    }

    CellStyle resolveFileTreeWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return styles.dialogTitle();
        }
        if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(key)
            || WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono();
        }
        if (WidgetThemeKeys.LIST_ITEM.equals(key)
            || WidgetThemeKeys.LIST_ITEM_DISABLED.equals(key)) {
            return styles.dialog();
        }
        if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.dialog();
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono();
        }
        return null;
    }

    private ConfirmBarInputRouter.Policy confirmPolicy(UiModalState modalState) {
        if (modalState.isRecoveryConfirm()) {
            return ConfirmBarInputRouter.Policy.recovery();
        }
        if (modalState.isSaveOverwriteConfirm()) {
            return ConfirmBarInputRouter.Policy.overwrite();
        }
        if (modalState.isQuitConfirm()) {
            return ConfirmBarInputRouter.Policy.quit();
        }
        if (modalState.isCloseTabConfirm()) {
            return ConfirmBarInputRouter.Policy.closeTab();
        }
        throw new IllegalArgumentException("Modal is not a confirmation prompt: " + modalState.mode());
    }

    private void deactivateConfirmFocus() {
        confirmFocusManager.activeScopeId().ifPresent(confirmFocusManager::deactivate);
    }

    private HelpPage activeHelpPage(boolean helpHintsMode, boolean helpLicensesMode, boolean helpAuthorMode) {
        if (helpHintsMode) {
            return HelpPages.KEYMAP;
        }
        if (helpLicensesMode) {
            return HelpPages.LICENSES;
        }
        if (helpAuthorMode) {
            return HelpPages.AUTHOR;
        }
        return null;
    }

    private String clipText(String text, int width) {
        if (text == null || width <= 0) {
            return "";
        }
        return CellText.clip(text, 0, width);
    }

    private OpenFileApplyResult applyOpenFileIntent(
        OpenFileInputRouter.Intent intent,
        OpenFileTreeController tree,
        List<EditorTab> tabs,
        int activeTabIndex,
        NotificationState notification
    ) {
        if (intent == null) {
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.ToggleDirectory toggle) {
            tree.requestExpansion(toggle.id(), toggle.expand());
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.ChangeRoot root) {
            tree.setRoot(root.root());
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.MoveToParentRoot) {
            tree.moveToParentRoot();
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.ToggleHidden) {
            tree.toggleHiddenFiles();
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.CloseDialog) {
            return new OpenFileApplyResult(false, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.SelectionRejected rejected) {
            notification.warning(
                rejected.reason() == OpenFileInputRouter.Rejection.ERROR
                    ? "The selected tree entry cannot be opened"
                    : "Select a file"
            );
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.OpenFile open) {
            Path target = open.file();
            try {
                EditorTab tab = newEditorTab();
                tab.engine().open(target);
                tab = tab.withRenderPlanBuilder(
                    new RenderPlanBuilder(resolveHighlighter(target), tab.textLayout())
                );
                tabs.add(tab);
                int newIndex = tabs.size() - 1;
                notification.success("Opened " + target);
                return new OpenFileApplyResult(false, newIndex);
            } catch (IOException e) {
                notification.error("Open failed: " + e.getMessage());
                return new OpenFileApplyResult(true, activeTabIndex);
            }
        }
        throw new IllegalStateException("Unsupported open-file intent: " + intent);
    }

    private String buildBottomBar(
        EditorState state,
        TextModel model,
        int cols,
        boolean readingMode,
        int activeTabIndex,
        int totalTabs,
        String searchSummary
    ) {
        final int leftMargin = 1;
        final int rightMargin = 1;
        Position p = state.getCursor();
        int totalLines = model.lineCount();
        int lineCols = model.getLine(p.line()).length();
        String indicators = buildStateIndicators(state, readingMode);
        String left = " ".repeat(Math.max(0, leftMargin))
            + "Ln " + (p.line() + 1) + "/" + totalLines
            + ", Col " + (p.col() + 1) + "/" + lineCols;
        if (totalTabs > 1) {
            left = left + "  Tab " + (activeTabIndex + 1) + "/" + totalTabs;
        }
        if (searchSummary != null && !searchSummary.isBlank()) {
            left = left + "  " + searchSummary;
        }
        if (cols <= 0) {
            return "";
        }
        int leftWidth = CellText.width(left);
        if (leftWidth >= cols) {
            return CellText.clip(left, 0, cols);
        }
        String rightBlock = indicators + " ".repeat(Math.max(0, rightMargin));
        int rightWidth = CellText.width(rightBlock);
        if (rightWidth >= cols) {
            return CellText.clip(rightBlock, 0, cols);
        }
        int free = cols - leftWidth;
        if (free <= rightWidth) {
            return left + CellText.clip(rightBlock, 0, Math.max(0, free));
        }
        return left + " ".repeat(free - rightWidth) + rightBlock;
    }

    private String buildSearchSummary(
        boolean searchMode,
        String query,
        SearchController.SearchUiOptions searchUi,
        int matchCount,
        int totalTabs
    ) {
        if (!searchMode || query == null || query.isBlank()) {
            return "";
        }
        if (searchUi != null && searchUi.allTabs() && totalTabs > 1) {
            return "Find " + matchCount + " in " + totalTabs + " tabs";
        }
        return "Find " + matchCount;
    }

    private String buildStateIndicators(EditorState state, boolean readingMode) {
        String ro = isReadOnlyFile(state.getCurrentFile()) ? "RO" : "RW";
        String enc = state.getFileEncoding() == null ? "UTF-8" : state.getFileEncoding().name();
        String eol = switch (state.getLineSeparator()) {
            case EditorState.LINE_SEPARATOR_CRLF -> "CRLF";
            case EditorState.LINE_SEPARATOR_CR -> "CR";
            default -> "LF";
        };
        return ro + " | ENC:" + enc + " | EOL:" + eol + " | " + (readingMode ? "READ" : "INS");
    }

    private RenderFrameResult renderFrame(
        FrameComposition composition,
        RenderFrameController services
    ) {
        FrameComposition.Document document = composition.document();
        FrameComposition.Surface surface = composition.surface();
        FrameComposition.Search search = composition.search();
        FrameComposition.Layers layers = composition.layers();
        FrameComposition.OpenFile openFile = layers.openFile();
        RenderPlanBuilder renderPlanBuilder = document.planBuilder();
        TextModel model = document.model();
        EditorState state = document.state();
        EditorLayout editorLayout = document.layout();
        RenderPlan previousPlan = document.previousPlan();
        int rows = editorLayout.rows();
        int cols = editorLayout.cols();
        int contentTopRow = editorLayout.contentTopRow();
        int topMenuPrefixWidth = surface.topBar().menuPrefixWidth();
        String topBar = surface.topBar().text();
        String bottomBar = surface.bottomBar();
        InlinePromptBar inlinePromptBar = surface.inlinePrompt();
        NotificationBar notificationBar = surface.notification();
        boolean searchMode = search.active();
        String searchQuery = search.query();
        boolean autoSelectionHighlightSetting = search.autoSelectionHighlight();
        SearchBarView.Placement searchBarPlacement = search.placement();
        TabBarView.Placement tabBarPlacement = layers.tabBar();
        TopMenuView.Placement topMenuPlacement = layers.topMenu();
        SaveAsBarView.Placement saveAsBarPlacement = layers.saveAs();
        boolean saveAsMode = layers.saveAsMode();
        ConfirmBarView.Placement confirmBarPlacement = layers.confirm();
        boolean openFileMode = openFile.visible();
        FileTreeView.Placement openFileTreePlacement = openFile.placement();
        HelpPage helpPage = layers.helpPage();
        boolean helpMode = helpPage != null;
        HelpOverlayPlacement helpOverlay = helpPage == null
            ? null
            : helpOverlayPlacement(rows, cols, helpPage);
        boolean colorEnabled = services.colorEnabled;
        ScreenPresenter screenPresenter = services.screenPresenter;
        RenderRasterizer renderer = services.renderer;
        EditorView editorView = services.editorView;
        RenderPlan plan;
        SearchOptions renderSearchOptions = searchMode
            ? search.options()
            : SearchOptions.DEFAULT;
        String renderSearchQuery = resolveRenderSearchQuery(
            searchMode,
            searchQuery,
            model,
            state,
            autoSelectionHighlightSetting,
            editorView.isMouseSelecting()
        );
        if (profileEnabled) {
            long buildStart = System.nanoTime();
            plan = renderPlanBuilder.build(
                model,
                state,
                rows,
                cols,
                topBar,
                bottomBar,
                renderSearchQuery,
                renderSearchOptions,
                inlinePromptBar.text(),
                inlinePromptBar.style(),
                notificationBar.text(),
                notificationBar.style(),
                composition.wrapLines(),
                composition.wrapWords(),
                editorLayout
            );
            frameProfiler.buildNanos = System.nanoTime() - buildStart;
        } else {
            plan = renderPlanBuilder.build(
                model,
                state,
                rows,
                cols,
                topBar,
                bottomBar,
                renderSearchQuery,
                renderSearchOptions,
                inlinePromptBar.text(),
                inlinePromptBar.style(),
                notificationBar.text(),
                notificationBar.style(),
                composition.wrapLines(),
                composition.wrapWords(),
                editorLayout
            );
        }
        CellBuffer current;
        if (profileEnabled) {
            long rasterStart = System.nanoTime();
            current = renderer.rasterize(plan, rows, cols, contentTopRow);
            frameProfiler.rasterNanos = System.nanoTime() - rasterStart;
        } else {
            current = renderer.rasterize(plan, rows, cols, contentTopRow);
        }
        Canvas canvas = frameCanvas(current, rows, cols);
        paintSaveAsField(canvas, saveAsBarPlacement);
        paintConfirmButtons(canvas, confirmBarPlacement);
        paintSearchBar(canvas, searchBarPlacement);
        if (tabBarPlacement != null) {
            tabBarPlacement.paint(
                canvas,
                colorEnabled ? colorTabBarWidgetTheme : monoTabBarWidgetTheme
            );
        }
        if (topMenuPlacement != null) {
            topMenuPlacement.paint(
                canvas,
                colorEnabled ? colorTopMenuWidgetTheme : monoTopMenuWidgetTheme
            );
        }
        drawTopFileNameBold(current, canvas, cols, state, topMenuPrefixWidth);
        drawOpenFileOverlay(
            canvas,
            rows,
            cols,
            openFile,
            colorEnabled,
            composition.layers().blockedInputHighlight(),
            composition.layers().contentFocused()
        );
        paintHelpOverlay(canvas, rows, cols, helpPage, colorEnabled, composition.layers().blockedInputHighlight());
        paintBlockingAttention(current, composition, colorEnabled);

        EditorView.CursorPosition editorCursor = editorView.cursorFromPlan(plan, contentTopRow);
        int desiredCursorRow = editorCursor.row();
        int desiredCursorCol = editorCursor.col();
        Point searchCursor = searchBarPlacement == null
            ? null
            : searchBarPlacement.cursorPosition(searchFocusManager).orElse(null);
        boolean searchOwnsCursor = searchBarPlacement != null && !search.editorFocused();
        boolean blockingContentFocused = composition.layers().contentFocused();
        boolean topMenuOpen = topMenuPlacement != null && topMenuPlacement.state().isOpen();
        boolean desiredCursorVisible = CursorPolicy.shouldShowCursor(
            saveAsMode && !blockingContentFocused,
            searchOwnsCursor,
            searchCursor != null,
            confirmBarPlacement != null && !blockingContentFocused,
            topMenuOpen,
            openFileMode && !blockingContentFocused,
            helpMode && !blockingContentFocused
        );
        boolean editorOwnsCursor = blockingContentFocused || !saveAsMode
            && !searchOwnsCursor
            && confirmBarPlacement == null
            && !topMenuOpen
            && !openFileMode
            && !helpMode;
        if (editorOwnsCursor && !plan.cursorInViewport()) {
            desiredCursorVisible = false;
        }
        if (blockingContentFocused && blockingSurface(composition).contains(desiredCursorCol - 1, desiredCursorRow - 1)) {
            desiredCursorVisible = false;
        }
        if (saveAsMode && !blockingContentFocused) {
            Point saveAsCursor = saveAsBarPlacement == null
                ? null
                : saveAsBarPlacement.cursorPosition(saveAsFocusManager).orElse(null);
            if (saveAsCursor == null) {
                desiredCursorVisible = false;
            } else {
                desiredCursorRow = saveAsCursor.y() + 1;
                desiredCursorCol = saveAsCursor.x() + 1;
            }
        } else if (searchOwnsCursor) {
            if (searchCursor == null) {
                desiredCursorVisible = false;
            } else {
                desiredCursorRow = searchCursor.y() + 1;
                desiredCursorCol = searchCursor.x() + 1;
            }
        } else if (topMenuOpen) {
            Point menuCursor = topMenuPlacement.cursorPosition().orElse(null);
            if (menuCursor == null) {
                desiredCursorVisible = false;
            } else {
                desiredCursorRow = menuCursor.y() + 1;
                desiredCursorCol = menuCursor.x() + 1;
            }
        } else if (helpOverlay != null && !blockingContentFocused) {
            Button close = helpOverlay.close();
            desiredCursorRow = Math.min(rows, close.row() + 1);
            desiredCursorCol = Math.min(cols, close.cursorCol() + 1);
        }

        boolean canUseVerticalScrollFastPath = composition.allowsScrollHints()
            && previousPlan != null
            && plan.wrapLines() == previousPlan.wrapLines()
            && plan.gutterWidth() == previousPlan.gutterWidth()
            && plan.scrollbarWidth() == previousPlan.scrollbarWidth()
            && plan.leftVisualColumn() == previousPlan.leftVisualColumn()
            && plan.topLine() != previousPlan.topLine();
        boolean canUseHorizontalScrollFastPath = composition.allowsScrollHints()
            && previousPlan != null
            && !plan.wrapLines()
            && !state.hasSelection()
            && plan.wrapLines() == previousPlan.wrapLines()
            && plan.gutterWidth() == previousPlan.gutterWidth()
            && plan.scrollbarWidth() == previousPlan.scrollbarWidth()
            && plan.topLine() == previousPlan.topLine()
            && plan.leftVisualColumn() != previousPlan.leftVisualColumn();
        int horizontalDeltaColumns = previousPlan == null ? 0 : plan.leftVisualColumn() - previousPlan.leftVisualColumn();
        int diffRegionTop = contentTopRow;
        int diffRegionHeight = plan.contentRows();
        int textStartCol = plan.gutterWidth();
        int textEndCol = cols - plan.scrollbarWidth() - 1;
        FrameComposition.OpenTreeDiffKey openTreeDiffKey = openFile.diffKey();
        if (openFileMode && openFileTreePlacement != null) {
            Rect treeContent = openFileTreePlacement.contentBounds();
            diffRegionTop = treeContent.y();
            diffRegionHeight = treeContent.height();
            textStartCol = treeContent.x();
            textEndCol = treeContent.rightExclusive() - 1;
            textStartCol = Math.max(0, Math.min(cols - 1, textStartCol));
            textEndCol = Math.max(textStartCol, Math.min(cols - 1, textEndCol));
            canUseVerticalScrollFastPath = composition.allowsScrollHints()
                && previousPlan != null
                && openTreeDiffKey != null
                && openTreeDiffKey.isVerticalScrollFrom(lastOpenTreeDiffKey);
            canUseHorizontalScrollFastPath = false;
            horizontalDeltaColumns = 0;
        }

        int regionLeft = Math.max(0, Math.min(cols, textStartCol));
        int regionRight = Math.max(regionLeft, Math.min(cols, textEndCol + 1));
        int regionTop = Math.max(0, Math.min(rows, diffRegionTop));
        int regionBottom = Math.max(regionTop, Math.min(rows, diffRegionTop + diffRegionHeight));
        Rect diffRegion = new Rect(
            regionLeft,
            regionTop,
            regionRight - regionLeft,
            regionBottom - regionTop
        );
        ScrollHint scrollHint = ScrollHint.none();
        if (!diffRegion.isEmpty()) {
            if (canUseHorizontalScrollFastPath && horizontalDeltaColumns != 0) {
                scrollHint = new ScrollHint.Horizontal(diffRegion, horizontalDeltaColumns);
            } else if (canUseVerticalScrollFastPath) {
                scrollHint = new ScrollHint.Vertical(diffRegion);
            }
        }

        Point cursorPosition = new Point(
            Math.max(0, Math.min(cols - 1, desiredCursorCol - 1)),
            Math.max(0, Math.min(rows - 1, desiredCursorRow - 1))
        );
        Frame frame = new Frame(
            current,
            new ScreenCursor(cursorPosition, desiredCursorVisible),
            scrollHint
        );
        ScreenPresenter.PresentResult presentResult = screenPresenter.present(frame, profileEnabled);
        if (profileEnabled) {
            frameProfiler.diffNanos = presentResult.patchNanos();
        }
        lastOpenTreeDiffKey = openTreeDiffKey;
        return new RenderFrameResult(plan, true);
    }

    private String resolveRenderSearchQuery(
        boolean searchMode,
        String searchQuery,
        TextModel model,
        EditorState state,
        boolean autoSelectionHighlightSetting,
        boolean mouseSelectionInProgress
    ) {
        if (searchMode) {
            return searchQuery;
        }
        if (!autoSelectionHighlightSetting) {
            return null;
        }
        if (mouseSelectionInProgress) {
            return null;
        }
        if (!state.hasSelection()) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a == null || b == null || a.line() != b.line()) {
            return null;
        }
        if (a.col() > b.col()) {
            Position tmp = a;
            a = b;
            b = tmp;
        }
        int length = b.col() - a.col();
        if (length < AUTO_SELECTION_HIGHLIGHT_MIN_CHARS || length > AUTO_SELECTION_HIGHLIGHT_MAX_CHARS) {
            return null;
        }
        String selected = model.getTextRange(a, b);
        if (selected.isBlank() || selected.indexOf('\n') >= 0) {
            return null;
        }
        return selected;
    }

    private String selectionQueryForCtrlF(TextModel model, EditorState state) {
        if (!state.hasSelection()) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a == null || b == null || a.line() != b.line()) {
            return null;
        }
        if (a.col() > b.col()) {
            Position tmp = a;
            a = b;
            b = tmp;
        }
        int length = b.col() - a.col();
        if (length <= 0 || length > CTRL_F_SELECTION_MAX_CHARS) {
            return null;
        }
        String selected = model.getTextRange(a, b);
        return selected.isBlank() ? null : selected;
    }

    private boolean focusCurrentMatchIfOffscreen(
        TextModel model,
        EditorState state,
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords,
        String query,
        SearchOptions options
    ) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String selected = selectionQueryForCtrlF(model, state);
        if (selected == null || !searchSelectionMatchesQuery(model, state, query, options)) {
            return false;
        }
        int topBefore = state.getTopLine();
        int wrapBefore = state.getTopWrapSegment();
        int leftBefore = state.getLeftVisualColumn();
        engine.ensureCursorVisible(contentRows, contentCols, wrapLines, wrapByWords);
        return topBefore != state.getTopLine()
            || wrapBefore != state.getTopWrapSegment()
            || leftBefore != state.getLeftVisualColumn();
    }

    private boolean searchSelectionMatchesQuery(
        TextModel model,
        EditorState state,
        String query,
        SearchOptions options
    ) {
        if (!state.hasSelection()) {
            return false;
        }
        Position start = state.getSelectionAnchor();
        Position end = state.getCursor();
        if (start == null || end == null || start.line() != end.line()) {
            return false;
        }
        if (start.col() > end.col()) {
            Position temporary = start;
            start = end;
            end = temporary;
        }
        SearchMatcher matcher = SearchMatcher.compile(query, options);
        return matcher.matchesRange(model.getLine(start.line()), start.col(), end.col());
    }

    private final class RenderFrameController {
        private final RenderRasterizer renderer;
        private final EditorView editorView;
        private final boolean colorEnabled;
        private final ScreenPresenter screenPresenter;

        private RenderFrameController(
            RenderRasterizer renderer,
            EditorView editorView,
            boolean colorEnabled,
            ScreenPresenter screenPresenter
        ) {
            this.renderer = renderer;
            this.editorView = editorView;
            this.colorEnabled = colorEnabled;
            this.screenPresenter = screenPresenter;
        }

        private RenderFrameResult renderFrameIfNeeded(FrameComposition composition) {
            return renderFrame(composition, this);
        }
    }

    private String buildOverwritePrompt(Path path) {
        return "Overwrite existing file: " + path + " ? (y/N)";
    }

    private ConfirmBarView buildQuitConfirmBar(
        List<EditorTab> tabs,
        int cols
    ) {
        List<EditorTab> dirtyTabs = new ArrayList<>();
        List<Path> dirtyPaths = new ArrayList<>();
        for (EditorTab tab : tabs) {
            if (tab.state().isDirty()) {
                dirtyTabs.add(tab);
                dirtyPaths.add(tab.state().getCurrentFile());
            }
        }
        boolean multiple = dirtyTabs.size() > 1;
        String primaryLabel;
        if (multiple) {
            primaryLabel = "Save All";
        } else if (dirtyTabs.size() == 1 && requiresSaveAs(dirtyTabs.get(0))) {
            primaryLabel = "Save As";
        } else {
            primaryLabel = "Save";
        }
        String secondaryLabel = multiple ? "Discard All" : "Discard";
        int promptWidth = ConfirmBarView.promptWidth(
            cols,
            primaryLabel,
            secondaryLabel,
            true,
            "Cancel"
        );
        return ConfirmBarView.build(
            DirtyTabSummary.build(dirtyPaths, promptWidth),
            cols,
            primaryLabel,
            secondaryLabel,
            true,
            "Cancel"
        );
    }

    private ConfirmBarView buildCloseTabConfirmBar(
        List<EditorTab> tabs,
        int closeTabIndex,
        int cols
    ) {
        EditorTab pendingTab = closeTabIndex >= 0 && closeTabIndex < tabs.size()
            ? tabs.get(closeTabIndex)
            : null;
        String primaryLabel = pendingTab != null && requiresSaveAs(pendingTab) ? "Save As" : "Save";
        return ConfirmBarView.build(
            buildCloseTabPrompt(tabs, closeTabIndex),
            cols,
            primaryLabel,
            "Discard",
            true,
            "Cancel"
        );
    }

    private String buildRecoveryPrompt(int count) {
        return "Recovery snapshots found: " + count + ". Restore now? (Y/n)";
    }

    private String buildCloseTabPrompt(List<EditorTab> tabs, int closeTabIndex) {
        if (closeTabIndex < 0 || closeTabIndex >= tabs.size()) {
            return "Close modified tab? (y/N)";
        }
        Path path = tabs.get(closeTabIndex).state().getCurrentFile();
        String name = path == null ? "--no name--" : path.toString();
        return "Close modified tab: " + name + " ? (y/N)";
    }

    private boolean requiresSaveAs(EditorTab tab) {
        if (tab == null) {
            return false;
        }
        Path file = tab.state().getCurrentFile();
        return file == null || isReadOnlyFile(file);
    }

    private void prepareSaveAs(
        EditorTab tab,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification
    ) {
        Path file = tab.state().getCurrentFile();
        String suggestion = file == null
            ? saveAsSuggestions.suggestionFor(tab.tabSession(), null, DEFAULT_FILE_NAME)
            : saveAsSuggestions.suggestionFor(
                tab.tabSession(),
                null,
                suggestWritableName(file)
            );
        saveAsInput.set(suggestion);
        saveAsSuggestions.rememberDraft(tab.tabSession(), suggestion);
        notification.clear();
    }

    private SaveFlowProgress continueQuitSaveFlow(
        SessionContext sessionContext,
        List<EditorTab> tabs,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification
    ) {
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            if (tab.state().isDirty() && requiresSaveAs(tab)) {
                sessionContext.activate(i);
                prepareSaveAs(tab, saveAsInput, saveAsSuggestions, notification);
                return new SaveFlowProgress(true, UiModalState.saveAsForQuit());
            }
        }
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            if (!tab.state().isDirty()) {
                continue;
            }
            if (!saveDirtyTab(tab, notification, saveAsSuggestions)) {
                sessionContext.activate(i);
                return new SaveFlowProgress(true, UiModalState.quitConfirm());
            }
        }
        return new SaveFlowProgress(false, UiModalState.none());
    }

    private SaveFlowProgress continueAfterSuccessfulSave(
        UiModalState completedSaveState,
        SessionContext sessionContext,
        List<EditorTab> tabs,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification
    ) {
        if (completedSaveState.saveContinuation() == SaveContinuation.QUIT) {
            return continueQuitSaveFlow(
                sessionContext,
                tabs,
                saveAsInput,
                saveAsSuggestions,
                notification
            );
        }
        if (completedSaveState.saveContinuation() == SaveContinuation.CLOSE_TAB) {
            int closeIndex = completedSaveState.pendingCloseTabIndex();
            if (closeIndex >= 0 && closeIndex < tabs.size()) {
                sessionContext.activate(
                    closeTab(
                        tabs,
                        sessionContext.activeTabIndex(),
                        closeIndex,
                        saveAsSuggestions
                    )
                );
            }
        }
        return new SaveFlowProgress(true, UiModalState.none());
    }

    private boolean saveDirtyTab(
        EditorTab tab,
        NotificationState notification,
        SaveAsSuggestionStore saveAsSuggestions
    ) {
        if (tab == null || !tab.state().isDirty()) {
            return true;
        }
        Path file = tab.state().getCurrentFile();
        if (file == null) {
            notification.error("Save failed: tab has no file path. Use Save As.");
            return false;
        }
        if (isReadOnlyFile(file)) {
            notification.error(readOnlySaveAsMessage(file));
            return false;
        }
        try {
            tab.engine().save();
            saveAsSuggestions.rememberSavedPath(tab.tabSession(), file);
            return true;
        } catch (IOException e) {
            notification.error(saveErrorMessage(e));
            return false;
        }
    }

    private int restoreRecoveredTabs(
        List<RecoveryStore.Entry> entries,
        List<EditorTab> tabs,
        SessionContext sessionContext,
        RecoveryAutosaveManager recoveryAutosave,
        long nowNanos
    ) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        List<EditorTab> restored = new ArrayList<>();
        for (RecoveryStore.Entry entry : entries) {
            if (entry == null || entry.snapshot() == null) {
                continue;
            }
            TabSession tabSession = TabSession.createEmpty(TerminalEditorTextLayout::new, clipboard);
            tabSession.engine().restoreRecoverySnapshot(entry.snapshot());
            RenderPlanBuilder builder = new RenderPlanBuilder(
                resolveHighlighter(tabSession.state().getCurrentFile()),
                tabSession.textLayout()
            );
            // Keep the already committed startup snapshot as the durable backing
            // for this tab. The next autosave updates the same entry instead of
            // creating a gap in which neither the old nor a new snapshot exists.
            recoveryAutosave.adoptSnapshot(tabSession, entry.id(), nowNanos);
            restored.add(new EditorTab(tabSession, builder));
        }
        if (restored.isEmpty()) {
            return 0;
        }
        tabs.clear();
        tabs.addAll(restored);
        sessionContext.activate(0);
        return restored.size();
    }

    private String savedMessage(EditorState state) {
        return "Saved " + state.getCurrentFile() + " [" + state.getFileEncoding().displayName() + "]";
    }

    private String saveErrorMessage(IOException e) {
        String msg = e.getMessage() == null ? "Unknown I/O error" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return "Save failed: permission denied";
        }
        if (lower.contains("no space") || lower.contains("not enough space")) {
            return "Save failed: no space left on device";
        }
        if (lower.contains("is a directory")) {
            return "Save failed: target is a directory";
        }
        return "Save failed: " + msg;
    }

    private String readOnlySaveAsMessage(Path path) {
        return "File is read-only. Use Save As. " + path;
    }

    private String validateSaveTarget(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            if (!Files.exists(parent)) {
                return "Save failed: directory does not exist: " + parent;
            }
            if (!Files.isDirectory(parent)) {
                return "Save failed: parent path is not a directory: " + parent;
            }
            if (!Files.isWritable(parent)) {
                return "Save failed: no write access to directory: " + parent;
            }
        }
        if (Files.isDirectory(normalized)) {
            return "Save failed: target is a directory: " + normalized;
        }
        return null;
    }

    private boolean isReadOnlyFile(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.exists(path) && !Files.isWritable(path);
        } catch (SecurityException e) {
            return true;
        }
    }

    private boolean hasDirtyTabs(List<EditorTab> tabs) {
        return TabSessions.hasDirtyTabs(toTabSessions(tabs));
    }

    private String suggestWritableName(Path path) {
        String fileName = path.getFileName() == null ? DEFAULT_FILE_NAME : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        String candidate = base + "-copy" + ext;
        Path parent = path.getParent();
        Path preferred = parent == null ? Path.of(candidate) : parent.resolve(candidate);
        return SaveTargetSuggester.firstAvailable(preferred, Files::exists).toString();
    }

    private boolean isSameFilePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    private int countSearchMatches(TextModel model, List<EditorTab> tabs, String query, SearchOptions options) {
        if (query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        if (tabs != null) {
            return TabSearch.countMatches(toTabSessions(tabs), query, safe);
        }
        if (model == null) {
            return 0;
        }
        return TabSearch.countMatchesInModel(model, query, safe);
    }

    private long searchSourceIdentity(TextModel model, List<EditorTab> tabs, boolean allTabs) {
        if (!allTabs) {
            return model == null ? 0L : model.cacheIdentity();
        }
        long hash = 1469598103934665603L;
        for (EditorTab tab : tabs) {
            hash ^= tab.model().cacheIdentity();
            hash *= 1099511628211L;
        }
        hash ^= tabs.size();
        return hash;
    }

    private long searchSourceRevision(TextModel model, List<EditorTab> tabs, boolean allTabs) {
        if (!allTabs) {
            return model == null ? 0L : model.revision();
        }
        long hash = 1469598103934665603L;
        for (EditorTab tab : tabs) {
            hash ^= tab.model().cacheIdentity();
            hash *= 1099511628211L;
            hash ^= tab.model().revision();
            hash *= 1099511628211L;
        }
        hash ^= tabs.size();
        return hash;
    }

    private record HelpDialogLayout(int top, int left, int width, int height) {
    }

    private record HelpOverlayPlacement(
        HelpPage page,
        HelpDialogLayout layout,
        Rect viewport,
        PlacedModal frame,
        Button close,
        FocusScope<HelpOverlayInputRouter.Target, Button.Action> focusScope
    ) {
    }

    private record RenderFrameResult(
        RenderPlan plan,
        boolean rendered
    ) {
    }

    private EditorTab newEditorTab() {
        TabSession session = TabSession.createEmpty(TerminalEditorTextLayout::new, clipboard);
        RenderPlanBuilder builder = new RenderPlanBuilder(RegexHighlighter.withDefaults(), session.textLayout());
        return new EditorTab(session, builder);
    }

    private int switchTab(List<EditorTab> tabs, int currentIndex, int delta) {
        return TabSessions.switchByDelta(tabs.size(), currentIndex, delta);
    }

    private int closeCurrentTab(List<EditorTab> tabs, int activeTabIndex, SaveAsSuggestionStore saveAsSuggestions) {
        int size = tabs.size();
        saveAsSuggestions.remove(tabs.get(activeTabIndex).tabSession());
        if (tabs.size() <= 1) {
            tabs.set(0, newEditorTab());
            return 0;
        }
        tabs.remove(activeTabIndex);
        return TabSessions.closeIndex(size, activeTabIndex, activeTabIndex);
    }

    private int closeTab(
        List<EditorTab> tabs,
        int activeTabIndex,
        int closeTabIndex,
        SaveAsSuggestionStore saveAsSuggestions
    ) {
        int size = tabs.size();
        saveAsSuggestions.remove(tabs.get(closeTabIndex).tabSession());
        if (tabs.size() <= 1) {
            tabs.set(0, newEditorTab());
            return 0;
        }
        tabs.remove(closeTabIndex);
        return TabSessions.closeIndex(size, activeTabIndex, closeTabIndex);
    }

    private List<TabSession> toTabSessions(List<EditorTab> tabs) {
        List<TabSession> sessions = new ArrayList<>(tabs.size());
        for (EditorTab tab : tabs) {
            sessions.add(tab.tabSession());
        }
        return sessions;
    }

    private long tabsRevisionFingerprint(List<EditorTab> tabs) {
        long hash = 1469598103934665603L;
        for (EditorTab tab : tabs) {
            hash ^= tab.model().revision();
            hash *= 1099511628211L;
        }
        return hash;
    }

    private List<TabBarView.TabItem> toTabItems(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TabBarView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TabBarView.TabItem(
                tab.state().getCurrentFile(),
                tab.state().isDirty(),
                hasMatch
            ));
        }
        return out;
    }

    private boolean[] findTabsWithMatches(List<EditorTab> tabs, String query, SearchOptions options) {
        boolean[] flags = new boolean[tabs.size()];
        if (query == null || query.isBlank()) {
            return flags;
        }
        int[] match = TabSearch.findMatchTabs(toTabSessions(tabs), query, options);
        for (int i = 0; i < flags.length && i < match.length; i++) {
            flags[i] = match[i] != 0;
        }
        return flags;
    }

    private boolean[] resolveTabMatchFlags(
        List<EditorTab> tabs,
        boolean searchMode,
        String query,
        SearchOptions options,
        TabMatchCache cache
    ) {
        if (!searchMode || query == null || query.isBlank()) {
            return new boolean[tabs.size()];
        }
        long revisionFingerprint = tabsRevisionFingerprint(tabs);
        return cache.resolve(tabs, query, options, revisionFingerprint, () -> findTabsWithMatches(tabs, query, options));
    }

    private List<TopMenuView.TabItem> toTopMenuTabs(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TopMenuView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TopMenuView.TabItem(
                tab.state().getCurrentFile(),
                tab.state().isDirty(),
                hasMatch
            ));
        }
        return out;
    }

    private record EditorTab(
        TabSession tabSession,
        RenderPlanBuilder renderPlanBuilder,
        ViewportController viewportController
    ) {
        private EditorTab(TabSession tabSession, RenderPlanBuilder renderPlanBuilder) {
            this(tabSession, renderPlanBuilder, new ViewportController());
        }

        private EditorTab {
            if (tabSession == null
                || renderPlanBuilder == null
                || viewportController == null
                || renderPlanBuilder.textLayout() != tabSession.textLayout()) {
                throw new IllegalArgumentException("tab components must share one textLayout");
            }
        }

        private TextModel model() {
            return tabSession.model();
        }

        private EditorState state() {
            return tabSession.state();
        }

        private EditorEngine engine() {
            return tabSession.engine();
        }

        private EditorTextLayout textLayout() {
            return tabSession.textLayout();
        }

        private EditorTab withRenderPlanBuilder(RenderPlanBuilder nextBuilder) {
            return new EditorTab(tabSession, nextBuilder, viewportController);
        }
    }

    private static final class TabMatchCache {
        private String query;
        private SearchOptions options;
        private long tabsFingerprint = Long.MIN_VALUE;
        private boolean[] flags = new boolean[0];

        private boolean[] resolve(
            List<EditorTab> tabs,
            String query,
            SearchOptions options,
            long tabsFingerprint,
            java.util.function.Supplier<boolean[]> compute
        ) {
            if (this.query != null
                && this.query.equals(query)
                && this.options != null
                && this.options.equals(options)
                && this.tabsFingerprint == tabsFingerprint
                && this.flags.length == tabs.size()) {
                return this.flags;
            }
            this.query = query;
            this.options = options;
            this.tabsFingerprint = tabsFingerprint;
            this.flags = compute.get();
            return this.flags;
        }
    }

    private static final class SessionContext {
        private final List<EditorTab> tabs;
        private int activeTabIndex;
        private TextModel model;
        private EditorState state;
        private EditorEngine engine;
        private RenderPlanBuilder renderPlanBuilder;

        private SessionContext(List<EditorTab> tabs, int activeTabIndex) {
            this.tabs = tabs;
            activate(activeTabIndex);
        }

        private void activate(int nextActiveTabIndex) {
            this.activeTabIndex = Math.max(0, Math.min(nextActiveTabIndex, tabs.size() - 1));
            EditorTab active = tabs.get(this.activeTabIndex);
            this.model = active.model();
            this.state = active.state();
            this.engine = active.engine();
            this.renderPlanBuilder = active.renderPlanBuilder();
        }

        private void addNewTabAndActivate(EditorTab tab) {
            tabs.add(tab);
            activate(tabs.size() - 1);
        }

        private void replaceActiveRenderPlanBuilder(RenderPlanBuilder nextRenderPlanBuilder) {
            tabs.set(activeTabIndex, tabs.get(activeTabIndex).withRenderPlanBuilder(nextRenderPlanBuilder));
            this.renderPlanBuilder = nextRenderPlanBuilder;
        }

        private int activeTabIndex() {
            return activeTabIndex;
        }

        private TextModel model() {
            return model;
        }

        private EditorState state() {
            return state;
        }

        private EditorEngine engine() {
            return engine;
        }

        private RenderPlanBuilder renderPlanBuilder() {
            return renderPlanBuilder;
        }
    }

    private final class NotificationState {
        private enum Kind {
            SUCCESS,
            INFO,
            WARNING,
            ERROR
        }

        private String message;
        private Kind kind;
        private long expiresAtNanos;

        private boolean visible() {
            return message != null;
        }

        private void success(String message) {
            this.message = message;
            this.kind = Kind.SUCCESS;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        private void info(String message) {
            this.message = message;
            this.kind = Kind.INFO;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        private void warning(String message) {
            this.message = message;
            this.kind = Kind.WARNING;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        private void error(String message) {
            this.message = message;
            this.kind = Kind.ERROR;
            this.expiresAtNanos = 0L;
        }

        private void clearErrorOnUserAction() {
            if (kind == Kind.ERROR) {
                clear();
            }
        }

        private void clear() {
            this.message = null;
            this.kind = null;
            this.expiresAtNanos = 0L;
        }

        private CellStyle style() {
            if (kind == null) {
                return null;
            }
            return switch (kind) {
                case SUCCESS -> styles.notifySuccess();
                case INFO -> styles.notifyInfo();
                case WARNING -> styles.notifyWarning();
                case ERROR -> styles.notifyError();
            };
        }

        private boolean expireIfNeeded() {
            if (message != null && expiresAtNanos > 0L && System.nanoTime() >= expiresAtNanos) {
                clear();
                return true;
            }
            return false;
        }

        private long nanosUntilExpire(long nowNanos) {
            if (message == null || expiresAtNanos <= 0L) {
                return -1L;
            }
            return Math.max(0L, expiresAtNanos - nowNanos);
        }
    }

    private LineHighlighter resolveHighlighter(Path file) {
        return file == null ? RegexHighlighter.withDefaults() : RegexHighlighter.forFileName(file.getFileName().toString());
    }

    static void runWithRecovery(
        TuiEventLoop<TuiInputEvent> eventLoop,
        TuiApplicationRunner.Frontend<TuiInputEvent> frontend,
        RecoveryAutosaveManager recovery,
        Supplier<List<TabSession>> tabs,
        Consumer<String> reportWarning
    ) throws IOException {
        boolean completed = false;
        Throwable failure = null;
        try {
            new TuiApplicationRunner<>(eventLoop).run(frontend);
            completed = true;
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            // Only the frontend can authorize leaving unsaved edits behind.
            // EOF, external interrupts and failures stop the runtime independently.
            try {
                String warning = recovery.finishSession(tabs.get(), completed && !frontend.running());
                if (warning != null) {
                    reportWarning.accept(warning);
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    interface RecoveryPersistence {
        void save(String entryId, RecoverySnapshot snapshot) throws IOException;

        void delete(String entryId) throws IOException;
    }

    static final class RecoveryAutosaveManager {
        private final RecoveryPersistence store;
        private final LongSupplier nanoClock;
        private final Map<TabSession, RuntimeRecoveryState> runtime = new IdentityHashMap<>();
        private final Map<String, Long> pendingDeletes = new LinkedHashMap<>();
        private long lastErrorNotifyAtNanos = Long.MIN_VALUE;

        private RecoveryAutosaveManager(RecoveryStore store) {
            this(new RecoveryPersistence() {
                @Override
                public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
                    store.save(entryId, snapshot);
                }

                @Override
                public void delete(String entryId) throws IOException {
                    store.delete(entryId);
                }
            });
        }

        RecoveryAutosaveManager(RecoveryPersistence store) {
            this(store, System::nanoTime);
        }

        RecoveryAutosaveManager(RecoveryPersistence store, LongSupplier nanoClock) {
            if (store == null) {
                throw new IllegalArgumentException("store must not be null");
            }
            this.store = store;
            this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        }

        void syncTabs(List<TabSession> tabs) {
            Map<TabSession, Boolean> active = new IdentityHashMap<>();
            if (tabs != null) {
                for (TabSession session : tabs) {
                    if (session == null) {
                        continue;
                    }
                    active.put(session, Boolean.TRUE);
                    runtime.computeIfAbsent(
                        session,
                        key -> new RuntimeRecoveryState(UUID.randomUUID().toString())
                    );
                }
            }
            var iterator = runtime.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TabSession, RuntimeRecoveryState> entry = iterator.next();
                if (!active.containsKey(entry.getKey())) {
                    queueDelete(entry.getValue().entryId());
                    iterator.remove();
                }
            }
        }

        void adoptSnapshot(TabSession session, String entryId, long nowNanos) {
            if (session == null || entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("session and recovery entryId are required");
            }
            RuntimeRecoveryState adopted = new RuntimeRecoveryState(entryId);
            adopted.markSaved(session.model().revision(), nowNanos);
            RuntimeRecoveryState replaced = runtime.put(session, adopted);
            pendingDeletes.remove(entryId);
            if (replaced != null && !entryId.equals(replaced.entryId())) {
                queueDelete(replaced.entryId());
            }
        }

        String autosaveDirtyTabs(List<TabSession> tabs, long nowNanos) {
            return autosaveDirtyTabs(tabs, nowNanos, false);
        }

        private String autosaveDirtyTabs(List<TabSession> tabs, long nowNanos, boolean force) {
            String warning = retryPendingDeletes(nowNanos, force);
            if (tabs == null || tabs.isEmpty()) {
                return warning;
            }
            for (TabSession session : tabs) {
                if (session == null) {
                    continue;
                }
                RuntimeRecoveryState state = runtime.computeIfAbsent(
                    session,
                    key -> new RuntimeRecoveryState(UUID.randomUUID().toString())
                );
                if (!session.state().isDirty()) {
                    if (state.hasSnapshot()) {
                        if (!force && !state.cleanupDue(nowNanos)) {
                            continue;
                        }
                        state.markCleanupAttempt(nowNanos);
                        try {
                            store.delete(state.entryId());
                            pendingDeletes.remove(state.entryId());
                            state.clearSnapshotState();
                        } catch (IOException e) {
                            warning = firstNonNull(
                                warning,
                                failureWarning("Recovery cleanup failed", e, nowNanos, force)
                            );
                        }
                    }
                    continue;
                }

                long revision = session.model().revision();
                if (!force && state.hasSnapshot() && revision == state.lastRevision()) {
                    continue;
                }
                if (!force && !state.saveDue(nowNanos)) {
                    continue;
                }
                state.markSaveAttempt(nowNanos);
                try {
                    store.save(state.entryId(), session.engine().captureRecoverySnapshot());
                    state.markSaved(revision, nowNanos);
                } catch (IOException e) {
                    warning = firstNonNull(
                        warning,
                        failureWarning("Recovery autosave failed", e, nowNanos, force)
                    );
                }
            }
            return warning;
        }

        DeletionResult deleteEntries(List<RecoveryStore.Entry> entries) {
            if (entries == null || entries.isEmpty()) {
                return new DeletionResult(0, List.of(), List.of());
            }
            int deleted = 0;
            List<RecoveryStore.Entry> failed = new ArrayList<>();
            List<String> failureDetails = new ArrayList<>();
            for (RecoveryStore.Entry entry : entries) {
                if (entry == null) {
                    continue;
                }
                try {
                    store.delete(entry.id());
                    pendingDeletes.remove(entry.id());
                    deleted++;
                } catch (IOException e) {
                    failed.add(entry);
                    failureDetails.add(entry.id() + ": " + messageOf(e));
                }
            }
            return new DeletionResult(deleted, failed, failureDetails);
        }

        String finishSession(List<TabSession> tabs, boolean exitConfirmed) {
            syncTabs(tabs);
            if (exitConfirmed) {
                return clearRuntimeSnapshots();
            }
            // A final input event may have changed the document immediately
            // before EOF/interrupt. Flush it even inside the autosave debounce.
            return autosaveDirtyTabs(tabs, nanoClock.getAsLong(), true);
        }

        private String clearRuntimeSnapshots() {
            for (RuntimeRecoveryState state : runtime.values()) {
                queueDelete(state.entryId());
            }
            runtime.clear();
            return retryPendingDeletes(nanoClock.getAsLong(), true);
        }

        private void queueDelete(String entryId) {
            if (entryId == null || entryId.isBlank()) {
                return;
            }
            pendingDeletes.putIfAbsent(entryId, Long.MIN_VALUE);
        }

        private String retryPendingDeletes(long nowNanos, boolean force) {
            String warning = null;
            var iterator = pendingDeletes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> pending = iterator.next();
                if (!force && !delayElapsed(nowNanos, pending.getValue(), RECOVERY_AUTOSAVE_DEBOUNCE_NANOS)) {
                    continue;
                }
                pending.setValue(nowNanos);
                try {
                    store.delete(pending.getKey());
                    iterator.remove();
                } catch (IOException e) {
                    warning = firstNonNull(
                        warning,
                        failureWarning("Recovery cleanup failed", e, nowNanos, force)
                    );
                }
            }
            return warning;
        }

        private String failureWarning(String prefix, IOException error, long nowNanos, boolean force) {
            if (force) {
                return prefix + ": " + messageOf(error);
            }
            if (!delayElapsed(
                nowNanos,
                lastErrorNotifyAtNanos,
                RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS
            )) {
                return null;
            }
            lastErrorNotifyAtNanos = nowNanos;
            return prefix + ": " + messageOf(error);
        }

        private static boolean delayElapsed(long nowNanos, long previousNanos, long delayNanos) {
            return previousNanos == Long.MIN_VALUE || nowNanos - previousNanos >= delayNanos;
        }

        private static String firstNonNull(String current, String candidate) {
            return current != null ? current : candidate;
        }

        private static String messageOf(IOException error) {
            return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }

        record DeletionResult(
            int deletedCount,
            List<RecoveryStore.Entry> failedEntries,
            List<String> failureDetails
        ) {
            DeletionResult {
                failedEntries = List.copyOf(failedEntries);
                failureDetails = List.copyOf(failureDetails);
            }

            boolean successful() {
                return failedEntries.isEmpty();
            }

            String failureMessage() {
                if (successful()) {
                    return "";
                }
                return "Failed to discard " + failedEntries.size()
                    + " recovery snapshot(s); they were kept for retry or restore: "
                    + String.join("; ", failureDetails);
            }
        }
    }

    private static final class RuntimeRecoveryState {
        private final String entryId;
        private long lastRevision = Long.MIN_VALUE;
        private long lastSaveAttemptAtNanos = Long.MIN_VALUE;
        private long lastCleanupAttemptAtNanos = Long.MIN_VALUE;
        private boolean hasSnapshot;

        private RuntimeRecoveryState(String entryId) {
            this.entryId = entryId;
        }

        private String entryId() {
            return entryId;
        }

        private long lastRevision() {
            return lastRevision;
        }

        private boolean hasSnapshot() {
            return hasSnapshot;
        }

        private void markSaved(long revision, long nowNanos) {
            lastRevision = revision;
            lastSaveAttemptAtNanos = nowNanos;
            lastCleanupAttemptAtNanos = Long.MIN_VALUE;
            hasSnapshot = true;
        }

        private boolean saveDue(long nowNanos) {
            return RecoveryAutosaveManager.delayElapsed(
                nowNanos,
                lastSaveAttemptAtNanos,
                RECOVERY_AUTOSAVE_DEBOUNCE_NANOS
            );
        }

        private void markSaveAttempt(long nowNanos) {
            lastSaveAttemptAtNanos = nowNanos;
        }

        private boolean cleanupDue(long nowNanos) {
            return RecoveryAutosaveManager.delayElapsed(
                nowNanos,
                lastCleanupAttemptAtNanos,
                RECOVERY_AUTOSAVE_DEBOUNCE_NANOS
            );
        }

        private void markCleanupAttempt(long nowNanos) {
            lastCleanupAttemptAtNanos = nowNanos;
        }

        private void clearSnapshotState() {
            hasSnapshot = false;
            lastSaveAttemptAtNanos = Long.MIN_VALUE;
            lastCleanupAttemptAtNanos = Long.MIN_VALUE;
        }
    }

    private boolean effectiveSyntaxHighlighting(boolean syntaxHighlightingSetting) {
        return theme != UiStyles.Theme.LIGHT && syntaxHighlightingSetting;
    }

    private void applyTheme(UiStyles.Theme theme) {
        this.theme = theme == null ? UiStyles.Theme.CYAN : theme;
        this.styles = UiStyles.forTheme(this.theme);
    }

    private void printUsage(PrintStream output) {
        output.println("Usage: tuink [options] [file]");
        output.println("Development: ./gradlew --console=plain :app:run -PappArgs=\"[options] [file]\"");
        output.println("Options:");
        output.println("  --no-mouse       Disable mouse tracking and mouse interactions");
        output.println("  --no-alt-screen  Do not use alternate screen buffer");
        output.println("  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        output.println("  --theme=<name>   UI palette: blue, cyan (default), light");
        output.println("  --read           Start in reading mode (editing disabled)");
        output.println("  --profile        Log render timings to stderr every " + PROFILE_LOG_EVERY_FRAMES + " frames");
        output.println("  --profile-file   Create a new profile log file (also enables --profile)");
        output.println("  --help, -h       Show this help");
    }

    private boolean shouldForceExecTerminalProvider() {
        return "exec".equalsIgnoreCase(System.getProperty("dev.tuiframe.provider", ""));
    }

    private final class FrameProfiler {
        private long frameStartNanos;
        private long searchNanos;
        private long buildNanos;
        private long rasterNanos;
        private long diffNanos;
        private long totalNanos;
        private long frameIndex;
        private long stopwatchStartNanos;
        private PrintWriter out = new PrintWriter(System.err, true);
        private boolean closeOut;

        private void setOutput(PrintWriter out, boolean closeOut) {
            if (out == null) {
                return;
            }
            this.out = out;
            this.closeOut = closeOut;
        }

        private void beginFrame() {
            frameStartNanos = System.nanoTime();
            stopwatchStartNanos = frameStartNanos;
            searchNanos = 0L;
            buildNanos = 0L;
            rasterNanos = 0L;
            diffNanos = 0L;
        }

        private long stopwatchNanos() {
            long now = System.nanoTime();
            long delta = now - stopwatchStartNanos;
            stopwatchStartNanos = now;
            return delta;
        }

        private void endFrame(boolean rendered) {
            if (!rendered) {
                return;
            }
            totalNanos = System.nanoTime() - frameStartNanos;
            frameIndex++;
            if (frameIndex % PROFILE_LOG_EVERY_FRAMES == 0) {
                log();
            }
        }

        private void log() {
            out.printf(
                Locale.ROOT,
                "frame=%d build=%.2fms raster=%.2fms diff=%.2fms search=%.2fms total=%.2fms%n",
                frameIndex,
                buildNanos / 1_000_000.0,
                rasterNanos / 1_000_000.0,
                diffNanos / 1_000_000.0,
                searchNanos / 1_000_000.0,
                totalNanos / 1_000_000.0
            );
            out.flush();
        }

        private void close() {
            if (closeOut) {
                out.close();
            }
        }
    }
}

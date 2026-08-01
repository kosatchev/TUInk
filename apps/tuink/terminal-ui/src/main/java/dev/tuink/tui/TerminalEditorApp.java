package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.EditorViewportBehavior;
import dev.tuink.terminal.text.TerminalText;
import dev.tuink.core.LanguageProfiles;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSessions;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.AppSettingsStore;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.CommandInvocation;
import dev.tuink.tui.input.KeyDecoder;
import dev.tuink.tui.input.KeyEvent;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.tui.input.LegacyKeyEventAdapter;
import dev.tuink.terminal.input.PasteEvent;
import dev.tuink.terminal.input.ResizeEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.input.TuiInputEvent;
import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.tui.presentation.ScreenPresenter;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.terminal.runtime.TuiEventLoop;
import dev.tuink.terminal.screen.Frame;
import dev.tuink.terminal.screen.ScreenCursor;
import dev.tuink.terminal.screen.ScrollHint;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.terminal.TerminalSession;
import dev.tuink.tui.ui.Button;
import dev.tuink.tui.ui.ConfirmBarController;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.DirtyTabSummary;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.MenuBar;
import dev.tuink.tui.ui.Modal;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.HelpPages;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogButtons;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.CursorPolicy;
import dev.tuink.tui.ui.TabBar;
import dev.tuink.tui.ui.TextField;
import dev.tuink.tui.ui.TreeView;
import dev.tuink.tui.ui.ViewportController;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * JLine terminal application wiring core + render + highlighter.
 */
public class TerminalEditorApp {
    private static final String DEFAULT_FILE_NAME = "untitled.txt";
    private static final String SAVE_AS_PROMPT = "Save As: ";
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    private static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final int PROFILE_LOG_EVERY_FRAMES = 30;
    private static final int AUTO_SELECTION_HIGHLIGHT_MIN_CHARS = 2;
    private static final int AUTO_SELECTION_HIGHLIGHT_MAX_CHARS = 128;
    private static final int CTRL_F_SELECTION_MAX_CHARS = 256;
    private static final long RECOVERY_AUTOSAVE_DEBOUNCE_NANOS = 2_000_000_000L;
    private static final long RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS = 5_000_000_000L;
    private UiStyles.Theme theme = UiStyles.Theme.CYAN;
    private UiStyles.Palette styles = UiStyles.forTheme(theme);
    private boolean profileEnabled = false;
    private final FrameProfiler frameProfiler = new FrameProfiler();
    private boolean lastOpenFileModeForDiff = false;
    private int lastOpenFileScrollForDiff = 0;

    public static void main(String[] args) throws Exception {
        new TerminalEditorApp().run(args);
    }

    private void run(String[] args) throws Exception {
        TuiCliOptions cli;
        try {
            cli = TuiCliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return;
        }
        if (cli.showHelp()) {
            printUsage();
            return;
        }
        profileEnabled = cli.profileEnabled();
        if (cli.profileFile() != null) {
            PrintWriter profileWriter = new PrintWriter(
                Files.newBufferedWriter(
                    cli.profileFile(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                ),
                true
            );
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
        RecoveryStore recoveryStore = new RecoveryStore();
        RecoveryAutosaveManager recoveryAutosave = new RecoveryAutosaveManager(recoveryStore);
        List<RecoveryStore.Entry> startupRecoveryEntries = new ArrayList<>();
        TextField saveAsInput = new TextField();
        SaveAsSuggestionStore saveAsSuggestions = new SaveAsSuggestionStore();
        UiSessionState uiSessionState = UiSessionState.from(
            false,
            SearchFocus.FIND,
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
        MenuBar topMenu = new MenuBar(
            styles.menuPopup(),
            styles.menuSelected(),
            styles.menuTopSelected(),
            styles.menuSelectedMono()
        );
        topMenu.setThemeLabel(theme.label());
        TabBar tabBar = new TabBar(
            styles.tabbar(),
            styles.tabbarMono(),
            styles.tabbarActive(),
            styles.tabbarActiveMono()
        );
        boolean openFileMode = false;
        TreeView openFileTree = new TreeView(initialOpenRoot(state), true, true);
        OpenFileDialogController.Focus openFileFocus = OpenFileDialogController.Focus.LIST;
        EditorView.ScrollAxis editorScrollDrag = EditorView.ScrollAxis.NONE;
        int editorScrollDragThumbOffset = 0;
        TreeView.ScrollAxis openFileScrollDrag = TreeView.ScrollAxis.NONE;
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

        try (TerminalSession terminal = TerminalSession.open(
            new TerminalSession.Options(
                cli.altScreenEnabled(),
                uiSessionState.mouseModeSetting,
                shouldForceExecTerminalProvider()
            )
        )) {
            PrintWriter writer = terminal.writer();
            var input = terminal.reader();

            RenderPlan plan = null;
            ScreenPresenter screenPresenter = new ScreenPresenter(writer, cli.colorEnabled());
            try {
                KeyDecoder decoder = new KeyDecoder(input);
                LegacyCommandBridge commandBridge = new LegacyCommandBridge();
                TuiEventLoop<TuiInputEvent> eventLoop = new TuiEventLoop<>(timeoutMillis -> {
                    KeyDecoder.ReadResult result = decoder.read(timeoutMillis);
                    return switch (result.status()) {
                        case EVENT -> TuiEventLoop.Read.event(result.event());
                        case IDLE -> TuiEventLoop.Read.idle();
                        case END -> TuiEventLoop.Read.end();
                    };
                });
                terminal.onResize(() -> eventLoop.requestResize(
                    new ResizeEvent(
                        Math.max(2, terminal.height()),
                        Math.max(1, terminal.width())
                    )
                ));
                terminal.onInterrupt(eventLoop::requestStop);
                RenderFrameController renderFrameController = new RenderFrameController(
                    renderer,
                    editorView,
                    tabBar,
                    topMenu,
                    cli.colorEnabled(),
                    screenPresenter
                );
                ViewportController viewportController = new ViewportController();
                boolean running = true;
                SearchBarView searchBar = null;
                ConfirmBarView confirmBar = null;
                InlinePromptBar inlinePromptBar = InlinePromptBar.hidden();
                boolean showTabBar = false;
                int contentTopRow = 1;
                int contentRows = 0;
                int contentCols = 0;
                List<TabBar.TabItem> tabItems = List.of();
                TabMatchCache tabMatchCache = new TabMatchCache();
                while (running && eventLoop.running()) {
                    activeTabIndex = sessionContext.activeTabIndex();
                    model = sessionContext.model();
                    state = sessionContext.state();
                    engine = sessionContext.engine();
                    renderPlanBuilder = sessionContext.renderPlanBuilder();
                    recoveryAutosave.syncTabs(tabs);
                    String recoveryWarning = recoveryAutosave.autosaveDirtyTabs(tabs, System.nanoTime());
                    if (recoveryWarning != null) {
                        notification.warning(recoveryWarning);
                    }
                    if (notification.expireIfNeeded()) {
                        eventLoop.invalidate();
                    }
                    if (openFileMode && openFileTree.consumeMetadataChanged()) {
                        eventLoop.invalidate();
                    }
                    int rows = Math.max(2, terminal.height());
                    int cols = Math.max(1, terminal.width());
                    boolean saveAsMode = modalState.isSaveAs();
                    boolean saveOverwriteConfirmMode = modalState.isSaveOverwriteConfirm();
                    boolean recoveryConfirmMode = modalState.isRecoveryConfirm();
                    boolean quitConfirmMode = modalState.isQuitConfirm();
                    boolean closeTabConfirmMode = modalState.isCloseTabConfirm();
                    boolean helpHintsMode = modalState.isHelpHints();
                    boolean helpLicensesMode = modalState.isHelpLicenses();
                    boolean helpAuthorMode = modalState.isHelpAuthor();
                    Path pendingOverwritePath = modalState.pendingOverwritePath();
                    int pendingCloseTabIndex = modalState.pendingCloseTabIndex();
                    TuiEventLoop.FrameTicket frameTicket = eventLoop.beginFrame();
                    if (frameTicket != null) {
                        if (frameTicket.fullRedraw()) {
                            screenPresenter.invalidate();
                        }
                        if (profileEnabled) {
                            frameProfiler.beginFrame();
                        }
                        int searchMatchCount = countSearchMatches(
                            searchUi.allTabs() ? null : model,
                            searchUi.allTabs() ? tabs : null,
                            searchQueryInput.text(),
                            SearchController.toSearchOptions(searchUi)
                        );
                        if (profileEnabled) {
                            frameProfiler.searchNanos = frameProfiler.stopwatchNanos();
                        }
                        searchBar = uiSessionState.searchMode
                            ? buildSearchBar(
                                searchQueryInput,
                                searchReplaceInput,
                                uiSessionState.searchFocus,
                                cols,
                                searchUi,
                                searchMatchCount
                            )
                            : null;
                        String searchSummary = buildSearchSummary(
                            uiSessionState.searchMode,
                            searchQueryInput.text(),
                            searchUi,
                            searchMatchCount,
                            tabs.size()
                        );
                        confirmBar = null;
                        inlinePromptBar = InlinePromptBar.hidden();
                        if (saveAsMode) {
                            inlinePromptBar = InlinePromptBar.of(
                                buildSaveAsPrompt(saveAsInput),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                TerminalText.visualWidth(SAVE_AS_PROMPT, 4) + saveAsInput.cursorCellColumn() + 1
                            );
                        } else if (saveOverwriteConfirmMode) {
                            confirmBar = ConfirmBarView.build(
                                buildOverwritePrompt(pendingOverwritePath),
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                confirmBar.cursorCol() + 1
                            );
                        } else if (recoveryConfirmMode) {
                            confirmBar = ConfirmBarView.buildRecovery(
                                buildRecoveryPrompt(startupRecoveryEntries.size()),
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                confirmBar.cursorCol() + 1
                            );
                        } else if (quitConfirmMode) {
                            confirmBar = buildQuitConfirmBar(tabs, modalState.confirmChoice(), cols);
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                confirmBar.cursorCol() + 1
                            );
                        } else if (closeTabConfirmMode) {
                            confirmBar = buildCloseTabConfirmBar(
                                tabs,
                                pendingCloseTabIndex,
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(
                                confirmBar.row(),
                                EditorStyleAdapter.toEditor(styles.promptAlert()),
                                1,
                                confirmBar.cursorCol() + 1
                            );
                        } else if (searchBar != null) {
                            inlinePromptBar = InlinePromptBar.of(
                                searchBar.text(),
                                EditorStyleAdapter.toEditor(styles.searchBar()),
                                searchBar.cursorRow(),
                                searchBar.cursorCol()
                            );
                        }
                        NotificationBar notificationBar = NotificationBar.of(
                            notification.message,
                            EditorStyleAdapter.toEditor(notification.style())
                        );
                        showTabBar = uiSessionState.showTabBarSetting && tabs.size() > 1;
                        int overlayRows = inlinePromptBar.height();
                        contentTopRow = showTabBar ? 2 : 1;
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
                                uiSessionState.wrapWordsSetting
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
                        boolean[] tabMatchFlags = resolveTabMatchFlags(
                            tabs,
                            uiSessionState.searchMode,
                            searchQueryInput.text(),
                            SearchController.toSearchOptions(searchUi),
                            tabMatchCache
                        );
                        tabItems = toTabItems(tabs, tabMatchFlags);
                        topMenu.setOpenTabs(toMenuTabs(tabs, tabMatchFlags), activeTabIndex);
                        topMenu.setShowTabBarEnabled(uiSessionState.showTabBarSetting);
                        topMenu.setWrapLinesEnabled(uiSessionState.wrapLinesSetting);
                        topMenu.setWrapWordsEnabled(uiSessionState.wrapWordsSetting);
                        topMenu.setReadingModeEnabled(uiSessionState.readingModeSetting);
                        topMenu.setConfirmOnCloseEnabled(uiSessionState.confirmOnCloseSetting);
                        topMenu.setMouseModeEnabled(uiSessionState.mouseModeSetting);
                        topMenu.setActiveGutterHighlightEnabled(uiSessionState.highlightActiveGutterSetting);
                        topMenu.setSmartHomeEndEnabled(uiSessionState.smartHomeEndSetting);
                        topMenu.setAutoIndentEnabled(uiSessionState.autoIndentSetting);
                        topMenu.setAutoPairsEnabled(uiSessionState.autoPairsSetting);
                        topMenu.setAutoListEnabled(uiSessionState.autoListSetting);
                        topMenu.setVisibleWhitespaceEnabled(uiSessionState.visibleWhitespaceSetting);
                        topMenu.setSyntaxHighlightingEnabled(
                            effectiveSyntaxHighlighting(uiSessionState.syntaxHighlightingSetting)
                        );
                        topMenu.setRainbowBracketsEnabled(uiSessionState.rainbowBracketsSetting);
                        topMenu.setAutoSelectionHighlightEnabled(uiSessionState.autoSelectionHighlightSetting);
                        topMenu.setGutterDiffMode(uiSessionState.gutterDiffModeSetting);
                        topMenu.setTabWidth(uiSessionState.tabWidthSetting);
                        topMenu.setTabInsertMode(uiSessionState.tabInsertModeSetting);
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
                        engine.setTabWidth(uiSessionState.tabWidthSetting);
                        engine.setTabInsertMode(uiSessionState.tabInsertModeSetting);
                        engine.setAutoIndentEnabled(uiSessionState.autoIndentSetting);
                        engine.setAutoPairsEnabled(uiSessionState.autoPairsSetting);
                        engine.setAutoListEnabled(uiSessionState.autoListSetting);

                        String topBar = topMenu.buildTopBar(
                            state.getCurrentFile(),
                            state.isDirty(),
                            activeTabIndex,
                            tabs.size(),
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
                        String interactiveBarText = inlinePromptBar.text();
                        Style interactiveBarStyle = inlinePromptBar.style();
                        RenderFrameResult frameResult = renderFrameController.renderFrameIfNeeded(
                            renderPlanBuilder,
                            model,
                            state,
                            rows,
                            cols,
                            contentTopRow,
                            showTabBar,
                            activeTabIndex,
                            tabItems,
                            editorLayout,
                            inlinePromptBar.height(),
                            topBar,
                            bottomBar,
                            uiSessionState.searchMode,
                            searchQueryInput.text(),
                            searchUi,
                            uiSessionState.searchFocus,
                            interactiveBarText,
                            interactiveBarStyle,
                            notificationBar,
                            uiSessionState.wrapLinesSetting,
                            uiSessionState.wrapWordsSetting,
                            uiSessionState.autoSelectionHighlightSetting,
                            modalState,
                            saveAsInput,
                            tabs,
                            searchBar,
                            confirmBar,
                            openFileMode,
                            openFileTree,
                            openFileFocus,
                            helpHintsMode,
                            helpLicensesMode,
                            helpAuthorMode,
                            plan
                        );
                        plan = frameResult.plan();
                        eventLoop.endFrame(frameTicket, frameResult.rendered());
                        if (profileEnabled) {
                            frameProfiler.endFrame(frameResult.rendered());
                        }
                    }

                    TuiEventLoop.Poll<TuiInputEvent> pollResult = eventLoop.poll(notification::nanosUntilExpire);
                    TuiInputEvent inputEvent = pollResult.event();
                    if (inputEvent == null) {
                        continue;
                    }
                    if (inputEvent instanceof ResizeEvent) {
                        continue;
                    }
                    notification.clearErrorOnUserAction();
                    eventLoop.invalidate();
                    CommandInvocation pendingCommand = inputEvent instanceof KeyStroke stroke
                        ? commandBridge.invocationFor(stroke).orElse(null)
                        : null;

                    if (inputEvent instanceof PasteEvent paste) {
                        if (paste.truncated()) {
                            notification.error("Paste is too large and was not inserted");
                            continue;
                        }
                        if (modalState.isSaveAs()) {
                            String singleLine = singleLinePaste(paste.text());
                            saveAsInput.insert(singleLine);
                            saveAsSuggestions.rememberDraft(
                                tabs.get(activeTabIndex).tabSession(),
                                saveAsInput.text()
                            );
                        } else if (!modalState.isNone() || openFileMode || topMenu.isOpen()) {
                            // The focused overlay owns input; paste cannot leak through it.
                        } else if (uiSessionState.searchMode) {
                            TextField target = uiSessionState.searchFocus == SearchFocus.REPLACE
                                ? searchReplaceInput
                                : uiSessionState.searchFocus == SearchFocus.FIND ? searchQueryInput : null;
                            if (target != null) {
                                target.insert(singleLinePaste(paste.text()));
                            }
                        } else {
                            if (uiSessionState.readingModeSetting) {
                                notification.warning("Reading mode: editing is disabled");
                            } else {
                                engine.insertInput(paste.text());
                                viewportController.onEditorTextInteraction();
                            }
                        }
                        continue;
                    }

                    if (inputEvent instanceof TextInput text
                        && text.text().codePointCount(0, text.text().length()) > 1) {
                        if (modalState.isSaveAs()) {
                            saveAsInput.insert(text.text());
                            saveAsSuggestions.rememberDraft(
                                tabs.get(activeTabIndex).tabSession(),
                                saveAsInput.text()
                            );
                        } else if (!modalState.isNone() || openFileMode || topMenu.isOpen()) {
                            // Multi-scalar IME input is ignored by overlays that only accept keys.
                        } else if (uiSessionState.searchMode) {
                            TextField target = uiSessionState.searchFocus == SearchFocus.REPLACE
                                ? searchReplaceInput
                                : uiSessionState.searchFocus == SearchFocus.FIND ? searchQueryInput : null;
                            if (target != null) {
                                target.insert(text.text());
                            }
                        } else if (uiSessionState.readingModeSetting) {
                            notification.warning("Reading mode: editing is disabled");
                        } else {
                            engine.insertInput(text.text());
                            viewportController.onEditorTextInteraction();
                        }
                        continue;
                    }

                    KeyEvent key = LegacyKeyEventAdapter.toLegacy(inputEvent).orElse(null);
                    if (key == null) {
                        continue;
                    }
                    viewportController.onInput(key);

                    if (key instanceof KeyEvent.MouseEvent mouse) {
                        if (!uiSessionState.mouseModeSetting) {
                            continue;
                        }
                        if (helpHintsMode || helpLicensesMode || helpAuthorMode) {
                            if (mouse.kind() == KeyEvent.MouseKind.PRESS
                                && mouse.button() == KeyEvent.MouseButton.LEFT) {
                                HelpPage helpPage = activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode);
                                HelpDialogLayout layout = helpDialogLayout(rows, cols, helpPage.rows().length);
                                Modal frame = infoDialogFrame(layout, helpPage.title());
                                Button close = helpCloseButton(layout);
                                if (frame.closeButton().hit(mouse.row(), mouse.col())
                                    || close.hit(mouse.row(), mouse.col())) {
                                    modalState = UiModalState.none();
                                }
                            }
                            continue;
                        }
                        KeyEvent remappedMouseKey = null;
                        switch (mouse.kind()) {
                            case SCROLL_UP -> {
                                OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
                                if (OpenFileDialogController.handleScroll(openFileMode, openFileTree, layout.listRows(), false)) {
                                    break;
                                }
                                if (showTabBar && mouse.row() <= 1 && !topMenu.isOpen()) {
                                    tabBar.scrollLeft();
                                    break;
                                }
                                if (modalState.isNone()) {
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
                            }
                            case SCROLL_DOWN -> {
                                OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
                                if (OpenFileDialogController.handleScroll(openFileMode, openFileTree, layout.listRows(), true)) {
                                    break;
                                }
                                if (showTabBar && mouse.row() <= 1 && !topMenu.isOpen()) {
                                    tabBar.scrollRight(tabItems);
                                    break;
                                }
                                if (modalState.isNone()) {
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
                            }
                            case PRESS -> {
                                if (mouse.button() == KeyEvent.MouseButton.LEFT) {
                                    editorScrollDrag = EditorView.ScrollAxis.NONE;
                                    editorScrollDragThumbOffset = 0;
                                    openFileScrollDrag = TreeView.ScrollAxis.NONE;
                                    if (openFileMode) {
                                        OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
                                        Modal frame = openFileFrame(layout);
                                        OpenFileDialogController.OpenDialogButtons buttons = OpenFileDialogController.buttonsForLayout(layout);
                                        Button hiddenToggle = openFileHiddenToggleButton(layout, openFileTree.showHiddenFiles());
                                        if (hiddenToggle.hit(mouse.row(), mouse.col())) {
                                            openFileTree.setShowHiddenFiles(!openFileTree.showHiddenFiles());
                                            openFileFocus = OpenFileDialogController.Focus.LIST;
                                            continue;
                                        }
                                        int openFileActiveTabIndex = activeTabIndex;
                                        OpenFileDialogController.MousePressResult mouseResult =
                                            OpenFileDialogController.handleMousePress(
                                                openFileMode,
                                                openFileFocus,
                                                openFileTree,
                                                openFileActiveTabIndex,
                                                mouse.row(),
                                                mouse.col(),
                                                layout.left(),
                                                layout.right(),
                                                layout.listStartRow(),
                                                layout.listRows(),
                                                frame.closeButton().hit(mouse.row(), mouse.col()),
                                                buttons.ok().hit(mouse.row(), mouse.col()),
                                                buttons.cancel().hit(mouse.row(), mouse.col()),
                                                System.nanoTime(),
                                                OPEN_DIALOG_DOUBLE_CLICK_NANOS,
                                                selected -> {
                                                    return activateOpenFileSelection(
                                                        selected,
                                                        tabs,
                                                        openFileActiveTabIndex,
                                                        notification
                                                    );
                                                }
                                            );
                                        if (mouseResult.consumed()) {
                                            openFileMode = mouseResult.openFileMode();
                                            openFileFocus = mouseResult.focus();
                                            openFileScrollDrag = mouseResult.dragAxis();
                                            if (mouseResult.activeTabIndex() != activeTabIndex) {
                                                sessionContext.activate(mouseResult.activeTabIndex());
                                            }
                                            continue;
                                        }
                                        continue;
                                    }
                                    if (topMenu.handleTopBarClick(mouse.row(), mouse.col())) {
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    TabBar.Hit tabHit = showTabBar
                                        ? tabBar.hit(mouse.row(), mouse.col())
                                        : new TabBar.Hit(false, -1, false, false, false);
                                    if (modalState.isCloseTabConfirm() && tabHit.consumed()) {
                                        if (tabHit.scrollLeft()) {
                                            tabBar.scrollLeft();
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        if (tabHit.scrollRight()) {
                                            tabBar.scrollRight(tabItems);
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        int clickedTabIndex = tabHit.tabIndex();
                                        if (tabHit.closeButton() && clickedTabIndex >= 0 && clickedTabIndex < tabs.size()) {
                                            if (clickedTabIndex == modalState.pendingCloseTabIndex()) {
                                                sessionContext.activate(
                                                    closeTab(tabs, activeTabIndex, modalState.pendingCloseTabIndex(), saveAsSuggestions)
                                                );
                                                modalState = UiModalState.none();
                                            } else if (tabs.get(clickedTabIndex).state().isDirty()) {
                                                modalState = UiModalState.closeTabConfirm(clickedTabIndex);
                                            } else {
                                                sessionContext.activate(closeTab(tabs, activeTabIndex, clickedTabIndex, saveAsSuggestions));
                                                modalState = UiModalState.none();
                                            }
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    if (tabHit.consumed() && modalState.isNone() && !uiSessionState.searchMode) {
                                        if (tabHit.scrollLeft()) {
                                            tabBar.scrollLeft();
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        if (tabHit.scrollRight()) {
                                            tabBar.scrollRight(tabItems);
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        int clickedTabIndex = tabHit.tabIndex();
                                        if (clickedTabIndex >= 0 && clickedTabIndex < tabs.size()) {
                                            if (tabHit.closeButton()) {
                                                if (tabs.get(clickedTabIndex).state().isDirty()) {
                                                    if (uiSessionState.confirmOnCloseSetting) {
                                                        modalState = UiModalState.closeTabConfirm(clickedTabIndex);
                                                    } else {
                                                        sessionContext.activate(
                                                            closeTab(tabs, activeTabIndex, clickedTabIndex, saveAsSuggestions)
                                                        );
                                                    }
                                                } else {
                                                    sessionContext.activate(closeTab(tabs, activeTabIndex, clickedTabIndex, saveAsSuggestions));
                                                }
                                            } else {
                                                sessionContext.activate(clickedTabIndex);
                                            }
                                            topMenu.close();
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    MenuBar.PopupMouseResult menuMouse = topMenu.handlePopupClick(mouse.row(), mouse.col(), cols);
                                    if (menuMouse.consumed()) {
                                        Integer tabActivation = topMenu.consumeTabActivationRequest();
                                        if (tabActivation != null && tabActivation >= 0 && tabActivation < tabs.size()) {
                                            CommandInvocation activation = commandBridge.tabActivationInvocation(
                                                tabActivation
                                            );
                                            int resolvedTab = commandBridge.invokeTabActivation(activation).orElse(-1);
                                            if (resolvedTab >= 0 && resolvedTab < tabs.size()) {
                                                sessionContext.activate(resolvedTab);
                                            }
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        if (menuMouse.mappedAction() != null) {
                                            pendingCommand = commandBridge.invocationFor(
                                                menuMouse.mappedAction()
                                            ).orElse(null);
                                            remappedMouseKey = new KeyEvent.ActionEvent(menuMouse.mappedAction());
                                            editorView.cancelMouseSelection();
                                            break;
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    if (confirmBar != null) {
                                        int confirmBarTopRow = rows - 1 - inlinePromptBar.height();
                                        ConfirmBarController.InputResult confirmMouseResult =
                                            ConfirmBarController.handleMousePress(
                                                confirmBar,
                                                mouse.row(),
                                                mouse.col(),
                                                confirmBarTopRow
                                            );
                                        if (confirmMouseResult.consumed()) {
                                            modalState = modalState.withConfirmChoice(confirmMouseResult.confirmChoice());
                                            pendingCommand = commandBridge.invocationFor(
                                                KeyEvent.Action.ENTER
                                            ).orElse(null);
                                            remappedMouseKey = new KeyEvent.ActionEvent(KeyEvent.Action.ENTER);
                                            editorView.cancelMouseSelection();
                                            break;
                                        }
                                    }
                                    if (uiSessionState.searchMode && searchBar != null) {
                                        int searchBarTopRow = rows - 1 - inlinePromptBar.height();
                                        SearchFocus clickedFocus = searchFocusAtMouse(
                                            searchBar,
                                            mouse.row(),
                                            mouse.col(),
                                            searchBarTopRow
                                        );
                                        if (clickedFocus != null) {
                                            uiSessionState.searchFocus = clickedFocus;
                                            if (SearchController.isSearchActionFocus(clickedFocus)) {
                                                KeyEvent.Action mappedAction = SearchController.actionForSearchFocus(clickedFocus);
                                                if (mappedAction != null) {
                                                    pendingCommand = commandBridge.invocationFor(
                                                        mappedAction
                                                    ).orElse(null);
                                                    remappedMouseKey = new KeyEvent.ActionEvent(mappedAction);
                                                    break;
                                                }
                                            }
                                            if (SearchController.isToggleFocus(clickedFocus)) {
                                                SearchController.SearchUiOptions before = searchUi;
                                                searchUi = SearchController.toggleFocusedSearchOption(searchUi, clickedFocus);
                                                if (!searchUi.equals(before)) {
                                                    // Toggle is reflected directly in the search bar.
                                                }
                                            }
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        KeyEvent.Action clickAction = searchActionAtMouse(
                                            searchBar,
                                            mouse.row(),
                                            mouse.col(),
                                            searchBarTopRow
                                        );
                                        if (clickAction != null) {
                                            pendingCommand = commandBridge.invocationFor(
                                                clickAction
                                            ).orElse(null);
                                            remappedMouseKey = new KeyEvent.ActionEvent(clickAction);
                                            break;
                                        }
                                    }
                                    if (modalState.isNone()) {
                                        int leftBeforeScrollPointer = state.getLeftVisualColumn();
                                        int topBeforeScrollPointer = state.getTopLine();
                                        int topWrapBeforeScrollPointer = state.getTopWrapSegment();
                                        EditorView.ScrollPointerResult scrollPointer = editorView.handleScrollbarPointer(
                                            mouse.row(),
                                            mouse.col(),
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
                                            continue;
                                        }
                                        Position cursorBeforePress = state.getCursor();
                                        Position anchorBeforePress = state.getSelectionAnchor();
                                        boolean selectedWordByDoubleClick = editorView.handleMousePress(
                                            mouse,
                                            model,
                                            state,
                                            plan,
                                            contentRows,
                                            engine,
                                            contentTopRow
                                        );
                                        if (uiSessionState.searchMode) {
                                            if (selectedWordByDoubleClick) {
                                                String selectedQuery = selectionQueryForCtrlF(model, state);
                                                if (selectedQuery != null) {
                                                    searchQueryInput.set(selectedQuery);
                                                }
                                                uiSessionState.searchFocus = SearchFocus.FIND;
                                            } else {
                                                state.setCursor(cursorBeforePress);
                                                state.setSelectionAnchor(anchorBeforePress);
                                            }
                                        }
                                        if (!uiSessionState.searchMode) {
                                            viewportController.onEditorTextInteraction();
                                        }
                                    }
                                }
                            }
                            case DRAG -> {
                                if (openFileMode && mouse.button() == KeyEvent.MouseButton.LEFT
                                    && openFileScrollDrag != TreeView.ScrollAxis.NONE) {
                                    OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
                                    boolean consumed = OpenFileDialogController.handleMouseDrag(
                                        openFileMode,
                                        openFileTree,
                                        openFileScrollDrag,
                                        mouse.row(),
                                        mouse.col(),
                                        layout.left(),
                                        layout.right(),
                                        layout.listStartRow(),
                                        layout.listRows()
                                    );
                                    if (consumed) {
                                        continue;
                                    }
                                }
                                if (modalState.isNone()) {
                                    if (mouse.button() == KeyEvent.MouseButton.LEFT
                                        && editorScrollDrag != EditorView.ScrollAxis.NONE) {
                                        int leftBeforeDragScroll = state.getLeftVisualColumn();
                                        int topBeforeDragScroll = state.getTopLine();
                                        int topWrapBeforeDragScroll = state.getTopWrapSegment();
                                        EditorView.ScrollPointerResult dragScrollPointer = editorView.handleScrollbarPointer(
                                            mouse.row(),
                                            mouse.col(),
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
                                        continue;
                                    }
                                    if (!uiSessionState.searchMode) {
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
                                }
                            }
                            case RELEASE -> {
                                editorScrollDrag = EditorView.ScrollAxis.NONE;
                                editorScrollDragThumbOffset = 0;
                                openFileScrollDrag = TreeView.ScrollAxis.NONE;
                                editorView.handleMouseRelease(state);
                            }
                            default -> {
                                // Ignore drag and release mouse events.
                            }
                        }
                        if (remappedMouseKey == null) {
                            continue;
                        }
                        key = remappedMouseKey;
                    }

                    if (openFileMode) {
                        OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
                        if (isAction(key, KeyEvent.Action.CTRL_H)) {
                            openFileTree.setShowHiddenFiles(!openFileTree.showHiddenFiles());
                            openFileFocus = OpenFileDialogController.Focus.LIST;
                            continue;
                        }
                        int openFileActiveTabIndex = activeTabIndex;
                        OpenFileDialogController.HandleResult openFileResult = OpenFileDialogController.handleKey(
                            key,
                            openFileMode,
                            openFileFocus,
                            openFileTree,
                            openFileActiveTabIndex,
                            layout.listRows(),
                            openFileTree.visibleTextWidth(layout.left(), layout.right(), layout.listRows()),
                            selected -> activateOpenFileSelection(
                                selected,
                                tabs,
                                openFileActiveTabIndex,
                                notification
                            )
                        );
                        openFileMode = openFileResult.openFileMode();
                        openFileFocus = openFileResult.focus();
                        if (openFileResult.activeTabIndex() != activeTabIndex) {
                            sessionContext.activate(openFileResult.activeTabIndex());
                        }
                        continue;
                    }

                    if (modalState.isHelpHints()) {
                        HelpHintsController.Result helpHintsResult = HelpHintsController.handle(
                            key,
                            true
                        );
                        if (helpHintsResult.consumed()) {
                            modalState = helpHintsResult.helpHintsMode() ? UiModalState.helpHints() : UiModalState.none();
                            continue;
                        }
                    }

                    if (modalState.isHelpLicenses()) {
                        if (key instanceof KeyEvent.CharInput) {
                            continue;
                        }
                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        if (action == KeyEvent.Action.ESCAPE
                            || action == KeyEvent.Action.ENTER
                            || action == KeyEvent.Action.SHOW_LICENSES) {
                            modalState = UiModalState.none();
                        }
                        continue;
                    }

                    if (modalState.isHelpAuthor()) {
                        if (key instanceof KeyEvent.CharInput) {
                            continue;
                        }
                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        if (action == KeyEvent.Action.ESCAPE
                            || action == KeyEvent.Action.ENTER
                            || action == KeyEvent.Action.SHOW_AUTHOR) {
                            modalState = UiModalState.none();
                        }
                        continue;
                    }

                    if (modalState.isRecoveryConfirm()) {
                        ConfirmBarController.InputResult confirmInput = ConfirmBarController.handleKey(
                            key,
                            modalState.confirmChoice(),
                            ConfirmChoice.SAVE,
                            ConfirmChoice.DISCARD,
                            false
                        );
                        if (confirmInput.consumed()) {
                            if (confirmInput.decision() == null) {
                                modalState = modalState.withConfirmChoice(confirmInput.confirmChoice());
                                continue;
                            }
                            if (confirmInput.decision() == ConfirmChoice.SAVE) {
                                int restoredCount = restoreRecoveredTabs(startupRecoveryEntries, tabs, sessionContext);
                                recoveryAutosave.deleteEntries(startupRecoveryEntries);
                                startupRecoveryEntries.clear();
                                if (restoredCount > 0) {
                                    activeTabIndex = sessionContext.activeTabIndex();
                                    state = sessionContext.state();
                                    openFileTree.setRoot(initialOpenRoot(state));
                                    notification.info("Recovered " + restoredCount + " tab(s)");
                                } else {
                                    notification.info("No recovery snapshots restored");
                                }
                            } else if (confirmInput.decision() == ConfirmChoice.DISCARD) {
                                recoveryAutosave.deleteEntries(startupRecoveryEntries);
                                startupRecoveryEntries.clear();
                                notification.info("Recovery snapshots discarded");
                            }
                            modalState = UiModalState.none();
                            continue;
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
                            this::validateSaveTarget,
                            path -> Files.exists(path),
                            this::isReadOnlyFile,
                            this::isSameFilePath,
                            this::suggestWritableName
                        );
                        if (saveAsResult.consumed()) {
                            modalState = saveAsResult.modalState();
                            if (saveAsResult.clearNotification()) {
                                notification.clear();
                            }
                            if (saveAsResult.errorMessage() != null) {
                                notification.error(saveAsResult.errorMessage());
                            }
                            applySaveAsTextInputEffect(saveAsInput, saveAsResult.textInputEffect());
                            if (saveAsResult.modalState().isSaveAs()) {
                                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsInput.text());
                            } else {
                                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsDraftBefore);
                            }
                            if (saveAsResult.saveNowPath() != null) {
                                try {
                                    engine.saveAs(saveAsResult.saveNowPath());
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
                                }
                            }
                            continue;
                        }
                    }

                    if (modalState.isSaveOverwriteConfirm()) {
                        UiModalState overwriteState = modalState;
                        ConfirmBarController.InputResult confirmInput = ConfirmBarController.handleKey(
                            key,
                            modalState.confirmChoice(),
                            ConfirmChoice.SAVE
                        );
                        if (confirmInput.consumed()) {
                            if (confirmInput.decision() == null) {
                                modalState = modalState.withConfirmChoice(confirmInput.confirmChoice());
                            } else if (confirmInput.decision() == ConfirmChoice.SAVE) {
                                try {
                                    engine.saveAs(modalState.pendingOverwritePath());
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
                            } else if (confirmInput.decision() == ConfirmChoice.DISCARD) {
                                modalState = modalState.backToSaveAs();
                                notification.clear();
                            } else {
                                modalState = UiModalState.none();
                                notification.clear();
                            }
                            continue;
                        }
                    }

                    if (modalState.isQuitConfirm()) {
                        if (isAction(key, KeyEvent.Action.CTRL_Q)) {
                            running = false;
                            modalState = UiModalState.none();
                            continue;
                        }
                        ConfirmBarController.InputResult confirmInput = ConfirmBarController.handleKey(
                            key,
                            modalState.confirmChoice(),
                            ConfirmChoice.DISCARD
                        );
                        if (confirmInput.consumed()) {
                            if (confirmInput.decision() == null) {
                                modalState = modalState.withConfirmChoice(confirmInput.confirmChoice());
                            } else if (confirmInput.decision() == ConfirmChoice.SAVE) {
                                SaveFlowProgress progress = continueQuitSaveFlow(
                                    sessionContext,
                                    tabs,
                                    saveAsInput,
                                    saveAsSuggestions,
                                    notification
                                );
                                running = progress.running();
                                modalState = progress.modalState();
                            } else if (confirmInput.decision() == ConfirmChoice.DISCARD) {
                                running = false;
                                modalState = UiModalState.none();
                            } else {
                                modalState = UiModalState.none();
                            }
                            continue;
                        }
                    }

                    if (modalState.isCloseTabConfirm()) {
                        ConfirmBarController.InputResult confirmInput = ConfirmBarController.handleKey(
                            key,
                            modalState.confirmChoice(),
                            ConfirmChoice.DISCARD
                        );
                        if (confirmInput.consumed()) {
                            if (confirmInput.decision() == null) {
                                modalState = modalState.withConfirmChoice(confirmInput.confirmChoice());
                                continue;
                            }
                            int closeIndex = modalState.pendingCloseTabIndex();
                            if (closeIndex < 0 || closeIndex >= tabs.size()) {
                                modalState = UiModalState.none();
                                continue;
                            }
                            if (confirmInput.decision() == ConfirmChoice.SAVE) {
                                EditorTab pendingTab = tabs.get(closeIndex);
                                if (requiresSaveAs(pendingTab)) {
                                    sessionContext.activate(closeIndex);
                                    prepareSaveAs(pendingTab, saveAsInput, saveAsSuggestions, notification);
                                    modalState = UiModalState.saveAsForCloseTab(closeIndex);
                                } else if (saveDirtyTab(pendingTab, notification, saveAsSuggestions)) {
                                    sessionContext.activate(closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions));
                                    modalState = UiModalState.none();
                                }
                            } else if (confirmInput.decision() == ConfirmChoice.DISCARD) {
                                sessionContext.activate(closeTab(tabs, activeTabIndex, closeIndex, saveAsSuggestions));
                                modalState = UiModalState.none();
                            } else {
                                modalState = UiModalState.none();
                            }
                            continue;
                        }
                    }

                    if (topMenu.isOpen()) {
                        TopMenuInputController.HandleResult topMenuResult = TopMenuInputController.handleWhenOpen(topMenu, key);
                        Integer tabActivation = topMenuResult.tabActivation();
                        if (tabActivation != null && tabActivation >= 0 && tabActivation < tabs.size()) {
                            CommandInvocation activation = commandBridge.tabActivationInvocation(tabActivation);
                            int resolvedTab = commandBridge.invokeTabActivation(activation).orElse(-1);
                            if (resolvedTab >= 0 && resolvedTab < tabs.size()) {
                                sessionContext.activate(resolvedTab);
                            }
                            activeTabIndex = sessionContext.activeTabIndex();
                            model = sessionContext.model();
                            state = sessionContext.state();
                            engine = sessionContext.engine();
                            renderPlanBuilder = sessionContext.renderPlanBuilder();
                        }
                        key = topMenuResult.nextKey();
                        if (topMenuResult.selectedAction() != null) {
                            pendingCommand = commandBridge.invocationFor(
                                topMenuResult.selectedAction()
                            ).orElse(null);
                        }
                        if (topMenuResult.keepMenuOpen() || topMenuResult.selectedAction() == null) {
                            continue;
                        }
                    }

                    if (uiSessionState.searchMode) {
                        SearchModeResult searchModeResult = handleSearchModeInput(
                            key,
                            pendingCommand,
                            commandBridge,
                            uiSessionState,
                            searchUi,
                            searchQueryInput,
                            searchReplaceInput,
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
                            openFileFocus,
                            topMenu,
                            terminal::setMouseTracking,
                            renderer,
                            tabBar
                        );
                        if (searchModeResult.consumed()) {
                            searchUi = searchModeResult.searchUi();
                            if (searchModeResult.activeTabIndex() != activeTabIndex) {
                                sessionContext.activate(searchModeResult.activeTabIndex());
                            }
                            running = searchModeResult.running();
                            modalState = searchModeResult.modalState();
                            openFileMode = searchModeResult.openFileMode();
                            openFileFocus = searchModeResult.openFileFocus();
                            continue;
                        }
                    }

                    if (key instanceof KeyEvent.CharInput charInput) {
                        if (uiSessionState.readingModeSetting) {
                            notification.warning("Reading mode: editing is disabled");
                        } else {
                            engine.insertInput(charInput.text());
                        }
                        continue;
                    }

                    KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                    if (pendingCommand == null) {
                        pendingCommand = commandBridge.invocationFor(action).orElse(null);
                    }
                    action = commandBridge.resolve(pendingCommand, action);
                    InputActionDispatcher.DispatchResult dispatchResult = InputActionDispatcher.dispatch(
                        action,
                        uiSessionState,
                        new InputActionDispatcher.DispatchContext(
                            engine,
                            state,
                            topMenu,
                            terminal::setMouseTracking,
                            contentRows
                        )
                    );
                    if (dispatchResult.handled()) {
                        if (action == KeyEvent.Action.CTRL_F) {
                            String selectedQuery = selectionQueryForCtrlF(model, state);
                            if (selectedQuery != null) {
                                searchQueryInput.set(selectedQuery);
                            }
                        }
                        if (uiSessionState.helpHintsMode) {
                            modalState = UiModalState.helpHints();
                            uiSessionState.helpHintsMode = false;
                        } else if (uiSessionState.helpLicensesMode) {
                            modalState = UiModalState.helpLicenses();
                            uiSessionState.helpLicensesMode = false;
                        } else if (uiSessionState.helpAuthorMode) {
                            modalState = UiModalState.helpAuthor();
                            uiSessionState.helpAuthorMode = false;
                        }
                        if (dispatchResult.statusMessage() != null && !dispatchResult.statusMessage().isBlank()) {
                            if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.ERROR) {
                                notification.error(dispatchResult.statusMessage());
                            } else if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.WARNING) {
                                notification.warning(dispatchResult.statusMessage());
                            } else {
                                notification.info(dispatchResult.statusMessage());
                            }
                        }
                        continue;
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
                        openFileFocus,
                        saveAsInput,
                        saveAsSuggestions,
                        renderer,
                        tabBar
                    );
                    running = editorActionApplyResult.running();
                    modalState = editorActionApplyResult.modalState();
                    openFileMode = editorActionApplyResult.openFileMode();
                    openFileFocus = editorActionApplyResult.openFileFocus();
                }
                recoveryAutosave.clearRuntimeSnapshots();
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
        MenuBar topMenu,
        TreeView openFileTree,
        boolean openFileMode,
        OpenFileDialogController.Focus openFileFocus,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        RenderRasterizer renderer,
        TabBar tabBar
    ) {
        boolean running = editorAction.running();
        UiModalState modalState = editorAction.modalState();
        boolean nextOpenFileMode = openFileMode;
        OpenFileDialogController.Focus nextOpenFileFocus = openFileFocus;

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
            nextOpenFileFocus = OpenFileDialogController.Focus.LIST;
        }
        if (editorAction.requestToggleTheme()) {
            applyTheme(theme.next());
            renderer.setTheme(theme);
            topMenu.setStyles(
                styles.menuPopup(),
                styles.menuSelected(),
                styles.menuTopSelected(),
                styles.menuSelectedMono()
            );
            topMenu.setThemeLabel(theme.label());
            tabBar.setStyles(
                styles.tabbar(),
                styles.tabbarMono(),
                styles.tabbarActive(),
                styles.tabbarActiveMono()
            );
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
        if (plan != null && plan.wrapLines()) {
            int visibleCols = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
            if (visibleCols > 0) {
                EditorViewportBehavior.WrapTop normalized = EditorViewportBehavior.normalizeWrapTop(
                    model,
                    textLayout,
                    state.getTopLine(),
                    state.getTopWrapSegment(),
                    visibleCols,
                    plan.wrapByWords(),
                    tabWidth
                );
                EditorViewportBehavior.applyWrapTop(state, normalized);
                int topWrappedRow = EditorViewportBehavior.wrappedOffsetForWrapTop(
                    model,
                    textLayout,
                    normalized,
                    visibleCols,
                    plan.wrapByWords(),
                    tabWidth
                );
                int totalWrappedRows = Math.max(1, plan.totalLines());
                int maxTopWrappedRow = Math.max(0, totalWrappedRows - Math.max(1, plan.contentRows()));
                int targetWrappedRow = Math.max(0, Math.min(topWrappedRow + deltaRows, maxTopWrappedRow));
                EditorViewportBehavior.WrapTop targetTop = EditorViewportBehavior.wrapTopFromWrappedOffset(
                    model,
                    textLayout,
                    targetWrappedRow,
                    visibleCols,
                    plan.wrapByWords(),
                    tabWidth
                );
                EditorViewportBehavior.applyWrapTop(state, targetTop);
                return;
            }
        }
        state.setTopWrapSegment(0);
        EditorViewportBehavior.applyVerticalScrollKeepCursor(
            state,
            model,
            Math.max(0, state.getTopLine() + deltaRows)
        );
    }

    private SearchModeResult handleSearchModeInput(
        KeyEvent key,
        CommandInvocation pendingCommand,
        LegacyCommandBridge commandBridge,
        UiSessionState uiSessionState,
        SearchController.SearchUiOptions searchUi,
        TextField searchQueryInput,
        TextField searchReplaceInput,
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
        TreeView openFileTree,
        OpenFileDialogController.Focus openFileFocus,
        MenuBar topMenu,
        Consumer<Boolean> mouseTrackingController,
        RenderRasterizer renderer,
        TabBar tabBar
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
        OpenFileDialogController.Focus nextOpenFileFocus = openFileFocus;

        if (key instanceof KeyEvent.CharInput charInput) {
            if (charInput.codePoint() == ' ' && SearchController.isToggleFocus(uiSessionState.searchFocus)) {
                SearchController.SearchUiOptions before = nextSearchUi;
                nextSearchUi = SearchController.toggleFocusedSearchOption(nextSearchUi, uiSessionState.searchFocus);
                if (!nextSearchUi.equals(before)) {
                    statusMessage = "";
                }
            } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                searchReplaceInput.insert(charInput.text());
                statusMessage = "";
            } else if (uiSessionState.searchFocus == SearchFocus.FIND) {
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

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        CommandInvocation focusedCommand = commandBridge.invocationForSearch(
            action,
            uiSessionState.searchFocus,
            pendingCommand
        );
        action = commandBridge.resolve(focusedCommand, action);
        switch (action) {
            case ESCAPE -> {
                uiSessionState.searchMode = false;
                uiSessionState.searchFocus = SearchFocus.FIND;
                statusMessage = "";
            }
            case TAB -> uiSessionState.searchFocus = SearchController.nextSearchFocus(uiSessionState.searchFocus);
            case BACKSPACE -> {
                TextField active = uiSessionState.searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                active.backspace();
            }
            case DELETE -> {
                TextField active = uiSessionState.searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                active.deleteForward();
            }
            case ARROW_LEFT -> {
                if (uiSessionState.searchFocus == SearchFocus.FIND) {
                    if (searchQueryInput.cursor() > 0) {
                        searchQueryInput.moveLeft();
                    } else {
                        uiSessionState.searchFocus = SearchController.previousSearchFocusHorizontal(uiSessionState.searchFocus);
                    }
                } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                    if (searchReplaceInput.cursor() > 0) {
                        searchReplaceInput.moveLeft();
                    } else {
                        uiSessionState.searchFocus = SearchController.previousSearchFocusHorizontal(uiSessionState.searchFocus);
                    }
                } else {
                    uiSessionState.searchFocus = SearchController.previousSearchFocusHorizontal(uiSessionState.searchFocus);
                }
            }
            case ARROW_RIGHT -> {
                if (uiSessionState.searchFocus == SearchFocus.FIND) {
                    if (searchQueryInput.cursor() < searchQueryInput.text().length()) {
                        searchQueryInput.moveRight();
                    } else {
                        uiSessionState.searchFocus = SearchController.nextSearchFocusHorizontal(uiSessionState.searchFocus);
                    }
                } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                    if (searchReplaceInput.cursor() < searchReplaceInput.text().length()) {
                        searchReplaceInput.moveRight();
                    } else {
                        uiSessionState.searchFocus = SearchController.nextSearchFocusHorizontal(uiSessionState.searchFocus);
                    }
                } else {
                    uiSessionState.searchFocus = SearchController.nextSearchFocusHorizontal(uiSessionState.searchFocus);
                }
            }
            case ARROW_UP -> uiSessionState.searchFocus = SearchController.verticalSearchFocus(uiSessionState.searchFocus, true);
            case ARROW_DOWN -> uiSessionState.searchFocus = SearchController.verticalSearchFocus(uiSessionState.searchFocus, false);
            case HOME -> {
                if (uiSessionState.searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveHome();
                } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveHome();
                } else {
                    if (uiSessionState.smartHomeEndSetting) {
                        nextEngine.moveHomeSmart();
                    } else {
                        nextEngine.moveHome();
                    }
                }
            }
            case END -> {
                if (uiSessionState.searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveEnd();
                } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveEnd();
                } else {
                    if (uiSessionState.smartHomeEndSetting) {
                        nextEngine.moveEndSmart();
                    } else {
                        nextEngine.moveEnd();
                    }
                }
            }
            case ENTER -> {
                KeyEvent.Action enterAction = SearchController.isSearchActionFocus(uiSessionState.searchFocus)
                    ? SearchController.actionForSearchFocus(uiSessionState.searchFocus)
                    : KeyEvent.Action.CTRL_F;
                if (enterAction == KeyEvent.Action.CTRL_G) {
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
                } else if (enterAction == KeyEvent.Action.CTRL_R) {
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
                } else if (enterAction == KeyEvent.Action.CTRL_A) {
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
                } else {
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
            }
            case CTRL_F -> {
                if (SearchController.isSearchActionFocus(uiSessionState.searchFocus)) {
                    uiSessionState.searchFocus = SearchFocus.NEXT_BUTTON;
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
            case CTRL_G -> {
                if (SearchController.isSearchActionFocus(uiSessionState.searchFocus)) {
                    uiSessionState.searchFocus = SearchFocus.PREV_BUTTON;
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
            case CTRL_R -> {
                if (SearchController.isSearchActionFocus(uiSessionState.searchFocus)) {
                    uiSessionState.searchFocus = SearchFocus.REPLACE_BUTTON;
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
            case CTRL_A -> {
                if (SearchController.isSearchActionFocus(uiSessionState.searchFocus)) {
                    uiSessionState.searchFocus = SearchFocus.ALL_BUTTON;
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
            case CTRL_Q -> {
                if (uiSessionState.confirmOnCloseSetting && hasDirtyTabs(tabs)) {
                    uiSessionState.searchMode = false;
                    uiSessionState.searchFocus = SearchFocus.FIND;
                    nextModalState = UiModalState.quitConfirm();
                } else {
                    nextRunning = false;
                }
            }
            case CTRL_S -> {
                if (nextState.getCurrentFile() == null) {
                    uiSessionState.searchMode = false;
                    uiSessionState.searchFocus = SearchFocus.FIND;
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
                    uiSessionState.searchFocus = SearchFocus.FIND;
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
            case CTRL_O -> {
                uiSessionState.searchMode = false;
                uiSessionState.searchFocus = SearchFocus.FIND;
                topMenu.close();
                nextOpenFileMode = true;
                openFileTree.setRoot(initialOpenRoot(nextState));
                nextOpenFileFocus = OpenFileDialogController.Focus.LIST;
                statusMessage = "Open file";
            }
            case TOGGLE_THEME -> {
                applyTheme(theme.next());
                renderer.setTheme(theme);
                topMenu.setStyles(
                    styles.menuPopup(),
                    styles.menuSelected(),
                    styles.menuTopSelected(),
                    styles.menuSelectedMono()
                );
                topMenu.setThemeLabel(theme.label());
                tabBar.setStyles(
                    styles.tabbar(),
                    styles.tabbarMono(),
                    styles.tabbarActive(),
                    styles.tabbarActiveMono()
                );
                statusMessage = "Theme: " + theme.label();
            }
            case CTRL_N, TAB_NEW -> {
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
            case CTRL_K, CTRL_W, CTRL_SHIFT_S -> {
                uiSessionState.searchMode = false;
                uiSessionState.searchFocus = SearchFocus.FIND;
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
                        contentRows
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
        OpenFileDialogController.Focus openFileFocus
    ) {
    }

    private record EditorActionApplyResult(
        boolean running,
        UiModalState modalState,
        boolean openFileMode,
        OpenFileDialogController.Focus openFileFocus
    ) {
    }

    private record SaveFlowProgress(boolean running, UiModalState modalState) {
    }

    private void drawTopFileNameBold(CellBuffer buffer, int cols, EditorState state, int startCol) {
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
            buffer.set(0, col, cell.grapheme(), styles.topbarFilenameHighlight());
            col += Math.max(1, cell.width());
        }
    }

    private void drawMenuLine(CellBuffer buffer, int row, int startCol, String text, CellStyle style, int cols) {
        CellText.draw(buffer, row, startCol, text, style, cols);
    }

    private void paintSelectedConfirmButton(CellBuffer buffer, ConfirmBarView confirmBar, int rows, int cols) {
        if (buffer == null || confirmBar == null || rows < 2 || cols <= 0) {
            return;
        }
        Button selected = confirmBar.selectedButton();
        if (selected == null) {
            return;
        }
        int row = rows - 2;
        if (row < 0 || row >= buffer.rows()) {
            return;
        }
        selected.drawAt(buffer, row, cols, focusedButtonStyle(styles.promptAlert()));
    }

    private void paintSelectedSearchButton(
        CellBuffer buffer,
        SearchBarView searchBar,
        SearchFocus searchFocus,
        int rows,
        int inlinePromptBarHeight,
        int cols
    ) {
        if (buffer == null || searchBar == null || cols <= 0) {
            return;
        }
        Button selected = searchBar.buttonForFocus(searchFocus);
        if (selected == null) {
            return;
        }
        int topRow = rows - 1 - inlinePromptBarHeight;
        int row = topRow + selected.row();
        if (row < 0 || row >= buffer.rows()) {
            return;
        }
        selected.drawAt(buffer, row, cols, focusedButtonStyle(styles.searchBar()));
    }

    private boolean isAction(KeyEvent key, KeyEvent.Action expected) {
        return key instanceof KeyEvent.ActionEvent actionEvent && actionEvent.action() == expected;
    }

    static String singleLinePaste(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        boolean separatorPending = false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') {
                separatorPending = !result.isEmpty();
                continue;
            }
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (separatorPending) {
                result.append(' ');
                separatorPending = false;
            }
            result.appendCodePoint(codePoint);
        }
        return result.toString();
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
        CellBuffer buffer,
        int rows,
        int cols,
        boolean visible,
        TreeView tree,
        OpenFileDialogController.Focus focus,
        boolean colorEnabled
    ) {
        if (!visible || rows < 8 || cols < 24) {
            return;
        }
        OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
        Modal frame = openFileFrame(layout);
        frame.draw(buffer, rows, cols);
        Button hiddenToggle = openFileHiddenToggleButton(layout, tree.showHiddenFiles());

        String pathLine = " " + tree.root();
        drawMenuLine(
            buffer,
            layout.top() + 1,
            layout.left() + 1,
            padRight(pathLine, Math.max(0, layout.width() - 2)),
            styles.dialog(),
            cols
        );
        hiddenToggle.draw(
            buffer,
            cols,
            tree.showHiddenFiles()
                ? (colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono())
                : styles.dialog()
        );
        tree.draw(
            buffer,
            cols,
            layout.left(),
            layout.right(),
            layout.listStartRow(),
            layout.listRows(),
            colorEnabled,
            styles.dialog(),
            styles.menuSelected(),
            styles.dialogSelectedMono(),
            styles.dialogScrollTrack(),
            styles.dialogScrollThumb()
        );

        OpenFileDialogController.OpenDialogButtons buttons = OpenFileDialogController.buttonsForLayout(layout);
        buttons.ok().draw(
            buffer,
            cols,
            focus == OpenFileDialogController.Focus.OK
                ? (colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono())
                : styles.dialog()
        );
        buttons.cancel().draw(
            buffer,
            cols,
            focus == OpenFileDialogController.Focus.CANCEL
                ? (colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono())
                : styles.dialog()
        );
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout) {
        return new Modal(
            layout.top(),
            layout.left(),
            layout.width(),
            layout.height(),
            " Open File ",
            styles.dialog(),
            styles.dialogBorder(),
            styles.dialogTitle()
        );
    }

    private Button openFileHiddenToggleButton(OpenFileDialogController.OpenDialogLayout layout, boolean hiddenVisible) {
        String text = hiddenVisible ? "[hidden:x]" : "[hidden: ]";
        int rightInnerCol = layout.right() - 1;
        int leftInnerCol = layout.left() + 1;
        int col = Math.max(leftInnerCol, rightInnerCol - text.length() + 1);
        return Button.plain(layout.top() + 1, col, text);
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
        return new Modal(
            layout.top(),
            layout.left(),
            layout.width(),
            layout.height(),
            title,
            styles.dialog(),
            styles.dialogBorder(),
            styles.dialogTitle()
        );
    }

    private Button helpCloseButton(HelpDialogLayout layout) {
        String closeText = " Close ";
        int col = layout.left() + Math.max(1, (layout.width() - (closeText.length() + 2)) / 2);
        int row = layout.top() + layout.height() - 1;
        return new Button(row, col, closeText);
    }

    private void drawInfoOverlay(
        CellBuffer buffer,
        int rows,
        int cols,
        boolean visible,
        HelpPage page,
        boolean colorEnabled
    ) {
        if (!visible || rows < 8 || cols < 24) {
            return;
        }
        HelpDialogLayout layout = helpDialogLayout(rows, cols, page.rows().length);
        Modal frame = infoDialogFrame(layout, page.title());
        frame.draw(buffer, rows, cols);
        int textRow = layout.top() + 1;
        int textCol = layout.left() + 2;
        int textWidth = Math.max(0, layout.width() - 4);
        int maxRows = Math.max(0, layout.height() - 2);
        for (int i = 0; i < page.rows().length && i < maxRows; i++) {
            drawMenuLine(
                buffer,
                textRow + i,
                textCol,
                clipText(page.rows()[i], textWidth),
                styles.dialog(),
                cols
            );
        }
        Button close = helpCloseButton(layout);
        close.draw(
            buffer,
            cols,
            colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono()
        );
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

    private OpenFileDialogController.ActivationResult activateOpenFileSelection(
        TreeView.Item entry,
        List<EditorTab> tabs,
        int activeTabIndex,
        NotificationState notification
    ) {
        if (entry == null) {
            notification.warning("Select a file");
            return new OpenFileDialogController.ActivationResult(true, activeTabIndex);
        }
        if (entry.parentLink() || entry.directory()) {
            notification.warning("Select a file");
            return new OpenFileDialogController.ActivationResult(true, activeTabIndex);
        }
        Path target = entry.path();
        try {
            EditorTab tab = newEditorTab();
            tab.engine().open(target);
            tab = tab.withRenderPlanBuilder(new RenderPlanBuilder(resolveHighlighter(target), tab.textLayout()));
            tabs.add(tab);
            int newIndex = tabs.size() - 1;
            notification.success("Opened " + target);
            return new OpenFileDialogController.ActivationResult(false, newIndex);
        } catch (IOException e) {
            notification.error("Open failed: " + e.getMessage());
            return new OpenFileDialogController.ActivationResult(true, activeTabIndex);
        }
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

    private RenderFrameResult doRenderFrameIfNeeded(
        RenderPlanBuilder renderPlanBuilder,
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        int contentTopRow,
        boolean showTabBar,
        int activeTabIndex,
        List<TabBar.TabItem> tabItems,
        EditorLayout editorLayout,
        int inlinePromptBarHeight,
        String topBar,
        String bottomBar,
        boolean searchMode,
        String searchQuery,
        SearchController.SearchUiOptions searchUi,
        SearchFocus searchFocus,
        String interactiveBarText,
        Style interactiveBarStyle,
        NotificationBar notificationBar,
        boolean wrapLinesSetting,
        boolean wrapWordsSetting,
        boolean autoSelectionHighlightSetting,
        UiModalState modalState,
        TextField saveAsInput,
        List<EditorTab> tabs,
        SearchBarView searchBar,
        ConfirmBarView confirmBar,
        boolean openFileMode,
        TreeView openFileTree,
        OpenFileDialogController.Focus openFileFocus,
        boolean helpHintsMode,
        boolean helpLicensesMode,
        boolean helpAuthorMode,
        RenderPlan previousPlan,
        boolean colorEnabled,
        ScreenPresenter screenPresenter,
        RenderRasterizer renderer,
        EditorView editorView,
        TabBar tabBar,
        MenuBar topMenu
    ) {
        boolean saveAsMode = modalState.isSaveAs();
        boolean saveOverwriteConfirmMode = modalState.isSaveOverwriteConfirm();
        Path pendingOverwritePath = modalState.pendingOverwritePath();
        boolean quitConfirmMode = modalState.isQuitConfirm();
        boolean closeTabConfirmMode = modalState.isCloseTabConfirm();
        int pendingCloseTabIndex = modalState.pendingCloseTabIndex();
        RenderPlan plan;
        SearchOptions renderSearchOptions = searchMode
            ? SearchController.toSearchOptions(searchUi)
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
                interactiveBarText,
                interactiveBarStyle,
                notificationBar.text(),
                notificationBar.style(),
                wrapLinesSetting,
                wrapWordsSetting,
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
                interactiveBarText,
                interactiveBarStyle,
                notificationBar.text(),
                notificationBar.style(),
                wrapLinesSetting,
                wrapWordsSetting,
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
        paintSelectedConfirmButton(current, confirmBar, rows, cols);
        paintSelectedSearchButton(current, searchBar, searchFocus, rows, inlinePromptBarHeight, cols);
        if (showTabBar) {
            tabBar.draw(current, cols, tabItems, activeTabIndex, colorEnabled);
        }
        topMenu.draw(current, cols, colorEnabled);
        drawTopFileNameBold(current, cols, state, topMenu.fileNameStartCol());
        drawOpenFileOverlay(
            current,
            rows,
            cols,
            openFileMode,
            openFileTree,
            openFileFocus,
            colorEnabled
        );
        drawInfoOverlay(
            current,
            rows,
            cols,
            helpHintsMode,
            HelpPages.KEYMAP,
            colorEnabled
        );
        drawInfoOverlay(
            current,
            rows,
            cols,
            helpLicensesMode,
            HelpPages.LICENSES,
            colorEnabled
        );
        drawInfoOverlay(
            current,
            rows,
            cols,
            helpAuthorMode,
            HelpPages.AUTHOR,
            colorEnabled
        );

        EditorView.CursorPosition editorCursor = editorView.cursorFromPlan(plan, contentTopRow);
        int desiredCursorRow = editorCursor.row();
        int desiredCursorCol = editorCursor.col();
        boolean desiredCursorVisible = CursorPolicy.shouldShowCursor(
            saveAsMode,
            searchBar != null,
            searchFocus,
            saveOverwriteConfirmMode,
            quitConfirmMode,
            closeTabConfirmMode,
            topMenu.isOpen(),
            openFileMode,
            helpHintsMode || helpLicensesMode || helpAuthorMode
        );
        boolean editorOwnsCursor = !saveAsMode
            && searchBar == null
            && !saveOverwriteConfirmMode
            && !quitConfirmMode
            && !closeTabConfirmMode
            && !topMenu.isOpen()
            && !openFileMode
            && !(helpHintsMode || helpLicensesMode || helpAuthorMode);
        if (editorOwnsCursor && !plan.cursorInViewport()) {
            desiredCursorVisible = false;
        }
        int interactiveBottomCursorRow = Math.max(1, rows - 1);
        int interactiveTopCursorRow = interactiveBottomCursorRow - inlinePromptBarHeight + 1;
        if (saveAsMode) {
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = Math.min(
                cols,
                TerminalText.visualWidth(SAVE_AS_PROMPT, 4) + saveAsInput.cursorCellColumn() + 1
            );
        } else if ((saveOverwriteConfirmMode || quitConfirmMode || closeTabConfirmMode) && confirmBar != null) {
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = 1;
        } else if (searchBar != null) {
            desiredCursorRow = interactiveTopCursorRow + searchBar.cursorRow() - 1;
            desiredCursorCol = searchBar.cursorCol();
        } else if (topMenu.isOpen()) {
            desiredCursorRow = Math.min(rows, 3 + topMenu.selectedIndex());
            desiredCursorCol = Math.min(cols, topMenu.popupCursorCol());
        } else if (openFileMode) {
            OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
            if (openFileFocus == OpenFileDialogController.Focus.LIST) {
                int visibleIndex = openFileTree.visibleIndex(layout.listRows());
                desiredCursorRow = layout.listStartRow() + visibleIndex + 1;
                desiredCursorCol = layout.left() + 3;
            } else {
                OpenFileDialogController.OpenDialogButtons buttons = OpenFileDialogController.buttonsForLayout(layout);
                desiredCursorCol = openFileFocus == OpenFileDialogController.Focus.OK
                    ? buttons.ok().cursorCol()
                    : buttons.cancel().cursorCol();
                desiredCursorRow = layout.bottom() + 1;
            }
        } else if (helpHintsMode || helpLicensesMode || helpAuthorMode) {
            HelpPage helpPage = activeHelpPage(helpHintsMode, helpLicensesMode, helpAuthorMode);
            HelpDialogLayout layout = helpDialogLayout(rows, cols, helpPage.rows().length);
            Button close = helpCloseButton(layout);
            desiredCursorRow = Math.min(rows, close.row() + 1);
            desiredCursorCol = Math.min(cols, close.cursorCol() + 1);
        }

        boolean canUseVerticalScrollFastPath = previousPlan != null
            && plan.wrapLines() == previousPlan.wrapLines()
            && plan.gutterWidth() == previousPlan.gutterWidth()
            && plan.scrollbarWidth() == previousPlan.scrollbarWidth()
            && plan.leftVisualColumn() == previousPlan.leftVisualColumn()
            && plan.topLine() != previousPlan.topLine();
        boolean canUseHorizontalScrollFastPath = previousPlan != null
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
        if (openFileMode) {
            OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
            int listTextWidth = openFileTree.visibleRowWidth(layout.left(), layout.right(), layout.listRows());
            diffRegionTop = layout.listStartRow();
            diffRegionHeight = layout.listRows();
            textStartCol = layout.left() + 1;
            textEndCol = textStartCol + Math.max(0, listTextWidth) - 1;
            textStartCol = Math.max(0, Math.min(cols - 1, textStartCol));
            textEndCol = Math.max(textStartCol, Math.min(cols - 1, textEndCol));
            canUseVerticalScrollFastPath = previousPlan != null
                && lastOpenFileModeForDiff
                && openFileTree.scrollOffset() != lastOpenFileScrollForDiff;
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
        lastOpenFileModeForDiff = openFileMode;
        lastOpenFileScrollForDiff = openFileMode ? openFileTree.scrollOffset() : 0;
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
        if (selected == null || !searchSelectionMatchesQuery(selected, query, options)) {
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

    private boolean searchSelectionMatchesQuery(String selected, String query, SearchOptions options) {
        SearchOptions normalized = options == null ? SearchOptions.DEFAULT : options;
        String selectedValue = selected;
        String queryValue = query;
        if (normalized.caseInsensitive()) {
            selectedValue = selectedValue.toLowerCase(Locale.ROOT);
            queryValue = queryValue.toLowerCase(Locale.ROOT);
        }
        return switch (normalized.matchMode()) {
            case SUBSTRING, WHOLE_WORD -> selectedValue.equals(queryValue);
            case WILDCARD -> wildcardPattern(queryValue).matcher(selectedValue).matches();
        };
    }

    private Pattern wildcardPattern(String wildcard) {
        StringBuilder regex = new StringBuilder();
        regex.append("(?s)");
        for (int i = 0; i < wildcard.length(); i++) {
            char ch = wildcard.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private final class RenderFrameController {
        private final RenderRasterizer renderer;
        private final EditorView editorView;
        private final TabBar tabBar;
        private final MenuBar topMenu;
        private final boolean colorEnabled;
        private final ScreenPresenter screenPresenter;

        private RenderFrameController(
            RenderRasterizer renderer,
            EditorView editorView,
            TabBar tabBar,
            MenuBar topMenu,
            boolean colorEnabled,
            ScreenPresenter screenPresenter
        ) {
            this.renderer = renderer;
            this.editorView = editorView;
            this.tabBar = tabBar;
            this.topMenu = topMenu;
            this.colorEnabled = colorEnabled;
            this.screenPresenter = screenPresenter;
        }

        private RenderFrameResult renderFrameIfNeeded(
            RenderPlanBuilder renderPlanBuilder,
            TextModel model,
            EditorState state,
            int rows,
            int cols,
            int contentTopRow,
            boolean showTabBar,
            int activeTabIndex,
            List<TabBar.TabItem> tabItems,
            EditorLayout editorLayout,
            int inlinePromptBarHeight,
            String topBar,
            String bottomBar,
            boolean searchMode,
            String searchQuery,
            SearchController.SearchUiOptions searchUi,
            SearchFocus searchFocus,
            String interactiveBarText,
            Style interactiveBarStyle,
            NotificationBar notificationBar,
            boolean wrapLinesSetting,
            boolean wrapWordsSetting,
            boolean autoSelectionHighlightSetting,
            UiModalState modalState,
            TextField saveAsInput,
            List<EditorTab> tabs,
            SearchBarView searchBar,
            ConfirmBarView confirmBar,
            boolean openFileMode,
            TreeView openFileTree,
            OpenFileDialogController.Focus openFileFocus,
            boolean helpHintsMode,
            boolean helpLicensesMode,
            boolean helpAuthorMode,
            RenderPlan previousPlan
        ) {
            return doRenderFrameIfNeeded(
                renderPlanBuilder,
                model,
                state,
                rows,
                cols,
                contentTopRow,
                showTabBar,
                activeTabIndex,
                tabItems,
                editorLayout,
                inlinePromptBarHeight,
                topBar,
                bottomBar,
                searchMode,
                searchQuery,
                searchUi,
                searchFocus,
                interactiveBarText,
                interactiveBarStyle,
                notificationBar,
                wrapLinesSetting,
                wrapWordsSetting,
                autoSelectionHighlightSetting,
                modalState,
                saveAsInput,
                tabs,
                searchBar,
                confirmBar,
                openFileMode,
                openFileTree,
                openFileFocus,
                helpHintsMode,
                helpLicensesMode,
                helpAuthorMode,
                previousPlan,
                colorEnabled,
                screenPresenter,
                renderer,
                editorView,
                tabBar,
                topMenu
            );
        }
    }

    private String buildSaveAsPrompt(TextField saveAsInput) {
        return SAVE_AS_PROMPT + saveAsInput.text();
    }

    private String buildOverwritePrompt(Path path) {
        return "Overwrite existing file: " + path + " ? (y/N)";
    }

    private ConfirmBarView buildQuitConfirmBar(
        List<EditorTab> tabs,
        ConfirmChoice selectedChoice,
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
            selectedChoice,
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
        ConfirmChoice selectedChoice,
        int cols
    ) {
        EditorTab pendingTab = closeTabIndex >= 0 && closeTabIndex < tabs.size()
            ? tabs.get(closeTabIndex)
            : null;
        String primaryLabel = pendingTab != null && requiresSaveAs(pendingTab) ? "Save As" : "Save";
        return ConfirmBarView.build(
            buildCloseTabPrompt(tabs, closeTabIndex),
            selectedChoice,
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

    private int restoreRecoveredTabs(List<RecoveryStore.Entry> entries, List<EditorTab> tabs, SessionContext sessionContext) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        List<EditorTab> restored = new ArrayList<>();
        for (RecoveryStore.Entry entry : entries) {
            if (entry == null || entry.snapshot() == null) {
                continue;
            }
            TabSession tabSession = TabSession.createEmpty(TerminalEditorTextLayout::new);
            tabSession.engine().restoreRecoverySnapshot(entry.snapshot());
            RenderPlanBuilder builder = new RenderPlanBuilder(
                resolveHighlighter(tabSession.state().getCurrentFile()),
                tabSession.textLayout()
            );
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

    private SearchBarView buildSearchBar(
        TextField queryField,
        TextField replacementField,
        SearchFocus focus,
        int cols,
        SearchController.SearchUiOptions searchUi,
        int matchCount
    ) {
        String safeQuery = queryField == null ? "" : queryField.text();
        String safeReplacement = replacementField == null ? "" : replacementField.text();
        int queryCursor = queryField == null
            ? TerminalText.visualWidth(safeQuery, 4)
            : queryField.cursorCellColumn();
        int replacementCursor = replacementField == null
            ? TerminalText.visualWidth(safeReplacement, 4)
            : replacementField.cursorCellColumn();
        String findField = formatField("Find", safeQuery);
        String replaceField = formatField("Replace", safeReplacement);
        Button caseToggleTemplate = new Button(0, 0, "Аа:" + (searchUi.caseSensitive() ? "x" : " "));
        Button wordToggleTemplate = new Button(0, 0, "word:" + (
            searchUi.matchMode() == SearchOptions.MatchMode.WHOLE_WORD ? "x" : " "
        ));
        Button wildcardToggleTemplate = new Button(0, 0, "*:" + (
            searchUi.matchMode() == SearchOptions.MatchMode.WILDCARD ? "x" : " "
        ));
        Button allToggleTemplate = new Button(0, 0, "All:" + (searchUi.allTabs() ? "x" : " "));
        String csToggle = caseToggleTemplate.renderedText();
        String wToggle = wordToggleTemplate.renderedText();
        String starToggle = wildcardToggleTemplate.renderedText();
        String allToggle = allToggleTemplate.renderedText();
        String optionsCluster = csToggle + " " + wToggle + " " + starToggle + " " + allToggle;
        String row1 = alignLeftRight(findField, optionsCluster, cols, 1);
        Button nextButtonTemplate = new Button(1, 0, "Next");
        Button prevButtonTemplate = new Button(1, 0, "Prev");
        Button replaceButtonTemplate = new Button(1, 0, "Replace");
        Button allButtonTemplate = new Button(1, 0, "All");
        String nextButton = nextButtonTemplate.renderedText();
        String prevButton = prevButtonTemplate.renderedText();
        String replaceButton = replaceButtonTemplate.renderedText();
        String allButton = allButtonTemplate.renderedText();
        String actionsCluster = nextButton + " " + prevButton + " " + replaceButton + " " + allButton;
        String row2 = alignLeftRight(replaceField, actionsCluster, cols, 1);

        int optionsStart = Math.max(0, rightClusterStart(row1, optionsCluster));
        int actionsStart = Math.max(0, rightClusterStart(row2, actionsCluster));
        int findStart = 0;
        int findEnd = TerminalText.visualWidth(findField, 4);
        int caseStart = optionsStart;
        int caseEnd = caseStart + TerminalText.visualWidth(csToggle, 4);
        int wordStart = caseEnd + 1;
        int wordEnd = wordStart + TerminalText.visualWidth(wToggle, 4);
        int wildcardStart = wordEnd + 1;
        int wildcardEnd = wildcardStart + TerminalText.visualWidth(starToggle, 4);
        int allStart = wildcardEnd + 1;
        int allEnd = allStart + TerminalText.visualWidth(allToggle, 4);
        Button caseButtonView = new Button(0, caseStart, caseToggleTemplate.text());
        Button wordButtonView = new Button(0, wordStart, wordToggleTemplate.text());
        Button wildcardButtonView = new Button(0, wildcardStart, wildcardToggleTemplate.text());
        Button allToggleButtonView = new Button(0, allStart, allToggleTemplate.text());
        int replaceStart = 0;
        int replaceEnd = TerminalText.visualWidth(replaceField, 4);
        int nextStart = actionsStart;
        int nextEnd = nextStart + TerminalText.visualWidth(nextButton, 4);
        int prevStart = nextEnd + 1;
        int prevEnd = prevStart + TerminalText.visualWidth(prevButton, 4);
        int replaceActionStart = prevEnd + 1;
        int replaceActionEnd = replaceActionStart + TerminalText.visualWidth(replaceButton, 4);
        int allActionStart = replaceActionEnd + 1;
        int allActionEnd = allActionStart + TerminalText.visualWidth(allButton, 4);
        Button nextButtonView = new Button(1, nextStart, "Next");
        Button prevButtonView = new Button(1, prevStart, "Prev");
        Button replaceButtonView = new Button(1, replaceActionStart, "Replace");
        Button allButtonView = new Button(1, allActionStart, "All");

        SearchCursor cursor = cursorForFocus(
            findField,
            replaceField,
            csToggle,
            wToggle,
            starToggle,
            allToggle,
            safeQuery,
            safeReplacement,
            queryCursor,
            replacementCursor,
            focus,
            caseStart,
            wordStart,
            wildcardStart,
            allStart,
            nextStart,
            prevStart,
            replaceActionStart,
            allActionStart
        );
        return new SearchBarView(
            row1,
            row2,
            Math.max(1, Math.min(2, cursor.row())),
            Math.max(1, Math.min(cols, cursor.col() + 1)),
            findStart,
            findEnd,
            replaceStart,
            replaceEnd,
            caseStart,
            caseEnd,
            wordStart,
            wordEnd,
            wildcardStart,
            wildcardEnd,
            allStart,
            allEnd,
            nextStart,
            nextEnd,
            prevStart,
            prevEnd,
            replaceActionStart,
            replaceActionEnd,
            allActionStart,
            allActionEnd,
            caseButtonView,
            wordButtonView,
            wildcardButtonView,
            allToggleButtonView,
            nextButtonView,
            prevButtonView,
            replaceButtonView,
            allButtonView
        );
    }

    private SearchFocus searchFocusAtMouse(SearchBarView searchBar, int mouseRow, int mouseCol, int topRow) {
        if (searchBar == null || mouseCol < 0) {
            return null;
        }
        if (mouseRow == topRow && mouseCol >= searchBar.findStart() && mouseCol < searchBar.findEnd()) {
            return SearchFocus.FIND;
        }
        if (mouseRow == topRow + 1 && mouseCol >= searchBar.replaceStart() && mouseCol < searchBar.replaceEnd()) {
            return SearchFocus.REPLACE;
        }
        if (mouseRow == topRow + 1 && mouseCol >= searchBar.nextStart() && mouseCol < searchBar.nextEnd()) {
            return SearchFocus.NEXT_BUTTON;
        }
        if (mouseRow == topRow + 1 && mouseCol >= searchBar.prevStart() && mouseCol < searchBar.prevEnd()) {
            return SearchFocus.PREV_BUTTON;
        }
        if (mouseRow == topRow + 1
            && mouseCol >= searchBar.replaceActionStart() && mouseCol < searchBar.replaceActionEnd()) {
            return SearchFocus.REPLACE_BUTTON;
        }
        if (mouseRow == topRow + 1
            && mouseCol >= searchBar.allActionStart() && mouseCol < searchBar.allActionEnd()) {
            return SearchFocus.ALL_BUTTON;
        }
        if (mouseRow == topRow && mouseCol >= searchBar.caseStart() && mouseCol < searchBar.caseEnd()) {
            return SearchFocus.CASE;
        }
        if (mouseRow == topRow && mouseCol >= searchBar.wordStart() && mouseCol < searchBar.wordEnd()) {
            return SearchFocus.WORD;
        }
        if (mouseRow == topRow && mouseCol >= searchBar.wildcardStart() && mouseCol < searchBar.wildcardEnd()) {
            return SearchFocus.WILDCARD;
        }
        if (mouseRow == topRow && mouseCol >= searchBar.allStart() && mouseCol < searchBar.allEnd()) {
            return SearchFocus.ALL;
        }
        return null;
    }

    private KeyEvent.Action searchActionAtMouse(SearchBarView searchBar, int mouseRow, int mouseCol, int topRow) {
        if (searchBar == null || mouseCol < 0 || mouseRow != topRow + 1) {
            return null;
        }
        if (mouseCol >= searchBar.nextStart() && mouseCol < searchBar.nextEnd()) {
            return KeyEvent.Action.CTRL_F;
        }
        if (mouseCol >= searchBar.prevStart() && mouseCol < searchBar.prevEnd()) {
            return KeyEvent.Action.CTRL_G;
        }
        if (mouseCol >= searchBar.replaceActionStart() && mouseCol < searchBar.replaceActionEnd()) {
            return KeyEvent.Action.CTRL_R;
        }
        if (mouseCol >= searchBar.allActionStart() && mouseCol < searchBar.allActionEnd()) {
            return KeyEvent.Action.CTRL_A;
        }
        return null;
    }

    private record SearchBarView(
        String row1,
        String row2,
        int cursorRow,
        int cursorCol,
        int findStart,
        int findEnd,
        int replaceStart,
        int replaceEnd,
        int caseStart,
        int caseEnd,
        int wordStart,
        int wordEnd,
        int wildcardStart,
        int wildcardEnd,
        int allStart,
        int allEnd,
        int nextStart,
        int nextEnd,
        int prevStart,
        int prevEnd,
        int replaceActionStart,
        int replaceActionEnd,
        int allActionStart,
        int allActionEnd,
        Button caseButtonView,
        Button wordButtonView,
        Button wildcardButtonView,
        Button allToggleButtonView,
        Button nextButtonView,
        Button prevButtonView,
        Button replaceButtonView,
        Button allButtonView
    ) {
        private String text() {
            return row1 + "\n" + row2;
        }

        private Button buttonForFocus(SearchFocus focus) {
            if (focus == SearchFocus.CASE) {
                return caseButtonView;
            }
            if (focus == SearchFocus.WORD) {
                return wordButtonView;
            }
            if (focus == SearchFocus.WILDCARD) {
                return wildcardButtonView;
            }
            if (focus == SearchFocus.ALL) {
                return allToggleButtonView;
            }
            if (focus == SearchFocus.NEXT_BUTTON) {
                return nextButtonView;
            }
            if (focus == SearchFocus.PREV_BUTTON) {
                return prevButtonView;
            }
            if (focus == SearchFocus.REPLACE_BUTTON) {
                return replaceButtonView;
            }
            if (focus == SearchFocus.ALL_BUTTON) {
                return allButtonView;
            }
            return null;
        }
    }

    private static String formatField(String label, String value) {
        return label + ": " + value;
    }

    private static SearchCursor cursorForFocus(
        String findField,
        String replaceField,
        String iToggle,
        String wToggle,
        String starToggle,
        String allToggle,
        String query,
        String replacement,
        int queryCursor,
        int replacementCursor,
        SearchFocus focus,
        int caseStart,
        int wordStart,
        int wildcardStart,
        int allStart,
        int nextStart,
        int prevStart,
        int replaceActionStart,
        int allActionStart
    ) {
        int findCursorCol = TerminalText.visualWidth("Find: ", 4)
            + Math.max(0, Math.min(queryCursor, TerminalText.visualWidth(query, 4)));
        if (focus == SearchFocus.FIND) {
            return new SearchCursor(1, findCursorCol);
        }
        if (focus == SearchFocus.REPLACE) {
            return new SearchCursor(
                2,
                TerminalText.visualWidth("Replace: ", 4)
                    + Math.max(0, Math.min(replacementCursor, TerminalText.visualWidth(replacement, 4)))
            );
        }
        int iCheckOffset = iToggle.indexOf(':') + 1;
        int wCheckOffset = wToggle.indexOf(':') + 1;
        int starCheckOffset = starToggle.indexOf(':') + 1;
        if (focus == SearchFocus.CASE) {
            return new SearchCursor(1, caseStart + iCheckOffset);
        }
        if (focus == SearchFocus.WORD) {
            return new SearchCursor(1, wordStart + wCheckOffset);
        }
        if (focus == SearchFocus.WILDCARD) {
            return new SearchCursor(1, wildcardStart + starCheckOffset);
        }
        if (focus == SearchFocus.ALL) {
            int allCheckOffset = allToggle.indexOf(':') + 1;
            return new SearchCursor(1, allStart + allCheckOffset);
        }
        return new SearchCursor(1, findCursorCol);
    }

    private static String clipPad(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        String safe = text == null ? "" : text;
        StringBuilder clipped = new StringBuilder();
        int used = 0;
        for (TerminalText.Grapheme grapheme : TerminalText.graphemes(safe)) {
            int advance = grapheme.tab()
                ? 4 - (used % 4)
                : Math.max(1, grapheme.width());
            if (used + advance > cols) {
                break;
            }
            if (grapheme.tab()) {
                clipped.append(" ".repeat(advance));
            } else {
                clipped.append(grapheme.text());
            }
            used += advance;
        }
        return clipped + " ".repeat(cols - used);
    }

    private static String alignLeftRight(String left, String right, int cols, int rightMargin) {
        if (cols <= 0) {
            return "";
        }
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        int margin = Math.max(0, rightMargin);
        int rightBlock = TerminalText.visualWidth(safeRight, 4) + margin;
        if (safeRight.isEmpty()) {
            return clipPad(safeLeft, cols);
        }
        if (rightBlock >= cols) {
            return clipPad(safeRight, cols);
        }
        int leftWidth = cols - rightBlock;
        String leftPart = clipPad(safeLeft, leftWidth);
        return leftPart + safeRight + " ".repeat(margin);
    }

    private static int rightClusterStart(String row, String cluster) {
        if (row == null || cluster == null || cluster.isEmpty()) {
            return -1;
        }
        int charOffset = row.indexOf(cluster);
        return charOffset < 0 ? -1 : TerminalText.visualColumnAt(row, charOffset, 4);
    }

    private record SearchCursor(int row, int col) {
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

    private record HelpDialogLayout(int top, int left, int width, int height) {
    }

    private record RenderFrameResult(
        RenderPlan plan,
        boolean rendered
    ) {
    }

    private EditorTab newEditorTab() {
        TabSession session = TabSession.createEmpty(TerminalEditorTextLayout::new);
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

    private List<TabBar.TabItem> toTabItems(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TabBar.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TabBar.TabItem(tab.state().getCurrentFile(), tab.state().isDirty(), hasMatch));
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

    private List<MenuBar.OpenTab> toMenuTabs(List<EditorTab> tabs, boolean[] matchFlags) {
        List<MenuBar.OpenTab> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new MenuBar.OpenTab(tab.state().getCurrentFile(), tab.state().isDirty(), hasMatch));
        }
        return out;
    }

    private record EditorTab(
        TabSession tabSession,
        RenderPlanBuilder renderPlanBuilder
    ) {
        private EditorTab {
            if (tabSession == null
                || renderPlanBuilder == null
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
            return new EditorTab(tabSession, nextBuilder);
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

    private final class RecoveryAutosaveManager {
        private final RecoveryStore store;
        private final Map<TabSession, RuntimeRecoveryState> runtime = new IdentityHashMap<>();
        private long lastErrorNotifyAtNanos = Long.MIN_VALUE;

        private RecoveryAutosaveManager(RecoveryStore store) {
            this.store = store;
        }

        private void syncTabs(List<EditorTab> tabs) {
            if (tabs == null || tabs.isEmpty()) {
                clearRuntimeSnapshots();
                return;
            }
            Map<TabSession, Boolean> active = new IdentityHashMap<>();
            for (EditorTab tab : tabs) {
                TabSession session = tab.tabSession();
                active.put(session, Boolean.TRUE);
                runtime.computeIfAbsent(session, key -> new RuntimeRecoveryState(UUID.randomUUID().toString()));
            }
            var iterator = runtime.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TabSession, RuntimeRecoveryState> entry = iterator.next();
                if (!active.containsKey(entry.getKey())) {
                    deleteSnapshotQuietly(entry.getValue().entryId());
                    iterator.remove();
                }
            }
        }

        private String autosaveDirtyTabs(List<EditorTab> tabs, long nowNanos) {
            if (tabs == null || tabs.isEmpty()) {
                return null;
            }
            for (EditorTab tab : tabs) {
                TabSession session = tab.tabSession();
                RuntimeRecoveryState state = runtime.computeIfAbsent(
                    session,
                    key -> new RuntimeRecoveryState(UUID.randomUUID().toString())
                );
                if (!tab.state().isDirty()) {
                    if (state.hasSnapshot()) {
                        deleteSnapshotQuietly(state.entryId());
                        state.clearSnapshotState();
                    }
                    continue;
                }

                long revision = tab.model().revision();
                if (state.hasSnapshot() && revision == state.lastRevision()) {
                    continue;
                }
                boolean firstSnapshot = !state.hasSnapshot();
                if (!firstSnapshot && nowNanos - state.lastSavedAtNanos() < RECOVERY_AUTOSAVE_DEBOUNCE_NANOS) {
                    continue;
                }
                try {
                    store.save(state.entryId(), tab.engine().captureRecoverySnapshot());
                    state.markSaved(revision, nowNanos);
                } catch (IOException e) {
                    if (nowNanos - lastErrorNotifyAtNanos >= RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS) {
                        lastErrorNotifyAtNanos = nowNanos;
                        return "Recovery autosave failed: " + e.getMessage();
                    }
                }
            }
            return null;
        }

        private void deleteEntries(List<RecoveryStore.Entry> entries) {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            for (RecoveryStore.Entry entry : entries) {
                if (entry == null) {
                    continue;
                }
                deleteSnapshotQuietly(entry.id());
            }
        }

        private void clearRuntimeSnapshots() {
            for (RuntimeRecoveryState state : runtime.values()) {
                deleteSnapshotQuietly(state.entryId());
            }
            runtime.clear();
        }

        private void deleteSnapshotQuietly(String entryId) {
            if (entryId == null || entryId.isBlank()) {
                return;
            }
            try {
                store.delete(entryId);
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private static final class RuntimeRecoveryState {
        private final String entryId;
        private long lastRevision = Long.MIN_VALUE;
        private long lastSavedAtNanos = Long.MIN_VALUE;
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

        private long lastSavedAtNanos() {
            return lastSavedAtNanos;
        }

        private boolean hasSnapshot() {
            return hasSnapshot;
        }

        private void markSaved(long revision, long nowNanos) {
            lastRevision = revision;
            lastSavedAtNanos = nowNanos;
            hasSnapshot = true;
        }

        private void clearSnapshotState() {
            hasSnapshot = false;
            lastSavedAtNanos = Long.MIN_VALUE;
        }
    }

    private boolean effectiveSyntaxHighlighting(boolean syntaxHighlightingSetting) {
        return theme != UiStyles.Theme.LIGHT && syntaxHighlightingSetting;
    }

    private void applyTheme(UiStyles.Theme theme) {
        this.theme = theme == null ? UiStyles.Theme.CYAN : theme;
        this.styles = UiStyles.forTheme(this.theme);
    }

    private void printUsage() {
        System.out.println("Usage: tuink [options] [file]");
        System.out.println("Development: ./gradlew --console=plain :app:run -PappArgs=\"[options] [file]\"");
        System.out.println("Options:");
        System.out.println("  --no-mouse       Disable mouse tracking and mouse interactions");
        System.out.println("  --no-alt-screen  Do not use alternate screen buffer");
        System.out.println("  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        System.out.println("  --theme=<name>   UI palette: blue, cyan (default), light");
        System.out.println("  --read           Start in reading mode (editing disabled)");
        System.out.println("  --profile        Log render timings to stderr every " + PROFILE_LOG_EVERY_FRAMES + " frames");
        System.out.println("  --profile-file   Write profile logs to a file (also enables --profile)");
        System.out.println("  --help, -h       Show this help");
    }

    private boolean shouldForceExecTerminalProvider() {
        return "exec".equalsIgnoreCase(System.getProperty("dev.tuink.terminal.provider", ""));
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

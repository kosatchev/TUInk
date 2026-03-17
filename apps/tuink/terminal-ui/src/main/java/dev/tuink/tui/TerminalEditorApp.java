package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorViewportBehavior;
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
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.render.CellBuffer;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.ui.Button;
import dev.tuink.tui.ui.ConfirmBarController;
import dev.tuink.tui.ui.ConfirmBarView;
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
import dev.tuink.tui.ui.TerminalCursorController;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

/**
 * JLine terminal application wiring core + render + highlighter.
 */
public class TerminalEditorApp {
    private static final String DEFAULT_FILE_NAME = "untitled.txt";
    private static final String SAVE_AS_PROMPT = "Save As: ";
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    private static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final long IDLE_INPUT_POLL_TIMEOUT_MS = 16L;
    private static final long MAX_DEADLINE_INPUT_POLL_TIMEOUT_MS = 50L;
    private static final int PROFILE_LOG_EVERY_FRAMES = 30;
    private static final int AUTO_SELECTION_HIGHLIGHT_MIN_CHARS = 2;
    private static final int AUTO_SELECTION_HIGHLIGHT_MAX_CHARS = 128;
    private static final int CTRL_F_SELECTION_MAX_CHARS = 256;
    private static final long RECOVERY_AUTOSAVE_DEBOUNCE_NANOS = 2_000_000_000L;
    private static final long RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS = 5_000_000_000L;
    private static final String ENABLE_MOUSE_TRACKING = "\u001B[?1000h\u001B[?1002h\u001B[?1006h";
    private static final String DISABLE_MOUSE_TRACKING = "\u001B[?1000l\u001B[?1002l\u001B[?1006l";
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
        CliOptions cli;
        try {
            cli = parseArgs(args);
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
        try {
            loadedSettings = AppSettingsStore.load();
        } catch (IOException e) {
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
            startupRecoveryEntries.addAll(recoveryStore.loadAll());
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
                renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(file));
                sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
            } catch (IOException e) {
                notification.error("Open failed: " + e.getMessage());
            }
        }

        TerminalBuilder terminalBuilder = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8);
        if (shouldForceExecTerminalProvider()) {
            // Explicit fallback for musl static images that cannot load jline-native .so from /tmp.
            terminalBuilder
                .jni(false)
                .ffm(false)
                .jna(false)
                .jansi(false)
                .exec(true)
                .providers("exec,dumb");
        }
        try (Terminal terminal = terminalBuilder.build()) {
            if ("dumb".equalsIgnoreCase(terminal.getType())) {
                throw new IOException(
                    "Interactive terminal is not available (dumb terminal). " +
                    "Run in a real TTY, for example: ./gradlew --console=plain :tui:run --args=\"file.txt\""
                );
            }
            PrintWriter writer = terminal.writer();
            Attributes original = terminal.enterRawMode();
            Attributes raw = terminal.getAttributes();
            raw.setLocalFlag(LocalFlag.ISIG, false);
            terminal.setAttributes(raw);
            NonBlockingReader input = terminal.reader();
            AtomicBoolean resized = new AtomicBoolean(false);
            AtomicBoolean stopRequested = new AtomicBoolean(false);
            AtomicBoolean cleanedUp = new AtomicBoolean(false);
            terminal.handle(Terminal.Signal.WINCH, sig -> resized.set(true));
            terminal.handle(Terminal.Signal.INT, sig -> stopRequested.set(true));

            CellBuffer previous = null;
            RenderPlan plan = null;
            int lastCursorRow = -1;
            int lastCursorCol = -1;
            boolean lastCursorVisible = true;
            TerminalCursorController cursorController = new TerminalCursorController();
            Runnable cleanup = () -> {
                if (cleanedUp.compareAndSet(false, true)) {
                    StringBuilder out = new StringBuilder("\u001B[0m");
                    out.append(DISABLE_MOUSE_TRACKING);
                    out.append("\u001B[?25h");
                    if (cli.altScreenEnabled()) {
                        out.append("\u001B[?1049l");
                    }
                    out.append("\u001B[2J\u001B[H");
                    writer.print(out);
                    writer.flush();
                    terminal.setAttributes(original);
                }
            };
            Thread shutdownHook = new Thread(cleanup, "tuink-terminal-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                StringBuilder init = new StringBuilder();
                if (cli.altScreenEnabled()) {
                    init.append("\u001B[?1049h");
                }
                init.append("\u001B[?25h\u001B[2J");
                if (uiSessionState.mouseModeSetting) {
                    init.append(ENABLE_MOUSE_TRACKING);
                }
                writer.print(init);
                writer.flush();

                KeyDecoder decoder = new KeyDecoder(input);
                RenderFrameController renderFrameController = new RenderFrameController(
                    writer,
                    renderer,
                    editorView,
                    tabBar,
                    topMenu,
                    cli.colorEnabled(),
                    cursorController
                );
                InputEventPump inputEventPump = new InputEventPump(decoder, notification, resized);
                ViewportController viewportController = new ViewportController();
                boolean running = true;
                boolean needsRender = true;
                SearchBarView searchBar = null;
                ConfirmBarView confirmBar = null;
                InlinePromptBar inlinePromptBar = InlinePromptBar.hidden();
                boolean showTabBar = false;
                int contentTopRow = 1;
                int contentRows = 0;
                int contentCols = 0;
                List<TabBar.TabItem> tabItems = List.of();
                TabMatchCache tabMatchCache = new TabMatchCache();
                while (running && !stopRequested.get()) {
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
                        needsRender = true;
                    }
                    if (openFileMode && openFileTree.consumeMetadataChanged()) {
                        needsRender = true;
                    }
                    int rows = Math.max(2, terminal.getHeight());
                    int cols = Math.max(1, terminal.getWidth());
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
                    if (needsRender) {
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
                                styles.promptAlert(),
                                SAVE_AS_PROMPT.length() + saveAsInput.cursor() + 1
                            );
                        } else if (saveOverwriteConfirmMode) {
                            confirmBar = ConfirmBarView.build(
                                buildOverwritePrompt(pendingOverwritePath),
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(confirmBar.row(), styles.promptAlert(), 1, confirmBar.cursorCol() + 1);
                        } else if (recoveryConfirmMode) {
                            confirmBar = ConfirmBarView.buildRecovery(
                                buildRecoveryPrompt(startupRecoveryEntries.size()),
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(confirmBar.row(), styles.promptAlert(), 1, confirmBar.cursorCol() + 1);
                        } else if (quitConfirmMode) {
                            confirmBar = ConfirmBarView.build(
                                buildQuitPrompt(countDirtyTabs(tabs)),
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(confirmBar.row(), styles.promptAlert(), 1, confirmBar.cursorCol() + 1);
                        } else if (closeTabConfirmMode) {
                            confirmBar = ConfirmBarView.build(
                                buildCloseTabPrompt(tabs, pendingCloseTabIndex),
                                modalState.confirmChoice(),
                                cols
                            );
                            inlinePromptBar = InlinePromptBar.of(confirmBar.row(), styles.promptAlert(), 1, confirmBar.cursorCol() + 1);
                        } else if (searchBar != null) {
                            inlinePromptBar = InlinePromptBar.of(
                                searchBar.text(),
                                styles.searchBar(),
                                searchBar.cursorRow(),
                                searchBar.cursorCol()
                            );
                        }
                        NotificationBar notificationBar = NotificationBar.of(notification.message, notification.style());
                        showTabBar = uiSessionState.showTabBarSetting && tabs.size() > 1;
                        int overlayRows = inlinePromptBar.height();
                        contentTopRow = showTabBar ? 2 : 1;
                        int baseContentRows = Math.max(0, rows - overlayRows - contentTopRow - 1);
                        int gutterWidth = Math.max(1, String.valueOf(Math.max(model.lineCount(), 1)).length()) + 1;
                        int maxLineLength = model.maxVisualLineWidth(uiSessionState.tabWidthSetting);
                        boolean canShowVScrollbar = cols >= 8;
                        contentRows = baseContentRows;
                        contentCols = Math.max(0, cols - gutterWidth);
                        boolean needHorizontalScrollbar = false;
                        for (int i = 0; i < 3; i++) {
                            boolean needVerticalScrollbar = canShowVScrollbar
                                && (state.getTopLine() > 0 || model.lineCount() > contentRows);
                            int scrollbarWidth = needVerticalScrollbar ? 1 : 0;
                            contentCols = Math.max(0, cols - gutterWidth - scrollbarWidth);
                            needHorizontalScrollbar = !uiSessionState.wrapLinesSetting && contentCols > 0
                                && (state.getLeftVisualColumn() > 0 || maxLineLength > contentCols);
                            int newContentRows = Math.max(0, baseContentRows - (needHorizontalScrollbar ? 1 : 0));
                            if (newContentRows == contentRows) {
                                break;
                            }
                            contentRows = newContentRows;
                        }
                        viewportController.ensureCursorVisible(engine, contentRows, contentCols);
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
                        renderPlanBuilder.setCommentSyntaxProfile(resolveCommentSyntaxProfile(state.getCurrentFile()));
                        renderPlanBuilder.setRainbowBracketsEnabled(uiSessionState.rainbowBracketsSetting);
                        renderPlanBuilder.setTabWidth(uiSessionState.tabWidthSetting);
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
                        int planRows = Math.max(1, (showTabBar ? rows - 1 : rows) - (needHorizontalScrollbar ? 1 : 0));
                        RenderFrameResult frameResult = renderFrameController.renderFrameIfNeeded(
                            needsRender,
                            renderPlanBuilder,
                            model,
                            state,
                            rows,
                            cols,
                            contentTopRow,
                            showTabBar,
                            activeTabIndex,
                            tabItems,
                            planRows,
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
                            saveAsMode,
                            saveAsInput,
                            saveOverwriteConfirmMode,
                            pendingOverwritePath,
                            quitConfirmMode,
                            tabs,
                            closeTabConfirmMode,
                            pendingCloseTabIndex,
                            searchBar,
                            confirmBar,
                            openFileMode,
                            openFileTree,
                            openFileFocus,
                            helpHintsMode,
                            helpLicensesMode,
                            helpAuthorMode,
                            previous,
                            plan,
                            lastCursorRow,
                            lastCursorCol,
                            lastCursorVisible
                        );
                        previous = frameResult.previous();
                        plan = frameResult.plan();
                        lastCursorRow = frameResult.lastCursorRow();
                        lastCursorCol = frameResult.lastCursorCol();
                        lastCursorVisible = frameResult.lastCursorVisible();
                        if (frameResult.rendered()) {
                            needsRender = false;
                        }
                        if (profileEnabled) {
                            frameProfiler.endFrame(frameResult.rendered());
                        }
                    }

                    InputEventPump.PollResult pollResult = inputEventPump.poll();
                    if (pollResult.resized()) {
                        previous = null;
                        needsRender = true;
                    }
                    KeyEvent key = pollResult.key();
                    if (key == null) {
                        continue;
                    }
                    viewportController.onInput(key);
                    notification.clearErrorOnUserAction();
                    needsRender = true;

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
                                if (modalState.isNone() && !uiSessionState.searchMode) {
                                    int topBefore = state.getTopLine();
                                    int topWrapBefore = state.getTopWrapSegment();
                                    applyWheelVerticalScroll(model, state, plan, -3, uiSessionState.tabWidthSetting);
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
                                if (modalState.isNone() && !uiSessionState.searchMode) {
                                    int topBefore = state.getTopLine();
                                    int topWrapBefore = state.getTopWrapSegment();
                                    applyWheelVerticalScroll(model, state, plan, 3, uiSessionState.tabWidthSetting);
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
                                            sessionContext.activate(tabActivation);
                                            editorView.cancelMouseSelection();
                                            continue;
                                        }
                                        if (menuMouse.mappedAction() != null) {
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
                                        } else {
                                            KeyEvent.Action clickAction = searchActionAtMouse(
                                                searchBar,
                                                mouse.row(),
                                                mouse.col(),
                                                searchBarTopRow
                                            );
                                            if (clickAction != null) {
                                                remappedMouseKey = new KeyEvent.ActionEvent(clickAction);
                                                break;
                                            }
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    if (modalState.isNone() && !uiSessionState.searchMode) {
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
                                        editorView.handleMousePress(
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
                                if (modalState.isNone() && !uiSessionState.searchMode) {
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
                        String saveAsDraftBefore = saveAsInput.text();
                        SaveFlowController.SaveAsResult saveAsResult = SaveFlowController.handleSaveAsInput(
                            key,
                            true,
                            saveAsInput.text(),
                            false,
                            modalState.pendingOverwritePath(),
                            state.getCurrentFile(),
                            this::validateSaveTarget,
                            path -> Files.exists(path),
                            this::isReadOnlyFile,
                            this::isSameFilePath,
                            this::suggestWritableName
                        );
                        if (saveAsResult.consumed()) {
                            modalState = modalFromSaveAsResult(saveAsResult);
                            if (saveAsResult.clearNotification()) {
                                notification.clear();
                            }
                            if (saveAsResult.errorMessage() != null) {
                                notification.error(saveAsResult.errorMessage());
                            }
                            applySaveAsTextInputEffect(saveAsInput, saveAsResult.textInputEffect());
                            if (saveAsResult.saveAsMode()) {
                                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsInput.text());
                            } else {
                                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), saveAsDraftBefore);
                            }
                            if (saveAsResult.saveNowPath() != null) {
                                try {
                                    engine.saveAs(saveAsResult.saveNowPath());
                                    renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(state.getCurrentFile()));
                                    sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
                                    saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                                    notification.success(savedMessage(state));
                                } catch (IOException e) {
                                    notification.error(saveErrorMessage(e));
                                }
                            }
                            continue;
                        }
                    }

                    if (modalState.isSaveOverwriteConfirm()) {
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
                                    renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(state.getCurrentFile()));
                                    sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
                                    saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                                    notification.success(savedMessage(state));
                                    modalState = UiModalState.none();
                                } catch (IOException e) {
                                    notification.error(saveErrorMessage(e));
                                }
                            } else if (confirmInput.decision() == ConfirmChoice.DISCARD) {
                                modalState = UiModalState.saveAs();
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
                                if (saveDirtyTabs(tabs, notification)) {
                                    running = false;
                                    modalState = UiModalState.none();
                                }
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
                                if (saveDirtyTab(pendingTab, notification)) {
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
                            sessionContext.activate(tabActivation);
                            activeTabIndex = sessionContext.activeTabIndex();
                            model = sessionContext.model();
                            state = sessionContext.state();
                            engine = sessionContext.engine();
                            renderPlanBuilder = sessionContext.renderPlanBuilder();
                        }
                        key = topMenuResult.nextKey();
                        if (topMenuResult.keepMenuOpen()) {
                            continue;
                        }
                    }

                    if (uiSessionState.searchMode) {
                        SearchModeResult searchModeResult = handleSearchModeInput(
                            key,
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
                            running,
                            modalState.isQuitConfirm(),
                            modalState.isCloseTabConfirm(),
                            modalState.pendingCloseTabIndex(),
                            modalState.isSaveAs(),
                            saveAsInput,
                            saveAsSuggestions,
                            notification,
                            openFileMode,
                            openFileTree,
                            openFileFocus,
                            topMenu,
                            writer,
                            renderer,
                            tabBar
                        );
                        if (searchModeResult.consumed()) {
                            searchUi = searchModeResult.searchUi();
                            if (searchModeResult.activeTabIndex() != activeTabIndex) {
                                sessionContext.activate(searchModeResult.activeTabIndex());
                            }
                            running = searchModeResult.running();
                            modalState = modalFromLegacyFlags(
                                searchModeResult.saveAsMode(),
                                false,
                                null,
                                searchModeResult.quitConfirmMode(),
                                searchModeResult.closeTabConfirmMode(),
                                searchModeResult.pendingCloseTabIndex()
                            );
                            openFileMode = searchModeResult.openFileMode();
                            openFileFocus = searchModeResult.openFileFocus();
                            continue;
                        }
                    }

                    if (key instanceof KeyEvent.CharInput charInput) {
                        if (uiSessionState.readingModeSetting) {
                            notification.warning("Reading mode: editing is disabled");
                        } else {
                            engine.insertChar(charInput.ch());
                        }
                        continue;
                    }

                    KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                    InputActionDispatcher.DispatchResult dispatchResult = InputActionDispatcher.dispatch(
                        action,
                        uiSessionState,
                        new InputActionDispatcher.DispatchContext(
                            engine,
                            state,
                            topMenu,
                            writer,
                            ENABLE_MOUSE_TRACKING,
                            DISABLE_MOUSE_TRACKING,
                            contentRows
                        )
                    );
                    if (dispatchResult.handled()) {
                        if (action == KeyEvent.Action.CTRL_F) {
                            String selectedQuery = selectionQueryForCtrlF(model, state);
                            if (selectedQuery != null) {
                                searchQueryInput.set(selectedQuery);
                                boolean found;
                                if (searchUi.allTabs()) {
                                    int foundTab = TabSearch.findNextAcrossTabs(
                                        toTabSessions(tabs),
                                        activeTabIndex,
                                        searchQueryInput.text(),
                                        SearchController.toSearchOptions(searchUi)
                                    );
                                    found = foundTab >= 0;
                                    if (found && foundTab != activeTabIndex) {
                                        sessionContext.activate(foundTab);
                                    }
                                } else {
                                    found = engine.findNext(
                                        searchQueryInput.text(),
                                        SearchController.toSearchOptions(searchUi)
                                    );
                                }
                                notification.info(found ? "Found next match" : "Not found");
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
                        modalState.isQuitConfirm(),
                        modalState.isCloseTabConfirm(),
                        modalState.pendingCloseTabIndex(),
                        modalState.isSaveAs(),
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
                    modalState = modalFromLegacyFlags(
                        editorActionApplyResult.saveAsMode(),
                        false,
                        null,
                        editorActionApplyResult.quitConfirmMode(),
                        editorActionApplyResult.closeTabConfirmMode(),
                        editorActionApplyResult.pendingCloseTabIndex()
                    );
                    openFileMode = editorActionApplyResult.openFileMode();
                    openFileFocus = editorActionApplyResult.openFileFocus();
                }
                recoveryAutosave.clearRuntimeSnapshots();
            } finally {
                cleanup.run();
                frameProfiler.close();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down.
                }
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
        if (!effectiveSettings.equals(loadedSettings)) {
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
        boolean quitConfirmMode = editorAction.quitConfirmMode();
        boolean closeTabConfirmMode = editorAction.closeTabConfirmMode();
        int pendingCloseTabIndex = editorAction.pendingCloseTabIndex();
        boolean saveAsMode = editorAction.saveAsMode();
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
                String suggestion = suggestWritableName(state.getCurrentFile());
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
            quitConfirmMode,
            closeTabConfirmMode,
            pendingCloseTabIndex,
            saveAsMode,
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
            case INSERT -> saveAsInput.insert(effect.ch());
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
                    state.getTopLine(),
                    state.getTopWrapSegment(),
                    visibleCols,
                    plan.wrapByWords(),
                    tabWidth
                );
                EditorViewportBehavior.applyWrapTop(state, normalized);
                int topWrappedRow = EditorViewportBehavior.wrappedOffsetForWrapTop(
                    model,
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

    private UiModalState modalFromSaveAsResult(SaveFlowController.SaveAsResult result) {
        return modalFromLegacyFlags(
            result.saveAsMode(),
            result.saveOverwriteConfirmMode(),
            result.pendingOverwritePath(),
            false,
            false,
            -1
        );
    }

    private UiModalState modalFromSaveOverwriteResult(SaveFlowController.SaveOverwriteResult result) {
        return modalFromLegacyFlags(
            result.saveAsMode(),
            result.saveOverwriteConfirmMode(),
            result.pendingOverwritePath(),
            false,
            false,
            -1
        );
    }

    private UiModalState modalFromLegacyFlags(
        boolean saveAsMode,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath,
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex
    ) {
        if (saveAsMode) {
            return UiModalState.saveAs();
        }
        if (saveOverwriteConfirmMode) {
            return UiModalState.saveOverwriteConfirm(pendingOverwritePath);
        }
        if (quitConfirmMode) {
            return UiModalState.quitConfirm();
        }
        if (closeTabConfirmMode) {
            return UiModalState.closeTabConfirm(pendingCloseTabIndex);
        }
        return UiModalState.none();
    }

    private SearchModeResult handleSearchModeInput(
        KeyEvent key,
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
        boolean running,
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        boolean saveAsMode,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification,
        boolean openFileMode,
        TreeView openFileTree,
        OpenFileDialogController.Focus openFileFocus,
        MenuBar topMenu,
        PrintWriter writer,
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
        boolean nextQuitConfirmMode = quitConfirmMode;
        boolean nextCloseTabConfirmMode = closeTabConfirmMode;
        int nextPendingCloseTabIndex = pendingCloseTabIndex;
        boolean nextSaveAsMode = saveAsMode;
        boolean nextOpenFileMode = openFileMode;
        OpenFileDialogController.Focus nextOpenFileFocus = openFileFocus;

        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = charInput.ch();
            if (ch == ' ' && SearchController.isToggleFocus(uiSessionState.searchFocus)) {
                SearchController.SearchUiOptions before = nextSearchUi;
                nextSearchUi = SearchController.toggleFocusedSearchOption(nextSearchUi, uiSessionState.searchFocus);
                if (!nextSearchUi.equals(before)) {
                    statusMessage = "";
                }
            } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                searchReplaceInput.insert(ch);
                statusMessage = "";
            } else if (uiSessionState.searchFocus == SearchFocus.FIND) {
                searchQueryInput.insert(ch);
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
                nextQuitConfirmMode,
                nextCloseTabConfirmMode,
                nextPendingCloseTabIndex,
                nextSaveAsMode,
                nextOpenFileMode,
                nextOpenFileFocus
            );
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
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
                    if (nextSearchUi.allTabs()) {
                        int foundTab = TabSearch.findNextAcrossTabs(
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
                        found = nextEngine.findNext(
                            searchQueryInput.text(),
                            SearchController.toSearchOptions(nextSearchUi)
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
                if (nextSearchUi.allTabs()) {
                    int foundTab = TabSearch.findNextAcrossTabs(
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
                    found = nextEngine.findNext(
                        searchQueryInput.text(),
                        SearchController.toSearchOptions(nextSearchUi)
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
                    nextQuitConfirmMode = true;
                } else {
                    nextRunning = false;
                }
            }
            case CTRL_S -> {
                if (nextState.getCurrentFile() == null) {
                    uiSessionState.searchMode = false;
                    uiSessionState.searchFocus = SearchFocus.FIND;
                    nextSaveAsMode = true;
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
                    nextSaveAsMode = true;
                    String suggestion = suggestWritableName(nextState.getCurrentFile());
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
                    nextCloseTabConfirmMode = true;
                    nextPendingCloseTabIndex = nextActiveTabIndex;
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
                nextSaveAsMode = true;
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
                        writer,
                        ENABLE_MOUSE_TRACKING,
                        DISABLE_MOUSE_TRACKING,
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
            nextQuitConfirmMode,
            nextCloseTabConfirmMode,
            nextPendingCloseTabIndex,
            nextSaveAsMode,
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
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        boolean saveAsMode,
        boolean openFileMode,
        OpenFileDialogController.Focus openFileFocus
    ) {
    }

    private record EditorActionApplyResult(
        boolean running,
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        boolean saveAsMode,
        boolean openFileMode,
        OpenFileDialogController.Focus openFileFocus
    ) {
    }

    private void drawTopFileNameBold(CellBuffer buffer, int cols, EditorState state, int startCol) {
        if (buffer.rows() < 1 || cols <= startCol || startCol < 0) {
            return;
        }
        String file = state.getCurrentFile() == null ? "--no name--" : state.getCurrentFile().toString();
        int endCol = Math.min(cols, startCol + file.length());
        for (int col = startCol; col < endCol; col++) {
            String grapheme = buffer.get(0, col).grapheme();
            buffer.set(0, col, grapheme, styles.topbarFilenameHighlight());
        }
    }

    private void drawMenuLine(CellBuffer buffer, int row, int startCol, String text, Style style, int cols) {
        for (int i = 0; i < text.length() && startCol + i < cols; i++) {
            buffer.set(row, startCol + i, String.valueOf(text.charAt(i)), style);
        }
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

    private Style focusedButtonStyle(Style parentStyle) {
        if (parentStyle == null) {
            return styles.menuSelected();
        }
        return new Style(parentStyle.bg(), parentStyle.fg(), true, parentStyle.underline());
    }

    private String padRight(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
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
        return text.length() <= width ? text : text.substring(0, width);
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
            tab = tab.withRenderPlanBuilder(new RenderPlanBuilder(resolveHighlighter(target)));
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
        if (left.length() >= cols) {
            return left.substring(0, cols);
        }
        String rightBlock = indicators + " ".repeat(Math.max(0, rightMargin));
        if (rightBlock.length() >= cols) {
            return rightBlock.substring(0, cols);
        }
        int free = cols - left.length();
        if (free <= rightBlock.length()) {
            return left + rightBlock.substring(0, Math.max(0, free));
        }
        return left + " ".repeat(free - rightBlock.length()) + rightBlock;
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

    private long nextInputTimeoutMs(NotificationState notification) {
        long nanosUntilExpire = notification.nanosUntilExpire(System.nanoTime());
        if (nanosUntilExpire < 0) {
            return IDLE_INPUT_POLL_TIMEOUT_MS;
        }
        long millisUntilExpire = nanosUntilExpire / 1_000_000L;
        if (nanosUntilExpire % 1_000_000L != 0L) {
            millisUntilExpire++;
        }
        return Math.max(0L, Math.min(MAX_DEADLINE_INPUT_POLL_TIMEOUT_MS, millisUntilExpire));
    }

    private RenderFrameResult doRenderFrameIfNeeded(
        boolean needsRender,
        RenderPlanBuilder renderPlanBuilder,
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        int contentTopRow,
        boolean showTabBar,
        int activeTabIndex,
        List<TabBar.TabItem> tabItems,
        int planRows,
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
        boolean saveAsMode,
        TextField saveAsInput,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath,
        boolean quitConfirmMode,
        List<EditorTab> tabs,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        SearchBarView searchBar,
        ConfirmBarView confirmBar,
        boolean openFileMode,
        TreeView openFileTree,
        OpenFileDialogController.Focus openFileFocus,
        boolean helpHintsMode,
        boolean helpLicensesMode,
        boolean helpAuthorMode,
        CellBuffer previous,
        RenderPlan previousPlan,
        int previousCursorRow,
        int previousCursorCol,
        boolean previousCursorVisible,
        boolean colorEnabled,
        PrintWriter writer,
        TerminalCursorController cursorController,
        RenderRasterizer renderer,
        EditorView editorView,
        TabBar tabBar,
        MenuBar topMenu
    ) {
        if (!needsRender) {
            return new RenderFrameResult(
                previous,
                previousPlan,
                previousCursorRow,
                previousCursorCol,
                previousCursorVisible,
                false
            );
        }
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
                planRows,
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
                wrapWordsSetting
            );
            frameProfiler.buildNanos = System.nanoTime() - buildStart;
        } else {
            plan = renderPlanBuilder.build(
                model,
                state,
                planRows,
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
                wrapWordsSetting
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
        int interactiveBottomCursorRow = Math.max(1, rows - 1);
        int interactiveTopCursorRow = interactiveBottomCursorRow - inlinePromptBarHeight + 1;
        if (saveAsMode) {
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = Math.min(cols, SAVE_AS_PROMPT.length() + saveAsInput.cursor() + 1);
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

        String frameDiff;
        boolean canUseVerticalScrollFastPath = previousPlan != null
            && previous != null
            && plan.wrapLines() == previousPlan.wrapLines()
            && plan.gutterWidth() == previousPlan.gutterWidth()
            && plan.scrollbarWidth() == previousPlan.scrollbarWidth()
            && plan.leftVisualColumn() == previousPlan.leftVisualColumn()
            && plan.topLine() != previousPlan.topLine();
        boolean canUseHorizontalScrollFastPath = previousPlan != null
            && previous != null
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
            canUseVerticalScrollFastPath = previous != null
                && lastOpenFileModeForDiff
                && openFileTree.scrollOffset() != lastOpenFileScrollForDiff;
            canUseHorizontalScrollFastPath = false;
            horizontalDeltaColumns = 0;
        }
        if (profileEnabled) {
            long diffStart = System.nanoTime();
            frameDiff = AnsiDiff.diff(
                previous,
                current,
                colorEnabled,
                diffRegionTop,
                diffRegionHeight,
                textStartCol,
                textEndCol,
                canUseVerticalScrollFastPath,
                canUseHorizontalScrollFastPath,
                horizontalDeltaColumns
            );
            frameProfiler.diffNanos = System.nanoTime() - diffStart;
        } else {
            frameDiff = AnsiDiff.diff(
                previous,
                current,
                colorEnabled,
                diffRegionTop,
                diffRegionHeight,
                textStartCol,
                textEndCol,
                canUseVerticalScrollFastPath,
                canUseHorizontalScrollFastPath,
                horizontalDeltaColumns
            );
        }
        TerminalCursorController.ApplyResult cursorApplyResult = cursorController.apply(
            writer,
            frameDiff,
            desiredCursorRow,
            desiredCursorCol,
            desiredCursorVisible
        );
        lastOpenFileModeForDiff = openFileMode;
        lastOpenFileScrollForDiff = openFileMode ? openFileTree.scrollOffset() : 0;
        return new RenderFrameResult(
            current,
            plan,
            cursorApplyResult.row(),
            cursorApplyResult.col(),
            cursorApplyResult.visible(),
            true
        );
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

    private final class RenderFrameController {
        private final PrintWriter writer;
        private final RenderRasterizer renderer;
        private final EditorView editorView;
        private final TabBar tabBar;
        private final MenuBar topMenu;
        private final boolean colorEnabled;
        private final TerminalCursorController cursorController;

        private RenderFrameController(
            PrintWriter writer,
            RenderRasterizer renderer,
            EditorView editorView,
            TabBar tabBar,
            MenuBar topMenu,
            boolean colorEnabled,
            TerminalCursorController cursorController
        ) {
            this.writer = writer;
            this.renderer = renderer;
            this.editorView = editorView;
            this.tabBar = tabBar;
            this.topMenu = topMenu;
            this.colorEnabled = colorEnabled;
            this.cursorController = cursorController;
        }

        private RenderFrameResult renderFrameIfNeeded(
            boolean needsRender,
            RenderPlanBuilder renderPlanBuilder,
            TextModel model,
            EditorState state,
            int rows,
            int cols,
            int contentTopRow,
            boolean showTabBar,
            int activeTabIndex,
            List<TabBar.TabItem> tabItems,
            int planRows,
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
            boolean saveAsMode,
            TextField saveAsInput,
            boolean saveOverwriteConfirmMode,
            Path pendingOverwritePath,
            boolean quitConfirmMode,
            List<EditorTab> tabs,
            boolean closeTabConfirmMode,
            int pendingCloseTabIndex,
            SearchBarView searchBar,
            ConfirmBarView confirmBar,
            boolean openFileMode,
            TreeView openFileTree,
            OpenFileDialogController.Focus openFileFocus,
            boolean helpHintsMode,
            boolean helpLicensesMode,
            boolean helpAuthorMode,
            CellBuffer previous,
            RenderPlan previousPlan,
            int previousCursorRow,
            int previousCursorCol,
            boolean previousCursorVisible
        ) {
            return doRenderFrameIfNeeded(
                needsRender,
                renderPlanBuilder,
                model,
                state,
                rows,
                cols,
                contentTopRow,
                showTabBar,
                activeTabIndex,
                tabItems,
                planRows,
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
                saveAsMode,
                saveAsInput,
                saveOverwriteConfirmMode,
                pendingOverwritePath,
                quitConfirmMode,
                tabs,
                closeTabConfirmMode,
                pendingCloseTabIndex,
                searchBar,
                confirmBar,
                openFileMode,
                openFileTree,
                openFileFocus,
                helpHintsMode,
                helpLicensesMode,
                helpAuthorMode,
                previous,
                previousPlan,
                previousCursorRow,
                previousCursorCol,
                previousCursorVisible,
                colorEnabled,
                writer,
                cursorController,
                renderer,
                editorView,
                tabBar,
                topMenu
            );
        }
    }

    private final class InputEventPump {
        private final KeyDecoder decoder;
        private final NotificationState notification;
        private final AtomicBoolean resized;

        private InputEventPump(KeyDecoder decoder, NotificationState notification, AtomicBoolean resized) {
            this.decoder = decoder;
            this.notification = notification;
            this.resized = resized;
        }

        private PollResult poll() throws IOException {
            KeyEvent key = decoder.readKey(nextInputTimeoutMs(notification));
            boolean wasResized = resized.getAndSet(false);
            return new PollResult(key, wasResized);
        }

        private record PollResult(KeyEvent key, boolean resized) {
        }
    }

    private String buildSaveAsPrompt(TextField saveAsInput) {
        return SAVE_AS_PROMPT + saveAsInput.text();
    }

    private String buildOverwritePrompt(Path path) {
        return "Overwrite existing file: " + path + " ? (y/N)";
    }

    private String buildQuitPrompt(int dirtyTabs) {
        return "Unsaved tabs: " + dirtyTabs + ". Quit now? (y/N)";
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

    private boolean saveDirtyTabs(List<EditorTab> tabs, NotificationState notification) {
        for (EditorTab tab : tabs) {
            if (!tab.state().isDirty()) {
                continue;
            }
            if (!saveDirtyTab(tab, notification)) {
                return false;
            }
        }
        return true;
    }

    private boolean saveDirtyTab(EditorTab tab, NotificationState notification) {
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
            TabSession tabSession = TabSession.createEmpty();
            tabSession.engine().restoreRecoverySnapshot(entry.snapshot());
            RenderPlanBuilder builder = new RenderPlanBuilder(resolveHighlighter(tabSession.state().getCurrentFile()));
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

    private int countDirtyTabs(List<EditorTab> tabs) {
        return TabSessions.countDirtyTabs(toTabSessions(tabs));
    }

    private String suggestWritableName(Path path) {
        String fileName = path.getFileName() == null ? DEFAULT_FILE_NAME : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        String candidate = base + "-copy" + ext;
        Path parent = path.getParent();
        return parent == null ? candidate : parent.resolve(candidate).toString();
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
        int queryCursor = queryField == null ? safeQuery.length() : queryField.cursor();
        int replacementCursor = replacementField == null ? safeReplacement.length() : replacementField.cursor();
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
        int findEnd = findField.length();
        int caseStart = optionsStart;
        int caseEnd = caseStart + csToggle.length();
        int wordStart = caseEnd + 1;
        int wordEnd = wordStart + wToggle.length();
        int wildcardStart = wordEnd + 1;
        int wildcardEnd = wildcardStart + starToggle.length();
        int allStart = wildcardEnd + 1;
        int allEnd = allStart + allToggle.length();
        Button caseButtonView = new Button(0, caseStart, caseToggleTemplate.text());
        Button wordButtonView = new Button(0, wordStart, wordToggleTemplate.text());
        Button wildcardButtonView = new Button(0, wildcardStart, wildcardToggleTemplate.text());
        Button allToggleButtonView = new Button(0, allStart, allToggleTemplate.text());
        int replaceStart = 0;
        int replaceEnd = replaceField.length();
        int nextStart = actionsStart;
        int nextEnd = nextStart + nextButton.length();
        int prevStart = nextEnd + 1;
        int prevEnd = prevStart + prevButton.length();
        int replaceActionStart = prevEnd + 1;
        int replaceActionEnd = replaceActionStart + replaceButton.length();
        int allActionStart = replaceActionEnd + 1;
        int allActionEnd = allActionStart + allButton.length();
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
        int findCursorCol = "Find: ".length() + Math.max(0, Math.min(queryCursor, query.length()));
        if (focus == SearchFocus.FIND) {
            return new SearchCursor(1, findCursorCol);
        }
        if (focus == SearchFocus.REPLACE) {
            return new SearchCursor(2, "Replace: ".length() + Math.max(0, Math.min(replacementCursor, replacement.length())));
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
        if (text.length() >= cols) {
            return text.substring(0, cols);
        }
        return text + " ".repeat(cols - text.length());
    }

    private static String alignLeftRight(String left, String right, int cols, int rightMargin) {
        if (cols <= 0) {
            return "";
        }
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        int margin = Math.max(0, rightMargin);
        int rightBlock = safeRight.length() + margin;
        if (safeRight.isEmpty()) {
            return clipPad(safeLeft, cols);
        }
        if (rightBlock >= cols) {
            return clipPad(safeRight, cols);
        }
        int leftWidth = cols - rightBlock;
        String leftPart = safeLeft.length() > leftWidth
            ? safeLeft.substring(0, leftWidth)
            : safeLeft + " ".repeat(leftWidth - safeLeft.length());
        return leftPart + safeRight + " ".repeat(margin);
    }

    private static int rightClusterStart(String row, String cluster) {
        if (row == null || cluster == null || cluster.isEmpty()) {
            return -1;
        }
        return row.indexOf(cluster);
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
        CellBuffer previous,
        RenderPlan plan,
        int lastCursorRow,
        int lastCursorCol,
        boolean lastCursorVisible,
        boolean rendered
    ) {
    }

    private EditorTab newEditorTab() {
        TabSession session = TabSession.createEmpty();
        RenderPlanBuilder builder = new RenderPlanBuilder(RegexHighlighter.withDefaults());
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
        private TextModel model() {
            return tabSession.model();
        }

        private EditorState state() {
            return tabSession.state();
        }

        private EditorEngine engine() {
            return tabSession.engine();
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

        private Style style() {
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

    private RenderPlanBuilder.CommentSyntaxProfile resolveCommentSyntaxProfile(Path file) {
        if (file == null || file.getFileName() == null) {
            return RenderPlanBuilder.CommentSyntaxProfile.GENERIC;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = extensionOf(name);
        if ("sql".equals(ext)) {
            return RenderPlanBuilder.CommentSyntaxProfile.SQL;
        }
        if (isHashCommentFile(name, ext)) {
            return RenderPlanBuilder.CommentSyntaxProfile.HASH;
        }
        if (isCStyleCommentFile(ext)) {
            return RenderPlanBuilder.CommentSyntaxProfile.C_STYLE;
        }
        if ("html".equals(ext) || "htm".equals(ext) || "xml".equals(ext)) {
            return RenderPlanBuilder.CommentSyntaxProfile.HTML;
        }
        if ("md".equals(ext) || "markdown".equals(ext)) {
            return RenderPlanBuilder.CommentSyntaxProfile.MARKDOWN;
        }
        if ("txt".equals(ext)) {
            return RenderPlanBuilder.CommentSyntaxProfile.NONE;
        }
        return RenderPlanBuilder.CommentSyntaxProfile.GENERIC;
    }

    private static boolean isHashCommentFile(String fileName, String ext) {
        if (ext == null) {
            return false;
        }
        if (ext.equals("py") || ext.equals("sh") || ext.equals("bash") || ext.equals("zsh")
            || ext.equals("yaml") || ext.equals("yml") || ext.equals("toml")
            || ext.equals("ini") || ext.equals("cfg") || ext.equals("conf")
            || ext.equals("properties")) {
            return true;
        }
        return fileName.equals("dockerfile")
            || fileName.equals(".gitignore")
            || fileName.equals(".editorconfig")
            || fileName.startsWith(".env")
            || fileName.equals("makefile");
    }

    private static boolean isCStyleCommentFile(String ext) {
        if (ext == null) {
            return false;
        }
        return ext.equals("java")
            || ext.equals("c")
            || ext.equals("h")
            || ext.equals("ino")
            || ext.equals("js")
            || ext.equals("mjs")
            || ext.equals("cjs")
            || ext.equals("jsx")
            || ext.equals("ts")
            || ext.equals("tsx")
            || ext.equals("go")
            || ext.equals("rs")
            || ext.equals("kt")
            || ext.equals("kts")
            || ext.equals("css")
            || ext.equals("scss")
            || ext.equals("proto")
            || ext.equals("gradle");
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1);
    }

    private boolean effectiveSyntaxHighlighting(boolean syntaxHighlightingSetting) {
        return theme != UiStyles.Theme.LIGHT && syntaxHighlightingSetting;
    }

    private void applyTheme(UiStyles.Theme theme) {
        this.theme = theme == null ? UiStyles.Theme.CYAN : theme;
        this.styles = UiStyles.forTheme(this.theme);
    }

    private CliOptions parseArgs(String[] args) {
        Boolean mouseEnabledOverride = null;
        boolean altScreenEnabled = true;
        boolean colorEnabled = true;
        Boolean readModeOverride = null;
        boolean showHelp = false;
        boolean profileEnabled = false;
        Path profileFile = null;
        UiStyles.Theme themeOverride = null;
        Path file = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--no-mouse" -> mouseEnabledOverride = false;
                case "--no-alt-screen" -> altScreenEnabled = false;
                case "--no-color" -> colorEnabled = false;
                case "--read" -> readModeOverride = true;
                case "--profile" -> profileEnabled = true;
                case "--help", "-h" -> showHelp = true;
                default -> {
                    if ("--profile-file".equals(arg)) {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Missing value after --profile-file");
                        }
                        profileFile = Path.of(args[++i]);
                        profileEnabled = true;
                        continue;
                    }
                    if (arg.startsWith("--theme=")) {
                        themeOverride = UiStyles.Theme.parse(arg.substring("--theme=".length()));
                        continue;
                    }
                    if (arg.startsWith("--profile-file=")) {
                        profileFile = Path.of(arg.substring("--profile-file=".length()));
                        profileEnabled = true;
                        continue;
                    }
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (file != null) {
                        throw new IllegalArgumentException("Only one file path is supported.");
                    }
                    file = Path.of(arg);
                }
            }
        }
        return new CliOptions(
            file,
            mouseEnabledOverride,
            altScreenEnabled,
            colorEnabled,
            readModeOverride,
            showHelp,
            themeOverride,
            profileEnabled,
            profileFile
        );
    }

    private void printUsage() {
        System.out.println("Usage: :tui:run --args=\"[options] [file]\"");
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

    private record CliOptions(
        Path file,
        Boolean mouseEnabledOverride,
        boolean altScreenEnabled,
        boolean colorEnabled,
        Boolean readModeOverride,
        boolean showHelp,
        UiStyles.Theme themeOverride,
        boolean profileEnabled,
        Path profileFile
    ) {
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

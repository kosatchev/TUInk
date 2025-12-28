package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSessions;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.tui.render.CellBuffer;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.ui.Button;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.MenuBar;
import dev.tuink.tui.ui.Modal;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogButtons;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.TabBar;
import dev.tuink.tui.ui.TextField;
import dev.tuink.tui.ui.TreeView;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private static final String SEARCH_HELP = "Tab focus  Space toggle  Enter next  Ctrl+G prev  Ctrl+R replace  Ctrl+A all  Esc";
    private static final String SAVE_AS_PROMPT = "Save As: ";
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    private static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final long IDLE_INPUT_POLL_TIMEOUT_MS = 16L;
    private static final long MAX_DEADLINE_INPUT_POLL_TIMEOUT_MS = 50L;
    private static final String ENABLE_MOUSE_TRACKING = "\u001B[?1000h\u001B[?1002h\u001B[?1006h";
    private static final String DISABLE_MOUSE_TRACKING = "\u001B[?1000l\u001B[?1002l\u001B[?1006l";
    private static final String[] HELP_HINT_ROWS = {
        "Key                  Action",
        "-------------------- ----------------------------------",
        "",
        "              GLOBAL",
        "Ctrl+O               Open file dialog",
        "Ctrl+N               New tab",
        "Ctrl+S               Save",
        "Ctrl+K               Save As",
        "Ctrl+Q               Quit",
        "Ctrl+F               Open find bar",
        "Ctrl+A               Select all",
        "Arrows/Home/End      Move cursor",
        "PgUp/PgDn            Scroll page",
        "",
        "               MENU",
        "F10 or Alt+F         Open File menu",
        "Ctrl+E or Alt+E      Open Edit menu",
        "Ctrl+T or Alt+T      Open Tab menu",
        "Alt+V                Open View menu",
        "F2 or Alt+S          Open Settings menu",
        "F1 or Alt+H          Open Help menu",
        "Arrows               Move selection",
        "Enter                Activate item",
        "Letters / 1-9        Mnemonic selection",
        "Esc                  Close menu",
        "",
        "              SEARCH",
        "Tab                  Next search field/toggle",
        "Space                Toggle option",
        "Enter or Ctrl+F      Next match",
        "Ctrl+G               Previous match",
        "Ctrl+R               Replace current/next",
        "Ctrl+A               Replace all",
        "Esc                  Close search bar"
    };
    private UiStyles.Theme theme = UiStyles.Theme.CLASSIC;
    private UiStyles.Palette styles = UiStyles.forTheme(theme);

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

        applyTheme(cli.theme());
        List<EditorTab> tabs = new ArrayList<>();
        tabs.add(newEditorTab());
        int activeTabIndex = 0;
        TextModel model = tabs.get(activeTabIndex).model();
        EditorState state = tabs.get(activeTabIndex).state();
        EditorEngine engine = tabs.get(activeTabIndex).engine();
        RenderPlanBuilder renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
        RenderRasterizer renderer = new RenderRasterizer(1, theme);
        EditorView editorView = new EditorView();

        String statusMessage = "Ctrl+S save | Ctrl+Q quit";
        NotificationState notification = new NotificationState();
        boolean quitConfirmMode = false;
        boolean closeTabConfirmMode = false;
        int pendingCloseTabIndex = -1;
        boolean saveAsMode = false;
        boolean saveOverwriteConfirmMode = false;
        Path pendingOverwritePath = null;
        TextField saveAsInput = new TextField();
        UiSessionState uiSessionState = UiSessionState.from(
            false,
            SearchFocus.FIND,
            true,
            false,
            true,
            cli.readMode(),
            true,
            cli.mouseEnabled(),
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
        TreeView openFileTree = new TreeView(initialOpenRoot(state));
        OpenFileDialogController.Focus openFileFocus = OpenFileDialogController.Focus.LIST;
        EditorView.ScrollAxis editorScrollDrag = EditorView.ScrollAxis.NONE;
        TreeView.ScrollAxis openFileScrollDrag = TreeView.ScrollAxis.NONE;

        if (cli.file() != null) {
            Path file = cli.file();
            try {
                engine.open(file);
                renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(file));
                tabs.set(activeTabIndex, tabs.get(activeTabIndex).withRenderPlanBuilder(renderPlanBuilder));
                statusMessage = "Opened " + file;
            } catch (IOException e) {
                statusMessage = "Open failed: " + e.getMessage();
            }
        } else {
            statusMessage = "Ctrl+S save as " + DEFAULT_FILE_NAME + " | Ctrl+Q quit";
        }

        try (Terminal terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build()) {
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
                    cli.colorEnabled()
                );
                InputEventPump inputEventPump = new InputEventPump(decoder, notification, resized);
                boolean running = true;
                boolean needsRender = true;
                while (running && !stopRequested.get()) {
                    if (notification.expireIfNeeded()) {
                        needsRender = true;
                    }
                    int rows = Math.max(2, terminal.getHeight());
                    int cols = Math.max(1, terminal.getWidth());
                    int searchMatchCount = countSearchMatches(
                        searchUi.allTabs() ? null : model,
                        searchUi.allTabs() ? tabs : null,
                        searchQueryInput.text(),
                        SearchController.toSearchOptions(searchUi)
                    );
                    SearchBarView searchBar = uiSessionState.searchMode
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
                    InlinePromptBar inlinePromptBar = InlinePromptBar.hidden();
                    if (saveAsMode) {
                        inlinePromptBar = InlinePromptBar.of(
                            buildSaveAsPrompt(saveAsInput),
                            styles.promptAlert(),
                            SAVE_AS_PROMPT.length() + saveAsInput.cursor() + 1
                        );
                    } else if (saveOverwriteConfirmMode) {
                        String prompt = buildOverwritePrompt(pendingOverwritePath);
                        inlinePromptBar = InlinePromptBar.of(prompt, styles.promptAlert(), prompt.length() + 1);
                    } else if (quitConfirmMode) {
                        String prompt = buildQuitPrompt(countDirtyTabs(tabs));
                        inlinePromptBar = InlinePromptBar.of(prompt, styles.promptAlert(), prompt.length() + 1);
                    } else if (closeTabConfirmMode) {
                        String prompt = buildCloseTabPrompt(tabs, pendingCloseTabIndex);
                        inlinePromptBar = InlinePromptBar.of(prompt, styles.promptAlert(), prompt.length() + 1);
                    } else if (searchBar != null) {
                        inlinePromptBar = InlinePromptBar.of(
                            searchBar.text(),
                            styles.searchBar(),
                            searchBar.cursorRow(),
                            searchBar.cursorCol()
                        );
                    }
                    NotificationBar notificationBar = NotificationBar.of(notification.message, notification.style());
                    boolean showTabBar = uiSessionState.showTabBarSetting && tabs.size() > 1;
                    int overlayRows = inlinePromptBar.height() + notificationBar.height();
                    int contentTopRow = showTabBar ? 2 : 1;
                    int baseContentRows = Math.max(0, rows - overlayRows - contentTopRow - 1);
                    int gutterWidth = Math.max(1, String.valueOf(Math.max(model.lineCount(), 1)).length()) + 1;
                    int maxLineLength = model.maxVisualLineWidth(4);
                    boolean canShowVScrollbar = cols >= 8;
                    int contentRows = baseContentRows;
                    int contentCols = Math.max(0, cols - gutterWidth);
                    boolean needHorizontalScrollbar = false;
                    for (int i = 0; i < 3; i++) {
                        boolean needVerticalScrollbar = canShowVScrollbar
                            && (state.getTopLine() > 0 || model.lineCount() > contentRows);
                        int scrollbarWidth = needVerticalScrollbar ? 1 : 0;
                        contentCols = Math.max(0, cols - gutterWidth - scrollbarWidth);
                        needHorizontalScrollbar = !uiSessionState.wrapLinesSetting && contentCols > 0
                            && (state.getLeftColumn() > 0 || maxLineLength > contentCols);
                        int newContentRows = Math.max(0, baseContentRows - (needHorizontalScrollbar ? 1 : 0));
                        if (newContentRows == contentRows) {
                            break;
                        }
                        contentRows = newContentRows;
                    }
                    engine.ensureCursorVisible(contentRows, contentCols);
                    boolean[] tabMatchFlags = findTabsWithMatches(
                        tabs,
                        searchQueryInput.text(),
                        SearchController.toSearchOptions(searchUi)
                    );
                    List<TabBar.TabItem> tabItems = toTabItems(tabs, tabMatchFlags);
                    topMenu.setOpenTabs(toMenuTabs(tabs, tabMatchFlags), activeTabIndex);
                    topMenu.setShowTabBarEnabled(uiSessionState.showTabBarSetting);
                    topMenu.setWrapLinesEnabled(uiSessionState.wrapLinesSetting);
                    topMenu.setWrapWordsEnabled(uiSessionState.wrapWordsSetting);
                    topMenu.setReadingModeEnabled(uiSessionState.readingModeSetting);
                    topMenu.setConfirmOnCloseEnabled(uiSessionState.confirmOnCloseSetting);
                    topMenu.setMouseModeEnabled(uiSessionState.mouseModeSetting);

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
                        interactiveBarText,
                        interactiveBarStyle,
                        notificationBar,
                        uiSessionState.wrapLinesSetting,
                        uiSessionState.wrapWordsSetting,
                        saveAsMode,
                        saveAsInput,
                        saveOverwriteConfirmMode,
                        pendingOverwritePath,
                        quitConfirmMode,
                        tabs,
                        closeTabConfirmMode,
                        pendingCloseTabIndex,
                        searchBar,
                        openFileMode,
                        openFileTree,
                        openFileFocus,
                        uiSessionState.helpHintsMode,
                        previous,
                        plan,
                        lastCursorRow,
                        lastCursorCol
                    );
                    previous = frameResult.previous();
                    plan = frameResult.plan();
                    lastCursorRow = frameResult.lastCursorRow();
                    lastCursorCol = frameResult.lastCursorCol();
                    if (frameResult.rendered()) {
                        needsRender = false;
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
                    needsRender = true;

                    if (key instanceof KeyEvent.MouseEvent mouse) {
                        if (!uiSessionState.mouseModeSetting) {
                            continue;
                        }
                        if (uiSessionState.helpHintsMode) {
                            if (mouse.kind() == KeyEvent.MouseKind.PRESS
                                && mouse.button() == KeyEvent.MouseButton.LEFT) {
                                HelpDialogLayout layout = helpDialogLayout(rows, cols);
                                Modal frame = helpDialogFrame(layout);
                                Button close = helpCloseButton(layout);
                                if (frame.closeButton().hit(mouse.row(), mouse.col())
                                    || close.hit(mouse.row(), mouse.col())) {
                                    uiSessionState.helpHintsMode = false;
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
                                if (!saveAsMode && !saveOverwriteConfirmMode
                                    && !quitConfirmMode && !closeTabConfirmMode && !uiSessionState.searchMode) {
                                    engine.pageUp(Math.max(1, contentRows / 2));
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
                                if (!saveAsMode && !saveOverwriteConfirmMode
                                    && !quitConfirmMode && !closeTabConfirmMode && !uiSessionState.searchMode) {
                                    engine.pageDown(Math.max(1, contentRows / 2));
                                }
                            }
                            case PRESS -> {
                                if (mouse.button() == KeyEvent.MouseButton.LEFT) {
                                    editorScrollDrag = EditorView.ScrollAxis.NONE;
                                    openFileScrollDrag = TreeView.ScrollAxis.NONE;
                                    if (openFileMode) {
                                        OpenFileDialogController.OpenDialogLayout layout = OpenFileDialogController.layoutForTerminal(rows, cols);
                                        Modal frame = openFileFrame(layout);
                                        OpenFileDialogController.OpenDialogButtons buttons = OpenFileDialogController.buttonsForLayout(layout);
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
                                                    OpenFileActionResult result = activateOpenFileSelection(
                                                        selected,
                                                        tabs,
                                                        openFileActiveTabIndex
                                                    );
                                                    return new OpenFileDialogController.ActivationResult(
                                                        result.keepDialogOpen(),
                                                        result.activeTabIndex(),
                                                        result.statusMessage()
                                                    );
                                                }
                                            );
                                        if (mouseResult.consumed()) {
                                            openFileMode = mouseResult.openFileMode();
                                            openFileFocus = mouseResult.focus();
                                            openFileScrollDrag = mouseResult.dragAxis();
                                            if (mouseResult.activeTabIndex() != activeTabIndex) {
                                                activeTabIndex = mouseResult.activeTabIndex();
                                                model = tabs.get(activeTabIndex).model();
                                                state = tabs.get(activeTabIndex).state();
                                                engine = tabs.get(activeTabIndex).engine();
                                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                            }
                                            if (mouseResult.statusMessage() != null && !mouseResult.statusMessage().isBlank()) {
                                                statusMessage = mouseResult.statusMessage();
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
                                    if (closeTabConfirmMode && tabHit.consumed()) {
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
                                            if (clickedTabIndex == pendingCloseTabIndex) {
                                                activeTabIndex = closeTab(tabs, activeTabIndex, pendingCloseTabIndex);
                                                model = tabs.get(activeTabIndex).model();
                                                state = tabs.get(activeTabIndex).state();
                                                engine = tabs.get(activeTabIndex).engine();
                                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                                closeTabConfirmMode = false;
                                                pendingCloseTabIndex = -1;
                                                statusMessage = "Closed tab";
                                            } else if (tabs.get(clickedTabIndex).state().isDirty()) {
                                                pendingCloseTabIndex = clickedTabIndex;
                                            } else {
                                                activeTabIndex = closeTab(tabs, activeTabIndex, clickedTabIndex);
                                                model = tabs.get(activeTabIndex).model();
                                                state = tabs.get(activeTabIndex).state();
                                                engine = tabs.get(activeTabIndex).engine();
                                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                                closeTabConfirmMode = false;
                                                pendingCloseTabIndex = -1;
                                                statusMessage = "Closed tab";
                                            }
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    if (tabHit.consumed() && !saveAsMode && !saveOverwriteConfirmMode
                                        && !quitConfirmMode && !closeTabConfirmMode && !uiSessionState.searchMode) {
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
                                                        closeTabConfirmMode = true;
                                                        pendingCloseTabIndex = clickedTabIndex;
                                                    } else {
                                                        activeTabIndex = closeTab(tabs, activeTabIndex, clickedTabIndex);
                                                        statusMessage = "Closed tab";
                                                    }
                                                } else {
                                                    activeTabIndex = closeTab(tabs, activeTabIndex, clickedTabIndex);
                                                    statusMessage = "Closed tab";
                                                }
                                            } else {
                                                activeTabIndex = clickedTabIndex;
                                            }
                                            model = tabs.get(activeTabIndex).model();
                                            state = tabs.get(activeTabIndex).state();
                                            engine = tabs.get(activeTabIndex).engine();
                                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                            topMenu.close();
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    MenuBar.PopupMouseResult menuMouse = topMenu.handlePopupClick(mouse.row(), mouse.col(), cols);
                                    if (menuMouse.consumed()) {
                                        Integer tabActivation = topMenu.consumeTabActivationRequest();
                                        if (tabActivation != null && tabActivation >= 0 && tabActivation < tabs.size()) {
                                            activeTabIndex = tabActivation;
                                            model = tabs.get(activeTabIndex).model();
                                            state = tabs.get(activeTabIndex).state();
                                            engine = tabs.get(activeTabIndex).engine();
                                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                            statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
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
                                                    statusMessage = "Search options updated";
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
                                    if (!saveAsMode && !saveOverwriteConfirmMode
                                        && !quitConfirmMode && !closeTabConfirmMode && !uiSessionState.searchMode) {
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
                                if (!saveAsMode && !saveOverwriteConfirmMode
                                    && !quitConfirmMode && !closeTabConfirmMode && !uiSessionState.searchMode) {
                                    if (mouse.button() == KeyEvent.MouseButton.LEFT
                                        && editorScrollDrag != EditorView.ScrollAxis.NONE) {
                                        editorView.handleScrollbarPointer(
                                            mouse.row(),
                                            mouse.col(),
                                            rows,
                                            cols,
                                            contentTopRow,
                                            plan,
                                            model,
                                            state,
                                            editorScrollDrag
                                        );
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
                                }
                            }
                            case RELEASE -> {
                                editorScrollDrag = EditorView.ScrollAxis.NONE;
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
                        int openFileActiveTabIndex = activeTabIndex;
                        OpenFileDialogController.HandleResult openFileResult = OpenFileDialogController.handleKey(
                            key,
                            openFileMode,
                            openFileFocus,
                            openFileTree,
                            openFileActiveTabIndex,
                            layout.listRows(),
                            openFileTree.visibleTextWidth(layout.left(), layout.right(), layout.listRows()),
                            selected -> {
                                OpenFileActionResult result = activateOpenFileSelection(selected, tabs, openFileActiveTabIndex);
                                return new OpenFileDialogController.ActivationResult(
                                    result.keepDialogOpen(),
                                    result.activeTabIndex(),
                                    result.statusMessage()
                                );
                            }
                        );
                        openFileMode = openFileResult.openFileMode();
                        openFileFocus = openFileResult.focus();
                        if (openFileResult.activeTabIndex() != activeTabIndex) {
                            activeTabIndex = openFileResult.activeTabIndex();
                            model = tabs.get(activeTabIndex).model();
                            state = tabs.get(activeTabIndex).state();
                            engine = tabs.get(activeTabIndex).engine();
                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                        }
                        if (openFileResult.statusMessage() != null && !openFileResult.statusMessage().isBlank()) {
                            statusMessage = openFileResult.statusMessage();
                        }
                        continue;
                    }

                    if (uiSessionState.helpHintsMode) {
                        if (key instanceof KeyEvent.CharInput) {
                            continue;
                        }
                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case ESCAPE, ENTER, SHOW_KEY_HINTS -> uiSessionState.helpHintsMode = false;
                            default -> {
                                // Ignore other keys in help modal.
                            }
                        }
                        continue;
                    }

                    if (saveAsMode) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            notification.clear();
                            saveAsInput.insert(charInput.ch());
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case BACKSPACE -> {
                                notification.clear();
                                saveAsInput.backspace();
                            }
                            case ENTER -> {
                                String target = saveAsInput.text().trim();
                                if (target.isEmpty()) {
                                    notification.clear();
                                } else {
                                    Path path = Path.of(target);
                                    String validation = validateSaveTarget(path);
                                    if (validation != null) {
                                        notification.error(validation);
                                        continue;
                                    }
                                    if (isReadOnlyFile(path)) {
                                        saveAsInput.set(suggestWritableName(path));
                                        notification.error("Save failed: target is read-only: " + path + ". Choose another name.");
                                        continue;
                                    }
                                    if (Files.exists(path) && !isSameFilePath(path, state.getCurrentFile())) {
                                        pendingOverwritePath = path;
                                        saveOverwriteConfirmMode = true;
                                        saveAsMode = false;
                                        notification.clear();
                                        continue;
                                    }
                                    try {
                                        engine.saveAs(path);
                                        renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(state.getCurrentFile()));
                                        tabs.set(activeTabIndex, tabs.get(activeTabIndex).withRenderPlanBuilder(renderPlanBuilder));
                                        notification.success(savedMessage(state));
                                    } catch (IOException e) {
                                        notification.error(saveErrorMessage(e));
                                    }
                                }
                                saveAsMode = false;
                                saveAsInput.clear();
                            }
                            case ESCAPE, CTRL_Q -> {
                                saveAsMode = false;
                                saveAsInput.clear();
                                notification.clear();
                            }
                            case ARROW_LEFT -> saveAsInput.moveLeft();
                            case ARROW_RIGHT -> saveAsInput.moveRight();
                            case HOME -> saveAsInput.moveHome();
                            case END -> saveAsInput.moveEnd();
                            case DELETE -> saveAsInput.deleteForward();
                            default -> {
                                // Ignore unsupported keys while Save As prompt is active.
                            }
                        }
                        continue;
                    }

                    if (saveOverwriteConfirmMode) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            char ch = charInput.ch();
                            if (ch == 'y' || ch == 'Y') {
                                try {
                                    engine.saveAs(pendingOverwritePath);
                                    renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(state.getCurrentFile()));
                                    tabs.set(activeTabIndex, tabs.get(activeTabIndex).withRenderPlanBuilder(renderPlanBuilder));
                                    notification.success(savedMessage(state));
                                } catch (IOException e) {
                                    notification.error(saveErrorMessage(e));
                                }
                                saveOverwriteConfirmMode = false;
                                pendingOverwritePath = null;
                            } else if (ch == 'n' || ch == 'N') {
                                saveOverwriteConfirmMode = false;
                                pendingOverwritePath = null;
                                saveAsMode = true;
                                notification.clear();
                            }
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case ESCAPE, CTRL_Q, ENTER -> {
                                saveOverwriteConfirmMode = false;
                                pendingOverwritePath = null;
                                saveAsMode = true;
                                notification.clear();
                            }
                            default -> {
                                // Ignore unsupported keys in overwrite confirmation.
                            }
                        }
                        continue;
                    }

                    if (quitConfirmMode) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            char ch = charInput.ch();
                            if (ch == 'y' || ch == 'Y') {
                                running = false;
                            } else if (ch == 'n' || ch == 'N') {
                                quitConfirmMode = false;
                            }
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case CTRL_Q -> running = false;
                            case ESCAPE, ENTER -> quitConfirmMode = false;
                            default -> {
                                // Ignore unsupported keys in quit confirmation.
                            }
                        }
                        continue;
                    }

                    if (closeTabConfirmMode) {
                        if (pendingCloseTabIndex < 0 || pendingCloseTabIndex >= tabs.size()) {
                            closeTabConfirmMode = false;
                            pendingCloseTabIndex = -1;
                            continue;
                        }
                        if (key instanceof KeyEvent.CharInput charInput) {
                            char ch = charInput.ch();
                            if (ch == 'y' || ch == 'Y') {
                                activeTabIndex = closeTab(tabs, activeTabIndex, pendingCloseTabIndex);
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                closeTabConfirmMode = false;
                                pendingCloseTabIndex = -1;
                                statusMessage = "Closed tab";
                            } else if (ch == 'n' || ch == 'N') {
                                closeTabConfirmMode = false;
                                pendingCloseTabIndex = -1;
                            }
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case TAB_CLOSE -> {
                                activeTabIndex = closeTab(tabs, activeTabIndex, pendingCloseTabIndex);
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                closeTabConfirmMode = false;
                                pendingCloseTabIndex = -1;
                                statusMessage = "Closed tab";
                            }
                            case ESCAPE, ENTER -> {
                                closeTabConfirmMode = false;
                                pendingCloseTabIndex = -1;
                            }
                            default -> {
                                // Ignore unsupported keys in close-tab confirmation.
                            }
                        }
                        continue;
                    }

                    if (topMenu.isOpen()) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            KeyEvent.Action mapped = topMenu.handleChar(charInput.ch());
                            Integer tabActivation = topMenu.consumeTabActivationRequest();
                            if (tabActivation != null && tabActivation >= 0 && tabActivation < tabs.size()) {
                                activeTabIndex = tabActivation;
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
                            }
                            if (mapped != null) {
                                key = new KeyEvent.ActionEvent(mapped);
                            }
                            if (topMenu.isOpen()) {
                                continue;
                            }
                        } else {
                            KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                            KeyEvent.Action mapped = topMenu.handleKey(action);
                            Integer tabActivation = topMenu.consumeTabActivationRequest();
                            if (tabActivation != null && tabActivation >= 0 && tabActivation < tabs.size()) {
                                activeTabIndex = tabActivation;
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
                            }
                            if (mapped != null) {
                                key = new KeyEvent.ActionEvent(mapped);
                            }
                            if (topMenu.isOpen()) {
                                continue;
                            }
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
                            quitConfirmMode,
                            closeTabConfirmMode,
                            pendingCloseTabIndex,
                            saveAsMode,
                            saveAsInput,
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
                            activeTabIndex = searchModeResult.activeTabIndex();
                            model = searchModeResult.model();
                            state = searchModeResult.state();
                            engine = searchModeResult.engine();
                            renderPlanBuilder = searchModeResult.renderPlanBuilder();
                            running = searchModeResult.running();
                            quitConfirmMode = searchModeResult.quitConfirmMode();
                            closeTabConfirmMode = searchModeResult.closeTabConfirmMode();
                            pendingCloseTabIndex = searchModeResult.pendingCloseTabIndex();
                            saveAsMode = searchModeResult.saveAsMode();
                            openFileMode = searchModeResult.openFileMode();
                            openFileFocus = searchModeResult.openFileFocus();
                            if (searchModeResult.statusMessage() != null) {
                                statusMessage = searchModeResult.statusMessage();
                            }
                            continue;
                        }
                    }

                    if (key instanceof KeyEvent.CharInput charInput) {
                        if (uiSessionState.readingModeSetting) {
                            statusMessage = "Reading mode: editing is disabled";
                        } else {
                            engine.insertChar(charInput.ch());
                            statusMessage = "";
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
                            contentRows,
                            SEARCH_HELP
                        )
                    );
                    if (dispatchResult.handled()) {
                        if (dispatchResult.statusMessage() != null) {
                            statusMessage = dispatchResult.statusMessage();
                        }
                        continue;
                    }
                    switch (action) {
                        case CTRL_Q -> {
                            if (uiSessionState.confirmOnCloseSetting && hasDirtyTabs(tabs)) {
                                quitConfirmMode = true;
                            } else {
                                running = false;
                            }
                        }
                        case CTRL_S -> {
                            if (state.getCurrentFile() == null) {
                                saveAsMode = true;
                                saveAsInput.set(DEFAULT_FILE_NAME);
                                notification.clear();
                            } else if (isReadOnlyFile(state.getCurrentFile())) {
                                saveAsMode = true;
                                saveAsInput.set(suggestWritableName(state.getCurrentFile()));
                                notification.error("Save failed: current file is read-only: "
                                    + state.getCurrentFile() + ". Save As mode.");
                            } else {
                                try {
                                    engine.save();
                                    notification.success(savedMessage(state));
                                } catch (IOException e) {
                                    notification.error(saveErrorMessage(e));
                                }
                            }
                        }
                        case CTRL_O -> {
                            topMenu.close();
                            openFileMode = true;
                            openFileTree.setRoot(initialOpenRoot(state));
                            openFileFocus = OpenFileDialogController.Focus.LIST;
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
                            activeTabIndex = tabs.size() - 1;
                            model = tabs.get(activeTabIndex).model();
                            state = tabs.get(activeTabIndex).state();
                            engine = tabs.get(activeTabIndex).engine();
                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                            statusMessage = "New tab";
                        }
                        case TAB_NEXT -> {
                            activeTabIndex = switchTab(tabs, activeTabIndex, 1);
                            model = tabs.get(activeTabIndex).model();
                            state = tabs.get(activeTabIndex).state();
                            engine = tabs.get(activeTabIndex).engine();
                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                            statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
                        }
                        case TAB_PREVIOUS -> {
                            activeTabIndex = switchTab(tabs, activeTabIndex, -1);
                            model = tabs.get(activeTabIndex).model();
                            state = tabs.get(activeTabIndex).state();
                            engine = tabs.get(activeTabIndex).engine();
                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                            statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
                        }
                        case TAB_CLOSE -> {
                            if (state.isDirty() && uiSessionState.confirmOnCloseSetting) {
                                closeTabConfirmMode = true;
                                pendingCloseTabIndex = activeTabIndex;
                            } else {
                                activeTabIndex = closeCurrentTab(tabs, activeTabIndex);
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                statusMessage = "Closed tab";
                            }
                        }
                        case CTRL_K, CTRL_W, CTRL_SHIFT_S -> {
                            saveAsMode = true;
                            saveAsInput.clear();
                            if (state.getCurrentFile() != null) {
                                saveAsInput.set(state.getCurrentFile().toString());
                            } else {
                                saveAsInput.set(DEFAULT_FILE_NAME);
                            }
                            notification.clear();
                        }
                        case CTRL_E, CTRL_T, OPEN_VIEW_MENU, OPEN_HELP_MENU, F10, F2,
                            CTRL_C, CTRL_X, CTRL_V, CTRL_Z, CTRL_Y,
                            TOGGLE_TAB_BAR, TOGGLE_WRAP_LINES, TOGGLE_WORD_WRAP,
                            TOGGLE_READING_MODE, TOGGLE_CONFIRM_ON_CLOSE, TOGGLE_MOUSE_MODE, SHOW_KEY_HINTS,
                            CTRL_F, CTRL_A, CTRL_R, TAB, CTRL_G, ESCAPE,
                            ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
                            SHIFT_ARROW_UP, SHIFT_ARROW_DOWN, SHIFT_ARROW_LEFT, SHIFT_ARROW_RIGHT,
                            PAGE_UP, PAGE_DOWN, HOME, END, BACKSPACE, DELETE, ENTER,
                            UNKNOWN -> {
                            // Already handled by InputActionDispatcher.
                        }
                        default -> {
                            // Ignore unrecognized input.
                        }
                    }
                }
            } finally {
                cleanup.run();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down.
                }
            }
        }
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
                    statusMessage = "Search options updated";
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
                statusMessage,
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
                    nextEngine.moveHome();
                }
            }
            case END -> {
                if (uiSessionState.searchFocus == SearchFocus.FIND) {
                    searchQueryInput.moveEnd();
                } else if (uiSessionState.searchFocus == SearchFocus.REPLACE) {
                    searchReplaceInput.moveEnd();
                } else {
                    nextEngine.moveEnd();
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
                        notification.warning("Replace all: " + replaced);
                        statusMessage = replaced > 0
                            ? "Replaced " + replaced + " occurrence(s)"
                            : "Nothing to replace";
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
                if (!SearchController.isSearchFieldFocus(uiSessionState.searchFocus)) {
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
                if (!SearchController.isSearchFieldFocus(uiSessionState.searchFocus)) {
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
                if (!SearchController.isSearchFieldFocus(uiSessionState.searchFocus)) {
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
                if (!SearchController.isSearchFieldFocus(uiSessionState.searchFocus)) {
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
                    notification.warning("Replace all: " + replaced);
                    statusMessage = replaced > 0
                        ? "Replaced " + replaced + " occurrence(s)"
                        : "Nothing to replace";
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
                    saveAsInput.set(DEFAULT_FILE_NAME);
                    notification.clear();
                } else if (isReadOnlyFile(nextState.getCurrentFile())) {
                    uiSessionState.searchMode = false;
                    uiSessionState.searchFocus = SearchFocus.FIND;
                    nextSaveAsMode = true;
                    saveAsInput.set(suggestWritableName(nextState.getCurrentFile()));
                    notification.error("Save failed: current file is read-only: "
                        + nextState.getCurrentFile() + ". Save As mode.");
                } else {
                    try {
                        nextEngine.save();
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
                statusMessage = "Tab " + (nextActiveTabIndex + 1) + "/" + tabs.size();
            }
            case TAB_PREVIOUS -> {
                nextActiveTabIndex = switchTab(tabs, nextActiveTabIndex, -1);
                nextModel = tabs.get(nextActiveTabIndex).model();
                nextState = tabs.get(nextActiveTabIndex).state();
                nextEngine = tabs.get(nextActiveTabIndex).engine();
                nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                uiSessionState.searchMode = false;
                statusMessage = "Tab " + (nextActiveTabIndex + 1) + "/" + tabs.size();
            }
            case TAB_CLOSE -> {
                if (nextState.isDirty() && uiSessionState.confirmOnCloseSetting) {
                    nextCloseTabConfirmMode = true;
                    nextPendingCloseTabIndex = nextActiveTabIndex;
                } else {
                    nextActiveTabIndex = closeCurrentTab(tabs, nextActiveTabIndex);
                    nextModel = tabs.get(nextActiveTabIndex).model();
                    nextState = tabs.get(nextActiveTabIndex).state();
                    nextEngine = tabs.get(nextActiveTabIndex).engine();
                    nextRenderPlanBuilder = tabs.get(nextActiveTabIndex).renderPlanBuilder();
                    uiSessionState.searchMode = false;
                    statusMessage = "Closed tab";
                }
            }
            case CTRL_K, CTRL_W, CTRL_SHIFT_S -> {
                uiSessionState.searchMode = false;
                uiSessionState.searchFocus = SearchFocus.FIND;
                nextSaveAsMode = true;
                saveAsInput.clear();
                if (nextState.getCurrentFile() != null) {
                    saveAsInput.set(nextState.getCurrentFile().toString());
                } else {
                    saveAsInput.set(DEFAULT_FILE_NAME);
                }
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
                        contentRows,
                        SEARCH_HELP
                    )
                );
                if (dispatchResult.handled()) {
                    statusMessage = dispatchResult.statusMessage();
                }
            }
        }

        return new SearchModeResult(
            true,
            statusMessage,
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
        String statusMessage,
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

        String pathLine = " " + tree.root();
        drawMenuLine(
            buffer,
            layout.top() + 1,
            layout.left() + 1,
            padRight(pathLine, Math.max(0, layout.width() - 2)),
            styles.dialog(),
            cols
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

    private HelpDialogLayout helpDialogLayout(int rows, int cols) {
        int preferredWidth = 68;
        int preferredHeight = HELP_HINT_ROWS.length + 3;
        int width = Math.max(24, Math.min(preferredWidth, cols - 4));
        int height = Math.max(8, Math.min(preferredHeight, rows - 4));
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        return new HelpDialogLayout(top, left, width, height);
    }

    private Modal helpDialogFrame(HelpDialogLayout layout) {
        return new Modal(
            layout.top(),
            layout.left(),
            layout.width(),
            layout.height(),
            " Keyboard Hints ",
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

    private void drawHelpOverlay(CellBuffer buffer, int rows, int cols, boolean visible, boolean colorEnabled) {
        if (!visible || rows < 8 || cols < 24) {
            return;
        }
        HelpDialogLayout layout = helpDialogLayout(rows, cols);
        Modal frame = helpDialogFrame(layout);
        frame.draw(buffer, rows, cols);
        int textRow = layout.top() + 1;
        int textCol = layout.left() + 2;
        int textWidth = Math.max(0, layout.width() - 4);
        int maxRows = Math.max(0, layout.height() - 2);
        for (int i = 0; i < HELP_HINT_ROWS.length && i < maxRows; i++) {
            drawMenuLine(
                buffer,
                textRow + i,
                textCol,
                clipText(HELP_HINT_ROWS[i], textWidth),
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

    private String clipText(String text, int width) {
        if (text == null || width <= 0) {
            return "";
        }
        return text.length() <= width ? text : text.substring(0, width);
    }

    private OpenFileActionResult activateOpenFileSelection(
        TreeView.Item entry,
        List<EditorTab> tabs,
        int activeTabIndex
    ) {
        if (entry == null) {
            return new OpenFileActionResult(true, activeTabIndex, "Select a file");
        }
        if (entry.parentLink() || entry.directory()) {
            return new OpenFileActionResult(true, activeTabIndex, "Select a file");
        }
        Path target = entry.path();
        try {
            EditorTab tab = newEditorTab();
            tab.engine().open(target);
            tab = tab.withRenderPlanBuilder(new RenderPlanBuilder(resolveHighlighter(target)));
            tabs.add(tab);
            int newIndex = tabs.size() - 1;
            return new OpenFileActionResult(false, newIndex, "Opened " + target);
        } catch (IOException e) {
            return new OpenFileActionResult(true, activeTabIndex, "Open failed: " + e.getMessage());
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
            return left.substring(0, cols);
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
        return ro + " | ENC:" + enc + " | " + (readingMode ? "READ" : "INS");
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
        String interactiveBarText,
        Style interactiveBarStyle,
        NotificationBar notificationBar,
        boolean wrapLinesSetting,
        boolean wrapWordsSetting,
        boolean saveAsMode,
        TextField saveAsInput,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath,
        boolean quitConfirmMode,
        List<EditorTab> tabs,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        SearchBarView searchBar,
        boolean openFileMode,
        TreeView openFileTree,
        OpenFileDialogController.Focus openFileFocus,
        boolean helpHintsMode,
        CellBuffer previous,
        RenderPlan previousPlan,
        int previousCursorRow,
        int previousCursorCol,
        boolean colorEnabled,
        PrintWriter writer,
        RenderRasterizer renderer,
        EditorView editorView,
        TabBar tabBar,
        MenuBar topMenu
    ) {
        if (!needsRender) {
            return new RenderFrameResult(previous, previousPlan, previousCursorRow, previousCursorCol, false);
        }
        RenderPlan plan = renderPlanBuilder.build(
            model,
            state,
            planRows,
            cols,
            topBar,
            bottomBar,
            searchMode ? searchQuery : null,
            SearchController.toSearchOptions(searchUi),
            interactiveBarText,
            interactiveBarStyle,
            notificationBar.text(),
            notificationBar.style(),
            wrapLinesSetting,
            wrapWordsSetting
        );
        CellBuffer current = renderer.rasterize(plan, rows, cols, contentTopRow);
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
        drawHelpOverlay(current, rows, cols, helpHintsMode, colorEnabled);

        EditorView.CursorPosition editorCursor = editorView.cursorFromPlan(plan, contentTopRow);
        int desiredCursorRow = editorCursor.row();
        int desiredCursorCol = editorCursor.col();
        int interactiveBottomCursorRow = Math.max(1, rows - 1);
        int interactiveTopCursorRow = interactiveBottomCursorRow - inlinePromptBarHeight + 1;
        if (saveAsMode) {
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = Math.min(cols, SAVE_AS_PROMPT.length() + saveAsInput.cursor() + 1);
        } else if (saveOverwriteConfirmMode) {
            String prompt = buildOverwritePrompt(pendingOverwritePath);
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = Math.min(cols, prompt.length() + 1);
        } else if (quitConfirmMode) {
            String prompt = buildQuitPrompt(countDirtyTabs(tabs));
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = Math.min(cols, prompt.length() + 1);
        } else if (closeTabConfirmMode) {
            String prompt = buildCloseTabPrompt(tabs, pendingCloseTabIndex);
            desiredCursorRow = interactiveBottomCursorRow;
            desiredCursorCol = Math.min(cols, prompt.length() + 1);
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
        } else if (helpHintsMode) {
            HelpDialogLayout layout = helpDialogLayout(rows, cols);
            Button close = helpCloseButton(layout);
            desiredCursorRow = Math.min(rows, close.row() + 1);
            desiredCursorCol = Math.min(cols, close.cursorCol() + 1);
        }

        String frameDiff = AnsiDiff.diff(previous, current, colorEnabled);
        boolean cursorMoved = desiredCursorRow != previousCursorRow || desiredCursorCol != previousCursorCol;
        int nextCursorRow = previousCursorRow;
        int nextCursorCol = previousCursorCol;
        if (!frameDiff.isEmpty() || cursorMoved) {
            writer.print(frameDiff);
            writer.print("\u001B[" + desiredCursorRow + ";" + desiredCursorCol + "H");
            writer.flush();
            nextCursorRow = desiredCursorRow;
            nextCursorCol = desiredCursorCol;
        }
        return new RenderFrameResult(current, plan, nextCursorRow, nextCursorCol, true);
    }

    private final class RenderFrameController {
        private final PrintWriter writer;
        private final RenderRasterizer renderer;
        private final EditorView editorView;
        private final TabBar tabBar;
        private final MenuBar topMenu;
        private final boolean colorEnabled;

        private RenderFrameController(
            PrintWriter writer,
            RenderRasterizer renderer,
            EditorView editorView,
            TabBar tabBar,
            MenuBar topMenu,
            boolean colorEnabled
        ) {
            this.writer = writer;
            this.renderer = renderer;
            this.editorView = editorView;
            this.tabBar = tabBar;
            this.topMenu = topMenu;
            this.colorEnabled = colorEnabled;
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
            String interactiveBarText,
            Style interactiveBarStyle,
            NotificationBar notificationBar,
            boolean wrapLinesSetting,
            boolean wrapWordsSetting,
            boolean saveAsMode,
            TextField saveAsInput,
            boolean saveOverwriteConfirmMode,
            Path pendingOverwritePath,
            boolean quitConfirmMode,
            List<EditorTab> tabs,
            boolean closeTabConfirmMode,
            int pendingCloseTabIndex,
            SearchBarView searchBar,
            boolean openFileMode,
            TreeView openFileTree,
            OpenFileDialogController.Focus openFileFocus,
            boolean helpHintsMode,
            CellBuffer previous,
            RenderPlan previousPlan,
            int previousCursorRow,
            int previousCursorCol
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
                interactiveBarText,
                interactiveBarStyle,
                notificationBar,
                wrapLinesSetting,
                wrapWordsSetting,
                saveAsMode,
                saveAsInput,
                saveOverwriteConfirmMode,
                pendingOverwritePath,
                quitConfirmMode,
                tabs,
                closeTabConfirmMode,
                pendingCloseTabIndex,
                searchBar,
                openFileMode,
                openFileTree,
                openFileFocus,
                helpHintsMode,
                previous,
                previousPlan,
                previousCursorRow,
                previousCursorCol,
                colorEnabled,
                writer,
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
        return "Overwrite: " + path + " ? (y/N)";
    }

    private String buildQuitPrompt(int dirtyTabs) {
        return "Unsaved tabs: " + dirtyTabs + ". Quit anyway? (y/N)";
    }

    private String buildCloseTabPrompt(List<EditorTab> tabs, int closeTabIndex) {
        if (closeTabIndex < 0 || closeTabIndex >= tabs.size()) {
            return "Close modified tab? (y/N)";
        }
        Path path = tabs.get(closeTabIndex).state().getCurrentFile();
        String name = path == null ? "--no name--" : path.toString();
        return "Close modified tab: " + name + " ? (y/N)";
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
        String csToggle = formatToggle("Аа", searchUi.caseSensitive());
        String wToggle = formatToggle("word", searchUi.matchMode() == SearchOptions.MatchMode.WHOLE_WORD);
        String starToggle = formatToggle("*", searchUi.matchMode() == SearchOptions.MatchMode.WILDCARD);
        String allToggle = formatToggle("All", searchUi.allTabs());
        String optionsCluster = csToggle + " " + wToggle + " " + starToggle + " " + allToggle;
        String row1 = alignLeftRight(findField, optionsCluster, cols, 1);
        String nextButton = "[Next]";
        String prevButton = "[Prev]";
        String replaceButton = "[Replace]";
        String allButton = "[All]";
        String actionsCluster = nextButton + " " + prevButton + " " + replaceButton + " " + allButton;
        String row2 = alignLeftRight(replaceField, actionsCluster, cols, 1);

        int optionsStart = rightClusterStart(row1, optionsCluster);
        int actionsStart = rightClusterStart(row2, actionsCluster);
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
            allActionEnd
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
        int allActionEnd
    ) {
        private String text() {
            return row1 + "\n" + row2;
        }
    }

    private static String formatField(String label, String value) {
        return label + ": " + value;
    }

    private static String formatToggle(String label, boolean checked) {
        return "[" + label + ":" + (checked ? "x" : " ") + "]";
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
        if (focus == SearchFocus.FIND) {
            return new SearchCursor(1, "Find: ".length() + Math.max(0, Math.min(queryCursor, query.length())));
        }
        if (focus == SearchFocus.REPLACE) {
            return new SearchCursor(2, "Replace: ".length() + Math.max(0, Math.min(replacementCursor, replacement.length())));
        }
        if (focus == SearchFocus.NEXT_BUTTON) {
            return new SearchCursor(2, nextStart + 1);
        }
        if (focus == SearchFocus.PREV_BUTTON) {
            return new SearchCursor(2, prevStart + 1);
        }
        if (focus == SearchFocus.REPLACE_BUTTON) {
            return new SearchCursor(2, replaceActionStart + 1);
        }
        if (focus == SearchFocus.ALL_BUTTON) {
            return new SearchCursor(2, allActionStart + 1);
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
        int allCheckOffset = allToggle.indexOf(':') + 1;
        return new SearchCursor(1, allStart + allCheckOffset);
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

    private record OpenFileActionResult(
        boolean keepDialogOpen,
        int activeTabIndex,
        String statusMessage
    ) {
    }

    private record RenderFrameResult(
        CellBuffer previous,
        RenderPlan plan,
        int lastCursorRow,
        int lastCursorCol,
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

    private int closeCurrentTab(List<EditorTab> tabs, int activeTabIndex) {
        int size = tabs.size();
        if (tabs.size() <= 1) {
            tabs.set(0, newEditorTab());
            return 0;
        }
        tabs.remove(activeTabIndex);
        return TabSessions.closeIndex(size, activeTabIndex, activeTabIndex);
    }

    private int closeTab(List<EditorTab> tabs, int activeTabIndex, int closeTabIndex) {
        int size = tabs.size();
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
        int[] match = TabSearch.findMatchTabs(toTabSessions(tabs), query, options);
        for (int i = 0; i < flags.length && i < match.length; i++) {
            flags[i] = match[i] != 0;
        }
        return flags;
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

    enum SearchFocus {
        FIND,
        REPLACE,
        CASE,
        WORD,
        WILDCARD,
        ALL,
        NEXT_BUTTON,
        PREV_BUTTON,
        REPLACE_BUTTON,
        ALL_BUTTON
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
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
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
            if (message != null && System.nanoTime() >= expiresAtNanos) {
                clear();
                return true;
            }
            return false;
        }

        private long nanosUntilExpire(long nowNanos) {
            if (message == null) {
                return -1L;
            }
            return Math.max(0L, expiresAtNanos - nowNanos);
        }
    }

    private LineHighlighter resolveHighlighter(Path file) {
        return file == null ? RegexHighlighter.withDefaults() : RegexHighlighter.forFileName(file.getFileName().toString());
    }

    private void applyTheme(UiStyles.Theme theme) {
        this.theme = theme == null ? UiStyles.Theme.CLASSIC : theme;
        this.styles = UiStyles.forTheme(this.theme);
    }

    private CliOptions parseArgs(String[] args) {
        boolean mouseEnabled = true;
        boolean altScreenEnabled = true;
        boolean colorEnabled = true;
        boolean readMode = false;
        boolean showHelp = false;
        UiStyles.Theme theme = UiStyles.Theme.CLASSIC;
        Path file = null;

        for (String arg : args) {
            switch (arg) {
                case "--no-mouse" -> mouseEnabled = false;
                case "--no-alt-screen" -> altScreenEnabled = false;
                case "--no-color" -> colorEnabled = false;
                case "--read" -> readMode = true;
                case "--help", "-h" -> showHelp = true;
                default -> {
                    if (arg.startsWith("--theme=")) {
                        theme = UiStyles.Theme.parse(arg.substring("--theme=".length()));
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
        return new CliOptions(file, mouseEnabled, altScreenEnabled, colorEnabled, readMode, showHelp, theme);
    }

    private void printUsage() {
        System.out.println("Usage: :tui:run --args=\"[options] [file]\"");
        System.out.println("Options:");
        System.out.println("  --no-mouse       Disable mouse tracking and mouse interactions");
        System.out.println("  --no-alt-screen  Do not use alternate screen buffer");
        System.out.println("  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        System.out.println("  --theme=<name>   UI palette: classic (default), light");
        System.out.println("  --read           Start in reading mode (editing disabled)");
        System.out.println("  --help, -h       Show this help");
    }

    private record CliOptions(
        Path file,
        boolean mouseEnabled,
        boolean altScreenEnabled,
        boolean colorEnabled,
        boolean readMode,
        boolean showHelp,
        UiStyles.Theme theme
    ) {
    }
}

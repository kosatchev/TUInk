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
import dev.tuink.render.Color;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.ui.Button;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.MenuBar;
import dev.tuink.tui.ui.Modal;
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
    private static final String KEY_HINTS = "Ctrl+O Open  Ctrl+N New Tab  Ctrl+T Tab Menu  Ctrl+F Search  Ctrl+Q";
    private static final String SEARCH_HELP = "Tab focus  Space toggle  Enter next  Ctrl+G prev  Ctrl+R replace  Ctrl+A all  Esc";
    private static final String SAVE_AS_PROMPT = "Save As: ";
    private static final Style SAVE_PROMPT_STYLE = new Style(Color.BRIGHT_WHITE, Color.RED, false, false);
    private static final Style TOPBAR_FILENAME_STYLE = new Style(Color.BRIGHT_WHITE, Color.BLUE, true, false);
    private static final Style MENU_POPUP_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style MENU_SELECTED_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false);
    private static final Style MENU_SELECTED_MONO_STYLE = new Style(Color.DEFAULT, Color.DEFAULT, false, false);
    private static final Style TABBAR_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style TABBAR_MONO_STYLE = new Style(Color.DEFAULT, Color.BRIGHT_WHITE, false, false);
    private static final Style TABBAR_ACTIVE_STYLE = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, false, false);
    private static final Style TABBAR_ACTIVE_MONO_STYLE = new Style(Color.DEFAULT, Color.DEFAULT, false, false);
    private static final Style FILE_DIALOG_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style FILE_DIALOG_BORDER_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, true, false);
    private static final Style FILE_DIALOG_TITLE_STYLE = new Style(Color.BRIGHT_WHITE, Color.BLUE, true, false);
    private static final Style FILE_DIALOG_SELECTED_MONO_STYLE = new Style(Color.DEFAULT, Color.DEFAULT, false, false);
    private static final Style FILE_DIALOG_SCROLL_TRACK_STYLE = new Style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style FILE_DIALOG_SCROLL_THUMB_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false);
    private static final Style NOTIFY_SUCCESS_STYLE = new Style(Color.BLACK, Color.BRIGHT_GREEN, false, false);
    private static final Style NOTIFY_ERROR_STYLE = new Style(Color.BRIGHT_WHITE, Color.RED, false, false);
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    private static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final String ENABLE_MOUSE_TRACKING = "\u001B[?1000h\u001B[?1002h\u001B[?1006h";
    private static final String DISABLE_MOUSE_TRACKING = "\u001B[?1000l\u001B[?1002l\u001B[?1006l";

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

        List<EditorTab> tabs = new ArrayList<>();
        tabs.add(newEditorTab());
        int activeTabIndex = 0;
        TextModel model = tabs.get(activeTabIndex).model();
        EditorState state = tabs.get(activeTabIndex).state();
        EditorEngine engine = tabs.get(activeTabIndex).engine();
        RenderPlanBuilder renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
        RenderRasterizer renderer = new RenderRasterizer();
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
        boolean searchMode = false;
        SearchFocus searchFocus = SearchFocus.FIND;
        TextField searchQueryInput = new TextField();
        TextField searchReplaceInput = new TextField();
        SearchUiOptions searchUi = SearchUiOptions.DEFAULT;
        MenuBar topMenu = new MenuBar(MENU_POPUP_STYLE, MENU_SELECTED_STYLE, MENU_SELECTED_MONO_STYLE);
        TabBar tabBar = new TabBar(TABBAR_STYLE, TABBAR_MONO_STYLE, TABBAR_ACTIVE_STYLE, TABBAR_ACTIVE_MONO_STYLE);
        boolean showTabBarSetting = true;
        boolean wrapLinesSetting = false;
        boolean wrapWordsSetting = true;
        boolean confirmOnCloseSetting = true;
        boolean mouseModeSetting = cli.mouseEnabled();
        boolean openFileMode = false;
        TreeView openFileTree = new TreeView(initialOpenRoot(state));
        OpenDialogFocus openFileFocus = OpenDialogFocus.LIST;
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
                if (mouseModeSetting) {
                    init.append(ENABLE_MOUSE_TRACKING);
                }
                writer.print(init);
                writer.flush();

                KeyDecoder decoder = new KeyDecoder(input);
                boolean running = true;
                while (running && !stopRequested.get()) {
                    notification.expireIfNeeded();
                    int rows = Math.max(2, terminal.getHeight());
                    int cols = Math.max(1, terminal.getWidth());
                    boolean hasInteractiveBar = searchMode || saveAsMode || saveOverwriteConfirmMode
                        || quitConfirmMode || closeTabConfirmMode;
                    boolean hasNotificationBar = notification.visible();
                    boolean showTabBar = showTabBarSetting && tabs.size() > 1;
                    int overlayRows = (hasInteractiveBar ? 1 : 0) + (hasNotificationBar ? 1 : 0);
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
                        needHorizontalScrollbar = !wrapLinesSetting && contentCols > 0
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
                        toSearchOptions(searchUi)
                    );
                    List<TabBar.TabItem> tabItems = toTabItems(tabs, tabMatchFlags);
                    topMenu.setOpenTabs(toMenuTabs(tabs, tabMatchFlags), activeTabIndex);
                    topMenu.setShowTabBarEnabled(showTabBarSetting);
                    topMenu.setWrapLinesEnabled(wrapLinesSetting);
                    topMenu.setWrapWordsEnabled(wrapWordsSetting);
                    topMenu.setConfirmOnCloseEnabled(confirmOnCloseSetting);
                    topMenu.setMouseModeEnabled(mouseModeSetting);

                    String topBar = topMenu.buildTopBar(
                        state.getCurrentFile(),
                        state.isDirty(),
                        activeTabIndex,
                        tabs.size(),
                        cols
                    );
                    String bottomBar = buildBottomBar(state, model, statusMessage, cols);
                    String interactiveBarText = null;
                    Style interactiveBarStyle = null;
                    SearchBarView searchBar = searchMode
                        ? buildSearchBar(
                            searchQueryInput,
                            searchReplaceInput,
                            searchFocus,
                            cols,
                            searchUi,
                            countSearchMatches(
                                searchUi.allTabs() ? null : model,
                                searchUi.allTabs() ? tabs : null,
                                searchQueryInput.text(),
                                toSearchOptions(searchUi)
                            )
                        )
                        : null;
                    if (saveAsMode) {
                        interactiveBarText = buildSaveAsPrompt(saveAsInput);
                        interactiveBarStyle = SAVE_PROMPT_STYLE;
                    } else if (saveOverwriteConfirmMode) {
                        interactiveBarText = buildOverwritePrompt(pendingOverwritePath);
                        interactiveBarStyle = SAVE_PROMPT_STYLE;
                    } else if (quitConfirmMode) {
                        interactiveBarText = buildQuitPrompt(countDirtyTabs(tabs));
                        interactiveBarStyle = SAVE_PROMPT_STYLE;
                    } else if (closeTabConfirmMode) {
                        interactiveBarText = buildCloseTabPrompt(tabs, pendingCloseTabIndex);
                        interactiveBarStyle = SAVE_PROMPT_STYLE;
                    } else if (searchBar != null) {
                        interactiveBarText = searchBar.text();
                    }
                    int planRows = Math.max(1, (showTabBar ? rows - 1 : rows) - (needHorizontalScrollbar ? 1 : 0));
                    RenderPlan plan = renderPlanBuilder.build(
                        model,
                        state,
                        planRows,
                        cols,
                        topBar,
                        bottomBar,
                        searchMode ? searchQueryInput.text() : null,
                        toSearchOptions(searchUi),
                        interactiveBarText,
                        interactiveBarStyle,
                        notification.message,
                        notification.style,
                        wrapLinesSetting,
                        wrapWordsSetting
                    );
                    CellBuffer current = renderer.rasterize(plan, rows, cols, contentTopRow);
                    if (showTabBar) {
                        tabBar.draw(current, cols, tabItems, activeTabIndex, cli.colorEnabled());
                    }
                    topMenu.draw(current, cols, cli.colorEnabled());
                    drawTopFileNameBold(current, cols, state, topMenu.fileNameStartCol());
                    drawOpenFileOverlay(
                        current,
                        rows,
                        cols,
                        openFileMode,
                        openFileTree,
                        openFileFocus,
                        cli.colorEnabled()
                    );

                    EditorView.CursorPosition editorCursor = editorView.cursorFromPlan(plan, contentTopRow);
                    int desiredCursorRow = editorCursor.row();
                    int desiredCursorCol = editorCursor.col();
                    int interactiveCursorRow = Math.max(1, rows - 1);
                    if (saveAsMode) {
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = Math.min(cols, SAVE_AS_PROMPT.length() + saveAsInput.cursor() + 1);
                    } else if (saveOverwriteConfirmMode) {
                        String prompt = buildOverwritePrompt(pendingOverwritePath);
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = Math.min(cols, prompt.length() + 1);
                    } else if (quitConfirmMode) {
                        String prompt = buildQuitPrompt(countDirtyTabs(tabs));
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = Math.min(cols, prompt.length() + 1);
                    } else if (closeTabConfirmMode) {
                        String prompt = buildCloseTabPrompt(tabs, pendingCloseTabIndex);
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = Math.min(cols, prompt.length() + 1);
                    } else if (searchBar != null) {
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = searchBar.cursorCol();
                    } else if (topMenu.isOpen()) {
                        desiredCursorRow = Math.min(rows, 3 + topMenu.selectedIndex());
                        desiredCursorCol = Math.min(cols, topMenu.popupCursorCol());
                    } else if (openFileMode) {
                        OpenDialogLayout layout = openDialogLayout(rows, cols);
                        if (openFileFocus == OpenDialogFocus.LIST) {
                            int visibleIndex = openFileTree.visibleIndex(layout.listRows());
                            desiredCursorRow = layout.listStartRow() + visibleIndex + 1;
                            desiredCursorCol = layout.left() + 3;
                        } else {
                            OpenDialogButtons buttons = openDialogButtons(layout);
                            desiredCursorRow = layout.bottom() + 1;
                            desiredCursorCol = openFileFocus == OpenDialogFocus.OK
                                ? buttons.ok().cursorCol()
                                : buttons.cancel().cursorCol();
                        }
                    }

                    String frameDiff = AnsiDiff.diff(previous, current, cli.colorEnabled());
                    boolean cursorMoved = desiredCursorRow != lastCursorRow || desiredCursorCol != lastCursorCol;
                    if (!frameDiff.isEmpty() || cursorMoved) {
                        writer.print(frameDiff);
                        writer.print("\u001B[" + desiredCursorRow + ";" + desiredCursorCol + "H");
                        writer.flush();
                        lastCursorRow = desiredCursorRow;
                        lastCursorCol = desiredCursorCol;
                    }
                    previous = current;

                    KeyEvent key = decoder.readKey(100);
                    if (key == null) {
                        if (resized.getAndSet(false)) {
                            previous = null;
                        }
                        continue;
                    }

                    if (key instanceof KeyEvent.MouseEvent mouse) {
                        if (!mouseModeSetting) {
                            continue;
                        }
                        KeyEvent remappedMouseKey = null;
                        switch (mouse.kind()) {
                            case SCROLL_UP -> {
                                if (openFileMode) {
                                    int listRows = openDialogLayout(rows, cols).listRows();
                                    openFileTree.scrollUp(listRows);
                                    break;
                                }
                                if (showTabBar && mouse.row() <= 1 && !topMenu.isOpen()) {
                                    tabBar.scrollLeft();
                                    break;
                                }
                                if (!saveAsMode && !saveOverwriteConfirmMode
                                    && !quitConfirmMode && !closeTabConfirmMode && !searchMode) {
                                    engine.pageUp(Math.max(1, contentRows / 2));
                                }
                            }
                            case SCROLL_DOWN -> {
                                if (openFileMode) {
                                    int listRows = openDialogLayout(rows, cols).listRows();
                                    openFileTree.scrollDown(listRows);
                                    break;
                                }
                                if (showTabBar && mouse.row() <= 1 && !topMenu.isOpen()) {
                                    tabBar.scrollRight(tabItems);
                                    break;
                                }
                                if (!saveAsMode && !saveOverwriteConfirmMode
                                    && !quitConfirmMode && !closeTabConfirmMode && !searchMode) {
                                    engine.pageDown(Math.max(1, contentRows / 2));
                                }
                            }
                            case PRESS -> {
                                if (mouse.button() == KeyEvent.MouseButton.LEFT) {
                                    editorScrollDrag = EditorView.ScrollAxis.NONE;
                                    openFileScrollDrag = TreeView.ScrollAxis.NONE;
                                    if (openFileMode) {
                                        OpenDialogLayout layout = openDialogLayout(rows, cols);
                                        Modal frame = openFileFrame(layout);
                                        OpenDialogButtons buttons = openDialogButtons(layout);
                                        if (frame.closeButton().hit(mouse.row(), mouse.col())) {
                                            openFileMode = false;
                                            openFileTree.resetClickTracking();
                                            continue;
                                        }
                                        TreeView.ScrollPointerResult scrollPointer = openFileTree.handleScrollbarPointer(
                                            mouse.row(),
                                            mouse.col(),
                                            layout.left(),
                                            layout.right(),
                                            layout.listStartRow(),
                                            layout.listRows()
                                        );
                                        if (scrollPointer.consumed()) {
                                            openFileScrollDrag = scrollPointer.drag()
                                                ? scrollPointer.axis()
                                                : TreeView.ScrollAxis.NONE;
                                            openFileFocus = OpenDialogFocus.LIST;
                                            continue;
                                        }
                                        TreeView.ClickResult treeClick = openFileTree.clickList(
                                            mouse.row(),
                                            mouse.col(),
                                            layout.left(),
                                            layout.right(),
                                            layout.listStartRow(),
                                            layout.listRows(),
                                            System.nanoTime(),
                                            OPEN_DIALOG_DOUBLE_CLICK_NANOS
                                        );
                                        if (treeClick.consumed()) {
                                            openFileFocus = OpenDialogFocus.LIST;
                                            if (treeClick.arrowClick()) {
                                                openFileTree.toggleSelectedAndRefresh();
                                                openFileTree.resetClickTracking();
                                            } else if (treeClick.doubleClick()) {
                                                TreeView.Item selected = treeClick.item();
                                                if (selected != null && (selected.parentLink() || selected.directory())) {
                                                    openFileTree.toggleSelectedAndRefresh();
                                                } else {
                                                    OpenFileActionResult result = activateOpenFileSelection(
                                                        selected,
                                                        tabs,
                                                        activeTabIndex
                                                    );
                                                    openFileMode = result.keepDialogOpen();
                                                    activeTabIndex = result.activeTabIndex();
                                                    model = tabs.get(activeTabIndex).model();
                                                    state = tabs.get(activeTabIndex).state();
                                                    engine = tabs.get(activeTabIndex).engine();
                                                    renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                                    if (result.statusMessage() != null && !result.statusMessage().isBlank()) {
                                                        statusMessage = result.statusMessage();
                                                    }
                                                    openFileTree.resetClickTracking();
                                                }
                                            }
                                        } else if (buttons.ok().hit(mouse.row(), mouse.col())) {
                                            openFileFocus = OpenDialogFocus.OK;
                                            OpenFileActionResult result = activateOpenFileSelection(
                                                openFileTree.selectedItem(),
                                                tabs,
                                                activeTabIndex
                                            );
                                            openFileMode = result.keepDialogOpen();
                                            activeTabIndex = result.activeTabIndex();
                                            model = tabs.get(activeTabIndex).model();
                                            state = tabs.get(activeTabIndex).state();
                                            engine = tabs.get(activeTabIndex).engine();
                                            renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                            if (result.statusMessage() != null && !result.statusMessage().isBlank()) {
                                                statusMessage = result.statusMessage();
                                            }
                                        } else if (buttons.cancel().hit(mouse.row(), mouse.col())) {
                                            openFileFocus = OpenDialogFocus.CANCEL;
                                            openFileMode = false;
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
                                        && !quitConfirmMode && !closeTabConfirmMode && !searchMode) {
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
                                                    if (confirmOnCloseSetting) {
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
                                    if (searchMode && searchBar != null) {
                                        int searchBarRow = interactiveCursorRow - 1;
                                        if (mouse.row() == searchBarRow) {
                                            SearchFocus clickedFocus = searchFocusAtMouse(searchBar, mouse.col());
                                            if (clickedFocus != null) {
                                                searchFocus = clickedFocus;
                                                if (isToggleFocus(clickedFocus)) {
                                                    SearchUiOptions before = searchUi;
                                                    searchUi = toggleFocusedSearchOption(searchUi, clickedFocus);
                                                    if (!searchUi.equals(before)) {
                                                        statusMessage = "Search options updated";
                                                    }
                                                }
                                            }
                                        }
                                        editorView.cancelMouseSelection();
                                        continue;
                                    }
                                    if (!saveAsMode && !saveOverwriteConfirmMode
                                        && !quitConfirmMode && !closeTabConfirmMode && !searchMode) {
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
                                    OpenDialogLayout layout = openDialogLayout(rows, cols);
                                    openFileTree.dragScrollbarPointer(
                                        mouse.row(),
                                        mouse.col(),
                                        layout.left(),
                                        layout.right(),
                                        layout.listStartRow(),
                                        layout.listRows(),
                                        openFileScrollDrag
                                    );
                                    continue;
                                }
                                if (!saveAsMode && !saveOverwriteConfirmMode
                                    && !quitConfirmMode && !closeTabConfirmMode && !searchMode) {
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
                        if (key instanceof KeyEvent.CharInput) {
                            continue;
                        }
                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case ESCAPE -> openFileMode = false;
                            case TAB -> openFileFocus = nextOpenDialogFocus(openFileFocus);
                            case ARROW_UP -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.moveUp(openDialogLayout(rows, cols).listRows());
                                }
                            }
                            case ARROW_DOWN -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.moveDown(openDialogLayout(rows, cols).listRows());
                                }
                            }
                            case PAGE_UP -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.pageUp(openDialogLayout(rows, cols).listRows());
                                }
                            }
                            case PAGE_DOWN -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.pageDown(openDialogLayout(rows, cols).listRows());
                                }
                            }
                            case HOME -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.moveHome(openDialogLayout(rows, cols).listRows());
                                }
                            }
                            case END -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.moveEnd(openDialogLayout(rows, cols).listRows());
                                }
                            }
                            case ENTER -> {
                                if (openFileFocus == OpenDialogFocus.CANCEL) {
                                    openFileMode = false;
                                } else if (openFileFocus == OpenDialogFocus.OK) {
                                    OpenFileActionResult result = activateOpenFileSelection(
                                        openFileTree.selectedItem(),
                                        tabs,
                                        activeTabIndex
                                    );
                                    openFileMode = result.keepDialogOpen();
                                    activeTabIndex = result.activeTabIndex();
                                    model = tabs.get(activeTabIndex).model();
                                    state = tabs.get(activeTabIndex).state();
                                    engine = tabs.get(activeTabIndex).engine();
                                    renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                    if (result.statusMessage() != null && !result.statusMessage().isBlank()) {
                                        statusMessage = result.statusMessage();
                                    }
                                } else {
                                    openFileTree.toggleSelectedAndRefresh();
                                }
                            }
                            case ARROW_RIGHT -> {
                                if (openFileFocus == OpenDialogFocus.OK) {
                                    openFileFocus = OpenDialogFocus.CANCEL;
                                } else if (openFileFocus == OpenDialogFocus.LIST) {
                                    TreeView.Item selected = openFileTree.selectedItem();
                                    if (selected != null && (selected.parentLink() || (selected.directory() && !selected.expanded()))) {
                                        openFileTree.toggleSelectedAndRefresh();
                                    }
                                }
                            }
                            case SHIFT_ARROW_RIGHT -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    OpenDialogLayout layout = openDialogLayout(rows, cols);
                                    openFileTree.scrollRight(
                                        openFileTree.visibleTextWidth(layout.left(), layout.right(), layout.listRows())
                                    );
                                }
                            }
                            case ARROW_LEFT -> {
                                if (openFileFocus == OpenDialogFocus.CANCEL) {
                                    openFileFocus = OpenDialogFocus.OK;
                                } else if (openFileFocus == OpenDialogFocus.OK) {
                                    openFileFocus = OpenDialogFocus.LIST;
                                } else {
                                    TreeView.Item selected = openFileTree.selectedItem();
                                    if (selected != null && selected.directory() && selected.expanded()) {
                                        openFileTree.toggleSelectedAndRefresh();
                                    } else {
                                        openFileTree.moveToParentDepth(openDialogLayout(rows, cols).listRows());
                                    }
                                }
                            }
                            case SHIFT_ARROW_LEFT -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.scrollLeft();
                                }
                            }
                            case BACKSPACE -> {
                                if (openFileFocus == OpenDialogFocus.LIST) {
                                    openFileTree.moveToParentDirectory();
                                }
                            }
                            default -> {
                                // Ignore other keys while open dialog is active.
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

                    if (searchMode) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            char ch = charInput.ch();
                            if (ch == ' ' && isToggleFocus(searchFocus)) {
                                SearchUiOptions before = searchUi;
                                searchUi = toggleFocusedSearchOption(searchUi, searchFocus);
                                if (!searchUi.equals(before)) {
                                    statusMessage = "Search options updated";
                                }
                            } else if (searchFocus == SearchFocus.REPLACE) {
                                searchReplaceInput.insert(ch);
                                statusMessage = "";
                            } else if (searchFocus == SearchFocus.FIND) {
                                searchQueryInput.insert(ch);
                                statusMessage = "";
                            }
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case ESCAPE -> {
                                searchMode = false;
                                searchFocus = SearchFocus.FIND;
                                statusMessage = "";
                            }
                            case TAB -> searchFocus = nextSearchFocus(searchFocus);
                            case BACKSPACE -> {
                                TextField active = searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                                active.backspace();
                            }
                            case DELETE -> {
                                TextField active = searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                                active.deleteForward();
                            }
                            case ARROW_LEFT -> {
                                if (searchFocus == SearchFocus.FIND) {
                                    searchQueryInput.moveLeft();
                                } else if (searchFocus == SearchFocus.REPLACE) {
                                    searchReplaceInput.moveLeft();
                                } else {
                                    engine.moveLeft();
                                }
                            }
                            case ARROW_RIGHT -> {
                                if (searchFocus == SearchFocus.FIND) {
                                    searchQueryInput.moveRight();
                                } else if (searchFocus == SearchFocus.REPLACE) {
                                    searchReplaceInput.moveRight();
                                } else {
                                    engine.moveRight();
                                }
                            }
                            case HOME -> {
                                if (searchFocus == SearchFocus.FIND) {
                                    searchQueryInput.moveHome();
                                } else if (searchFocus == SearchFocus.REPLACE) {
                                    searchReplaceInput.moveHome();
                                } else {
                                    engine.moveHome();
                                }
                            }
                            case END -> {
                                if (searchFocus == SearchFocus.FIND) {
                                    searchQueryInput.moveEnd();
                                } else if (searchFocus == SearchFocus.REPLACE) {
                                    searchReplaceInput.moveEnd();
                                } else {
                                    engine.moveEnd();
                                }
                            }
                            case ENTER, CTRL_F -> {
                                boolean found;
                                if (searchUi.allTabs()) {
                                    int foundTab = TabSearch.findNextAcrossTabs(
                                        toTabSessions(tabs),
                                        activeTabIndex,
                                        searchQueryInput.text(),
                                        toSearchOptions(searchUi)
                                    );
                                    found = foundTab >= 0;
                                    if (found) {
                                        activeTabIndex = foundTab;
                                        model = tabs.get(activeTabIndex).model();
                                        state = tabs.get(activeTabIndex).state();
                                        engine = tabs.get(activeTabIndex).engine();
                                        renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                    }
                                } else {
                                    found = engine.findNext(
                                        searchQueryInput.text(),
                                        toSearchOptions(searchUi)
                                    );
                                }
                                statusMessage = found ? "Found next match" : "Not found";
                            }
                            case CTRL_G -> {
                                boolean found;
                                if (searchUi.allTabs()) {
                                    int foundTab = TabSearch.findPreviousAcrossTabs(
                                        toTabSessions(tabs),
                                        activeTabIndex,
                                        searchQueryInput.text(),
                                        toSearchOptions(searchUi)
                                    );
                                    found = foundTab >= 0;
                                    if (found) {
                                        activeTabIndex = foundTab;
                                        model = tabs.get(activeTabIndex).model();
                                        state = tabs.get(activeTabIndex).state();
                                        engine = tabs.get(activeTabIndex).engine();
                                        renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                    }
                                } else {
                                    found = engine.findPrevious(
                                        searchQueryInput.text(),
                                        toSearchOptions(searchUi)
                                    );
                                }
                                statusMessage = found ? "Found previous match" : "Not found";
                            }
                            case CTRL_R -> {
                                boolean replaced = engine.replaceCurrentOrNext(
                                    searchQueryInput.text(),
                                    searchReplaceInput.text(),
                                    toSearchOptions(searchUi)
                                );
                                statusMessage = replaced ? "Replaced match" : "Nothing to replace";
                            }
                            case CTRL_A -> {
                                int replaced = engine.replaceAll(
                                    searchQueryInput.text(),
                                    searchReplaceInput.text(),
                                    toSearchOptions(searchUi)
                                );
                                statusMessage = replaced > 0
                                    ? "Replaced " + replaced + " occurrence(s)"
                                    : "Nothing to replace";
                            }
                            case CTRL_Q -> {
                                if (confirmOnCloseSetting && hasDirtyTabs(tabs)) {
                                    searchMode = false;
                                    searchFocus = SearchFocus.FIND;
                                    quitConfirmMode = true;
                                } else {
                                    running = false;
                                }
                            }
                            case CTRL_S -> {
                                if (state.getCurrentFile() == null) {
                                    searchMode = false;
                                    searchFocus = SearchFocus.FIND;
                                    saveAsMode = true;
                                    saveAsInput.set(DEFAULT_FILE_NAME);
                                    notification.clear();
                                } else if (isReadOnlyFile(state.getCurrentFile())) {
                                    searchMode = false;
                                    searchFocus = SearchFocus.FIND;
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
                            case CTRL_C -> statusMessage = engine.copySelection() ? "Copied selection" : "Nothing selected";
                            case CTRL_X -> statusMessage = engine.cutSelection() ? "Cut selection" : "Nothing selected";
                            case CTRL_V -> statusMessage = engine.pasteClipboard() ? "Pasted" : "Clipboard empty";
                            case CTRL_Z -> statusMessage = engine.undo() ? "Undo" : "Nothing to undo";
                            case CTRL_Y -> statusMessage = engine.redo() ? "Redo" : "Nothing to redo";
                            case CTRL_O -> {
                                searchMode = false;
                                searchFocus = SearchFocus.FIND;
                                topMenu.close();
                                openFileMode = true;
                                openFileTree.setRoot(initialOpenRoot(state));
                                openFileFocus = OpenDialogFocus.LIST;
                                statusMessage = "Open file";
                            }
                            case TOGGLE_TAB_BAR -> {
                                showTabBarSetting = !showTabBarSetting;
                                statusMessage = showTabBarSetting ? "Tab bar shown" : "Tab bar hidden";
                            }
                            case TOGGLE_WRAP_LINES -> {
                                wrapLinesSetting = !wrapLinesSetting;
                                if (wrapLinesSetting) {
                                    state.setLeftColumn(0);
                                }
                                statusMessage = wrapLinesSetting ? "Wrap lines enabled" : "Wrap lines disabled";
                            }
                            case TOGGLE_WORD_WRAP -> {
                                wrapWordsSetting = !wrapWordsSetting;
                                statusMessage = wrapWordsSetting ? "Word wrap enabled" : "Word wrap disabled";
                            }
                            case TOGGLE_CONFIRM_ON_CLOSE -> {
                                confirmOnCloseSetting = !confirmOnCloseSetting;
                                statusMessage = confirmOnCloseSetting
                                    ? "Confirm on close enabled"
                                    : "Confirm on close disabled";
                            }
                            case TOGGLE_MOUSE_MODE -> {
                                mouseModeSetting = !mouseModeSetting;
                                writer.print(mouseModeSetting ? ENABLE_MOUSE_TRACKING : DISABLE_MOUSE_TRACKING);
                                writer.flush();
                                statusMessage = mouseModeSetting ? "Mouse mode enabled" : "Mouse mode disabled";
                            }
                            case CTRL_N, TAB_NEW -> {
                                tabs.add(newEditorTab());
                                activeTabIndex = tabs.size() - 1;
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                searchMode = false;
                                statusMessage = "New tab";
                            }
                            case TAB_NEXT -> {
                                activeTabIndex = switchTab(tabs, activeTabIndex, 1);
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                searchMode = false;
                                statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
                            }
                            case TAB_PREVIOUS -> {
                                activeTabIndex = switchTab(tabs, activeTabIndex, -1);
                                model = tabs.get(activeTabIndex).model();
                                state = tabs.get(activeTabIndex).state();
                                engine = tabs.get(activeTabIndex).engine();
                                renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                searchMode = false;
                                statusMessage = "Tab " + (activeTabIndex + 1) + "/" + tabs.size();
                            }
                            case TAB_CLOSE -> {
                                if (state.isDirty() && confirmOnCloseSetting) {
                                    closeTabConfirmMode = true;
                                    pendingCloseTabIndex = activeTabIndex;
                                } else {
                                    activeTabIndex = closeCurrentTab(tabs, activeTabIndex);
                                    model = tabs.get(activeTabIndex).model();
                                    state = tabs.get(activeTabIndex).state();
                                    engine = tabs.get(activeTabIndex).engine();
                                    renderPlanBuilder = tabs.get(activeTabIndex).renderPlanBuilder();
                                    searchMode = false;
                                    statusMessage = "Closed tab";
                                }
                            }
                            case CTRL_K, CTRL_W, CTRL_SHIFT_S -> {
                                searchMode = false;
                                searchFocus = SearchFocus.FIND;
                                saveAsMode = true;
                                saveAsInput.clear();
                                if (state.getCurrentFile() != null) {
                                    saveAsInput.set(state.getCurrentFile().toString());
                                } else {
                                    saveAsInput.set(DEFAULT_FILE_NAME);
                                }
                                notification.clear();
                            }
                            case ARROW_UP -> engine.moveUp();
                            case ARROW_DOWN -> engine.moveDown();
                            case SHIFT_ARROW_UP -> engine.moveUp(true);
                            case SHIFT_ARROW_DOWN -> engine.moveDown(true);
                            case SHIFT_ARROW_LEFT -> engine.moveLeft(true);
                            case SHIFT_ARROW_RIGHT -> engine.moveRight(true);
                            case PAGE_UP -> engine.pageUp(contentRows);
                            case PAGE_DOWN -> engine.pageDown(contentRows);
                            default -> {
                                // Ignore unsupported keys in search mode.
                            }
                        }
                        continue;
                    }

                    if (key instanceof KeyEvent.CharInput charInput) {
                        engine.insertChar(charInput.ch());
                        statusMessage = "";
                        continue;
                    }

                    KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                    switch (action) {
                        case CTRL_E -> topMenu.openEdit();
                        case CTRL_T -> topMenu.openTab();
                        case OPEN_VIEW_MENU -> topMenu.openView();
                        case F10 -> topMenu.openFile();
                        case F2 -> topMenu.openSettings();
                        case CTRL_Q -> {
                            if (confirmOnCloseSetting && hasDirtyTabs(tabs)) {
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
                        case CTRL_C -> statusMessage = engine.copySelection() ? "Copied selection" : "Nothing selected";
                        case CTRL_X -> statusMessage = engine.cutSelection() ? "Cut selection" : "Nothing selected";
                        case CTRL_V -> statusMessage = engine.pasteClipboard() ? "Pasted" : "Clipboard empty";
                        case CTRL_Z -> statusMessage = engine.undo() ? "Undo" : "Nothing to undo";
                        case CTRL_Y -> statusMessage = engine.redo() ? "Redo" : "Nothing to redo";
                        case CTRL_O -> {
                            topMenu.close();
                            openFileMode = true;
                            openFileTree.setRoot(initialOpenRoot(state));
                            openFileFocus = OpenDialogFocus.LIST;
                            statusMessage = "Open file";
                        }
                        case TOGGLE_TAB_BAR -> {
                            showTabBarSetting = !showTabBarSetting;
                            statusMessage = showTabBarSetting ? "Tab bar shown" : "Tab bar hidden";
                        }
                        case TOGGLE_WRAP_LINES -> {
                            wrapLinesSetting = !wrapLinesSetting;
                            if (wrapLinesSetting) {
                                state.setLeftColumn(0);
                            }
                            statusMessage = wrapLinesSetting ? "Wrap lines enabled" : "Wrap lines disabled";
                        }
                        case TOGGLE_WORD_WRAP -> {
                            wrapWordsSetting = !wrapWordsSetting;
                            statusMessage = wrapWordsSetting ? "Word wrap enabled" : "Word wrap disabled";
                        }
                        case TOGGLE_CONFIRM_ON_CLOSE -> {
                            confirmOnCloseSetting = !confirmOnCloseSetting;
                            statusMessage = confirmOnCloseSetting
                                ? "Confirm on close enabled"
                                : "Confirm on close disabled";
                        }
                        case TOGGLE_MOUSE_MODE -> {
                            mouseModeSetting = !mouseModeSetting;
                            writer.print(mouseModeSetting ? ENABLE_MOUSE_TRACKING : DISABLE_MOUSE_TRACKING);
                            writer.flush();
                            statusMessage = mouseModeSetting ? "Mouse mode enabled" : "Mouse mode disabled";
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
                            if (state.isDirty() && confirmOnCloseSetting) {
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
                        case CTRL_F -> {
                            searchMode = true;
                            searchFocus = SearchFocus.FIND;
                            statusMessage = SEARCH_HELP;
                        }
                        case CTRL_A, CTRL_R -> {
                            // Reserved for search mode. No-op in normal mode.
                        }
                        case TAB -> {
                            engine.insertChar('\t');
                            statusMessage = "";
                        }
                        case CTRL_G -> statusMessage = "Goto line not implemented";
                        case ESCAPE -> {
                            // No-op outside prompt mode.
                        }
                        case ARROW_UP -> engine.moveUp();
                        case ARROW_DOWN -> engine.moveDown();
                        case ARROW_LEFT -> engine.moveLeft();
                        case ARROW_RIGHT -> engine.moveRight();
                        case SHIFT_ARROW_UP -> engine.moveUp(true);
                        case SHIFT_ARROW_DOWN -> engine.moveDown(true);
                        case SHIFT_ARROW_LEFT -> engine.moveLeft(true);
                        case SHIFT_ARROW_RIGHT -> engine.moveRight(true);
                        case PAGE_UP -> engine.pageUp(contentRows);
                        case PAGE_DOWN -> engine.pageDown(contentRows);
                        case HOME -> engine.moveHome();
                        case END -> engine.moveEnd();
                        case BACKSPACE -> engine.backspace();
                        case DELETE -> engine.deleteForward();
                        case ENTER -> engine.insertNewline();
                        case UNKNOWN -> {
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

    private void drawTopFileNameBold(CellBuffer buffer, int cols, EditorState state, int startCol) {
        if (buffer.rows() < 1 || cols <= startCol || startCol < 0) {
            return;
        }
        String file = state.getCurrentFile() == null ? "--no name--" : state.getCurrentFile().toString();
        int endCol = Math.min(cols, startCol + file.length());
        for (int col = startCol; col < endCol; col++) {
            String grapheme = buffer.get(0, col).grapheme();
            buffer.set(0, col, grapheme, TOPBAR_FILENAME_STYLE);
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
        OpenDialogFocus focus,
        boolean colorEnabled
    ) {
        if (!visible || rows < 8 || cols < 24) {
            return;
        }
        OpenDialogLayout layout = openDialogLayout(rows, cols);
        Modal frame = openFileFrame(layout);
        frame.draw(buffer, rows, cols);

        String pathLine = " " + tree.root();
        drawMenuLine(
            buffer,
            layout.top() + 1,
            layout.left() + 1,
            padRight(pathLine, Math.max(0, layout.width() - 2)),
            FILE_DIALOG_STYLE,
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
            FILE_DIALOG_STYLE,
            MENU_SELECTED_STYLE,
            FILE_DIALOG_SELECTED_MONO_STYLE,
            FILE_DIALOG_SCROLL_TRACK_STYLE,
            FILE_DIALOG_SCROLL_THUMB_STYLE
        );

        OpenDialogButtons buttons = openDialogButtons(layout);
        buttons.ok().draw(
            buffer,
            cols,
            focus == OpenDialogFocus.OK
                ? (colorEnabled ? MENU_SELECTED_STYLE : FILE_DIALOG_SELECTED_MONO_STYLE)
                : FILE_DIALOG_STYLE
        );
        buttons.cancel().draw(
            buffer,
            cols,
            focus == OpenDialogFocus.CANCEL
                ? (colorEnabled ? MENU_SELECTED_STYLE : FILE_DIALOG_SELECTED_MONO_STYLE)
                : FILE_DIALOG_STYLE
        );
    }

    private OpenDialogLayout openDialogLayout(int rows, int cols) {
        int width = Math.max(24, Math.min(cols - 4, (int) Math.round(cols * 0.8)));
        int height = Math.max(8, Math.min(rows - 2, (int) Math.round(rows * 0.75)));
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        int listStartRow = top + 2;
        int listRows = Math.max(1, height - 3);
        return new OpenDialogLayout(top, left, width, height, listStartRow, listRows);
    }

    private OpenDialogButtons openDialogButtons(OpenDialogLayout layout) {
        String ok = "  OK  ";
        String cancel = " Cancel ";
        int total = (ok.length() + 2) + 2 + (cancel.length() + 2);
        int start = layout.left() + Math.max(1, (layout.width() - 2 - total) / 2);
        Button okButton = new Button(layout.bottom(), start, ok);
        Button cancelButton = new Button(layout.bottom(), start + okButton.width() + 2, cancel);
        return new OpenDialogButtons(okButton, cancelButton);
    }

    private Modal openFileFrame(OpenDialogLayout layout) {
        return new Modal(
            layout.top(),
            layout.left(),
            layout.width(),
            layout.height(),
            " Open File ",
            FILE_DIALOG_STYLE,
            FILE_DIALOG_BORDER_STYLE,
            FILE_DIALOG_TITLE_STYLE
        );
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

    private OpenDialogFocus nextOpenDialogFocus(OpenDialogFocus current) {
        return switch (current) {
            case LIST -> OpenDialogFocus.OK;
            case OK -> OpenDialogFocus.CANCEL;
            case CANCEL -> OpenDialogFocus.LIST;
        };
    }

    private String buildBottomBar(EditorState state, TextModel model, String message, int cols) {
        Position p = state.getCursor();
        int totalLines = model.lineCount();
        int lineCols = model.getLine(p.line()).length();
        String indicators = buildStateIndicators(state);
        String left = "Ln " + (p.line() + 1) + "/" + totalLines
            + ", Col " + (p.col() + 1) + "/" + lineCols
            + "  |  " + indicators;
        if (message != null && !message.isBlank()) {
            left = left + "  |  " + message;
        }

        if (left.length() >= cols) {
            return left.substring(0, cols);
        }

        int free = cols - left.length();
        if (free <= 1) {
            return left + " ".repeat(free);
        }

        String right = "";

        int remaining = free;
        if (remaining >= 3) {
            int hintSpace = remaining - 2;
            String hints = KEY_HINTS;
            if (hints.length() > hintSpace) {
                hints = hints.substring(0, hintSpace);
            }
            if (!hints.isEmpty()) {
                right = hints;
            }
        }

        if (right.length() > free) {
            right = right.substring(0, free);
        }
        return left + " ".repeat(free - right.length()) + right;
    }

    private String buildStateIndicators(EditorState state) {
        String ro = isReadOnlyFile(state.getCurrentFile()) ? "RO" : "RW";
        String enc = state.getFileEncoding() == null ? "UTF-8" : state.getFileEncoding().name();
        return ro + " ENC:" + enc + " INS";
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
        SearchUiOptions searchUi,
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
        String countToken = "[N:" + matchCount + "]";
        int findStart = 0;
        int findEnd = findStart + findField.length();
        int replaceStart = findEnd + 2;
        int replaceEnd = replaceStart + replaceField.length();
        int caseStart = replaceEnd + 2;
        int caseEnd = caseStart + csToggle.length();
        int wordStart = caseEnd + 1;
        int wordEnd = wordStart + wToggle.length();
        int wildcardStart = wordEnd + 1;
        int wildcardEnd = wildcardStart + starToggle.length();
        int allStart = wildcardEnd + 1;
        int allEnd = allStart + allToggle.length();
        String prompt = findField + "  " + replaceField
            + "  " + csToggle + " " + wToggle + " " + starToggle + " " + allToggle + " " + countToken
            + "  " + SEARCH_HELP;

        int cursorIndex = cursorForFocus(
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
            focus
        );

        if (prompt.length() <= cols) {
            return new SearchBarView(
                prompt + " ".repeat(cols - prompt.length()),
                Math.min(cols, cursorIndex + 1),
                0,
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
                allEnd
            );
        }

        int start = Math.max(0, cursorIndex - cols + 1);
        int end = Math.min(prompt.length(), start + cols);
        String clipped = prompt.substring(start, end);
        if (clipped.length() < cols) {
            clipped = clipped + " ".repeat(cols - clipped.length());
        }
        int cursorCol = Math.max(1, Math.min(cols, cursorIndex - start + 1));
        return new SearchBarView(
            clipped,
            cursorCol,
            start,
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
            allEnd
        );
    }

    private SearchFocus searchFocusAtMouse(SearchBarView searchBar, int mouseCol) {
        if (searchBar == null || mouseCol < 0) {
            return null;
        }
        int promptIndex = searchBar.promptStart() + mouseCol;
        if (promptIndex >= searchBar.findStart() && promptIndex < searchBar.findEnd()) {
            return SearchFocus.FIND;
        }
        if (promptIndex >= searchBar.replaceStart() && promptIndex < searchBar.replaceEnd()) {
            return SearchFocus.REPLACE;
        }
        if (promptIndex >= searchBar.caseStart() && promptIndex < searchBar.caseEnd()) {
            return SearchFocus.CASE;
        }
        if (promptIndex >= searchBar.wordStart() && promptIndex < searchBar.wordEnd()) {
            return SearchFocus.WORD;
        }
        if (promptIndex >= searchBar.wildcardStart() && promptIndex < searchBar.wildcardEnd()) {
            return SearchFocus.WILDCARD;
        }
        if (promptIndex >= searchBar.allStart() && promptIndex < searchBar.allEnd()) {
            return SearchFocus.ALL;
        }
        return null;
    }

    private record SearchBarView(
        String text,
        int cursorCol,
        int promptStart,
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
        int allEnd
    ) {
    }

    private static String formatField(String label, String value) {
        return label + ": " + value;
    }

    private static String formatToggle(String label, boolean checked) {
        return "[" + label + ":" + (checked ? "x" : " ") + "]";
    }

    private static int cursorForFocus(
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
        SearchFocus focus
    ) {
        if (focus == SearchFocus.FIND) {
            return "Find: ".length() + Math.max(0, Math.min(queryCursor, query.length()));
        }
        int replaceStart = findField.length() + 2;
        if (focus == SearchFocus.REPLACE) {
            return replaceStart + "Replace: ".length() + Math.max(0, Math.min(replacementCursor, replacement.length()));
        }
        int togglesStart = replaceStart + replaceField.length() + 2;
        int iCheckOffset = iToggle.indexOf(':') + 1;
        int wCheckOffset = wToggle.indexOf(':') + 1;
        int starCheckOffset = starToggle.indexOf(':') + 1;
        if (focus == SearchFocus.CASE) {
            return togglesStart + iCheckOffset;
        }
        if (focus == SearchFocus.WORD) {
            return togglesStart + iToggle.length() + 1 + wCheckOffset;
        }
        int starStart = togglesStart + iToggle.length() + 1 + wToggle.length() + 1;
        if (focus == SearchFocus.WILDCARD) {
            return starStart + starCheckOffset;
        }
        int allStart = starStart + starToggle.length() + 1;
        int allCheckOffset = allToggle.indexOf(':') + 1;
        return allStart + allCheckOffset;
    }

    private SearchUiOptions toggleFocusedSearchOption(SearchUiOptions options, SearchFocus focus) {
        return switch (focus) {
            case CASE -> new SearchUiOptions(!options.caseSensitive(), options.matchMode(), options.allTabs());
            case WORD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode() == SearchOptions.MatchMode.WHOLE_WORD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WHOLE_WORD,
                options.allTabs()
            );
            case WILDCARD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode() == SearchOptions.MatchMode.WILDCARD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WILDCARD,
                options.allTabs()
            );
            case ALL -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode(),
                !options.allTabs()
            );
            default -> options;
        };
    }

    private SearchOptions toSearchOptions(SearchUiOptions ui) {
        SearchUiOptions safe = ui == null ? SearchUiOptions.DEFAULT : ui;
        return new SearchOptions(!safe.caseSensitive(), safe.matchMode());
    }

    private boolean isToggleFocus(SearchFocus focus) {
        return focus == SearchFocus.CASE || focus == SearchFocus.WORD
            || focus == SearchFocus.WILDCARD || focus == SearchFocus.ALL;
    }

    private SearchFocus nextSearchFocus(SearchFocus focus) {
        return switch (focus) {
            case FIND -> SearchFocus.REPLACE;
            case REPLACE -> SearchFocus.CASE;
            case CASE -> SearchFocus.WORD;
            case WORD -> SearchFocus.WILDCARD;
            case WILDCARD -> SearchFocus.ALL;
            case ALL -> SearchFocus.FIND;
        };
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

    private record OpenDialogLayout(int top, int left, int width, int height, int listStartRow, int listRows) {
        private int right() {
            return left + width - 1;
        }

        private int bottom() {
            return top + height - 1;
        }
    }

    private record OpenDialogButtons(Button ok, Button cancel) {
    }

    private record OpenFileActionResult(
        boolean keepDialogOpen,
        int activeTabIndex,
        String statusMessage
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

    private enum OpenDialogFocus {
        LIST,
        OK,
        CANCEL
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

    private enum SearchFocus {
        FIND,
        REPLACE,
        CASE,
        WORD,
        WILDCARD,
        ALL
    }

    private static final class NotificationState {
        private String message;
        private Style style;
        private long expiresAtNanos;

        private boolean visible() {
            return message != null;
        }

        private void success(String message) {
            this.message = message;
            this.style = NOTIFY_SUCCESS_STYLE;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        private void error(String message) {
            this.message = message;
            this.style = NOTIFY_ERROR_STYLE;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        private void clear() {
            this.message = null;
            this.style = null;
            this.expiresAtNanos = 0L;
        }

        private void expireIfNeeded() {
            if (message != null && System.nanoTime() >= expiresAtNanos) {
                clear();
            }
        }
    }

    private record SearchUiOptions(boolean caseSensitive, SearchOptions.MatchMode matchMode, boolean allTabs) {
        private static final SearchUiOptions DEFAULT = new SearchUiOptions(false, SearchOptions.MatchMode.SUBSTRING, false);
    }

    private LineHighlighter resolveHighlighter(Path file) {
        return file == null ? RegexHighlighter.withDefaults() : RegexHighlighter.forFileName(file.getFileName().toString());
    }

    private CliOptions parseArgs(String[] args) {
        boolean mouseEnabled = true;
        boolean altScreenEnabled = true;
        boolean colorEnabled = true;
        boolean showHelp = false;
        Path file = null;

        for (String arg : args) {
            switch (arg) {
                case "--no-mouse" -> mouseEnabled = false;
                case "--no-alt-screen" -> altScreenEnabled = false;
                case "--no-color" -> colorEnabled = false;
                case "--help", "-h" -> showHelp = true;
                default -> {
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
        return new CliOptions(file, mouseEnabled, altScreenEnabled, colorEnabled, showHelp);
    }

    private void printUsage() {
        System.out.println("Usage: :tui:run --args=\"[options] [file]\"");
        System.out.println("Options:");
        System.out.println("  --no-mouse       Disable mouse tracking and mouse interactions");
        System.out.println("  --no-alt-screen  Do not use alternate screen buffer");
        System.out.println("  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        System.out.println("  --help, -h       Show this help");
    }

    private record CliOptions(
        Path file,
        boolean mouseEnabled,
        boolean altScreenEnabled,
        boolean colorEnabled,
        boolean showHelp
    ) {
    }
}

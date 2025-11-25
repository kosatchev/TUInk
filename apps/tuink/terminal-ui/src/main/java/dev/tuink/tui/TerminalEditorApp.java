package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.render.CellBuffer;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.Color;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.RenderRasterizer;
import dev.tuink.render.Style;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final String KEY_HINTS = "Ctrl+E Menu  Ctrl+F Search  Ctrl+C/X/V  Ctrl+Z/Y  Ctrl+Q";
    private static final String SEARCH_HELP = "Tab focus  Space toggle  Enter next  Ctrl+G prev  Ctrl+R replace  Ctrl+A all  Esc";
    private static final String SAVE_AS_PROMPT = "Save As: ";
    private static final Style SAVE_PROMPT_STYLE = new Style(Color.BRIGHT_WHITE, Color.RED, false, false);
    private static final Style MENU_POPUP_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style MENU_SELECTED_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false);
    private static final Style NOTIFY_SUCCESS_STYLE = new Style(Color.BLACK, Color.BRIGHT_GREEN, false, false);
    private static final Style NOTIFY_ERROR_STYLE = new Style(Color.BRIGHT_WHITE, Color.RED, false, false);
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    private static final String[] FILE_MENU_ITEMS = {"Open...", "Save", "Save As...", "Quit"};
    private static final String[] EDIT_MENU_ITEMS = {"Undo", "Redo", "Cut", "Copy", "Paste"};
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

        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state);
        RenderPlanBuilder renderPlanBuilder = new RenderPlanBuilder(RegexHighlighter.withDefaults());
        RenderRasterizer renderer = new RenderRasterizer();

        String statusMessage = "Ctrl+S save | Ctrl+Q quit";
        NotificationState notification = new NotificationState();
        boolean confirmQuit = false;
        boolean saveAsMode = false;
        boolean saveOverwriteConfirmMode = false;
        Path pendingOverwritePath = null;
        StringBuilder saveAsInput = new StringBuilder();
        boolean searchMode = false;
        SearchFocus searchFocus = SearchFocus.FIND;
        StringBuilder searchQueryInput = new StringBuilder();
        StringBuilder searchReplaceInput = new StringBuilder();
        SearchUiOptions searchUi = SearchUiOptions.DEFAULT;
        boolean mainMenuOpen = false;
        MainMenu activeMenu = MainMenu.EDIT;
        int mainMenuIndex = 0;
        boolean mouseSelecting = false;

        if (cli.file() != null) {
            Path file = cli.file();
            try {
                engine.open(file);
                renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(file));
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
                    if (cli.mouseEnabled()) {
                        out.append(DISABLE_MOUSE_TRACKING);
                    }
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
                if (cli.mouseEnabled()) {
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
                    boolean hasInteractiveBar = searchMode || saveAsMode || saveOverwriteConfirmMode;
                    boolean hasNotificationBar = notification.visible();
                    int overlayRows = (hasInteractiveBar ? 1 : 0) + (hasNotificationBar ? 1 : 0);
                    int editorRows = Math.max(2, rows - overlayRows);
                    int contentRows = Math.max(0, editorRows - 2);
                    engine.ensureCursorVisible(contentRows);

                    String topBar = buildTopBar(state, cols, mainMenuOpen, activeMenu);
                    String bottomBar = buildBottomBar(state, model, statusMessage, cols);
                    String interactiveBarText = null;
                    Style interactiveBarStyle = null;
                    SearchBarView searchBar = searchMode
                        ? buildSearchBar(
                            searchQueryInput.toString(),
                            searchReplaceInput.toString(),
                            searchFocus,
                            cols,
                            searchUi,
                            countSearchMatches(
                                model,
                                searchQueryInput.toString(),
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
                    } else if (searchBar != null) {
                        interactiveBarText = searchBar.text();
                    }
                    RenderPlan plan = renderPlanBuilder.build(
                        model,
                        state,
                        rows,
                        cols,
                        topBar,
                        bottomBar,
                        searchMode ? searchQueryInput.toString() : null,
                        toSearchOptions(searchUi),
                        interactiveBarText,
                        interactiveBarStyle,
                        notification.message,
                        notification.style
                    );
                    CellBuffer current = renderer.rasterize(plan, rows, cols);
                    drawMainMenuOverlay(current, cols, mainMenuOpen, activeMenu, mainMenuIndex);

                    int desiredCursorRow = plan.cursorRow() + 2;
                    int desiredCursorCol = plan.cursorCol() + 1;
                    int interactiveCursorRow = Math.max(1, rows - 1);
                    if (saveAsMode) {
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = Math.min(cols, SAVE_AS_PROMPT.length() + saveAsInput.length() + 1);
                    } else if (saveOverwriteConfirmMode) {
                        String prompt = buildOverwritePrompt(pendingOverwritePath);
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = Math.min(cols, prompt.length() + 1);
                    } else if (searchBar != null) {
                        desiredCursorRow = interactiveCursorRow;
                        desiredCursorCol = searchBar.cursorCol();
                    } else if (mainMenuOpen) {
                        desiredCursorRow = Math.min(rows, 3 + mainMenuIndex);
                        desiredCursorCol = Math.min(cols, 3);
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

                    if (!(key instanceof KeyEvent.ActionEvent actionEvent
                        && actionEvent.action() == KeyEvent.Action.CTRL_Q)) {
                        confirmQuit = false;
                    }

                    if (key instanceof KeyEvent.MouseEvent mouse) {
                        if (!cli.mouseEnabled()) {
                            continue;
                        }
                        KeyEvent remappedMouseKey = null;
                        switch (mouse.kind()) {
                            case SCROLL_UP -> {
                                if (!saveAsMode && !saveOverwriteConfirmMode && !searchMode) {
                                    engine.pageUp(Math.max(1, contentRows / 2));
                                }
                            }
                            case SCROLL_DOWN -> {
                                if (!saveAsMode && !saveOverwriteConfirmMode && !searchMode) {
                                    engine.pageDown(Math.max(1, contentRows / 2));
                                }
                            }
                            case PRESS -> {
                                if (mouse.button() == KeyEvent.MouseButton.LEFT) {
                                    if (mouse.row() == 0) {
                                        if (mouse.col() >= 0 && mouse.col() < 6) {
                                            boolean sameMenuOpen = mainMenuOpen && activeMenu == MainMenu.FILE;
                                            activeMenu = MainMenu.FILE;
                                            mainMenuOpen = !sameMenuOpen;
                                            mainMenuIndex = 0;
                                            mouseSelecting = false;
                                            continue;
                                        }
                                        if (mouse.col() >= 7 && mouse.col() < 13) {
                                            boolean sameMenuOpen = mainMenuOpen && activeMenu == MainMenu.EDIT;
                                            activeMenu = MainMenu.EDIT;
                                            mainMenuOpen = !sameMenuOpen;
                                            mainMenuIndex = 0;
                                            mouseSelecting = false;
                                            continue;
                                        }
                                    }
                                    if (mainMenuOpen) {
                                        String[] items = menuItems(activeMenu);
                                        int popupStartCol = activeMenu == MainMenu.FILE ? 0 : 7;
                                        int popupWidth = menuPopupWidth(cols, activeMenu);
                                        if (mouse.col() >= popupStartCol && mouse.col() < popupStartCol + popupWidth
                                            && mouse.row() >= 2 && mouse.row() < 2 + items.length) {
                                            mainMenuIndex = mouse.row() - 2;
                                            KeyEvent.Action mapped = mapMenuSelection(activeMenu, mainMenuIndex);
                                            if (mapped == null) {
                                                statusMessage = activeMenu == MainMenu.FILE
                                                    ? "Open not implemented"
                                                    : "";
                                            } else {
                                                remappedMouseKey = new KeyEvent.ActionEvent(mapped);
                                            }
                                        }
                                        mainMenuOpen = false;
                                        mouseSelecting = false;
                                        break;
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
                                        mouseSelecting = false;
                                        continue;
                                    }
                                    if (!saveAsMode && !saveOverwriteConfirmMode && !searchMode) {
                                        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows);
                                        if (hit != null) {
                                            mouseSelecting = true;
                                            state.setSelectionAnchor(hit);
                                            state.setCursor(hit);
                                        } else {
                                            mouseSelecting = false;
                                        }
                                        engine.ensureCursorVisible(contentRows);
                                    }
                                }
                            }
                            case DRAG -> {
                                if (mouseSelecting && mouse.button() == KeyEvent.MouseButton.LEFT
                                    && !saveAsMode && !saveOverwriteConfirmMode && !searchMode) {
                                    Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows);
                                    if (hit != null) {
                                        if (state.getSelectionAnchor() == null) {
                                            state.setSelectionAnchor(state.getCursor());
                                        }
                                        state.setCursor(hit);
                                        engine.ensureCursorVisible(contentRows);
                                    }
                                }
                            }
                            case RELEASE -> {
                                if (mouseSelecting) {
                                    mouseSelecting = false;
                                    if (state.getSelectionAnchor() != null
                                        && state.getSelectionAnchor().equals(state.getCursor())) {
                                        state.clearSelection();
                                    }
                                }
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

                    if (saveAsMode) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            notification.clear();
                            saveAsInput.append(charInput.ch());
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case BACKSPACE -> {
                                notification.clear();
                                if (!saveAsInput.isEmpty()) {
                                    saveAsInput.deleteCharAt(saveAsInput.length() - 1);
                                }
                            }
                            case ENTER -> {
                                String target = saveAsInput.toString().trim();
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
                                        saveAsInput.setLength(0);
                                        saveAsInput.append(suggestWritableName(path));
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
                                        notification.success(savedMessage(state));
                                    } catch (IOException e) {
                                        notification.error(saveErrorMessage(e));
                                    }
                                }
                                saveAsMode = false;
                                saveAsInput.setLength(0);
                            }
                            case ESCAPE, CTRL_Q -> {
                                saveAsMode = false;
                                saveAsInput.setLength(0);
                                notification.clear();
                            }
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

                    if (mainMenuOpen) {
                        if (key instanceof KeyEvent.CharInput) {
                            continue;
                        }
                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case ESCAPE -> mainMenuOpen = false;
                            case CTRL_E -> {
                                activeMenu = MainMenu.EDIT;
                                mainMenuIndex = 0;
                            }
                            case F10 -> {
                                activeMenu = MainMenu.FILE;
                                mainMenuIndex = 0;
                            }
                            case ARROW_LEFT -> {
                                activeMenu = previousMenu(activeMenu);
                                mainMenuIndex = Math.min(mainMenuIndex, menuItems(activeMenu).length - 1);
                            }
                            case ARROW_RIGHT -> {
                                activeMenu = nextMenu(activeMenu);
                                mainMenuIndex = Math.min(mainMenuIndex, menuItems(activeMenu).length - 1);
                            }
                            case ARROW_UP -> {
                                String[] items = menuItems(activeMenu);
                                mainMenuIndex = (mainMenuIndex + items.length - 1) % items.length;
                            }
                            case ARROW_DOWN -> {
                                String[] items = menuItems(activeMenu);
                                mainMenuIndex = (mainMenuIndex + 1) % items.length;
                            }
                            case ENTER -> {
                                KeyEvent.Action mapped = mapMenuSelection(activeMenu, mainMenuIndex);
                                mainMenuOpen = false;
                                if (mapped == null) {
                                    statusMessage = activeMenu == MainMenu.FILE ? "Open not implemented" : "";
                                    continue;
                                }
                                key = new KeyEvent.ActionEvent(mapped);
                            }
                            default -> {
                                // Keep focus in menu until explicit close/action.
                            }
                        }
                        if (mainMenuOpen) {
                            continue;
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
                                searchReplaceInput.append(ch);
                                statusMessage = "";
                            } else if (searchFocus == SearchFocus.FIND) {
                                searchQueryInput.append(ch);
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
                                StringBuilder active = searchFocus == SearchFocus.REPLACE ? searchReplaceInput : searchQueryInput;
                                if (!active.isEmpty()) {
                                    active.deleteCharAt(active.length() - 1);
                                }
                            }
                            case ENTER, CTRL_F -> {
                                boolean found = engine.findNext(
                                    searchQueryInput.toString(),
                                    toSearchOptions(searchUi)
                                );
                                statusMessage = found ? "Found next match" : "Not found";
                            }
                            case CTRL_G -> {
                                boolean found = engine.findPrevious(
                                    searchQueryInput.toString(),
                                    toSearchOptions(searchUi)
                                );
                                statusMessage = found ? "Found previous match" : "Not found";
                            }
                            case CTRL_R -> {
                                boolean replaced = engine.replaceCurrentOrNext(
                                    searchQueryInput.toString(),
                                    searchReplaceInput.toString(),
                                    toSearchOptions(searchUi)
                                );
                                statusMessage = replaced ? "Replaced match" : "Nothing to replace";
                            }
                            case CTRL_A -> {
                                int replaced = engine.replaceAll(
                                    searchQueryInput.toString(),
                                    searchReplaceInput.toString(),
                                    toSearchOptions(searchUi)
                                );
                                statusMessage = replaced > 0
                                    ? "Replaced " + replaced + " occurrence(s)"
                                    : "Nothing to replace";
                            }
                            case CTRL_Q -> {
                                if (state.isDirty() && !confirmQuit) {
                                    confirmQuit = true;
                                    statusMessage = "Unsaved changes. Press Ctrl+Q again to quit.";
                                } else {
                                    running = false;
                                }
                            }
                            case CTRL_S -> {
                                if (state.getCurrentFile() == null) {
                                    searchMode = false;
                                    searchFocus = SearchFocus.FIND;
                                    saveAsMode = true;
                                    saveAsInput.setLength(0);
                                    saveAsInput.append(DEFAULT_FILE_NAME);
                                    notification.clear();
                                } else if (isReadOnlyFile(state.getCurrentFile())) {
                                    searchMode = false;
                                    searchFocus = SearchFocus.FIND;
                                    saveAsMode = true;
                                    saveAsInput.setLength(0);
                                    saveAsInput.append(suggestWritableName(state.getCurrentFile()));
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
                            case CTRL_K, CTRL_W, CTRL_O, F2, CTRL_SHIFT_S -> {
                                searchMode = false;
                                searchFocus = SearchFocus.FIND;
                                saveAsMode = true;
                                saveAsInput.setLength(0);
                                if (state.getCurrentFile() != null) {
                                    saveAsInput.append(state.getCurrentFile());
                                } else {
                                    saveAsInput.append(DEFAULT_FILE_NAME);
                                }
                                notification.clear();
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
                            case DELETE -> engine.deleteForward();
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
                        case CTRL_E -> {
                            activeMenu = MainMenu.EDIT;
                            mainMenuOpen = true;
                            mainMenuIndex = 0;
                        }
                        case F10 -> {
                            activeMenu = MainMenu.FILE;
                            mainMenuOpen = true;
                            mainMenuIndex = 0;
                        }
                        case CTRL_Q -> {
                            if (state.isDirty() && !confirmQuit) {
                                confirmQuit = true;
                                statusMessage = "Unsaved changes. Press Ctrl+Q again to quit.";
                            } else {
                                running = false;
                            }
                        }
                        case CTRL_S -> {
                            if (state.getCurrentFile() == null) {
                                saveAsMode = true;
                                saveAsInput.setLength(0);
                                saveAsInput.append(DEFAULT_FILE_NAME);
                                notification.clear();
                            } else if (isReadOnlyFile(state.getCurrentFile())) {
                                saveAsMode = true;
                                saveAsInput.setLength(0);
                                saveAsInput.append(suggestWritableName(state.getCurrentFile()));
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
                        case CTRL_K, CTRL_W, CTRL_O, F2, CTRL_SHIFT_S -> {
                            saveAsMode = true;
                            saveAsInput.setLength(0);
                            if (state.getCurrentFile() != null) {
                                saveAsInput.append(state.getCurrentFile());
                            } else {
                                saveAsInput.append(DEFAULT_FILE_NAME);
                            }
                            notification.clear();
                        }
                        case CTRL_F -> {
                            searchMode = true;
                            searchFocus = SearchFocus.FIND;
                            statusMessage = SEARCH_HELP;
                        }
                        case CTRL_A, CTRL_R, TAB -> {
                            // Reserved for search mode. No-op in normal mode.
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

    private String buildTopBar(EditorState state, int cols, boolean mainMenuOpen, MainMenu activeMenu) {
        String fileMenu = (mainMenuOpen && activeMenu == MainMenu.FILE) ? "[File]" : " File ";
        String editMenu = (mainMenuOpen && activeMenu == MainMenu.EDIT) ? "[Edit]" : " Edit ";
        String file = state.getCurrentFile() == null ? "[No Name]" : state.getCurrentFile().toString();
        String dirty = state.isDirty() ? " [+]" : "";
        String top = fileMenu + " " + editMenu + " " + file + dirty;
        if (top.length() > cols) {
            return top.substring(0, cols);
        }
        return top + " ".repeat(cols - top.length());
    }

    private Position mouseToTextPosition(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows
    ) {
        if (mouse.row() < 1 || mouse.row() > contentRows) {
            return null;
        }
        int line = state.getTopLine() + (mouse.row() - 1);
        line = Math.max(0, Math.min(line, model.lineCount() - 1));
        int col = mouse.col() < plan.gutterWidth()
            ? 0
            : state.getLeftColumn() + (mouse.col() - plan.gutterWidth());
        return model.clamp(new Position(line, Math.max(0, col)));
    }

    private void drawMainMenuOverlay(CellBuffer buffer, int cols, boolean visible, MainMenu menu, int selectedIndex) {
        if (!visible || buffer.rows() < 4 || cols < 8) {
            return;
        }
        String[] items = menuItems(menu);
        int popupWidth = menuPopupWidth(cols, menu);
        int popupHeight = Math.min(buffer.rows() - 1, items.length + 2);
        if (popupHeight < 3) {
            return;
        }
        int startCol = menu == MainMenu.FILE ? 0 : 7;
        drawMenuLine(buffer, 1, startCol, "┌" + "─".repeat(Math.max(0, popupWidth - 2)) + "┐", MENU_POPUP_STYLE, cols);
        for (int i = 0; i < popupHeight - 2; i++) {
            String label = i < items.length ? items[i] : "";
            String content = padRight(" " + label, Math.max(0, popupWidth - 2));
            Style style = i == selectedIndex ? MENU_SELECTED_STYLE : MENU_POPUP_STYLE;
            drawMenuLine(buffer, 2 + i, startCol, "│" + content + "│", style, cols);
        }
        drawMenuLine(buffer, popupHeight, startCol, "└" + "─".repeat(Math.max(0, popupWidth - 2)) + "┘", MENU_POPUP_STYLE, cols);
    }

    private int menuPopupWidth(int cols, MainMenu menu) {
        int popupWidth = 16;
        for (String item : menuItems(menu)) {
            popupWidth = Math.max(popupWidth, item.length() + 4);
        }
        return Math.min(cols, popupWidth);
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

    private String[] menuItems(MainMenu menu) {
        return menu == MainMenu.FILE ? FILE_MENU_ITEMS : EDIT_MENU_ITEMS;
    }

    private KeyEvent.Action mapMenuSelection(MainMenu menu, int itemIndex) {
        if (menu == MainMenu.FILE) {
            return switch (itemIndex) {
                case 0 -> null;
                case 1 -> KeyEvent.Action.CTRL_S;
                case 2 -> KeyEvent.Action.CTRL_K;
                case 3 -> KeyEvent.Action.CTRL_Q;
                default -> null;
            };
        }
        return switch (itemIndex) {
            case 0 -> KeyEvent.Action.CTRL_Z;
            case 1 -> KeyEvent.Action.CTRL_Y;
            case 2 -> KeyEvent.Action.CTRL_X;
            case 3 -> KeyEvent.Action.CTRL_C;
            case 4 -> KeyEvent.Action.CTRL_V;
            default -> null;
        };
    }

    private MainMenu nextMenu(MainMenu menu) {
        return menu == MainMenu.FILE ? MainMenu.EDIT : MainMenu.FILE;
    }

    private MainMenu previousMenu(MainMenu menu) {
        return nextMenu(menu);
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

    private String buildSaveAsPrompt(StringBuilder saveAsInput) {
        return SAVE_AS_PROMPT + saveAsInput;
    }

    private String buildOverwritePrompt(Path path) {
        return "Overwrite: " + path + " ? (y/N)";
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
        try {
            return Files.exists(path) && !Files.isWritable(path);
        } catch (SecurityException e) {
            return true;
        }
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
        String query,
        String replacement,
        SearchFocus focus,
        int cols,
        SearchUiOptions searchUi,
        int matchCount
    ) {
        String safeQuery = query == null ? "" : query;
        String safeReplacement = replacement == null ? "" : replacement;
        String findField = formatField("Find", safeQuery);
        String replaceField = formatField("Replace", safeReplacement);
        String csToggle = formatToggle("Аа", searchUi.caseSensitive());
        String wToggle = formatToggle("word", searchUi.matchMode() == SearchOptions.MatchMode.WHOLE_WORD);
        String starToggle = formatToggle("*", searchUi.matchMode() == SearchOptions.MatchMode.WILDCARD);
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
        String prompt = findField + "  " + replaceField
            + "  " + csToggle + " " + wToggle + " " + starToggle + " " + countToken
            + "  " + SEARCH_HELP;

        int cursorIndex = cursorForFocus(findField, replaceField, csToggle, wToggle, starToggle, safeQuery, safeReplacement, focus);

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
                wildcardEnd
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
            wildcardEnd
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
        int wildcardEnd
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
        String query,
        String replacement,
        SearchFocus focus
    ) {
        if (focus == SearchFocus.FIND) {
            return "Find: ".length() + query.length();
        }
        int replaceStart = findField.length() + 2;
        if (focus == SearchFocus.REPLACE) {
            return replaceStart + "Replace: ".length() + replacement.length();
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
        return togglesStart + iToggle.length() + 1 + wToggle.length() + 1 + starCheckOffset;
    }

    private SearchUiOptions toggleFocusedSearchOption(SearchUiOptions options, SearchFocus focus) {
        return switch (focus) {
            case CASE -> new SearchUiOptions(!options.caseSensitive(), options.matchMode());
            case WORD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode() == SearchOptions.MatchMode.WHOLE_WORD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WHOLE_WORD
            );
            case WILDCARD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode() == SearchOptions.MatchMode.WILDCARD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WILDCARD
            );
            default -> options;
        };
    }

    private SearchOptions toSearchOptions(SearchUiOptions ui) {
        SearchUiOptions safe = ui == null ? SearchUiOptions.DEFAULT : ui;
        return new SearchOptions(!safe.caseSensitive(), safe.matchMode());
    }

    private boolean isToggleFocus(SearchFocus focus) {
        return focus == SearchFocus.CASE || focus == SearchFocus.WORD || focus == SearchFocus.WILDCARD;
    }

    private SearchFocus nextSearchFocus(SearchFocus focus) {
        return switch (focus) {
            case FIND -> SearchFocus.REPLACE;
            case REPLACE -> SearchFocus.CASE;
            case CASE -> SearchFocus.WORD;
            case WORD -> SearchFocus.WILDCARD;
            case WILDCARD -> SearchFocus.FIND;
        };
    }

    private int countSearchMatches(TextModel model, String query, SearchOptions options) {
        if (query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        int total = 0;
        for (int i = 0; i < model.lineCount(); i++) {
            total += findMatchesInLine(model.getLine(i), query, safe).size();
        }
        return total;
    }

    private static List<LineMatch> findMatchesInLine(String line, String query, SearchOptions options) {
        String haystack = options.caseInsensitive() ? line.toLowerCase(Locale.ROOT) : line;
        String needle = options.caseInsensitive() ? query.toLowerCase(Locale.ROOT) : query;
        return switch (options.matchMode()) {
            case SUBSTRING -> findLiteralMatches(haystack, needle, false);
            case WHOLE_WORD -> findLiteralMatches(haystack, needle, true);
            case WILDCARD -> findWildcardMatches(haystack, needle);
        };
    }

    private static List<LineMatch> findLiteralMatches(String text, String needle, boolean wholeWord) {
        if (needle.isEmpty()) {
            return List.of();
        }
        List<LineMatch> matches = new ArrayList<>();
        int at = 0;
        while (at <= text.length() - needle.length()) {
            int idx = text.indexOf(needle, at);
            if (idx < 0) {
                break;
            }
            int end = idx + needle.length();
            if (!wholeWord || isWholeWord(text, idx, end)) {
                matches.add(new LineMatch(idx, end));
            }
            at = end;
        }
        return matches;
    }

    private static List<LineMatch> findWildcardMatches(String text, String wildcard) {
        List<LineMatch> matches = new ArrayList<>();
        Pattern pattern = wildcardPattern(wildcard);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.start() == matcher.end()) {
                break;
            }
            matches.add(new LineMatch(matcher.start(), matcher.end()));
        }
        return matches;
    }

    private static Pattern wildcardPattern(String wildcard) {
        StringBuilder regex = new StringBuilder("(?s)");
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

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end == text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private record LineMatch(int start, int end) {
    }

    private enum MainMenu {
        FILE,
        EDIT
    }

    private enum SearchFocus {
        FIND,
        REPLACE,
        CASE,
        WORD,
        WILDCARD
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

    private record SearchUiOptions(boolean caseSensitive, SearchOptions.MatchMode matchMode) {
        private static final SearchUiOptions DEFAULT = new SearchUiOptions(false, SearchOptions.MatchMode.SUBSTRING);
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
        System.out.println("  --no-color       Render without ANSI colors/styles");
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

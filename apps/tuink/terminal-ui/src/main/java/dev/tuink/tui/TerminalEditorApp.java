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
    private static final String KEY_HINTS = "Ctrl+F Search  Ctrl+C/X/V  Ctrl+Z/Y  Ctrl+Q";
    private static final String SEARCH_HELP = "Tab focus  Space toggle  Enter next  Ctrl+G prev  Ctrl+R replace  Ctrl+A all  Esc";
    private static final String SAVE_AS_PROMPT = "Save As: ";
    private static final Style SAVE_PROMPT_STYLE = new Style(Color.BRIGHT_WHITE, Color.RED, false, false);
    private static final Style NOTIFY_SUCCESS_STYLE = new Style(Color.BLACK, Color.BRIGHT_GREEN, false, false);
    private static final Style NOTIFY_ERROR_STYLE = new Style(Color.BRIGHT_WHITE, Color.RED, false, false);
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;

    public static void main(String[] args) throws Exception {
        new TerminalEditorApp().run(args);
    }

    private void run(String[] args) throws Exception {
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
        SearchOptions searchOptions = SearchOptions.DEFAULT;

        if (args.length > 0) {
            Path file = Path.of(args[0]);
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
                    writer.print("\u001B[0m\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H");
                    writer.flush();
                    terminal.setAttributes(original);
                }
            };
            Thread shutdownHook = new Thread(cleanup, "tuink-terminal-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                writer.print("\u001B[?1049h\u001B[?25h\u001B[2J");
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

                    String topBar = buildTopBar(state, cols);
                    String bottomBar = buildBottomBar(state, model, statusMessage, cols);
                    String interactiveBarText = null;
                    Style interactiveBarStyle = null;
                    SearchBarView searchBar = searchMode
                        ? buildSearchBar(
                            searchQueryInput.toString(),
                            searchReplaceInput.toString(),
                            searchFocus,
                            cols,
                            searchOptions
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
                        interactiveBarText,
                        interactiveBarStyle,
                        notification.message,
                        notification.style
                    );
                    CellBuffer current = renderer.rasterize(plan, rows, cols);

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
                    }

                    String frameDiff = AnsiDiff.diff(previous, current);
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

                    if (searchMode) {
                        if (key instanceof KeyEvent.CharInput charInput) {
                            char ch = charInput.ch();
                            if (ch == ' ' && isToggleFocus(searchFocus)) {
                                SearchOptions before = searchOptions;
                                searchOptions = toggleFocusedSearchOption(searchOptions, searchFocus);
                                if (!searchOptions.equals(before)) {
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
                                boolean found = engine.findNext(searchQueryInput.toString(), searchOptions);
                                statusMessage = found ? "Found next match" : "Not found";
                            }
                            case CTRL_G -> {
                                boolean found = engine.findPrevious(searchQueryInput.toString(), searchOptions);
                                statusMessage = found ? "Found previous match" : "Not found";
                            }
                            case CTRL_R -> {
                                boolean replaced = engine.replaceCurrentOrNext(
                                    searchQueryInput.toString(),
                                    searchReplaceInput.toString(),
                                    searchOptions
                                );
                                statusMessage = replaced ? "Replaced match" : "Nothing to replace";
                            }
                            case CTRL_A -> {
                                int replaced = engine.replaceAll(
                                    searchQueryInput.toString(),
                                    searchReplaceInput.toString(),
                                    searchOptions
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

    private String buildTopBar(EditorState state, int cols) {
        String file = state.getCurrentFile() == null ? "[No Name]" : state.getCurrentFile().toString();
        String dirty = state.isDirty() ? " [+]" : "";
        String top = file + dirty;
        if (top.length() > cols) {
            return top.substring(0, cols);
        }
        return top + " ".repeat(cols - top.length());
    }

    private String buildBottomBar(EditorState state, TextModel model, String message, int cols) {
        Position p = state.getCursor();
        int totalLines = model.lineCount();
        int lineCols = model.getLine(p.line()).length();
        String left = "Ln " + (p.line() + 1) + "/" + totalLines
            + ", Col " + (p.col() + 1) + "/" + lineCols;
        if (message != null && !message.isBlank()) {
            left = left + "  |  " + message;
        }

        if (left.length() >= cols) {
            return left.substring(0, cols);
        }

        int free = cols - left.length();
        if (free <= 2) {
            return left + " ".repeat(free);
        }

        String hints = KEY_HINTS;
        if (hints.length() + 2 > free) {
            hints = hints.substring(0, free - 2);
        }

        return left + " ".repeat(free - hints.length()) + hints;
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
        SearchOptions searchOptions
    ) {
        String safeQuery = query == null ? "" : query;
        String safeReplacement = replacement == null ? "" : replacement;
        String findField = formatField("Find", safeQuery);
        String replaceField = formatField("Replace", safeReplacement);
        String iToggle = formatToggle("i", searchOptions.caseInsensitive());
        String wToggle = formatToggle("word", searchOptions.matchMode() == SearchOptions.MatchMode.WHOLE_WORD);
        String starToggle = formatToggle("*", searchOptions.matchMode() == SearchOptions.MatchMode.WILDCARD);
        String prompt = findField + "  " + replaceField
            + "  " + iToggle + " " + wToggle + " " + starToggle
            + "  " + SEARCH_HELP;

        int cursorIndex = cursorForFocus(findField, replaceField, iToggle, wToggle, starToggle, safeQuery, safeReplacement, focus);

        if (prompt.length() <= cols) {
            return new SearchBarView(prompt + " ".repeat(cols - prompt.length()), Math.min(cols, cursorIndex + 1));
        }

        int start = Math.max(0, cursorIndex - cols + 1);
        int end = Math.min(prompt.length(), start + cols);
        String clipped = prompt.substring(start, end);
        if (clipped.length() < cols) {
            clipped = clipped + " ".repeat(cols - clipped.length());
        }
        int cursorCol = Math.max(1, Math.min(cols, cursorIndex - start + 1));
        return new SearchBarView(clipped, cursorCol);
    }

    private record SearchBarView(String text, int cursorCol) {
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

    private SearchOptions toggleFocusedSearchOption(SearchOptions options, SearchFocus focus) {
        return switch (focus) {
            case CASE -> new SearchOptions(!options.caseInsensitive(), options.matchMode());
            case WORD -> new SearchOptions(
                options.caseInsensitive(),
                options.matchMode() == SearchOptions.MatchMode.WHOLE_WORD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WHOLE_WORD
            );
            case WILDCARD -> new SearchOptions(
                options.caseInsensitive(),
                options.matchMode() == SearchOptions.MatchMode.WILDCARD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WILDCARD
            );
            default -> options;
        };
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

    private LineHighlighter resolveHighlighter(Path file) {
        return file == null ? RegexHighlighter.withDefaults() : RegexHighlighter.forFileName(file.getFileName().toString());
    }
}

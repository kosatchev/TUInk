package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.render.CellBuffer;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.RenderRasterizer;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
    private static final String KEY_HINTS = "Shift+Arrows Select  Ctrl+C/X/V  Ctrl+Z/Y  Ctrl+Q";

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
        boolean confirmQuit = false;
        boolean saveAsMode = false;
        StringBuilder saveAsInput = new StringBuilder();

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
                    int rows = Math.max(2, terminal.getHeight());
                    int cols = Math.max(1, terminal.getWidth());
                    int contentRows = Math.max(0, rows - 2);
                    engine.ensureCursorVisible(contentRows);

                    String topBar = buildTopBar(state, cols);
                    String bottomBar = buildBottomBar(state, model, statusMessage, cols);
                    RenderPlan plan = renderPlanBuilder.build(model, state, rows, cols, topBar, bottomBar);
                    CellBuffer current = renderer.rasterize(plan, rows, cols);

                    int desiredCursorRow = plan.cursorRow() + 2;
                    int desiredCursorCol = plan.cursorCol() + 1;
                    if (saveAsMode) {
                        String prompt = buildSaveAsPrompt(saveAsInput);
                        desiredCursorRow = rows;
                        desiredCursorCol = Math.min(cols, prompt.length() + 1);
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
                            saveAsInput.append(charInput.ch());
                            statusMessage = buildSaveAsPrompt(saveAsInput);
                            continue;
                        }

                        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
                        switch (action) {
                            case BACKSPACE -> {
                                if (!saveAsInput.isEmpty()) {
                                    saveAsInput.deleteCharAt(saveAsInput.length() - 1);
                                }
                                statusMessage = buildSaveAsPrompt(saveAsInput);
                            }
                            case ENTER -> {
                                String target = saveAsInput.toString().trim();
                                if (target.isEmpty()) {
                                    statusMessage = "Save As cancelled";
                                } else {
                                    try {
                                        engine.saveAs(Path.of(target));
                                        renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(state.getCurrentFile()));
                                        statusMessage = "Saved as " + state.getCurrentFile();
                                    } catch (IOException e) {
                                        statusMessage = "Save As failed: " + e.getMessage();
                                    }
                                }
                                saveAsMode = false;
                                saveAsInput.setLength(0);
                            }
                            case ESCAPE, CTRL_Q -> {
                                saveAsMode = false;
                                saveAsInput.setLength(0);
                                statusMessage = "Save As cancelled";
                            }
                            default -> {
                                // Ignore unsupported keys while Save As prompt is active.
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
                            try {
                                if (state.getCurrentFile() == null) {
                                    engine.saveAs(Path.of(DEFAULT_FILE_NAME));
                                    renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(state.getCurrentFile()));
                                    statusMessage = "Saved as " + state.getCurrentFile();
                                } else {
                                    engine.save();
                                    statusMessage = "Saved " + state.getCurrentFile();
                                }
                            } catch (IOException e) {
                                statusMessage = "Save failed: " + e.getMessage();
                            }
                        }
                        case CTRL_C -> statusMessage = engine.copySelection() ? "Copied selection" : "Nothing selected";
                        case CTRL_X -> statusMessage = engine.cutSelection() ? "Cut selection" : "Nothing selected";
                        case CTRL_V -> statusMessage = engine.pasteClipboard() ? "Pasted" : "Clipboard empty";
                        case CTRL_Z -> statusMessage = engine.undo() ? "Undo" : "Nothing to undo";
                        case CTRL_Y -> statusMessage = engine.redo() ? "Redo" : "Nothing to redo";
                        case CTRL_W, CTRL_O, F2 -> {
                            saveAsMode = true;
                            saveAsInput.setLength(0);
                            if (state.getCurrentFile() != null) {
                                saveAsInput.append(state.getCurrentFile());
                            }
                            statusMessage = buildSaveAsPrompt(saveAsInput);
                        }
                        case CTRL_F -> statusMessage = "Search not implemented";
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
        if (message != null && message.startsWith("Save As:")) {
            if (message.length() > cols) {
                return message.substring(message.length() - cols);
            }
            return message + " ".repeat(cols - message.length());
        }

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
        return "Save As: " + saveAsInput;
    }

    private LineHighlighter resolveHighlighter(Path file) {
        return file == null ? RegexHighlighter.withDefaults() : RegexHighlighter.forFileName(file.getFileName().toString());
    }
}

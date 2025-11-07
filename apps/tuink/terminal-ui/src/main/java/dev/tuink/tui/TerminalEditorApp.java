package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

/**
 * JLine terminal application wiring core + render + highlighter.
 */
public class TerminalEditorApp {
    public static void main(String[] args) throws Exception {
        new TerminalEditorApp().run(args);
    }

    private void run(String[] args) throws Exception {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state);
        LineHighlighter highlighter = RegexHighlighter.withDefaults();
        RenderPlanBuilder renderPlanBuilder = new RenderPlanBuilder(highlighter);
        TuiRenderer renderer = new TuiRenderer();

        String statusMessage = "Ctrl+S save | Ctrl+Q quit";
        boolean confirmQuit = false;

        if (args.length > 0) {
            Path file = Path.of(args[0]);
            try {
                engine.open(file);
                statusMessage = "Opened " + file;
            } catch (IOException e) {
                statusMessage = "Open failed: " + e.getMessage();
            }
        }

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            PrintWriter writer = terminal.writer();
            Attributes original = terminal.enterRawMode();
            NonBlockingReader input = terminal.reader();
            AtomicBoolean resized = new AtomicBoolean(false);
            terminal.handle(Terminal.Signal.WINCH, sig -> resized.set(true));

            CellBuffer previous = null;
            try {
                writer.print("\u001B[?1049h\u001B[?25l\u001B[2J");
                writer.flush();

                KeyDecoder decoder = new KeyDecoder(input);
                boolean running = true;
                while (running) {
                    int rows = Math.max(2, terminal.getHeight());
                    int cols = Math.max(20, terminal.getWidth());
                    int contentRows = rows - 1;
                    engine.ensureCursorVisible(contentRows);

                    String status = buildStatusLine(state, statusMessage, cols);
                    RenderPlan plan = renderPlanBuilder.build(model, state, rows, cols, status);
                    CellBuffer current = renderer.toCellBuffer(plan, rows, cols);

                    writer.print(AnsiDiff.diff(previous, current));
                    writer.print("\u001B[" + (plan.cursorRow() + 1) + ";" + (plan.cursorCol() + 1) + "H");
                    writer.flush();
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
                                engine.save();
                                statusMessage = "Saved " + state.getCurrentFile();
                            } catch (IOException e) {
                                statusMessage = "Save failed: " + e.getMessage();
                            }
                        }
                        case CTRL_F -> statusMessage = "Search not implemented";
                        case CTRL_G -> statusMessage = "Goto line not implemented";
                        case ARROW_UP -> engine.moveUp();
                        case ARROW_DOWN -> engine.moveDown();
                        case ARROW_LEFT -> engine.moveLeft();
                        case ARROW_RIGHT -> engine.moveRight();
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
                writer.print("\u001B[0m\u001B[2J\u001B[H\u001B[?25h\u001B[?1049l");
                writer.flush();
                terminal.setAttributes(original);
            }
        }
    }

    private String buildStatusLine(EditorState state, String message, int cols) {
        Position p = state.getCursor();
        String file = state.getCurrentFile() == null ? "[No Name]" : state.getCurrentFile().toString();
        String dirty = state.isDirty() ? " [+]" : "";
        String base = file + dirty + "  Ln " + (p.line() + 1) + ", Col " + (p.col() + 1);
        String status = message == null || message.isBlank() ? base : base + "  |  " + message;

        if (status.length() > cols) {
            return status.substring(0, cols);
        }
        return status + " ".repeat(cols - status.length());
    }
}

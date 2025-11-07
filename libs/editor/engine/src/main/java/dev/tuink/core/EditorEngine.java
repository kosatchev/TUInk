package dev.tuink.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Core editing commands and viewport management.
 */
public class EditorEngine {
    private final TextModel model;
    private final EditorState state;

    public EditorEngine(TextModel model, EditorState state) {
        this.model = model;
        this.state = state;
    }

    public TextModel model() {
        return model;
    }

    public EditorState state() {
        return state;
    }

    public void open(Path path) throws IOException {
        if (Files.exists(path)) {
            model.load(path);
        }
        state.setCurrentFile(path);
        state.setCursor(new Position(0, 0));
        state.setTopLine(0);
        state.setLeftColumn(0);
        state.setDirty(false);
    }

    public void save() throws IOException {
        if (state.getCurrentFile() == null) {
            throw new IOException("No file selected");
        }
        model.save(state.getCurrentFile());
        state.setDirty(false);
    }

    public void moveLeft() {
        Position p = state.getCursor();
        if (p.col() > 0) {
            state.setCursor(new Position(p.line(), p.col() - 1));
        } else if (p.line() > 0) {
            int prevLen = model.getLine(p.line() - 1).length();
            state.setCursor(new Position(p.line() - 1, prevLen));
        }
    }

    public void moveRight() {
        Position p = state.getCursor();
        int len = model.getLine(p.line()).length();
        if (p.col() < len) {
            state.setCursor(new Position(p.line(), p.col() + 1));
        } else if (p.line() < model.lineCount() - 1) {
            state.setCursor(new Position(p.line() + 1, 0));
        }
    }

    public void moveUp() {
        Position p = state.getCursor();
        if (p.line() == 0) {
            return;
        }
        int targetLine = p.line() - 1;
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        state.setCursor(new Position(targetLine, col));
    }

    public void moveDown() {
        Position p = state.getCursor();
        if (p.line() >= model.lineCount() - 1) {
            return;
        }
        int targetLine = p.line() + 1;
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        state.setCursor(new Position(targetLine, col));
    }

    public void moveHome() {
        state.setCursor(new Position(state.getCursor().line(), 0));
    }

    public void moveEnd() {
        Position p = state.getCursor();
        state.setCursor(new Position(p.line(), model.getLine(p.line()).length()));
    }

    public void pageUp(int pageRows) {
        Position p = state.getCursor();
        int targetLine = Math.max(0, p.line() - Math.max(pageRows, 1));
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        state.setCursor(new Position(targetLine, col));
        state.setTopLine(Math.max(0, state.getTopLine() - Math.max(pageRows, 1)));
    }

    public void pageDown(int pageRows) {
        Position p = state.getCursor();
        int targetLine = Math.min(model.lineCount() - 1, p.line() + Math.max(pageRows, 1));
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        state.setCursor(new Position(targetLine, col));
        state.setTopLine(Math.max(0, state.getTopLine() + Math.max(pageRows, 1)));
    }

    public void insertChar(char ch) {
        state.setCursor(model.insertChar(state.getCursor(), ch));
        state.setDirty(true);
    }

    public void backspace() {
        Position before = state.getCursor();
        Position after = model.deleteBackward(before);
        if (!after.equals(before)) {
            state.setDirty(true);
        }
        state.setCursor(after);
    }

    public void deleteForward() {
        Position before = state.getCursor();
        Position after = model.deleteForward(before);
        if (!after.equals(before) || model.lineCount() == 1) {
            String beforeText = model.getLine(after.line());
            if (!beforeText.isEmpty() || before.col() != after.col()) {
                state.setDirty(true);
            }
        }
        state.setCursor(after);
    }

    public void insertNewline() {
        state.setCursor(model.insertNewline(state.getCursor()));
        state.setDirty(true);
    }

    public void ensureCursorVisible(int contentRows) {
        if (contentRows <= 0) {
            return;
        }

        Position p = model.clamp(state.getCursor());
        state.setCursor(p);

        if (p.line() < state.getTopLine()) {
            state.setTopLine(p.line());
        } else if (p.line() >= state.getTopLine() + contentRows) {
            state.setTopLine(p.line() - contentRows + 1);
        }
    }
}

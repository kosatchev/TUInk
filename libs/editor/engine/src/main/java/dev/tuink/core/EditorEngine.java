package dev.tuink.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Core editing commands and viewport management.
 */
public class EditorEngine {
    private static final int MAX_HISTORY = 500;
    private static final long COALESCE_WINDOW_NANOS = 700_000_000L;
    private final TextModel model;
    private final EditorState state;
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private String clipboard = "";
    private EditKind lastEditKind = EditKind.NONE;
    private long lastEditAtNanos;
    private Position lastEditCursorAfter;

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
            state.setFileEncoding(model.load(path));
        } else {
            state.setFileEncoding(StandardCharsets.UTF_8);
        }
        state.setCurrentFile(path);
        state.setCursor(new Position(0, 0));
        state.setTopLine(0);
        state.setLeftColumn(0);
        state.setDirty(false);
        state.clearSelection();
        undoStack.clear();
        redoStack.clear();
        breakCoalescing();
    }

    public void save() throws IOException {
        if (state.getCurrentFile() == null) {
            throw new IOException("No file selected");
        }
        model.save(state.getCurrentFile(), state.getFileEncoding());
        state.setDirty(false);
        breakCoalescing();
    }

    public void saveAs(Path path) throws IOException {
        state.setCurrentFile(path);
        save();
    }

    public void moveLeft() {
        moveLeft(false);
    }

    public void moveLeft(boolean selecting) {
        Position p = state.getCursor();
        if (p.col() > 0) {
            moveCursor(new Position(p.line(), p.col() - 1), selecting, p);
        } else if (p.line() > 0) {
            int prevLen = model.getLine(p.line() - 1).length();
            moveCursor(new Position(p.line() - 1, prevLen), selecting, p);
        }
    }

    public void moveRight() {
        moveRight(false);
    }

    public void moveRight(boolean selecting) {
        Position p = state.getCursor();
        int len = model.getLine(p.line()).length();
        if (p.col() < len) {
            moveCursor(new Position(p.line(), p.col() + 1), selecting, p);
        } else if (p.line() < model.lineCount() - 1) {
            moveCursor(new Position(p.line() + 1, 0), selecting, p);
        }
    }

    public void moveUp() {
        moveUp(false);
    }

    public void moveUp(boolean selecting) {
        Position p = state.getCursor();
        if (p.line() == 0) {
            return;
        }
        int targetLine = p.line() - 1;
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        moveCursor(new Position(targetLine, col), selecting, p);
    }

    public void moveDown() {
        moveDown(false);
    }

    public void moveDown(boolean selecting) {
        Position p = state.getCursor();
        if (p.line() >= model.lineCount() - 1) {
            return;
        }
        int targetLine = p.line() + 1;
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        moveCursor(new Position(targetLine, col), selecting, p);
    }

    public void moveHome() {
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(state.getCursor().line(), 0));
    }

    public void moveEnd() {
        Position p = state.getCursor();
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(p.line(), model.getLine(p.line()).length()));
    }

    public void pageUp(int pageRows) {
        Position p = state.getCursor();
        int targetLine = Math.max(0, p.line() - Math.max(pageRows, 1));
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(targetLine, col));
        state.setTopLine(Math.max(0, state.getTopLine() - Math.max(pageRows, 1)));
    }

    public void pageDown(int pageRows) {
        Position p = state.getCursor();
        int targetLine = Math.min(model.lineCount() - 1, p.line() + Math.max(pageRows, 1));
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(targetLine, col));
        state.setTopLine(Math.max(0, state.getTopLine() + Math.max(pageRows, 1)));
    }

    public void insertChar(char ch) {
        boolean hadSelection = state.hasSelection();
        applyEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, () -> {
            deleteSelectionIfAny();
            state.setCursor(model.insertChar(state.getCursor(), ch));
            state.clearSelection();
        });
    }

    public void backspace() {
        boolean hadSelection = state.hasSelection();
        applyEdit(hadSelection ? EditKind.NONE : EditKind.BACKSPACE, () -> {
            if (state.hasSelection()) {
                deleteSelectionIfAny();
            } else {
                state.setCursor(model.deleteBackward(state.getCursor()));
            }
            state.clearSelection();
        });
    }

    public void deleteForward() {
        boolean hadSelection = state.hasSelection();
        applyEdit(hadSelection ? EditKind.NONE : EditKind.DELETE_FORWARD, () -> {
            if (state.hasSelection()) {
                deleteSelectionIfAny();
            } else {
                state.setCursor(model.deleteForward(state.getCursor()));
            }
            state.clearSelection();
        });
    }

    public void insertNewline() {
        applyEdit(EditKind.NONE, () -> {
            deleteSelectionIfAny();
            state.setCursor(model.insertNewline(state.getCursor()));
            state.clearSelection();
        });
    }

    public boolean copySelection() {
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        clipboard = model.getTextRange(range.start(), range.end());
        return !clipboard.isEmpty();
    }

    public boolean cutSelection() {
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        clipboard = model.getTextRange(range.start(), range.end());
        applyEdit(EditKind.NONE, () -> {
            state.setCursor(model.deleteRange(range.start(), range.end()));
            state.clearSelection();
        });
        return true;
    }

    public boolean pasteClipboard() {
        if (clipboard == null || clipboard.isEmpty()) {
            return false;
        }
        applyEdit(EditKind.NONE, () -> {
            deleteSelectionIfAny();
            state.setCursor(model.insertText(state.getCursor(), clipboard));
            state.clearSelection();
        });
        return true;
    }

    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        Snapshot current = captureSnapshot();
        Snapshot previous = undoStack.pop();
        pushWithLimit(redoStack, current);
        restoreSnapshot(previous);
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        Snapshot current = captureSnapshot();
        Snapshot next = redoStack.pop();
        pushWithLimit(undoStack, current);
        restoreSnapshot(next);
        return true;
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

    private void applyEdit(EditKind kind, Runnable editOperation) {
        Snapshot before = captureSnapshot();
        editOperation.run();
        Snapshot after = captureSnapshot();
        if (after.equals(before)) {
            return;
        }
        long now = System.nanoTime();
        if (!shouldCoalesce(kind, before, now)) {
            pushWithLimit(undoStack, before);
        }
        redoStack.clear();
        state.setDirty(true);
        lastEditKind = kind;
        lastEditAtNanos = now;
        lastEditCursorAfter = after.cursor();
    }

    private Snapshot captureSnapshot() {
        return new Snapshot(
            model.snapshotLines(),
            state.getCursor(),
            state.getTopLine(),
            state.getLeftColumn(),
            state.isDirty(),
            state.getSelectionAnchor()
        );
    }

    private void restoreSnapshot(Snapshot snapshot) {
        model.replaceLines(snapshot.lines());
        state.setCursor(model.clamp(snapshot.cursor()));
        state.setTopLine(snapshot.topLine());
        state.setLeftColumn(snapshot.leftColumn());
        state.setDirty(snapshot.dirty());
        state.setSelectionAnchor(snapshot.selectionAnchor());
    }

    private static void pushWithLimit(Deque<Snapshot> stack, Snapshot snapshot) {
        stack.push(snapshot);
        while (stack.size() > MAX_HISTORY) {
            stack.removeLast();
        }
    }

    private record Snapshot(
        List<String> lines,
        Position cursor,
        int topLine,
        int leftColumn,
        boolean dirty,
        Position selectionAnchor
    ) {
    }

    private record SelectionRange(Position start, Position end) {
    }

    private enum EditKind {
        NONE,
        INSERT_CHAR,
        BACKSPACE,
        DELETE_FORWARD
    }

    private void moveCursor(Position next, boolean selecting, Position previous) {
        breakCoalescing();
        if (selecting) {
            if (state.getSelectionAnchor() == null) {
                state.setSelectionAnchor(previous);
            }
        } else {
            state.clearSelection();
        }
        state.setCursor(next);
    }

    private SelectionRange currentSelection() {
        if (!state.hasSelection()) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a.line() > b.line() || (a.line() == b.line() && a.col() > b.col())) {
            return new SelectionRange(b, a);
        }
        return new SelectionRange(a, b);
    }

    private void deleteSelectionIfAny() {
        SelectionRange range = currentSelection();
        if (range != null) {
            state.setCursor(model.deleteRange(range.start(), range.end()));
        }
    }

    public boolean hasSelection() {
        return state.hasSelection();
    }

    private boolean shouldCoalesce(EditKind kind, Snapshot before, long nowNanos) {
        if (kind == EditKind.NONE || lastEditKind != kind || lastEditCursorAfter == null) {
            return false;
        }
        if (nowNanos - lastEditAtNanos > COALESCE_WINDOW_NANOS) {
            return false;
        }
        return before.cursor().equals(lastEditCursorAfter)
            && before.selectionAnchor() == null;
    }

    private void breakCoalescing() {
        lastEditKind = EditKind.NONE;
        lastEditAtNanos = 0L;
        lastEditCursorAfter = null;
    }
}

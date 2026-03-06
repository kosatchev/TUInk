package dev.tuink.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private int preferredVisualColumn = -1;
    private static final int H_SCROLL_MARGIN = 12;
    private int tabWidth = 4;
    private TabInsertMode tabInsertMode = TabInsertMode.AUTO;
    private boolean autoIndentEnabled = true;
    private boolean autoPairsEnabled = true;
    private boolean autoListEnabled = true;

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

    public void setTabWidth(int tabWidth) {
        this.tabWidth = tabWidth <= 2 ? 2 : 4;
    }

    public void setTabInsertMode(TabInsertMode mode) {
        this.tabInsertMode = mode == null ? TabInsertMode.AUTO : mode;
    }

    public void setAutoIndentEnabled(boolean enabled) {
        this.autoIndentEnabled = enabled;
    }

    public void setAutoPairsEnabled(boolean enabled) {
        this.autoPairsEnabled = enabled;
    }

    public void setAutoListEnabled(boolean enabled) {
        this.autoListEnabled = enabled;
    }

    public void open(Path path) throws IOException {
        if (Files.exists(path)) {
            TextModel.LoadMetadata loaded = model.load(path);
            state.setFileEncoding(loaded.charset());
            state.setLineSeparator(loaded.lineSeparator());
        } else {
            state.setFileEncoding(StandardCharsets.UTF_8);
            state.setLineSeparator(EditorState.LINE_SEPARATOR_LF);
        }
        state.setCurrentFile(path);
        state.setCursor(new Position(0, 0));
        state.setTopLine(0);
        state.setLeftColumn(0);
        state.setLeftVisualColumn(0);
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
        model.save(state.getCurrentFile(), state.getFileEncoding(), state.getLineSeparator());
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
        preferredVisualColumn = -1;
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
        preferredVisualColumn = -1;
        Position p = state.getCursor();
        int len = model.getLine(p.line()).length();
        if (p.col() < len) {
            moveCursor(new Position(p.line(), p.col() + 1), selecting, p);
        } else if (p.line() < model.lineCount() - 1) {
            moveCursor(new Position(p.line() + 1, 0), selecting, p);
        }
    }

    public void moveWordLeft() {
        preferredVisualColumn = -1;
        Position previous = state.getCursor();
        Position p = model.clamp(previous);
        int line = p.line();
        int col = p.col();
        if (line == 0 && col == 0) {
            return;
        }
        if (col == 0 && line > 0) {
            line--;
            col = model.getLine(line).length();
        }
        String text = model.getLine(line);
        int target = col;
        while (target > 0 && Character.isWhitespace(text.charAt(target - 1))) {
            target--;
        }
        if (target > 0) {
            boolean word = isWordChar(text.charAt(target - 1));
            while (target > 0) {
                char ch = text.charAt(target - 1);
                if (Character.isWhitespace(ch) || isWordChar(ch) != word) {
                    break;
                }
                target--;
            }
        }
        moveCursor(new Position(line, target), false, previous);
    }

    public void moveWordRight() {
        preferredVisualColumn = -1;
        Position previous = state.getCursor();
        Position p = model.clamp(previous);
        int line = p.line();
        int col = p.col();
        int lastLine = model.lineCount() - 1;
        if (line == lastLine && col >= model.getLine(line).length()) {
            return;
        }
        String text = model.getLine(line);
        if (col >= text.length() && line < lastLine) {
            line++;
            col = 0;
            text = model.getLine(line);
        }
        int target = col;
        while (target < text.length() && Character.isWhitespace(text.charAt(target))) {
            target++;
        }
        if (target < text.length()) {
            boolean word = isWordChar(text.charAt(target));
            while (target < text.length()) {
                char ch = text.charAt(target);
                if (Character.isWhitespace(ch) || isWordChar(ch) != word) {
                    break;
                }
                target++;
            }
        }
        moveCursor(new Position(line, target), false, previous);
    }

    public boolean selectWordAt(Position requested) {
        preferredVisualColumn = -1;
        breakCoalescing();
        Position p = model.clamp(requested);
        String text = model.getLine(p.line());
        if (text.isEmpty()) {
            state.clearSelection();
            state.setCursor(p);
            return false;
        }
        int pivot = Math.min(Math.max(0, p.col()), text.length() - 1);
        int group = charGroup(text.charAt(pivot));
        int start = pivot;
        while (start > 0 && charGroup(text.charAt(start - 1)) == group) {
            start--;
        }
        int end = pivot + 1;
        while (end < text.length() && charGroup(text.charAt(end)) == group) {
            end++;
        }
        Position anchor = new Position(p.line(), start);
        Position cursor = new Position(p.line(), end);
        state.setSelectionAnchor(anchor);
        state.setCursor(cursor);
        return start < end;
    }

    public void moveUp() {
        moveUp(false);
    }

    public void moveUp(boolean selecting) {
        Position p = state.getCursor();
        if (p.line() == 0) {
            return;
        }
        if (preferredVisualColumn < 0) {
            preferredVisualColumn = visualColumn(model.getLine(p.line()), 0, p.col());
        }
        int targetLine = p.line() - 1;
        int col = columnForVisualColumn(model.getLine(targetLine), preferredVisualColumn);
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
        if (preferredVisualColumn < 0) {
            preferredVisualColumn = visualColumn(model.getLine(p.line()), 0, p.col());
        }
        int targetLine = p.line() + 1;
        int col = columnForVisualColumn(model.getLine(targetLine), preferredVisualColumn);
        moveCursor(new Position(targetLine, col), selecting, p);
    }

    public void moveHome() {
        preferredVisualColumn = -1;
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(state.getCursor().line(), 0));
    }

    public void moveEnd() {
        preferredVisualColumn = -1;
        Position p = state.getCursor();
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(p.line(), model.getLine(p.line()).length()));
    }

    public void moveHomeSmart() {
        preferredVisualColumn = -1;
        breakCoalescing();
        state.clearSelection();
        Position p = state.getCursor();
        String line = model.getLine(p.line());
        int firstContentCol = firstNonWhitespaceColumn(line);
        int target = p.col() == firstContentCol ? 0 : firstContentCol;
        state.setCursor(new Position(p.line(), target));
    }

    public void moveEndSmart() {
        preferredVisualColumn = -1;
        breakCoalescing();
        state.clearSelection();
        Position p = state.getCursor();
        String line = model.getLine(p.line());
        int trimmedEnd = trailingTrimEndColumn(line);
        int absoluteEnd = line.length();
        int target = p.col() == trimmedEnd ? absoluteEnd : trimmedEnd;
        state.setCursor(new Position(p.line(), target));
    }

    public void pageUp(int pageRows) {
        preferredVisualColumn = -1;
        int step = Math.max(pageRows, 1);
        if (state.hasSelection()) {
            state.setTopWrapSegment(0);
            EditorViewportBehavior.applyVerticalScrollKeepCursor(
                state,
                model,
                Math.max(0, state.getTopLine() - step)
            );
            return;
        }
        Position p = state.getCursor();
        int targetLine = Math.max(0, p.line() - step);
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(targetLine, col));
        state.setTopLine(Math.max(0, state.getTopLine() - step));
    }

    public void pageDown(int pageRows) {
        preferredVisualColumn = -1;
        int step = Math.max(pageRows, 1);
        if (state.hasSelection()) {
            state.setTopWrapSegment(0);
            EditorViewportBehavior.applyVerticalScrollKeepCursor(
                state,
                model,
                Math.max(0, state.getTopLine() + step)
            );
            return;
        }
        Position p = state.getCursor();
        int targetLine = Math.min(model.lineCount() - 1, p.line() + step);
        int col = Math.min(p.col(), model.getLine(targetLine).length());
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(targetLine, col));
        state.setTopLine(Math.max(0, state.getTopLine() + step));
    }

    public void insertChar(char ch) {
        preferredVisualColumn = -1;
        if (isAutoPairsActive() && shouldSkipOverClosing(ch)) {
            moveRight();
            return;
        }
        boolean hadSelection = state.hasSelection();
        applyEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, () -> {
            if (isAutoPairsActive() && state.hasSelection() && shouldWrapSelectionWithPair(ch)) {
                wrapSelectionWithPair(ch);
                return;
            }
            deleteSelectionIfAny();
            if (isAutoPairsActive() && shouldAutoPair(ch)) {
                insertPair(ch);
            } else {
                state.setCursor(model.insertChar(state.getCursor(), ch));
            }
            maybeAutoOutdentKeywordLine();
            state.clearSelection();
        });
    }

    public void insertTab() {
        preferredVisualColumn = -1;
        boolean hadSelection = state.hasSelection();
        applyEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, () -> {
            deleteSelectionIfAny();
            Position cursor = model.clamp(state.getCursor());
            String line = model.getLine(cursor.line());
            int col = Math.min(cursor.col(), line.length());
            String indentPrefix = line.substring(0, Math.min(col, leadingIndentLength(line)));
            String tabText = indentUnit(cursor.line(), indentPrefix);
            state.setCursor(model.insertText(cursor, tabText));
            maybeAutoOutdentKeywordLine();
            state.clearSelection();
        });
    }

    public void backspace() {
        preferredVisualColumn = -1;
        boolean hadSelection = state.hasSelection();
        applyEdit(hadSelection ? EditKind.NONE : EditKind.BACKSPACE, () -> {
            if (state.hasSelection()) {
                deleteSelectionIfAny();
            } else if (isAutoPairsActive() && deletePairAroundCursor()) {
                return;
            } else if (deleteIndentUnitBackwardIfAny()) {
                return;
            } else {
                state.setCursor(model.deleteBackward(state.getCursor()));
            }
            state.clearSelection();
        });
    }

    public void deleteForward() {
        preferredVisualColumn = -1;
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
        preferredVisualColumn = -1;
        applyEdit(EditKind.NONE, () -> {
            deleteSelectionIfAny();
            Position before = model.clamp(state.getCursor());
            String indent = isAutoIndentActive() ? indentForNewLine(before) : "";
            String continuation = isAutoListActive() ? continuationAfterNewLine(before) : "";
            state.setCursor(model.insertNewline(before));
            if (!indent.isEmpty()) {
                state.setCursor(model.insertText(state.getCursor(), indent));
            }
            if (!continuation.isEmpty()) {
                state.setCursor(model.insertText(state.getCursor(), continuation));
            }
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

    public boolean copyCurrentLine() {
        Position cursor = model.clamp(state.getCursor());
        int line = cursor.line();
        if (line < 0 || line >= model.lineCount()) {
            return false;
        }
        Position start = new Position(line, 0);
        Position end;
        if (line < model.lineCount() - 1) {
            end = new Position(line + 1, 0);
        } else {
            end = new Position(line, model.getLine(line).length());
        }
        clipboard = model.getTextRange(start, end);
        return !clipboard.isEmpty();
    }

    public boolean cutSelection() {
        preferredVisualColumn = -1;
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

    public boolean cutCurrentLine() {
        preferredVisualColumn = -1;
        Position cursor = model.clamp(state.getCursor());
        int line = cursor.line();
        if (line < 0 || line >= model.lineCount()) {
            return false;
        }
        Position start = new Position(line, 0);
        Position end;
        if (line < model.lineCount() - 1) {
            // Include trailing newline by cutting until start of next line.
            end = new Position(line + 1, 0);
        } else {
            end = new Position(line, model.getLine(line).length());
        }
        clipboard = model.getTextRange(start, end);
        applyEdit(EditKind.NONE, () -> {
            state.setCursor(model.deleteRange(start, end));
            state.clearSelection();
        });
        return true;
    }

    public boolean pasteClipboard() {
        preferredVisualColumn = -1;
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

    public boolean selectAll() {
        preferredVisualColumn = -1;
        int lines = model.lineCount();
        if (lines <= 0) {
            return false;
        }
        Position start = new Position(0, 0);
        int lastLine = lines - 1;
        Position end = new Position(lastLine, model.getLine(lastLine).length());
        if (start.equals(end)) {
            state.clearSelection();
            state.setCursor(start);
            return false;
        }
        breakCoalescing();
        state.setSelectionAnchor(start);
        state.setCursor(end);
        return true;
    }

    public boolean undo() {
        preferredVisualColumn = -1;
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
        preferredVisualColumn = -1;
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

    public boolean findNext(String query) {
        return findNext(query, SearchOptions.DEFAULT);
    }

    public boolean findNext(String query, SearchOptions options) {
        String needle = normalizeQuery(query);
        if (needle == null) {
            return false;
        }
        breakCoalescing();

        Position from = model.clamp(state.getCursor());
        SelectionRange selected = currentSelection();
        if (selected != null && matchesSelectedText(needle, selected, options)) {
            from = selected.end();
        }

        SearchMatch found = findForward(from, needle, options, true);
        if (found == null) {
            return false;
        }
        selectMatch(found.start(), found.length());
        return true;
    }

    public boolean findPrevious(String query) {
        return findPrevious(query, SearchOptions.DEFAULT);
    }

    public boolean findPrevious(String query, SearchOptions options) {
        String needle = normalizeQuery(query);
        if (needle == null) {
            return false;
        }
        breakCoalescing();

        Position from = model.clamp(state.getCursor());
        SelectionRange selected = currentSelection();
        if (selected != null && matchesSelectedText(needle, selected, options)) {
            from = selected.start();
        }

        SearchMatch found = findBackward(from, needle, options, true);
        if (found == null) {
            return false;
        }
        selectMatch(found.start(), found.length());
        return true;
    }

    public boolean replaceCurrentOrNext(String query, String replacement) {
        return replaceCurrentOrNext(query, replacement, SearchOptions.DEFAULT);
    }

    public boolean replaceCurrentOrNext(String query, String replacement, SearchOptions options) {
        String needle = normalizeQuery(query);
        if (needle == null) {
            return false;
        }
        String replacementText = replacement == null ? "" : replacement;

        SelectionRange selected = currentSelection();
        if (selected == null || !matchesSelectedText(needle, selected, options)) {
            if (!findNext(needle, options)) {
                return false;
            }
            selected = currentSelection();
            if (selected == null) {
                return false;
            }
        }

        Position rangeStart = selected.start();
        Position rangeEnd = selected.end();
        applyEdit(EditKind.NONE, () -> {
            state.setCursor(model.deleteRange(rangeStart, rangeEnd));
            state.setCursor(model.insertText(state.getCursor(), replacementText));
            state.clearSelection();
        });
        state.setSelectionAnchor(rangeStart);
        return true;
    }

    public int replaceAll(String query, String replacement) {
        return replaceAll(query, replacement, SearchOptions.DEFAULT);
    }

    public int replaceAll(String query, String replacement, SearchOptions options) {
        String needle = normalizeQuery(query);
        if (needle == null) {
            return 0;
        }
        String replacementText = replacement == null ? "" : replacement;
        int count = countOccurrencesInDocument(needle, options);
        if (count == 0) {
            return 0;
        }

        applyEdit(EditKind.NONE, () -> {
            List<String> lines = model.snapshotLines();
            List<String> replaced = new ArrayList<>(lines.size());
            for (String line : lines) {
                replaced.add(replaceAllInLine(line, needle, replacementText, options));
            }
            model.replaceLines(replaced);
            state.setCursor(model.clamp(state.getCursor()));
            state.clearSelection();
        });
        return count;
    }

    public void ensureCursorVisible(int contentRows) {
        ensureCursorVisible(contentRows, -1);
    }

    public void ensureCursorVisible(int contentRows, int contentCols) {
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

        if (contentCols <= 0) {
            return;
        }
        String line = model.getLine(p.line());
        int safeMargin = Math.max(0, Math.min(H_SCROLL_MARGIN, Math.max(0, contentCols / 3)));
        int cursorVisual = visualColumn(line, 0, p.col());
        int longestVisualWidth = model.maxVisualLineWidth(tabWidth);
        int maxLeftVisual = Math.max(0, longestVisualWidth - Math.max(1, contentCols));
        int leftVisual = Math.min(state.getLeftVisualColumn(), maxLeftVisual);
        state.setLeftVisualColumn(leftVisual);
        state.setLeftColumn(columnForVisualColumn(line, leftVisual));

        int leftVisibleBound = leftVisual + safeMargin;
        if (cursorVisual < leftVisibleBound) {
            int targetLeftVisual = Math.max(0, cursorVisual - safeMargin);
            targetLeftVisual = Math.min(targetLeftVisual, maxLeftVisual);
            state.setLeftVisualColumn(targetLeftVisual);
            state.setLeftColumn(columnForVisualColumn(line, targetLeftVisual));
            return;
        }

        int rightVisibleBound = leftVisual + Math.max(0, contentCols - 1 - safeMargin);
        if (cursorVisual > rightVisibleBound) {
            int targetLeftVisual = Math.max(0, cursorVisual - Math.max(0, contentCols - 1 - safeMargin));
            targetLeftVisual = Math.min(targetLeftVisual, maxLeftVisual);
            state.setLeftVisualColumn(targetLeftVisual);
            state.setLeftColumn(columnForVisualColumn(line, targetLeftVisual));
        }
    }

    private int visualColumn(String line, int startCol, int endCol) {
        int start = Math.max(0, Math.min(startCol, line.length()));
        int end = Math.max(start, Math.min(endCol, line.length()));
        int visual = 0;
        for (int i = start; i < end; i++) {
            char ch = line.charAt(i);
            if (ch == '\t') {
                visual += tabWidth - (visual % tabWidth);
            } else {
                visual++;
            }
        }
        return visual;
    }

    private int columnForVisualColumn(String line, int targetVisual) {
        if (targetVisual <= 0) {
            return 0;
        }
        int visual = 0;
        for (int i = 0; i < line.length(); i++) {
            int advance = line.charAt(i) == '\t'
                ? tabWidth - (visual % tabWidth)
                : 1;
            if (visual + advance > targetVisual) {
                return i;
            }
            visual += advance;
        }
        return line.length();
    }

    private static int firstNonWhitespaceColumn(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return 0;
    }

    private static int trailingTrimEndColumn(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private String indentForNewLine(Position cursor) {
        String line = model.getLine(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        int leading = 0;
        while (leading < line.length() && Character.isWhitespace(line.charAt(leading))) {
            leading++;
        }
        int keep = Math.min(leading, col);
        String baseIndent = keep <= 0 ? "" : line.substring(0, keep);
        if (shouldDedentFromClosing(line, col)) {
            baseIndent = removeOneIndentUnit(baseIndent, cursor.line());
        }
        if (shouldIncreaseIndent(line, col)) {
            return baseIndent + indentUnit(cursor.line(), baseIndent);
        }
        return baseIndent;
    }

    private boolean shouldDedentFromClosing(String line, int col) {
        if (line.isEmpty()) {
            return false;
        }
        int safeCol = Math.max(0, Math.min(col, line.length()));
        String before = line.substring(0, safeCol).stripTrailing();
        if (startsWithStandaloneClosing(before)) {
            return true;
        }
        // Cursor can be before the first token on a line that already starts with a closing brace.
        return safeCol <= firstNonWhitespaceColumn(line) && startsWithStandaloneClosing(line);
    }

    private boolean shouldIncreaseIndent(String line, int col) {
        String before = line.substring(0, col).stripTrailing();
        if (before.isEmpty()) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
        LanguageProfile profile = languageProfile();
        if (profile.bracketIndentEnabled() && (last == '{' || last == '[' || last == '(')) {
            return true;
        }
        if (last != ':') {
            return false;
        }
        return switch (profile.colonIndentMode()) {
            case PYTHON -> isPythonBlockIntro(before);
            case YAML -> isYamlBlockIntro(before);
            default -> false;
        };
    }

    private boolean isPythonBlockIntro(String before) {
        if (!before.endsWith(":") || before.endsWith("::") || before.indexOf('?') >= 0) {
            return false;
        }
        String head = before.substring(0, before.length() - 1).trim().toLowerCase(Locale.ROOT);
        if (head.isEmpty() || head.endsWith(";")) {
            return false;
        }
        if (head.startsWith("async ")) {
            String tail = head.substring("async ".length()).trim();
            return tail.startsWith("def ")
                || tail.startsWith("for ")
                || tail.startsWith("with ");
        }
        return head.startsWith("if ")
            || head.startsWith("elif ")
            || head.equals("else")
            || head.startsWith("for ")
            || head.startsWith("while ")
            || head.startsWith("def ")
            || head.startsWith("class ")
            || head.equals("try")
            || head.startsWith("except")
            || head.equals("finally")
            || head.startsWith("with ")
            || head.startsWith("match ")
            || head.startsWith("case ");
    }

    private boolean isYamlBlockIntro(String before) {
        if (!before.endsWith(":")) {
            return false;
        }
        String head = before.substring(0, before.length() - 1).stripTrailing();
        if (head.isEmpty() || head.startsWith("#")) {
            return false;
        }
        // Allow list-style mapping items: "- key:"
        if (head.startsWith("-")) {
            head = head.substring(1).stripLeading();
            if (head.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String continuationAfterNewLine(Position cursor) {
        LanguageProfile profile = languageProfile();
        String line = model.getLine(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        String beforeCursor = line.substring(0, col);
        return switch (profile) {
            case YAML -> yamlContinuationMarker(beforeCursor);
            case MARKDOWN -> markdownContinuationMarker(cursor.line(), beforeCursor);
            default -> "";
        };
    }

    private boolean shouldAutoPair(char ch) {
        if (!isOpeningPair(ch)) {
            return false;
        }
        Position cursor = model.clamp(state.getCursor());
        String line = model.getLine(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));

        if ((ch == '\'' || ch == '"') && col > 0 && line.charAt(col - 1) == '\\') {
            return false;
        }
        if (ch == '\'' && ((col > 0 && isWordChar(line.charAt(col - 1)))
            || (col < line.length() && isWordChar(line.charAt(col))))) {
            return false;
        }
        if (col >= line.length()) {
            return true;
        }
        char next = line.charAt(col);
        return Character.isWhitespace(next)
            || isClosingPair(next)
            || next == ','
            || next == ';'
            || next == ':';
    }

    private boolean shouldSkipOverClosing(char ch) {
        if (!isClosingPair(ch) || state.hasSelection()) {
            return false;
        }
        Position cursor = model.clamp(state.getCursor());
        String line = model.getLine(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        if (col >= line.length()) {
            return false;
        }
        return line.charAt(col) == ch;
    }

    private boolean shouldWrapSelectionWithPair(char ch) {
        return isOpeningPair(ch) && (ch != '\'' || canWrapWithSingleQuote());
    }

    private boolean canWrapWithSingleQuote() {
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        String selected = model.getTextRange(range.start(), range.end());
        return selected.indexOf('\'') < 0;
    }

    private void insertPair(char open) {
        Position cursor = model.clamp(state.getCursor());
        Position afterOpen = model.insertChar(cursor, open);
        model.insertChar(afterOpen, closingPairFor(open));
        state.setCursor(afterOpen);
    }

    private void wrapSelectionWithPair(char open) {
        SelectionRange range = currentSelection();
        if (range == null) {
            return;
        }
        char close = closingPairFor(open);
        String selected = model.getTextRange(range.start(), range.end());
        if (selected.isEmpty()) {
            return;
        }
        state.setCursor(model.deleteRange(range.start(), range.end()));
        state.setCursor(model.insertChar(state.getCursor(), open));
        Position wrappedStart = state.getCursor();
        state.setCursor(model.insertText(state.getCursor(), selected));
        Position wrappedEnd = state.getCursor();
        state.setCursor(model.insertChar(state.getCursor(), close));
        state.setSelectionAnchor(wrappedStart);
        state.setCursor(wrappedEnd);
    }

    private boolean deletePairAroundCursor() {
        Position cursor = model.clamp(state.getCursor());
        if (cursor.col() <= 0) {
            return false;
        }
        String line = model.getLine(cursor.line());
        if (cursor.col() >= line.length()) {
            return false;
        }
        char left = line.charAt(cursor.col() - 1);
        char right = line.charAt(cursor.col());
        if (!isMatchingPair(left, right)) {
            return false;
        }
        Position afterRightDelete = model.deleteForward(cursor);
        state.setCursor(model.deleteBackward(afterRightDelete));
        return true;
    }

    private static boolean isOpeningPair(char ch) {
        return ch == '(' || ch == '[' || ch == '{' || ch == '"' || ch == '\'';
    }

    private static boolean isClosingPair(char ch) {
        return ch == ')' || ch == ']' || ch == '}' || ch == '"' || ch == '\'';
    }

    private static char closingPairFor(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"' -> '"';
            case '\'' -> '\'';
            default -> open;
        };
    }

    private static boolean isMatchingPair(char left, char right) {
        return (left == '(' && right == ')')
            || (left == '[' && right == ']')
            || (left == '{' && right == '}')
            || (left == '"' && right == '"')
            || (left == '\'' && right == '\'');
    }

    private String yamlContinuationMarker(String beforeCursor) {
        int leading = leadingIndentLength(beforeCursor);
        if (leading >= beforeCursor.length()) {
            return "";
        }
        String rest = beforeCursor.substring(leading);
        if (!rest.startsWith("- ")) {
            return "";
        }
        if (rest.startsWith("- [ ] ") || rest.startsWith("- [x] ") || rest.startsWith("- [X] ")) {
            String tail = rest.substring(6).trim();
            return tail.isEmpty() ? "" : "- [ ] ";
        }
        String tail = rest.substring(2).trim();
        return tail.isEmpty() ? "" : "- ";
    }

    private String markdownContinuationMarker(int lineIndex, String beforeCursor) {
        if (isInsideMarkdownFence(lineIndex)) {
            return "";
        }
        int leading = leadingIndentLength(beforeCursor);
        if (leading >= beforeCursor.length()) {
            return "";
        }
        String rest = beforeCursor.substring(leading);

        String unordered = markdownUnorderedContinuation(rest);
        if (!unordered.isEmpty()) {
            return unordered;
        }
        return markdownOrderedContinuation(rest);
    }

    private String markdownUnorderedContinuation(String rest) {
        if (rest.startsWith("- [ ] ") || rest.startsWith("- [x] ") || rest.startsWith("- [X] ")) {
            String tail = rest.substring(6).trim();
            return tail.isEmpty() ? "" : "- [ ] ";
        }
        if (rest.length() < 2) {
            return "";
        }
        char marker = rest.charAt(0);
        if (marker != '-' && marker != '*' && marker != '+') {
            return "";
        }
        if (rest.charAt(1) != ' ') {
            return "";
        }
        String tail = rest.substring(2).trim();
        return tail.isEmpty() ? "" : marker + " ";
    }

    private String markdownOrderedContinuation(String rest) {
        int sep = -1;
        char delimiter = '.';
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '.' || ch == ')') {
                sep = i;
                delimiter = ch;
                break;
            }
            if (!Character.isDigit(ch)) {
                return "";
            }
        }
        if (sep <= 0 || sep >= rest.length() - 1) {
            return "";
        }
        for (int i = 0; i < sep; i++) {
            if (!Character.isDigit(rest.charAt(i))) {
                return "";
            }
        }
        if (rest.charAt(sep + 1) != ' ') {
            return "";
        }
        String tail = rest.substring(sep + 2).trim();
        if (tail.isEmpty()) {
            return "";
        }
        try {
            int number = Integer.parseInt(rest.substring(0, sep));
            return Math.max(1, number + 1) + String.valueOf(delimiter) + " ";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private boolean isInsideMarkdownFence(int lineIndex) {
        String activeFence = null;
        for (int i = 0; i <= lineIndex; i++) {
            String line = model.getLine(i).stripLeading();
            String fence = fenceMarker(line);
            if (fence == null) {
                continue;
            }
            if (activeFence == null) {
                activeFence = fence;
                continue;
            }
            if (!activeFence.isEmpty() && activeFence.charAt(0) == fence.charAt(0) && fence.length() >= activeFence.length()) {
                activeFence = null;
            }
        }
        return activeFence != null;
    }

    private static String fenceMarker(String line) {
        if (line == null || line.length() < 3) {
            return null;
        }
        char ch = line.charAt(0);
        if (ch != '`' && ch != '~') {
            return null;
        }
        int count = 1;
        while (count < line.length() && line.charAt(count) == ch) {
            count++;
        }
        if (count < 3) {
            return null;
        }
        return String.valueOf(ch).repeat(count);
    }

    private boolean startsWithStandaloneClosing(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int first = firstNonWhitespaceColumn(text);
        if (first >= text.length()) {
            return false;
        }
        if (!isClosingBracket(text.charAt(first))) {
            return false;
        }
        for (int i = first + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (isClosingBracket(ch) || ch == ';' || ch == ',') {
                continue;
            }
            if (startsCommentAt(text, i)) {
                return true;
            }
            return false;
        }
        return true;
    }

    private static boolean startsCommentAt(String text, int at) {
        return text.startsWith("//", at)
            || text.startsWith("#", at)
            || text.startsWith("/*", at);
    }

    private static boolean isClosingBracket(char ch) {
        return ch == '}' || ch == ']' || ch == ')';
    }

    private String indentUnit(int lineIndex, String currentIndent) {
        return switch (tabInsertMode) {
            case ALWAYS_TAB -> "\t";
            case ALWAYS_SPACES_4 -> "    ";
            case ALWAYS_SPACES_2 -> "  ";
            case AUTO -> autoIndentUnit(lineIndex, currentIndent);
        };
    }

    private String autoIndentUnit(int lineIndex, String currentIndent) {
        int tabs = 0;
        int spaces = 0;
        for (int i = 0; i < currentIndent.length(); i++) {
            char ch = currentIndent.charAt(i);
            if (ch == '\t') {
                tabs++;
            } else if (ch == ' ') {
                spaces++;
            }
        }
        if (tabs > 0 && spaces == 0) {
            return "\t";
        }
        if (tabs > 0 && spaces > 0) {
            return currentIndent.charAt(currentIndent.length() - 1) == '\t'
                ? "\t"
                : " ".repeat(detectIndentSize(lineIndex, currentIndent));
        }
        if (preferTabsByContext(lineIndex)) {
            return "\t";
        }
        return " ".repeat(detectIndentSize(lineIndex, currentIndent));
    }

    private int detectIndentSize(int lineIndex, String currentIndent) {
        int fallback = tabWidth <= 2 ? 2 : 4;
        int score2 = 0;
        int score4 = 0;
        int observed = 0;
        int prevIndent = -1;
        int start = Math.max(0, lineIndex - 256);
        int end = Math.min(model.lineCount() - 1, lineIndex);
        for (int i = start; i <= end; i++) {
            String text = model.getLine(i);
            int leadingSpaces = leadingSpacesOnly(text);
            if (leadingSpaces < 0 || leadingSpaces >= text.length()) {
                continue;
            }
            if (leadingSpaces > 0) {
                observed++;
                if (leadingSpaces % 2 == 0) {
                    score2++;
                }
                if (leadingSpaces % 4 == 0) {
                    score4++;
                }
            }
            if (prevIndent >= 0) {
                int delta = leadingSpaces - prevIndent;
                if (delta > 0) {
                    observed++;
                    if (delta % 2 == 0) {
                        score2++;
                    }
                    if (delta % 4 == 0) {
                        score4++;
                    }
                }
            }
            prevIndent = leadingSpaces;
        }
        if (observed > 0) {
            if (score4 > 0 && score4 * 2 >= observed) {
                return 4;
            }
            if (score2 > 0) {
                return 2;
            }
        }
        int trailingSpaces = trailingSpaces(currentIndent);
        if (trailingSpaces >= 4 && trailingSpaces % 4 == 0) {
            return 4;
        }
        if (trailingSpaces >= 2 && trailingSpaces % 2 == 0) {
            return 2;
        }
        int docFallback = detectIndentFromDocumentStart();
        if (docFallback > 0) {
            return docFallback;
        }
        return fallback;
    }

    private int detectIndentFromDocumentStart() {
        int score2 = 0;
        int score4 = 0;
        int observed = 0;
        int limit = Math.min(model.lineCount(), 256);
        for (int i = 0; i < limit; i++) {
            String text = model.getLine(i);
            int leadingSpaces = leadingSpacesOnly(text);
            if (leadingSpaces <= 0 || leadingSpaces >= text.length()) {
                continue;
            }
            observed++;
            if (leadingSpaces % 2 == 0) {
                score2++;
            }
            if (leadingSpaces % 4 == 0) {
                score4++;
            }
        }
        if (observed == 0) {
            return -1;
        }
        if (score4 > 0 && score4 * 2 >= observed) {
            return 4;
        }
        if (score2 > 0) {
            return 2;
        }
        return -1;
    }

    private boolean preferTabsByContext(int lineIndex) {
        int tabIndented = 0;
        int spaceIndented = 0;
        int start = Math.max(0, lineIndex - 256);
        int end = Math.min(model.lineCount() - 1, lineIndex + 256);
        for (int i = start; i <= end; i++) {
            String text = model.getLine(i);
            if (text.isBlank()) {
                continue;
            }
            int first = firstNonWhitespaceColumn(text);
            if (first <= 0 || first >= text.length()) {
                continue;
            }
            char indentLead = text.charAt(0);
            if (indentLead == '\t') {
                tabIndented++;
            } else if (indentLead == ' ') {
                spaceIndented++;
            }
        }
        return tabIndented > spaceIndented && tabIndented > 0;
    }

    private static int leadingSpacesOnly(String line) {
        int spaces = 0;
        while (spaces < line.length()) {
            char ch = line.charAt(spaces);
            if (ch == ' ') {
                spaces++;
                continue;
            }
            if (ch == '\t') {
                return -1;
            }
            break;
        }
        return spaces;
    }

    private static int trailingSpaces(String text) {
        int spaces = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) != ' ') {
                break;
            }
            spaces++;
        }
        return spaces;
    }

    private boolean deleteIndentUnitBackwardIfAny() {
        Position cursor = model.clamp(state.getCursor());
        if (cursor.col() <= 0) {
            return false;
        }
        String line = model.getLine(cursor.line());
        int col = Math.min(cursor.col(), line.length());
        if (!isLeadingWhitespaceUntil(line, col)) {
            return false;
        }
        String currentIndent = line.substring(0, col);
        String reduced = removeOneIndentUnit(currentIndent, cursor.line());
        int drop = currentIndent.length() - reduced.length();
        if (drop <= 0) {
            return false;
        }
        int fromCol = col - drop;
        model.deleteRange(new Position(cursor.line(), fromCol), new Position(cursor.line(), col));
        state.setCursor(new Position(cursor.line(), fromCol));
        return true;
    }

    private static boolean isLeadingWhitespaceUntil(String line, int col) {
        int limit = Math.min(Math.max(col, 0), line.length());
        for (int i = 0; i < limit; i++) {
            char ch = line.charAt(i);
            if (ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return true;
    }

    private String removeOneIndentUnit(String indent, int lineIndex) {
        if (indent.isEmpty()) {
            return indent;
        }
        if (tabInsertMode == TabInsertMode.ALWAYS_TAB) {
            int end = indent.length();
            if (indent.charAt(end - 1) == '\t') {
                return indent.substring(0, end - 1);
            }
            return indent.substring(0, end - 1);
        }
        if (tabInsertMode == TabInsertMode.ALWAYS_SPACES_4 || tabInsertMode == TabInsertMode.ALWAYS_SPACES_2) {
            return removeForcedSpacesIndentUnit(indent, tabInsertMode == TabInsertMode.ALWAYS_SPACES_2 ? 2 : 4);
        }
        int end = indent.length();
        char last = indent.charAt(end - 1);
        if (last == '\t') {
            return indent.substring(0, end - 1);
        }
        int spacesInUnit = detectIndentSize(lineIndex, indent);
        int spacesToDrop = 0;
        while (end - spacesToDrop - 1 >= 0
            && indent.charAt(end - spacesToDrop - 1) == ' '
            && spacesToDrop < spacesInUnit) {
            spacesToDrop++;
        }
        if (spacesToDrop > 0) {
            return indent.substring(0, end - spacesToDrop);
        }
        return indent.substring(0, end - 1);
    }

    private static String removeForcedSpacesIndentUnit(String indent, int spacesInUnit) {
        if (indent.isEmpty()) {
            return indent;
        }
        int end = indent.length();
        char last = indent.charAt(end - 1);
        if (last == '\t') {
            return indent.substring(0, end - 1);
        }
        int spacesToDrop = 0;
        while (end - spacesToDrop - 1 >= 0
            && indent.charAt(end - spacesToDrop - 1) == ' '
            && spacesToDrop < spacesInUnit) {
            spacesToDrop++;
        }
        if (spacesToDrop > 0) {
            return indent.substring(0, end - spacesToDrop);
        }
        return indent.substring(0, end - 1);
    }

    private void maybeAutoOutdentKeywordLine() {
        if (!isAutoIndentActive()) {
            return;
        }
        LanguageProfile profile = languageProfile();
        if (!profile.keywordDedentEnabled()) {
            return;
        }
        Position cursor = model.clamp(state.getCursor());
        int lineIndex = cursor.line();
        String line = model.getLine(lineIndex);
        int cursorCol = Math.max(0, Math.min(cursor.col(), line.length()));
        int leading = leadingIndentLength(line);
        if (leading <= 0 || cursorCol <= leading) {
            return;
        }
        int prevLine = previousNonBlankLine(lineIndex - 1);
        if (prevLine < 0) {
            return;
        }
        String previous = model.getLine(prevLine);
        int prevLeading = leadingIndentLength(previous);
        if (prevLeading != leading) {
            return;
        }
        String currentIndent = line.substring(0, leading);
        if (!previous.substring(0, prevLeading).equals(currentIndent)) {
            return;
        }
        if (!startsWithDedentKeyword(line, leading, cursorCol, profile)) {
            return;
        }
        String reduced = removeOneIndentUnit(currentIndent, lineIndex);
        int drop = currentIndent.length() - reduced.length();
        if (drop <= 0) {
            return;
        }
        model.deleteRange(new Position(lineIndex, 0), new Position(lineIndex, drop));
        state.setCursor(new Position(lineIndex, Math.max(0, cursorCol - drop)));
    }

    private boolean startsWithDedentKeyword(String line, int leading, int cursorCol, LanguageProfile profile) {
        int tokenEnd = leading;
        while (tokenEnd < line.length() && isWordChar(line.charAt(tokenEnd))) {
            tokenEnd++;
        }
        if (tokenEnd <= leading || cursorCol < tokenEnd) {
            return false;
        }
        String keyword = line.substring(leading, tokenEnd).toLowerCase(Locale.ROOT);
        if (!profile.dedentKeywords().contains(keyword)) {
            return false;
        }
        for (int i = tokenEnd; i < cursorCol; i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int leadingIndentLength(String line) {
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            i++;
        }
        return i;
    }

    private int previousNonBlankLine(int fromLine) {
        for (int i = fromLine; i >= 0; i--) {
            if (!model.getLine(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private LanguageProfile languageProfile() {
        return LanguageProfiles.resolve(state.getCurrentFile());
    }

    private boolean isAutoIndentActive() {
        return autoIndentEnabled;
    }

    private boolean isAutoPairsActive() {
        return autoPairsEnabled;
    }

    private boolean isAutoListActive() {
        return autoListEnabled;
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
            state.getTopWrapSegment(),
            state.getLeftColumn(),
            state.getLeftVisualColumn(),
            state.isDirty(),
            state.getSelectionAnchor()
        );
    }

    private void restoreSnapshot(Snapshot snapshot) {
        model.replaceLines(snapshot.lines());
        state.setCursor(model.clamp(snapshot.cursor()));
        state.setTopLine(snapshot.topLine());
        state.setTopWrapSegment(snapshot.topWrapSegment());
        state.setLeftColumn(snapshot.leftColumn());
        state.setLeftVisualColumn(snapshot.leftVisualColumn());
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
        int topWrapSegment,
        int leftColumn,
        int leftVisualColumn,
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

    private String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        return query;
    }

    private SearchMatch findForward(Position from, String needle, SearchOptions options, boolean wrap) {
        int lineCount = model.lineCount();
        for (int line = from.line(); line < lineCount; line++) {
            String text = model.getLine(line);
            int startCol = line == from.line() ? Math.min(from.col(), text.length()) : 0;
            SearchMatch match = findInLineForward(line, text, needle, options, startCol);
            if (match != null) {
                return match;
            }
        }
        if (!wrap) {
            return null;
        }
        for (int line = 0; line <= from.line(); line++) {
            String text = model.getLine(line);
            int limit = line == from.line() ? Math.min(from.col(), text.length()) : text.length();
            SearchMatch match = findInLineForward(line, text, needle, options, 0);
            if (match != null && match.start().col() < limit) {
                return match;
            }
        }
        return null;
    }

    private SearchMatch findBackward(Position from, String needle, SearchOptions options, boolean wrap) {
        for (int line = from.line(); line >= 0; line--) {
            String text = model.getLine(line);
            int endExclusive = line == from.line() ? Math.min(from.col(), text.length()) : text.length();
            SearchMatch match = findInLineBackward(line, text, needle, options, endExclusive);
            if (match != null) {
                return match;
            }
        }
        if (!wrap) {
            return null;
        }
        for (int line = model.lineCount() - 1; line >= from.line(); line--) {
            String text = model.getLine(line);
            int startExclusive = line == from.line() ? Math.min(from.col(), text.length()) : -1;
            SearchMatch match = findInLineBackward(line, text, needle, options, text.length());
            if (match != null && match.start().col() > startExclusive) {
                return match;
            }
        }
        return null;
    }

    private void selectMatch(Position start, int length) {
        Position safeStart = model.clamp(start);
        int maxCol = model.getLine(safeStart.line()).length();
        int endCol = Math.min(maxCol, safeStart.col() + Math.max(length, 0));
        state.setSelectionAnchor(safeStart);
        state.setCursor(new Position(safeStart.line(), endCol));
    }

    private int countOccurrencesInDocument(String needle, SearchOptions options) {
        int count = 0;
        for (int line = 0; line < model.lineCount(); line++) {
            String text = model.getLine(line);
            count += findAllMatches(text, needle, options).size();
        }
        return count;
    }

    private static String replaceAllInLine(String text, String needle, String replacement, SearchOptions options) {
        List<LineMatch> matches = findAllMatches(text, needle, options);
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + matches.size() * Math.max(0, replacement.length() - needle.length()));
        int at = 0;
        for (LineMatch match : matches) {
            out.append(text, at, match.start());
            out.append(replacement);
            at = match.end();
        }
        out.append(text, at, text.length());
        return out.toString();
    }

    private boolean matchesSelectedText(String query, SelectionRange selected, SearchOptions options) {
        String selectedText = model.getTextRange(selected.start(), selected.end());
        if (selectedText.isEmpty()) {
            return false;
        }
        String normalizedQuery = options.caseInsensitive() ? query.toLowerCase(Locale.ROOT) : query;
        String normalizedSelected = options.caseInsensitive() ? selectedText.toLowerCase(Locale.ROOT) : selectedText;
        return switch (options.matchMode()) {
            case SUBSTRING -> normalizedSelected.equals(normalizedQuery);
            case WHOLE_WORD -> normalizedSelected.equals(normalizedQuery);
            case WILDCARD -> wildcardPattern(normalizedQuery).matcher(normalizedSelected).matches();
        };
    }

    private SearchMatch findInLineForward(int line, String text, String needle, SearchOptions options, int fromCol) {
        for (LineMatch match : findAllMatches(text, needle, options)) {
            if (match.start() >= fromCol) {
                return new SearchMatch(new Position(line, match.start()), match.end() - match.start());
            }
        }
        return null;
    }

    private SearchMatch findInLineBackward(int line, String text, String needle, SearchOptions options, int beforeCol) {
        List<LineMatch> matches = findAllMatches(text, needle, options);
        SearchMatch found = null;
        for (LineMatch match : matches) {
            if (match.start() < beforeCol) {
                found = new SearchMatch(new Position(line, match.start()), match.end() - match.start());
            } else {
                break;
            }
        }
        return found;
    }

    private static List<LineMatch> findAllMatches(String text, String query, SearchOptions options) {
        String haystack = options.caseInsensitive() ? text.toLowerCase(Locale.ROOT) : text;
        String needle = options.caseInsensitive() ? query.toLowerCase(Locale.ROOT) : query;

        return switch (options.matchMode()) {
            case SUBSTRING -> findLiteralMatches(haystack, needle, false);
            case WHOLE_WORD -> findLiteralMatches(haystack, needle, true);
            case WILDCARD -> findWildcardMatches(haystack, needle);
        };
    }

    private static List<LineMatch> findLiteralMatches(String text, String needle, boolean wholeWord) {
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

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end == text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static int charGroup(char ch) {
        if (Character.isWhitespace(ch)) {
            return 0;
        }
        return isWordChar(ch) ? 1 : 2;
    }

    private record SearchMatch(Position start, int length) {
    }

    private record LineMatch(int start, int end) {
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

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
        int left = state.getLeftColumn();
        if (p.col() < left) {
            state.setLeftColumn(p.col());
        } else if (p.col() >= left + contentCols) {
            state.setLeftColumn(p.col() - contentCols + 1);
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

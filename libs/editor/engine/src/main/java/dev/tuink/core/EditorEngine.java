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
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

/**
 * Core editing commands and viewport management.
 */
public class EditorEngine {
    private static final int MAX_HISTORY = 500;
    private static final long COALESCE_WINDOW_NANOS = 700_000_000L;
    private static final int GUTTER_DIFF_LARGE_FILE_THRESHOLD_LINES = 20_000;
    private static final long GUTTER_DIFF_MIN_REBUILD_INTERVAL_NANOS = 50_000_000L;
    private static final long GUTTER_DIFF_MIN_REBUILD_INTERVAL_LARGE_NANOS = 150_000_000L;
    private static final long GUTTER_DIFF_MAX_REBUILD_INTERVAL_NANOS = 350_000_000L;
    private static final int GUTTER_DIFF_DURATION_FACTOR = 2;
    private static final DiffAlgorithm GUTTER_DIFF_ALGORITHM_HISTOGRAM =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);
    private static final DiffAlgorithm GUTTER_DIFF_ALGORITHM_MYERS =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS);
    private static final LineSequenceComparator GUTTER_SEQUENCE_COMPARATOR = new LineSequenceComparator();
    private final TextModel model;
    private final EditorState state;
    private final Deque<HistoryEntry> undoStack = new ArrayDeque<>();
    private final Deque<HistoryEntry> redoStack = new ArrayDeque<>();
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
    private GutterDiffMode gutterDiffMode = GutterDiffMode.MYERS;
    private List<String> openBaselineLines = List.of("");
    private List<String> savedBaselineLines = List.of("");
    private final List<GutterDiffFlag> savedDiffFlags = new ArrayList<>();
    private final List<Boolean> savedDeletedMarkers = new ArrayList<>();
    private final List<GutterDiffFlag> openDiffFlags = new ArrayList<>();
    private final List<Boolean> openDeletedMarkers = new ArrayList<>();
    private long gutterDiffComputedRevision = Long.MIN_VALUE;
    private long gutterDiffLastRebuildAtNanos;
    private long gutterDiffLastRebuildDurationNanos;
    private boolean gutterDiffPendingRebuild;

    public EditorEngine(TextModel model, EditorState state) {
        this.model = model;
        this.state = state;
        resetGutterDiffBaselinesOnOpen();
    }

    public TextModel model() {
        return model;
    }

    public EditorState state() {
        return state;
    }

    public RecoverySnapshot captureRecoverySnapshot() {
        return RecoverySnapshot.capture(model, state);
    }

    public void restoreRecoverySnapshot(RecoverySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.restore(model, state);
        undoStack.clear();
        redoStack.clear();
        breakCoalescing();
        resetGutterDiffBaselinesOnOpen();
    }

    public GutterDiffMarker gutterDiffMarkerForLine(int lineIndex) {
        if (gutterDiffMode == GutterDiffMode.DISABLED) {
            return GutterDiffMarker.NONE;
        }
        ensureGutterDiffUpToDate();
        if (lineIndex < 0 || lineIndex >= model.lineCount()) {
            return GutterDiffMarker.NONE;
        }
        if (lineIndex < savedDeletedMarkers.size() && Boolean.TRUE.equals(savedDeletedMarkers.get(lineIndex))) {
            return new GutterDiffMarker(GutterDiffFlag.DELETED, false);
        }
        if (lineIndex < savedDiffFlags.size()) {
            GutterDiffFlag direct = savedDiffFlags.get(lineIndex);
            if (direct != null && direct != GutterDiffFlag.NONE) {
                return new GutterDiffMarker(direct, false);
            }
        }
        if (lineIndex < openDeletedMarkers.size() && Boolean.TRUE.equals(openDeletedMarkers.get(lineIndex))) {
            return new GutterDiffMarker(GutterDiffFlag.DELETED, true);
        }
        if (lineIndex < openDiffFlags.size()) {
            GutterDiffFlag direct = openDiffFlags.get(lineIndex);
            if (direct != null && direct != GutterDiffFlag.NONE) {
                return new GutterDiffMarker(direct, true);
            }
        }
        return GutterDiffMarker.NONE;
    }

    public GutterDiffFlag gutterDiffFlagForLine(int lineIndex) {
        return gutterDiffMarkerForLine(lineIndex).flag();
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

    public void setGutterDiffMode(GutterDiffMode mode) {
        GutterDiffMode nextMode = mode == null ? GutterDiffMode.MYERS : mode;
        if (gutterDiffMode == nextMode) {
            return;
        }
        gutterDiffMode = nextMode;
        if (gutterDiffMode == GutterDiffMode.DISABLED) {
            gutterDiffComputedRevision = model.revision();
            gutterDiffPendingRebuild = false;
        } else {
            gutterDiffComputedRevision = Long.MIN_VALUE;
            gutterDiffPendingRebuild = true;
        }
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
        resetGutterDiffBaselinesOnOpen();
    }

    public void save() throws IOException {
        if (state.getCurrentFile() == null) {
            throw new IOException("No file selected");
        }
        model.save(state.getCurrentFile(), state.getFileEncoding(), state.getLineSeparator());
        state.setDirty(false);
        breakCoalescing();
        resetGutterDiffSavedBaseline();
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
            String line = model.getLine(p.line());
            moveCursor(new Position(p.line(), DisplayText.previousBoundary(line, p.col())), selecting, p);
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
            String line = model.getLine(p.line());
            moveCursor(new Position(p.line(), DisplayText.nextBoundary(line, p.col())), selecting, p);
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
        int target = DisplayText.floorBoundary(text, col);
        while (target > 0) {
            int previousBoundary = DisplayText.previousBoundary(text, target);
            if (!isWhitespaceGrapheme(text, previousBoundary, target)) {
                break;
            }
            target = previousBoundary;
        }
        if (target > 0) {
            int previousBoundary = DisplayText.previousBoundary(text, target);
            boolean word = isWordGrapheme(text, previousBoundary, target);
            while (target > 0) {
                previousBoundary = DisplayText.previousBoundary(text, target);
                if (isWhitespaceGrapheme(text, previousBoundary, target)
                    || isWordGrapheme(text, previousBoundary, target) != word) {
                    break;
                }
                target = previousBoundary;
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
        int target = DisplayText.ceilBoundary(text, col);
        while (target < text.length()) {
            int nextBoundary = DisplayText.nextBoundary(text, target);
            if (!isWhitespaceGrapheme(text, target, nextBoundary)) {
                break;
            }
            target = nextBoundary;
        }
        if (target < text.length()) {
            int nextBoundary = DisplayText.nextBoundary(text, target);
            boolean word = isWordGrapheme(text, target, nextBoundary);
            while (target < text.length()) {
                nextBoundary = DisplayText.nextBoundary(text, target);
                if (isWhitespaceGrapheme(text, target, nextBoundary)
                    || isWordGrapheme(text, target, nextBoundary) != word) {
                    break;
                }
                target = nextBoundary;
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
        int requestedColumn = Math.min(Math.max(0, p.col()), text.length() - 1);
        int pivot = DisplayText.floorBoundary(text, requestedColumn);
        int pivotEnd = DisplayText.nextBoundary(text, pivot);
        int group = graphemeGroup(text, pivot, pivotEnd);
        int start = pivot;
        while (start > 0) {
            int previous = DisplayText.previousBoundary(text, start);
            if (graphemeGroup(text, previous, start) != group) {
                break;
            }
            start = previous;
        }
        int end = pivotEnd;
        while (end < text.length()) {
            int next = DisplayText.nextBoundary(text, end);
            if (graphemeGroup(text, end, next) != group) {
                break;
            }
            end = next;
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
        String targetText = model.getLine(targetLine);
        int col = DisplayText.floorBoundary(targetText, Math.min(p.col(), targetText.length()));
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
        String targetText = model.getLine(targetLine);
        int col = DisplayText.floorBoundary(targetText, Math.min(p.col(), targetText.length()));
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
        if (isAutoPairsActive() && state.hasSelection() && shouldWrapSelectionWithPair(ch)) {
            applyEdit(EditKind.NONE, () -> wrapSelectionWithPair(ch));
            return;
        }

        ViewState before = captureViewState();
        List<EditOp.ReplaceRange> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();

        if (isAutoPairsActive() && shouldAutoPair(ch)) {
            String paired = String.valueOf(ch) + closingPairFor(ch);
            Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, paired);
            state.setCursor(new Position(afterInsert.line(), Math.max(0, afterInsert.col() - 1)));
        } else {
            Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, String.valueOf(ch));
            state.setCursor(afterInsert);
            EditOp.ReplaceRange outdent = maybeAutoOutdentKeywordLineOp();
            if (outdent != null) {
                ops.add(outdent);
            }
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
    }

    /** Inserts one decoded Unicode input event without splitting supplementary characters. */
    public void insertInput(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.length() == 1 && !Character.isSurrogate(text.charAt(0))) {
            insertChar(text.charAt(0));
            return;
        }

        preferredVisualColumn = -1;
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<EditOp.ReplaceRange> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, text);
        state.setCursor(afterInsert);
        EditOp.ReplaceRange outdent = maybeAutoOutdentKeywordLineOp();
        if (outdent != null) {
            ops.add(outdent);
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
    }

    public void insertTab() {
        preferredVisualColumn = -1;
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<EditOp.ReplaceRange> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position cursor = model.clamp(rangeStart);
        String line = model.getLine(cursor.line());
        int col = Math.min(cursor.col(), line.length());
        String indentPrefix = line.substring(0, Math.min(col, leadingIndentLength(line)));
        String tabText = indentUnit(cursor.line(), indentPrefix);
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, tabText);
        state.setCursor(afterInsert);
        EditOp.ReplaceRange outdent = maybeAutoOutdentKeywordLineOp();
        if (outdent != null) {
            ops.add(outdent);
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
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
        ViewState before = captureViewState();
        List<EditOp.ReplaceRange> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position at = model.clamp(rangeStart);
        String indent = isAutoIndentActive() ? indentForNewLine(at) : "";
        String continuation = isAutoListActive() ? continuationAfterNewLine(at) : "";
        String insertText = "\n" + indent + continuation;
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, insertText);
        state.setCursor(afterInsert);
        state.clearSelection();
        commitOperationEdit(EditKind.NONE, before, ops);
    }

    public LineCommentResult toggleLineComment() {
        preferredVisualColumn = -1;
        CommentSyntaxProfile syntax = LanguageProfiles.resolveCommentSyntax(state.getCurrentFile());
        String marker = syntax.lineCommentPrefix();
        if (marker == null) {
            return new LineCommentResult(LineCommentAction.UNSUPPORTED, 0);
        }

        Position cursorBefore = model.clamp(state.getCursor());
        Position anchorBefore = state.getSelectionAnchor() == null
            ? null
            : model.clamp(state.getSelectionAnchor());
        SelectionRange selection = currentSelection();
        int firstLine = cursorBefore.line();
        int lastLine = cursorBefore.line();
        if (selection != null) {
            firstLine = selection.start().line();
            lastLine = selection.end().line();
            if (lastLine > firstLine && selection.end().col() == 0) {
                lastLine--;
            }
        }

        List<Integer> nonBlankLines = new ArrayList<>();
        for (int lineIndex = firstLine; lineIndex <= lastLine; lineIndex++) {
            if (!model.getLine(lineIndex).isBlank()) {
                nonBlankLines.add(lineIndex);
            }
        }
        if (nonBlankLines.isEmpty()) {
            return new LineCommentResult(LineCommentAction.NO_CONTENT, 0);
        }

        boolean uncomment = true;
        for (int lineIndex : nonBlankLines) {
            if (lineCommentColumn(model.getLine(lineIndex), marker, lineIndex) < 0) {
                uncomment = false;
                break;
            }
        }

        ViewState before = captureViewState();
        List<EditOp.ReplaceRange> ops = new ArrayList<>(nonBlankLines.size());
        List<LineCommentEdit> positionEdits = new ArrayList<>(nonBlankLines.size());
        if (uncomment) {
            for (int lineIndex : nonBlankLines) {
                String line = model.getLine(lineIndex);
                int column = lineCommentColumn(line, marker, lineIndex);
                int removeLength = marker.length();
                int afterMarker = column + removeLength;
                if (afterMarker < line.length() && line.charAt(afterMarker) == ' ') {
                    removeLength++;
                }
                applyReplaceAndTrack(
                    ops,
                    new Position(lineIndex, column),
                    new Position(lineIndex, column + removeLength),
                    ""
                );
                positionEdits.add(new LineCommentEdit(lineIndex, column, removeLength, 0));
            }
        } else {
            int commonIndent = commonIndentLength(nonBlankLines);
            String inserted = marker + " ";
            for (int lineIndex : nonBlankLines) {
                Position at = new Position(lineIndex, commonIndent);
                applyReplaceAndTrack(ops, at, at, inserted);
                positionEdits.add(new LineCommentEdit(lineIndex, commonIndent, 0, inserted.length()));
            }
        }

        state.setCursor(transformPosition(cursorBefore, positionEdits));
        if (anchorBefore != null) {
            state.setSelectionAnchor(transformPosition(anchorBefore, positionEdits));
        }
        commitOperationEdit(EditKind.NONE, before, ops);
        return new LineCommentResult(
            uncomment ? LineCommentAction.UNCOMMENTED : LineCommentAction.COMMENTED,
            nonBlankLines.size()
        );
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
        HistoryEntry previous = undoStack.pop();
        previous.undo();
        pushWithLimit(redoStack, previous);
        return true;
    }

    public boolean redo() {
        preferredVisualColumn = -1;
        if (redoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        HistoryEntry next = redoStack.pop();
        next.redo();
        pushWithLimit(undoStack, next);
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
        ensureCursorVisible(contentRows, -1, false, false);
    }

    public void ensureCursorVisible(int contentRows, int contentCols) {
        ensureCursorVisible(contentRows, contentCols, false, false);
    }

    public void ensureCursorVisible(
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        if (contentRows <= 0) {
            return;
        }

        Position p = model.clamp(state.getCursor());
        String cursorLine = model.getLine(p.line());
        p = new Position(p.line(), DisplayText.floorBoundary(cursorLine, p.col()));

        if (wrapLines) {
            int width = Math.max(1, contentCols);
            List<Integer> starts = model.wrapStarts(p.line(), width, wrapByWords, tabWidth);
            int cursorSegment = 0;
            for (int i = 1; i < starts.size(); i++) {
                if (p.col() < starts.get(i)) {
                    break;
                }
                cursorSegment = i;
            }
            int cursorWrappedOffset = model.wrappedRowsBefore(p.line(), width, wrapByWords, tabWidth)
                + cursorSegment;
            EditorViewportBehavior.WrapTop normalizedTop = EditorViewportBehavior.normalizeWrapTop(
                model,
                state.getTopLine(),
                state.getTopWrapSegment(),
                width,
                wrapByWords,
                tabWidth
            );
            int topWrappedOffset = EditorViewportBehavior.wrappedOffsetForWrapTop(
                model,
                normalizedTop,
                width,
                wrapByWords,
                tabWidth
            );
            int totalWrappedRows = model.totalWrappedRows(width, wrapByWords, tabWidth);
            int maxTop = Math.max(0, totalWrappedRows - contentRows);
            int targetTop = topWrappedOffset;
            if (cursorWrappedOffset < topWrappedOffset) {
                targetTop = cursorWrappedOffset;
            } else if (cursorWrappedOffset >= topWrappedOffset + contentRows) {
                targetTop = cursorWrappedOffset - contentRows + 1;
            }
            targetTop = Math.max(0, Math.min(targetTop, maxTop));
            EditorViewportBehavior.applyWrapTop(
                state,
                EditorViewportBehavior.wrapTopFromWrappedOffset(
                    model,
                    targetTop,
                    width,
                    wrapByWords,
                    tabWidth
                )
            );
            state.setLeftColumn(0);
            state.setLeftVisualColumn(0);
            return;
        }

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
        int requiredVisualWidth = Math.max(model.maxVisualLineWidth(tabWidth), cursorVisual + 1);
        int maxLeftVisual = Math.max(0, requiredVisualWidth - Math.max(1, contentCols));
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
        return ViewportMetrics.textToVisualColumn(line, startCol, endCol, tabWidth);
    }

    private int columnForVisualColumn(String line, int targetVisual) {
        return ViewportMetrics.visualToTextColumn(line, 0, targetVisual, tabWidth);
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

    private EditOp.ReplaceRange maybeAutoOutdentKeywordLineOp() {
        if (!isAutoIndentActive()) {
            return null;
        }
        LanguageProfile profile = languageProfile();
        if (!profile.keywordDedentEnabled()) {
            return null;
        }
        Position cursor = model.clamp(state.getCursor());
        int lineIndex = cursor.line();
        String line = model.getLine(lineIndex);
        int cursorCol = Math.max(0, Math.min(cursor.col(), line.length()));
        int leading = leadingIndentLength(line);
        if (leading <= 0 || cursorCol <= leading) {
            return null;
        }
        int prevLine = previousNonBlankLine(lineIndex - 1);
        if (prevLine < 0) {
            return null;
        }
        String previous = model.getLine(prevLine);
        int prevLeading = leadingIndentLength(previous);
        if (prevLeading != leading) {
            return null;
        }
        String currentIndent = line.substring(0, leading);
        if (!previous.substring(0, prevLeading).equals(currentIndent)) {
            return null;
        }
        if (!startsWithDedentKeyword(line, leading, cursorCol, profile)) {
            return null;
        }
        String reduced = removeOneIndentUnit(currentIndent, lineIndex);
        int drop = currentIndent.length() - reduced.length();
        if (drop <= 0) {
            return null;
        }
        Position deleteStart = new Position(lineIndex, 0);
        Position deleteEnd = new Position(lineIndex, drop);
        String deleted = model.getTextRange(deleteStart, deleteEnd);
        model.deleteRange(deleteStart, deleteEnd);
        state.setCursor(new Position(lineIndex, Math.max(0, cursorCol - drop)));
        return EditOp.delete(deleteStart, deleted);
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
        boolean merged = shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && tryMergeSnapshotEntry(kind, after);
        if (!merged) {
            pushWithLimit(undoStack, new SnapshotHistoryEntry(kind, before, after));
        }
        redoStack.clear();
        state.setDirty(true);
        lastEditKind = kind;
        lastEditAtNanos = now;
        lastEditCursorAfter = after.cursor();
        gutterDiffComputedRevision = Long.MIN_VALUE;
    }

    private void commitOperationEdit(EditKind kind, ViewState before, List<EditOp.ReplaceRange> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
        ViewState after = captureViewState();
        long now = System.nanoTime();
        boolean merged = shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && tryMergeOperationEntry(kind, after, ops);
        if (!merged) {
            pushWithLimit(undoStack, new OperationHistoryEntry(kind, before, after, ops));
        }
        redoStack.clear();
        state.setDirty(true);
        lastEditKind = kind;
        lastEditAtNanos = now;
        lastEditCursorAfter = after.cursor();
    }

    private boolean tryMergeSnapshotEntry(EditKind kind, Snapshot after) {
        HistoryEntry top = undoStack.peek();
        if (!(top instanceof SnapshotHistoryEntry entry) || entry.kind() != kind) {
            return false;
        }
        entry.setAfter(after);
        return true;
    }

    private boolean tryMergeOperationEntry(EditKind kind, ViewState after, List<EditOp.ReplaceRange> ops) {
        HistoryEntry top = undoStack.peek();
        if (!(top instanceof OperationHistoryEntry entry) || entry.kind() != kind) {
            return false;
        }
        entry.append(ops, after);
        return true;
    }

    private Position applyReplaceAndTrack(
        List<EditOp.ReplaceRange> ops,
        Position start,
        Position end,
        String insertedText
    ) {
        Position a = model.clamp(start);
        Position b = model.clamp(end);
        if (comparePositions(a, b) > 0) {
            Position temp = a;
            a = b;
            b = temp;
        }
        String deletedText = model.getTextRange(a, b);
        String inserted = insertedText == null ? "" : insertedText;
        if (deletedText.isEmpty() && inserted.isEmpty()) {
            return a;
        }
        Position cursor = model.deleteRange(a, b);
        if (!inserted.isEmpty()) {
            cursor = model.insertText(cursor, inserted);
        }
        EditOp.ReplaceRange op = EditOp.replace(a, deletedText, inserted);
        ops.add(op);
        applyGutterDiffOp(op);
        return cursor;
    }

    private static int comparePositions(Position left, Position right) {
        if (left.line() != right.line()) {
            return Integer.compare(left.line(), right.line());
        }
        return Integer.compare(left.col(), right.col());
    }

    private void resetGutterDiffBaselinesOnOpen() {
        List<String> snapshot = List.copyOf(model.snapshotLines());
        openBaselineLines = snapshot;
        savedBaselineLines = snapshot;
        clearAllGutterDiffBuffers(model.lineCount());
        gutterDiffComputedRevision = model.revision();
        gutterDiffPendingRebuild = false;
        gutterDiffLastRebuildAtNanos = 0L;
        gutterDiffLastRebuildDurationNanos = 0L;
    }

    private void resetGutterDiffSavedBaseline() {
        savedBaselineLines = List.copyOf(model.snapshotLines());
        if (gutterDiffMode == GutterDiffMode.DISABLED) {
            clearAllGutterDiffBuffers(model.lineCount());
            gutterDiffComputedRevision = model.revision();
            gutterDiffPendingRebuild = false;
            return;
        }
        rebuildGutterDiffFromCurrentState();
    }

    private void clearAllGutterDiffBuffers(int lineCount) {
        initGutterDiffBuffers(savedDiffFlags, savedDeletedMarkers, lineCount);
        initGutterDiffBuffers(openDiffFlags, openDeletedMarkers, lineCount);
    }

    private void initGutterDiffBuffers(List<GutterDiffFlag> flags, List<Boolean> deleted, int lineCount) {
        flags.clear();
        deleted.clear();
        int lines = Math.max(1, lineCount);
        for (int i = 0; i < lines; i++) {
            flags.add(GutterDiffFlag.NONE);
            deleted.add(false);
        }
    }

    private void rebuildGutterDiffFromCurrentState() {
        if (gutterDiffMode == GutterDiffMode.DISABLED) {
            clearAllGutterDiffBuffers(model.lineCount());
            gutterDiffComputedRevision = model.revision();
            gutterDiffPendingRebuild = false;
            return;
        }
        long startedAt = System.nanoTime();
        List<String> current = model.snapshotLines();
        int lines = Math.max(1, current.size());
        initGutterDiffBuffers(savedDiffFlags, savedDeletedMarkers, lines);
        initGutterDiffBuffers(openDiffFlags, openDeletedMarkers, lines);

        DiffAlgorithm algorithm = resolveGutterDiffAlgorithm();
        EditList savedEdits = algorithm.diff(
            GUTTER_SEQUENCE_COMPARATOR,
            new LineSequence(savedBaselineLines),
            new LineSequence(current)
        );
        applyDiffEdits(savedEdits, lines, savedDiffFlags, savedDeletedMarkers);

        EditList openEdits = algorithm.diff(
            GUTTER_SEQUENCE_COMPARATOR,
            new LineSequence(openBaselineLines),
            new LineSequence(current)
        );
        applyDiffEdits(openEdits, lines, openDiffFlags, openDeletedMarkers);

        gutterDiffLastRebuildDurationNanos = System.nanoTime() - startedAt;
        gutterDiffComputedRevision = model.revision();
        gutterDiffPendingRebuild = false;
        gutterDiffLastRebuildAtNanos = System.nanoTime();
    }

    private void applyGutterDiffOp(EditOp.ReplaceRange op) {
        if (op == null) {
            return;
        }
        if (gutterDiffMode == GutterDiffMode.DISABLED) {
            return;
        }
        gutterDiffComputedRevision = Long.MIN_VALUE;
        gutterDiffPendingRebuild = true;
    }

    private void ensureGutterDiffUpToDate() {
        if (gutterDiffMode == GutterDiffMode.DISABLED) {
            return;
        }
        if (gutterDiffComputedRevision == model.revision()
            && savedDiffFlags.size() == model.lineCount()
            && savedDeletedMarkers.size() == model.lineCount()
            && openDiffFlags.size() == model.lineCount()
            && openDeletedMarkers.size() == model.lineCount()) {
            return;
        }
        if (!shouldRebuildGutterDiffNow()) {
            return;
        }
        rebuildGutterDiffFromCurrentState();
    }

    private DiffAlgorithm resolveGutterDiffAlgorithm() {
        return switch (gutterDiffMode) {
            case DISABLED -> GUTTER_DIFF_ALGORITHM_MYERS;
            case HISTOGRAM -> GUTTER_DIFF_ALGORITHM_HISTOGRAM;
            case MYERS, STABLE -> GUTTER_DIFF_ALGORITHM_MYERS;
        };
    }

    private boolean shouldRebuildGutterDiffNow() {
        if (!gutterDiffPendingRebuild && gutterDiffComputedRevision == Long.MIN_VALUE) {
            return true;
        }
        if (!gutterDiffPendingRebuild) {
            return true;
        }
        long now = System.nanoTime();
        long minInterval = model.lineCount() >= GUTTER_DIFF_LARGE_FILE_THRESHOLD_LINES
            ? GUTTER_DIFF_MIN_REBUILD_INTERVAL_LARGE_NANOS
            : GUTTER_DIFF_MIN_REBUILD_INTERVAL_NANOS;
        if (gutterDiffLastRebuildDurationNanos > 0L) {
            long adaptive = gutterDiffLastRebuildDurationNanos * GUTTER_DIFF_DURATION_FACTOR;
            if (adaptive > minInterval) {
                minInterval = Math.min(adaptive, GUTTER_DIFF_MAX_REBUILD_INTERVAL_NANOS);
            }
        }
        return gutterDiffLastRebuildAtNanos == 0L || now - gutterDiffLastRebuildAtNanos >= minInterval;
    }

    private void applyDiffEdits(
        EditList edits,
        int currentLineCount,
        List<GutterDiffFlag> flags,
        List<Boolean> deletedMarkers
    ) {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        for (Edit edit : edits) {
            int deleted = Math.max(0, edit.getEndA() - edit.getBeginA());
            int added = Math.max(0, edit.getEndB() - edit.getBeginB());
            int beginCurrent = Math.max(0, edit.getBeginB());
            switch (edit.getType()) {
                case INSERT -> {
                    for (int i = 0; i < added; i++) {
                        int line = beginCurrent + i;
                        if (line >= 0 && line < flags.size()) {
                            flags.set(line, GutterDiffFlag.ADDED);
                        }
                    }
                }
                case DELETE -> {
                    if (!deletedMarkers.isEmpty()) {
                        int target = Math.max(0, Math.min(beginCurrent, currentLineCount - 1));
                        deletedMarkers.set(target, true);
                    }
                }
                case REPLACE -> {
                    if (gutterDiffMode == GutterDiffMode.STABLE) {
                        for (int i = 0; i < added; i++) {
                            int line = beginCurrent + i;
                            if (line >= 0 && line < flags.size()) {
                                flags.set(line, GutterDiffFlag.MODIFIED);
                            }
                        }
                    } else {
                        // Balanced mapping: replacement pairs are MODIFIED,
                        // extra new lines are ADDED.
                        int paired = Math.min(deleted, added);
                        for (int i = 0; i < paired; i++) {
                            int line = beginCurrent + i;
                            if (line >= 0 && line < flags.size()) {
                                flags.set(line, GutterDiffFlag.MODIFIED);
                            }
                        }
                        for (int i = paired; i < added; i++) {
                            int line = beginCurrent + i;
                            if (line >= 0 && line < flags.size()) {
                                flags.set(line, GutterDiffFlag.ADDED);
                            }
                        }
                    }
                }
                default -> {
                    // EMPTY edit: no markers.
                }
            }
        }
    }

    private static final class LineSequence extends Sequence {
        private final List<String> lines;

        private LineSequence(List<String> lines) {
            this.lines = lines == null ? List.of() : lines;
        }

        @Override
        public int size() {
            return lines.size();
        }

        private String line(int index) {
            return lines.get(index);
        }
    }

    private static final class LineSequenceComparator extends SequenceComparator<LineSequence> {
        @Override
        public boolean equals(LineSequence a, int ai, LineSequence b, int bi) {
            return a.line(ai).equals(b.line(bi));
        }

        @Override
        public int hash(LineSequence seq, int ptr) {
            return seq.line(ptr).hashCode();
        }
    }

    private ViewState captureViewState() {
        return new ViewState(
            state.getCursor(),
            state.getTopLine(),
            state.getTopWrapSegment(),
            state.getLeftColumn(),
            state.getLeftVisualColumn(),
            state.isDirty(),
            state.getSelectionAnchor()
        );
    }

    private void restoreViewState(ViewState view) {
        state.setCursor(model.clamp(view.cursor()));
        state.setTopLine(view.topLine());
        state.setTopWrapSegment(view.topWrapSegment());
        state.setLeftColumn(view.leftColumn());
        state.setLeftVisualColumn(view.leftVisualColumn());
        state.setDirty(view.dirty());
        state.setSelectionAnchor(view.selectionAnchor());
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
        restoreViewState(new ViewState(
            snapshot.cursor(),
            snapshot.topLine(),
            snapshot.topWrapSegment(),
            snapshot.leftColumn(),
            snapshot.leftVisualColumn(),
            snapshot.dirty(),
            snapshot.selectionAnchor()
        ));
        gutterDiffComputedRevision = Long.MIN_VALUE;
    }

    private static <T> void pushWithLimit(Deque<T> stack, T entry) {
        stack.push(entry);
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

    private record ViewState(
        Position cursor,
        int topLine,
        int topWrapSegment,
        int leftColumn,
        int leftVisualColumn,
        boolean dirty,
        Position selectionAnchor
    ) {
    }

    private interface HistoryEntry {
        void undo();

        void redo();
    }

    private final class SnapshotHistoryEntry implements HistoryEntry {
        private final EditKind kind;
        private final Snapshot before;
        private Snapshot after;

        private SnapshotHistoryEntry(EditKind kind, Snapshot before, Snapshot after) {
            this.kind = kind;
            this.before = before;
            this.after = after;
        }

        EditKind kind() {
            return kind;
        }

        void setAfter(Snapshot after) {
            this.after = after;
        }

        @Override
        public void undo() {
            restoreSnapshot(before);
        }

        @Override
        public void redo() {
            restoreSnapshot(after);
        }
    }

    private final class OperationHistoryEntry implements HistoryEntry {
        private final EditKind kind;
        private final ViewState beforeView;
        private ViewState afterView;
        private final List<EditOp.ReplaceRange> ops;

        private OperationHistoryEntry(
            EditKind kind,
            ViewState beforeView,
            ViewState afterView,
            List<EditOp.ReplaceRange> ops
        ) {
            this.kind = kind;
            this.beforeView = beforeView;
            this.afterView = afterView;
            this.ops = new ArrayList<>(ops);
        }

        EditKind kind() {
            return kind;
        }

        void append(List<EditOp.ReplaceRange> extraOps, ViewState mergedAfterView) {
            this.ops.addAll(extraOps);
            this.afterView = mergedAfterView;
        }

        @Override
        public void undo() {
            for (int i = ops.size() - 1; i >= 0; i--) {
                applyReplace(ops.get(i).start(), ops.get(i).insertedText(), ops.get(i).deletedText());
            }
            restoreViewState(beforeView);
        }

        @Override
        public void redo() {
            for (EditOp.ReplaceRange op : ops) {
                applyReplace(op.start(), op.deletedText(), op.insertedText());
            }
            restoreViewState(afterView);
        }

        private void applyReplace(Position start, String deletedText, String insertedText) {
            Position a = model.clamp(start);
            Position b = advancePosition(a, deletedText);
            Position cursor = model.deleteRange(a, b);
            if (insertedText != null && !insertedText.isEmpty()) {
                model.insertText(cursor, insertedText);
            }
            applyGutterDiffOp(EditOp.replace(a, deletedText, insertedText));
        }

        private Position advancePosition(Position start, String text) {
            int line = start.line();
            int col = start.col();
            if (text == null || text.isEmpty()) {
                return start;
            }
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    line++;
                    col = 0;
                } else {
                    col++;
                }
            }
            return model.clamp(new Position(line, col));
        }
    }

    private record SelectionRange(Position start, Position end) {
    }

    public enum LineCommentAction {
        COMMENTED,
        UNCOMMENTED,
        UNSUPPORTED,
        NO_CONTENT
    }

    public record LineCommentResult(LineCommentAction action, int lineCount) {
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

    private int commonIndentLength(List<Integer> lineIndexes) {
        String common = null;
        for (int lineIndex : lineIndexes) {
            String line = model.getLine(lineIndex);
            String indent = line.substring(0, leadingIndentLength(line));
            if (common == null) {
                common = indent;
                continue;
            }
            int shared = 0;
            int limit = Math.min(common.length(), indent.length());
            while (shared < limit && common.charAt(shared) == indent.charAt(shared)) {
                shared++;
            }
            common = common.substring(0, shared);
            if (common.isEmpty()) {
                return 0;
            }
        }
        return common == null ? 0 : common.length();
    }

    private static int lineCommentColumn(String line, String marker, int lineIndex) {
        int column = leadingIndentLength(line);
        if (!line.startsWith(marker, column)) {
            return -1;
        }
        if ("#".equals(marker) && lineIndex == 0 && line.startsWith("#!", column)) {
            return -1;
        }
        return column;
    }

    private static Position transformPosition(Position position, List<LineCommentEdit> edits) {
        for (LineCommentEdit edit : edits) {
            if (edit.line() != position.line()) {
                continue;
            }
            int column = position.col();
            if (edit.removedLength() == 0) {
                if (column >= edit.column()) {
                    column += edit.insertedLength();
                }
            } else if (column > edit.column()) {
                column = Math.max(edit.column(), column - edit.removedLength());
            }
            return new Position(position.line(), column);
        }
        return position;
    }

    private void deleteSelectionIfAny() {
        SelectionRange range = currentSelection();
        if (range != null) {
            state.setCursor(model.deleteRange(range.start(), range.end()));
        }
    }

    private record LineCommentEdit(int line, int column, int removedLength, int insertedLength) {
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

    private static boolean isWhitespaceGrapheme(String text, int start, int end) {
        if (start >= end) {
            return false;
        }
        for (int i = start; i < end;) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isWordGrapheme(String text, int start, int end) {
        if (start >= end) {
            return false;
        }
        int codePoint = text.codePointAt(start);
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static int graphemeGroup(String text, int start, int end) {
        if (isWhitespaceGrapheme(text, start, end)) {
            return 0;
        }
        return isWordGrapheme(text, start, end) ? 1 : 2;
    }

    private record SearchMatch(Position start, int length) {
    }

    private record LineMatch(int start, int end) {
    }

    public boolean hasSelection() {
        return state.hasSelection();
    }

    private boolean shouldCoalesce(
        EditKind kind,
        Position beforeCursor,
        Position beforeSelectionAnchor,
        long nowNanos
    ) {
        if (kind == EditKind.NONE || lastEditKind != kind || lastEditCursorAfter == null) {
            return false;
        }
        if (nowNanos - lastEditAtNanos > COALESCE_WINDOW_NANOS) {
            return false;
        }
        return beforeCursor.equals(lastEditCursorAfter)
            && beforeSelectionAnchor == null;
    }

    private void breakCoalescing() {
        lastEditKind = EditKind.NONE;
        lastEditAtNanos = 0L;
        lastEditCursorAfter = null;
    }
}

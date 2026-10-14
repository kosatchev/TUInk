package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;

/**
 * Core document editing, selection and history commands.
 */
public class EditorEngine {
    private static final int MAX_HISTORY = 500;
    private static final long COALESCE_WINDOW_NANOS = 700_000_000L;
    private static final int ASSIST_LOOKBACK_LINES = 256;
    private static final int ASSIST_LOOKBACK_CHARS = 16_384;
    private static final int GUTTER_DIFF_LARGE_FILE_THRESHOLD_LINES = 20_000;
    private static final long GUTTER_DIFF_MIN_REBUILD_INTERVAL_NANOS = 50_000_000L;
    private static final long GUTTER_DIFF_MIN_REBUILD_INTERVAL_LARGE_NANOS = 150_000_000L;
    private static final long GUTTER_DIFF_MAX_REBUILD_INTERVAL_NANOS = 350_000_000L;
    private static final int GUTTER_DIFF_DURATION_FACTOR = 2;
    private static final long GUTTER_DIFF_MAX_WORK = 250_000L;
    private static final DiffAlgorithm GUTTER_DIFF_ALGORITHM_HISTOGRAM =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);
    private static final DiffAlgorithm GUTTER_DIFF_ALGORITHM_MYERS =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS);
    private final TextModel model;
    private final EditorState state;
    private final EditorTextLayout textLayout;
    private final EditorClipboard clipboard;
    private final Deque<HistoryEntry> undoStack = new ArrayDeque<>();
    private final Deque<HistoryEntry> redoStack = new ArrayDeque<>();
    private EditKind lastEditKind = EditKind.NONE;
    private long lastEditAtNanos;
    private Position lastEditCursorAfter;
    private long navigationRevision;
    private GraphemeText cachedGraphemes;
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
    private long nextDocumentStateId;
    private long currentDocumentStateId;
    private long savedDocumentStateId;

    public EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout) {
        this(model, state, textLayout, new EditorClipboard());
    }

    public EditorEngine(
        TextModel model,
        EditorState state,
        EditorTextLayout textLayout,
        EditorClipboard clipboard
    ) {
        this.model = model;
        this.state = state;
        if (textLayout == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to the engine model");
        }
        this.textLayout = textLayout;
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        resetGutterDiffBaselinesOnOpen();
        if (state.isDirty()) {
            savedDocumentStateId = -1L;
        } else {
            markCurrentAsSaved();
        }
    }

    public TextModel model() {
        return model;
    }

    public EditorState state() {
        return state;
    }

    public EditorTextLayout textLayout() {
        return textLayout;
    }

    /** Invalidates frontend navigation context after a document or cursor interaction. */
    public long navigationRevision() {
        return navigationRevision;
    }

    public int tabWidth() {
        return tabWidth;
    }

    /**
     * Applies a prevalidated batch of document replacements as one atomic undo step.
     *
     * <p>Every range is checked against the same pre-edit model before any mutation.
     * Ranges use exact UTF-16 offsets, may not split surrogate pairs or overlap, and
     * are composed in one forward pass over the original document. The cursor is
     * moved to the end of the earliest applied replacement.</p>
     *
     * @return {@code true} when logical document content changed
     * @throws IllegalArgumentException when any replacement is invalid
     */
    public boolean applyTextReplacements(List<TextReplacement> replacements) {
        List<TextReplacement> canonical = validateTextReplacements(replacements);
        List<TextReplacement> effective = new ArrayList<>(canonical.size());
        for (TextReplacement replacement : canonical) {
            String normalized = normalizeInsertedText(replacement.replacement());
            if (!model.getTextRange(replacement.start(), replacement.end()).equals(normalized)) {
                effective.add(new TextReplacement(
                    replacement.start(),
                    replacement.end(),
                    normalized
                ));
            }
        }
        if (effective.isEmpty()) {
            return false;
        }

        navigationRevision++;
        breakCoalescing();
        applyEdit(EditKind.NONE, () -> {
            DocumentFileMetadata metadata = model.fileMetadata();
            ReplacementBuilder builder = new ReplacementBuilder();
            Position sourcePosition = new Position(0, 0);
            Position earliestResult = null;
            for (TextReplacement replacement : effective) {
                appendOriginalRange(builder, sourcePosition, replacement.start(), metadata);
                builder.appendReplacement(replacement.replacement(), metadata.defaultLineSeparator());
                if (earliestResult == null) {
                    earliestResult = builder.position();
                }
                sourcePosition = replacement.end();
            }
            int lastLine = model.lineCount() - 1;
            appendOriginalRange(
                builder, sourcePosition, new Position(lastLine, model.getLine(lastLine).length()), metadata
            );
            builder.finishLine();
            model.replaceLines(builder.lines, metadata.withLineSeparators(builder.separators));
            state.setCursor(earliestResult);
            state.clearSelection();
        });
        return true;
    }

    private void appendOriginalRange(
        ReplacementBuilder builder,
        Position start,
        Position end,
        DocumentFileMetadata metadata
    ) {
        int column = start.col();
        for (int line = start.line(); line < end.line(); line++) {
            String text = model.getLine(line);
            builder.append(text, column, text.length());
            builder.newline(metadata.lineSeparators().get(line));
            column = 0;
        }
        builder.append(model.getLine(end.line()), column, end.col());
    }

    /** Reuses untouched line strings and copies each changed line only once. */
    private static final class ReplacementBuilder {
        private final List<String> lines = new ArrayList<>();
        private final List<String> separators = new ArrayList<>();
        private String wholeLine;
        private StringBuilder pending;

        private void append(String text, int start, int end) {
            if (start == end) {
                return;
            }
            if (pending == null && wholeLine == null && start == 0 && end == text.length()) {
                wholeLine = text;
                return;
            }
            if (pending == null) {
                pending = new StringBuilder();
                if (wholeLine != null) {
                    pending.append(wholeLine);
                    wholeLine = null;
                }
            }
            pending.append(text, start, end);
        }

        private void appendReplacement(String text, String separator) {
            int start = 0;
            int end;
            while ((end = text.indexOf('\n', start)) >= 0) {
                append(text, start, end);
                newline(separator);
                start = end + 1;
            }
            append(text, start, text.length());
        }

        private Position position() {
            return new Position(lines.size(), pending == null
                ? wholeLine == null ? 0 : wholeLine.length()
                : pending.length());
        }

        private void newline(String separator) {
            finishLine();
            separators.add(separator);
        }

        private void finishLine() {
            lines.add(pending == null ? wholeLine == null ? "" : wholeLine : pending.toString());
            pending = null;
            wholeLine = null;
        }
    }

    /** Moves the primary cursor through the engine-owned mutation boundary. */
    public void moveCursorTo(Position requested) {
        navigateTo(requested, false);
    }

    /**
     * Applies a frontend-resolved navigation target through the selection and history boundary.
     * Even a boundary no-op clears an unextended selection and separates consecutive typing edits.
     */
    public void navigateTo(Position requested, boolean selecting) {
        navigationRevision++;
        Position previous = clampCursorBoundary(state.getCursor());
        moveCursor(clampCursorBoundary(requested), selecting, previous);
    }

    /** Starts a pointer selection without exposing mutable cursor state to a frontend. */
    public void beginSelectionAt(Position requested) {
        navigationRevision++;
        breakCoalescing();
        Position next = clampCursorBoundary(requested);
        state.setSelectionAnchor(next);
        state.setCursor(next);
    }

    /** Extends the current selection to a frontend-resolved text position. */
    public void extendSelectionTo(Position requested) {
        navigationRevision++;
        breakCoalescing();
        if (state.getSelectionAnchor() == null) {
            state.setSelectionAnchor(state.getCursor());
        }
        state.setCursor(clampCursorBoundary(requested));
    }

    /** Restores a cursor/selection snapshot after speculative frontend routing. */
    public void restoreSelection(Position cursor, Position anchor) {
        navigationRevision++;
        breakCoalescing();
        state.setCursor(requireTextBoundary(cursor));
        state.setSelectionAnchor(anchor == null ? null : requireTextBoundary(anchor));
    }

    /** Removes a collapsed selection after pointer release. */
    public void finishSelection() {
        if (state.getSelectionAnchor() != null && state.getSelectionAnchor().equals(state.getCursor())) {
            state.clearSelection();
        }
    }

    public RecoverySnapshot captureRecoverySnapshot() {
        return RecoverySnapshot.capture(model, state);
    }

    public void restoreRecoverySnapshot(RecoverySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.restore(model, state);
        navigationRevision++;
        undoStack.clear();
        redoStack.clear();
        breakCoalescing();
        resetGutterDiffBaselinesOnOpen();
        beginNewDocumentState();
        if (snapshot.dirty()) {
            savedDocumentStateId = -1L;
            state.setDirty(true);
        } else {
            markCurrentAsSaved();
        }
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

    public void setTabWidth(int tabWidth) {
        int nextWidth = tabWidth <= 2 ? 2 : 4;
        if (this.tabWidth != nextWidth) {
            navigationRevision++;
        }
        this.tabWidth = nextWidth;
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
        open(path, true);
    }

    /** Opens an existing file without treating a missing target as a new document. */
    public void openExisting(Path path) throws IOException {
        open(path, false);
    }

    private void open(Path path, boolean createIfMissing) throws IOException {
        if (path == null) {
            throw new IOException("No file selected");
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        SaveDisposition openedDisposition;
        try {
            TextModel.LoadMetadata loaded = model.load(normalizedPath);
            state.setFileEncoding(loaded.charset());
            state.setLineSeparator(loaded.lineSeparator());
            openedDisposition = SaveDisposition.REPLACE_EXISTING;
        } catch (NoSuchFileException missing) {
            if (!createIfMissing || Files.isSymbolicLink(normalizedPath)) {
                throw missing;
            }
            model.replaceLines(
                List.of(""),
                DocumentFileMetadata.newDocument(1)
            );
            model.restoreFileBaseline(null);
            state.setFileEncoding(StandardCharsets.UTF_8);
            state.setLineSeparator(EditorState.LINE_SEPARATOR_LF);
            openedDisposition = SaveDisposition.CREATE_NEW;
        }
        state.setCurrentFile(normalizedPath);
        state.setSaveDisposition(openedDisposition);
        state.setCursor(new Position(0, 0));
        navigationRevision++;
        state.setTopLine(0);
        state.setLeftColumn(0);
        state.setLeftVisualColumn(0);
        state.setDirty(false);
        state.clearSelection();
        undoStack.clear();
        redoStack.clear();
        breakCoalescing();
        resetGutterDiffBaselinesOnOpen();
        beginNewDocumentState();
        markCurrentAsSaved();
    }

    public void save() throws IOException {
        if (state.getCurrentFile() == null) {
            throw new IOException("No file selected");
        }
        model.saveIfUnchanged(
            state.getCurrentFile(),
            model.fileMetadata(),
            state.getSaveDisposition()
        );
        state.setSaveDisposition(SaveDisposition.REPLACE_EXISTING);
        state.setDirty(false);
        breakCoalescing();
        resetGutterDiffSavedBaseline();
        markCurrentAsSaved();
    }

    public void saveAs(Path path) throws IOException {
        saveAs(path, SaveDisposition.CREATE_NEW);
    }

    public void saveAs(Path path, SaveDisposition disposition) throws IOException {
        if (path == null) {
            throw new IOException("No file selected");
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path currentFile = state.getCurrentFile();
        FileBaseline baseline = model.fileBaseline();
        boolean sameFile = isSameSaveTarget(normalizedPath, currentFile)
            || (baseline != null && isSameSaveTarget(normalizedPath, baseline.target()));
        if (sameFile) {
            model.saveIfUnchanged(normalizedPath, model.fileMetadata(), disposition);
        } else {
            model.save(normalizedPath, model.fileMetadata(), disposition);
        }
        state.setCurrentFile(normalizedPath);
        state.setSaveDisposition(SaveDisposition.REPLACE_EXISTING);
        state.setDirty(false);
        breakCoalescing();
        resetGutterDiffSavedBaseline();
        markCurrentAsSaved();
    }

    private static boolean isSameSaveTarget(Path requested, Path known) throws IOException {
        return known != null && (
            requested.equals(known.toAbsolutePath().normalize())
                || (Files.exists(requested) && Files.exists(known) && Files.isSameFile(requested, known))
        );
    }

    public void moveLeft() {
        moveLeft(false);
    }

    public void moveLeft(boolean selecting) {
        navigationRevision++;
        Position p = state.getCursor();
        if (p.col() > 0) {
            String line = model.getLine(p.line());
            moveCursor(new Position(p.line(), graphemes(line).previousBoundary(p.col())), selecting, p);
        } else if (p.line() > 0) {
            int prevLen = model.getLine(p.line() - 1).length();
            moveCursor(new Position(p.line() - 1, prevLen), selecting, p);
        } else {
            moveCursor(p, selecting, p);
        }
    }

    public void moveRight() {
        moveRight(false);
    }

    public void moveRight(boolean selecting) {
        navigationRevision++;
        Position p = state.getCursor();
        int len = model.getLine(p.line()).length();
        if (p.col() < len) {
            String line = model.getLine(p.line());
            moveCursor(new Position(p.line(), graphemes(line).nextBoundary(p.col())), selecting, p);
        } else if (p.line() < model.lineCount() - 1) {
            moveCursor(new Position(p.line() + 1, 0), selecting, p);
        } else {
            moveCursor(p, selecting, p);
        }
    }

    public void moveWordLeft() {
        navigationRevision++;
        Position previous = state.getCursor();
        Position p = model.clamp(previous);
        int line = p.line();
        int col = p.col();
        if (line == 0 && col == 0) {
            moveCursor(p, false, previous);
            return;
        }
        if (col == 0 && line > 0) {
            line--;
            col = model.getLine(line).length();
        }
        String text = model.getLine(line);
        GraphemeText graphemes = graphemes(text);
        int target = graphemes.floorBoundary(col);
        while (target > 0) {
            int previousBoundary = graphemes.previousBoundary(target);
            if (!isWhitespaceGrapheme(text, previousBoundary, target)) {
                break;
            }
            target = previousBoundary;
        }
        if (target > 0) {
            int previousBoundary = graphemes.previousBoundary(target);
            boolean word = isWordGrapheme(text, previousBoundary, target);
            while (target > 0) {
                previousBoundary = graphemes.previousBoundary(target);
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
        navigationRevision++;
        Position previous = state.getCursor();
        Position p = model.clamp(previous);
        int line = p.line();
        int col = p.col();
        int lastLine = model.lineCount() - 1;
        if (line == lastLine && col >= model.getLine(line).length()) {
            moveCursor(p, false, previous);
            return;
        }
        String text = model.getLine(line);
        if (col >= text.length() && line < lastLine) {
            line++;
            col = 0;
            text = model.getLine(line);
        }
        GraphemeText graphemes = graphemes(text);
        int target = graphemes.ceilBoundary(col);
        while (target < text.length()) {
            int nextBoundary = graphemes.nextBoundary(target);
            if (!isWhitespaceGrapheme(text, target, nextBoundary)) {
                break;
            }
            target = nextBoundary;
        }
        if (target < text.length()) {
            int nextBoundary = graphemes.nextBoundary(target);
            boolean word = isWordGrapheme(text, target, nextBoundary);
            while (target < text.length()) {
                nextBoundary = graphemes.nextBoundary(target);
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
        navigationRevision++;
        breakCoalescing();
        Position p = model.clamp(requested);
        String text = model.getLine(p.line());
        if (text.isEmpty()) {
            state.clearSelection();
            state.setCursor(p);
            return false;
        }
        int requestedColumn = Math.min(Math.max(0, p.col()), text.length() - 1);
        GraphemeText graphemes = graphemes(text);
        int pivot = graphemes.floorBoundary(requestedColumn);
        int pivotEnd = graphemes.nextBoundary(pivot);
        int group = graphemeGroup(text, pivot, pivotEnd);
        int start = pivot;
        while (start > 0) {
            int previous = graphemes.previousBoundary(start);
            if (graphemeGroup(text, previous, start) != group) {
                break;
            }
            start = previous;
        }
        int end = pivotEnd;
        while (end < text.length()) {
            int next = graphemes.nextBoundary(end);
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

    public void moveHome() {
        navigationRevision++;
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(state.getCursor().line(), 0));
    }

    public void moveEnd() {
        navigationRevision++;
        Position p = state.getCursor();
        breakCoalescing();
        state.clearSelection();
        state.setCursor(new Position(p.line(), model.getLine(p.line()).length()));
    }

    public void moveHomeSmart() {
        navigationRevision++;
        breakCoalescing();
        state.clearSelection();
        Position p = state.getCursor();
        String line = model.getLine(p.line());
        int firstContentCol = firstNonWhitespaceColumn(line);
        int target = p.col() == firstContentCol ? 0 : firstContentCol;
        state.setCursor(new Position(p.line(), target));
    }

    public void moveEndSmart() {
        navigationRevision++;
        breakCoalescing();
        state.clearSelection();
        Position p = state.getCursor();
        String line = model.getLine(p.line());
        int trimmedEnd = trailingTrimEndColumn(line);
        int absoluteEnd = line.length();
        int target = p.col() == trimmedEnd ? absoluteEnd : trimmedEnd;
        state.setCursor(new Position(p.line(), target));
    }

    public void insertChar(char ch) {
        if (Character.isSurrogate(ch)) {
            throw new IllegalArgumentException("A standalone UTF-16 surrogate is not valid input");
        }
        navigationRevision++;
        if (isAutoPairsActive() && shouldSkipOverClosing(ch)) {
            moveRight();
            return;
        }
        boolean hadSelection = state.hasSelection();
        if (isAutoPairsActive() && state.hasSelection() && shouldWrapSelectionWithPair(ch)) {
            wrapSelectionWithPair(ch);
            return;
        }

        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>();
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
            TrackedEdit outdent = maybeAutoOutdentKeywordLineOp();
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
        LogicalDocumentLines.requireWellFormedUtf16(text);
        if (text.length() == 1 && !Character.isSurrogate(text.charAt(0))) {
            insertChar(text.charAt(0));
            return;
        }

        navigationRevision++;
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, text);
        state.setCursor(afterInsert);
        TrackedEdit outdent = maybeAutoOutdentKeywordLineOp();
        if (outdent != null) {
            ops.add(outdent);
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
    }

    public void insertTab() {
        navigationRevision++;
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>();
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
        TrackedEdit outdent = maybeAutoOutdentKeywordLineOp();
        if (outdent != null) {
            ops.add(outdent);
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
    }

    public void backspace() {
        navigationRevision++;
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>();
        SelectionRange range = currentSelection();
        if (range == null && isAutoPairsActive()) {
            range = pairRangeAroundCursor();
        }
        if (range == null) {
            range = indentUnitBackwardRange();
        }
        if (range == null) {
            Position cursor = model.clamp(state.getCursor());
            if (cursor.col() > 0) {
                GraphemeText graphemes = graphemes(model.getLine(cursor.line()));
                int end = graphemes.ceilBoundary(cursor.col());
                range = new SelectionRange(
                    new Position(cursor.line(), graphemes.previousBoundary(end)),
                    new Position(cursor.line(), end)
                );
            } else if (cursor.line() > 0) {
                int previousLine = cursor.line() - 1;
                range = new SelectionRange(
                    new Position(previousLine, model.getLine(previousLine).length()),
                    cursor
                );
            }
        }
        deleteAndCommit(hadSelection ? EditKind.NONE : EditKind.BACKSPACE, before, ops, range);
    }

    public void deleteForward() {
        navigationRevision++;
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>();
        SelectionRange range = currentSelection();
        if (range == null) {
            Position cursor = model.clamp(state.getCursor());
            String line = model.getLine(cursor.line());
            if (cursor.col() < line.length()) {
                GraphemeText graphemes = graphemes(line);
                int start = graphemes.floorBoundary(cursor.col());
                range = new SelectionRange(
                    new Position(cursor.line(), start),
                    new Position(cursor.line(), graphemes.nextBoundary(start))
                );
            } else if (cursor.line() < model.lineCount() - 1) {
                range = new SelectionRange(cursor, new Position(cursor.line() + 1, 0));
            }
        }
        deleteAndCommit(hadSelection ? EditKind.NONE : EditKind.DELETE_FORWARD, before, ops, range);
    }

    private void deleteAndCommit(
        EditKind kind,
        ViewState before,
        List<TrackedEdit> ops,
        SelectionRange range
    ) {
        if (range != null) {
            state.setCursor(applyReplaceAndTrack(ops, range.start(), range.end(), ""));
        }
        state.clearSelection();
        commitOperationEdit(kind, before, ops);
    }

    public void insertNewline() {
        navigationRevision++;
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position at = model.clamp(rangeStart);
        if (selection == null && isAutoListActive() && canTerminateMarkdownList(at)) {
            String line = model.getLine(at.line());
            Position markerStart = new Position(at.line(), leadingIndentLength(line));
            state.setCursor(applyReplaceAndTrack(ops, markerStart, new Position(at.line(), line.length()), ""));
            state.clearSelection();
            commitOperationEdit(EditKind.NONE, before, ops);
            return;
        }
        String indent = isAutoIndentActive() ? indentForNewLine(at) : "";
        String continuation = isAutoListActive() ? continuationAfterNewLine(at) : "";
        String insertText = "\n" + indent + continuation;
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, insertText);
        state.setCursor(afterInsert);
        state.clearSelection();
        commitOperationEdit(EditKind.NONE, before, ops);
    }

    public LineCommentResult toggleLineComment() {
        navigationRevision++;
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
        List<TrackedEdit> ops = new ArrayList<>(nonBlankLines.size());
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
        String copied = model.getTextRange(range.start(), range.end());
        clipboard.setText(copied);
        return !copied.isEmpty();
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
        String copied = model.getTextRange(start, end);
        clipboard.setText(copied);
        return !copied.isEmpty();
    }

    public boolean cutSelection() {
        navigationRevision++;
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        clipboard.setText(model.getTextRange(range.start(), range.end()));
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>(1);
        deleteAndCommit(EditKind.NONE, before, ops, range);
        return true;
    }

    public boolean cutCurrentLine() {
        navigationRevision++;
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
        clipboard.setText(model.getTextRange(start, end));
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>(1);
        deleteAndCommit(EditKind.NONE, before, ops, new SelectionRange(start, end));
        return true;
    }

    public boolean pasteClipboard() {
        navigationRevision++;
        String pasted = clipboard.text();
        if (pasted.isEmpty()) {
            return false;
        }
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>(1);
        SelectionRange selection = currentSelection();
        Position start = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position end = selection == null ? start : selection.end();
        state.setCursor(applyReplaceAndTrack(ops, start, end, pasted));
        state.clearSelection();
        commitOperationEdit(EditKind.NONE, before, ops);
        return true;
    }

    public boolean selectAll() {
        navigationRevision++;
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
        navigationRevision++;
        if (undoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        HistoryEntry previous = undoStack.pop();
        previous.undo();
        pushWithLimit(redoStack, previous);
        updateDirtyFromSavepoint();
        return true;
    }

    public boolean redo() {
        navigationRevision++;
        if (redoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        HistoryEntry next = redoStack.pop();
        next.redo();
        pushWithLimit(undoStack, next);
        updateDirtyFromSavepoint();
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
        SearchMatcher matcher = SearchMatcher.compile(needle, options);
        breakCoalescing();

        Position from = model.clamp(state.getCursor());
        SelectionRange selected = currentSelection();
        if (selected != null && matchesSelectedText(matcher, selected)) {
            from = selected.end();
        }

        SearchMatch found = findForward(from, matcher, true);
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
        SearchMatcher matcher = SearchMatcher.compile(needle, options);
        breakCoalescing();

        Position from = model.clamp(state.getCursor());
        SelectionRange selected = currentSelection();
        if (selected != null && matchesSelectedText(matcher, selected)) {
            from = selected.start();
        }

        SearchMatch found = findBackward(from, matcher, true);
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
        SearchMatcher matcher = SearchMatcher.compile(needle, options);
        String replacementText = normalizeInsertedText(replacement == null ? "" : replacement);
        LogicalDocumentLines.requireWellFormedUtf16(replacementText);

        SelectionRange selected = currentSelection();
        if (selected == null || !matchesSelectedText(matcher, selected)) {
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
        String selectedText = model.getTextRange(rangeStart, rangeEnd);
        if (selectedText.equals(replacementText)) {
            // Replacing a match with the exact same text must not manufacture a
            // document revision or an undo entry. Move to the following match so
            // repeated Replace commands still make progress.
            return findNext(needle, options);
        }
        navigationRevision++;
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>(1);
        state.setCursor(applyReplaceAndTrack(ops, rangeStart, rangeEnd, replacementText));
        state.setSelectionAnchor(rangeStart);
        commitOperationEdit(EditKind.NONE, before, ops);
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
        SearchMatcher matcher = SearchMatcher.compile(needle, options);
        String replacementText = normalizeInsertedText(replacement == null ? "" : replacement);
        LogicalDocumentLines.requireWellFormedUtf16(replacementText);
        List<TextReplacement> replacements = new ArrayList<>();
        for (int line = 0; line < model.lineCount(); line++) {
            for (SearchMatcher.Match match : matcher.findAll(model.getLine(line))) {
                replacements.add(new TextReplacement(
                    new Position(line, match.startUtf16()),
                    new Position(line, match.endUtf16()),
                    replacementText
                ));
            }
        }
        if (replacements.isEmpty()) {
            return 0;
        }
        applyTextReplacements(replacements);
        return replacements.size();
    }

    private int firstNonWhitespaceColumn(String line) {
        GraphemeText graphemes = graphemes(line);
        for (int index = 0; index < graphemes.graphemeCount(); index++) {
            if (!graphemes.isWhitespace(index)) {
                return graphemes.start(index);
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
        if (shouldDedentFromClosing(cursor.line(), line, col, baseIndent)) {
            baseIndent = removeOneIndentUnit(baseIndent, cursor.line());
        }
        if (shouldIncreaseIndent(line, col)) {
            return baseIndent + indentUnit(cursor.line(), baseIndent);
        }
        return baseIndent;
    }

    private boolean shouldDedentFromClosing(int lineIndex, String line, int col, String currentIndent) {
        if (line.isEmpty() || currentIndent.isEmpty() || !languageProfile().bracketIndentEnabled()) {
            return false;
        }
        int safeCol = Math.max(0, Math.min(col, line.length()));
        if (safeCol <= leadingIndentLength(line)) {
            return false;
        }
        String before = IndentationSyntax.codeBeforeCursor(line, safeCol, state.getCurrentFile());
        if (!startsWithStandaloneClosing(before)) {
            return false;
        }
        Deque<Character> pending = new ArrayDeque<>();
        for (int index = before.length() - 1; index >= 0; index--) {
            if (isClosingBracket(before.charAt(index))) {
                pending.push(before.charAt(index));
            }
        }
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        int firstLine = Math.max(0, lineIndex - ASSIST_LOOKBACK_LINES);
        for (int previous = lineIndex - 1; previous >= firstLine; previous--) {
            String text = model.getLine(previous);
            remainingChars -= text.length();
            if (remainingChars < 0) {
                return false;
            }
            String code = IndentationSyntax.codeBeforeCursor(text, text.length(), state.getCurrentFile());
            // Multiline literals, incomplete quotes and ambiguous slash syntax need a lexer.
            // Keep the existing indentation when this small lookback cannot prove a match.
            code = IndentationSyntax.maskOrdinaryLiterals(code);
            if (code == null || code.indexOf('/') >= 0) {
                return false;
            }
            for (int index = code.length() - 1; index >= 0; index--) {
                char ch = code.charAt(index);
                if (isClosingBracket(ch)) {
                    pending.push(ch);
                } else if (ch == '(' || ch == '[' || ch == '{') {
                    if (pending.isEmpty() || !isMatchingPair(ch, pending.pop())) {
                        return false;
                    }
                    if (pending.isEmpty()) {
                        String openerIndent = text.substring(0, leadingIndentLength(text));
                        return currentIndent.length() > openerIndent.length() && currentIndent.startsWith(openerIndent)
                            && hasCertainContextBefore(previous, index, firstLine, remainingChars);
                    }
                }
            }
        }
        return false;
    }

    private boolean hasCertainContextBefore(int lineIndex, int column, int firstLine, int remainingChars) {
        CommentSyntaxProfile syntax = LanguageProfiles.resolveCommentSyntax(state.getCurrentFile());
        String openingComment = syntax.slashBlock() ? "/*" : syntax.htmlBlock() ? "<!--" : null;
        String closingComment = syntax.slashBlock() ? "*/" : syntax.htmlBlock() ? "-->" : null;
        boolean foundCommentBoundary = false;
        for (int previous = lineIndex; previous >= firstLine; previous--) {
            String text = model.getLine(previous);
            int end = previous == lineIndex ? column : text.length();
            remainingChars -= end;
            if (remainingChars < 0) {
                return false;
            }
            String code = IndentationSyntax.codeBeforeCursor(text, end, state.getCurrentFile());
            if (IndentationSyntax.maskOrdinaryLiterals(code) == null) {
                return false;
            }
            if (openingComment != null && !foundCommentBoundary) {
                String prefix = text.substring(0, end);
                int opening = prefix.lastIndexOf(openingComment);
                int closing = prefix.lastIndexOf(closingComment);
                if (opening >= 0 || closing >= 0) {
                    if (opening > closing) {
                        return false;
                    }
                    foundCommentBoundary = true;
                }
            }
        }
        return firstLine == 0;
    }

    private boolean shouldIncreaseIndent(String line, int col) {
        LanguageProfile profile = languageProfile();
        String before = IndentationSyntax.codeBeforeCursor(line, col, state.getCurrentFile()).stripTrailing();
        if (before.isEmpty()) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
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
        if (!before.endsWith(":") || before.endsWith("::")) {
            return false;
        }
        String head = before.substring(0, before.length() - 1).strip();
        if (head.isEmpty() || head.endsWith(";")) {
            return false;
        }
        boolean async = head.startsWith("async") && head.length() > 5
            && Character.isWhitespace(head.charAt(5));
        if (async) {
            head = head.substring(5).stripLeading();
        }
        int end = 0;
        while (end < head.length() && Character.isUnicodeIdentifierPart(head.charAt(end))) {
            end++;
        }
        String keyword = head.substring(0, end);
        if (async && !keyword.equals("def") && !keyword.equals("for") && !keyword.equals("with")) {
            return false;
        }
        boolean hasExpression = end < head.length()
            && (Character.isWhitespace(head.charAt(end)) || head.charAt(end) == '(');
        return switch (keyword) {
            case "if", "elif", "for", "while", "def", "class", "with", "match", "case" -> hasExpression;
            case "except" -> end == head.length() || hasExpression || head.charAt(end) == '*';
            case "else", "try", "finally" -> end == head.length();
            default -> false;
        };
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

        if ((ch == '\'' || ch == '"') && isEscaped(line, col)) {
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
        return line.charAt(col) == ch
            && ((ch != '\'' && ch != '"') || !isEscaped(line, col));
    }

    private static boolean isEscaped(String line, int column) {
        int backslashes = 0;
        for (int index = column - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return (backslashes & 1) != 0;
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

    private void wrapSelectionWithPair(char open) {
        SelectionRange range = currentSelection();
        if (range == null) {
            return;
        }
        char close = closingPairFor(open);
        ViewState before = captureViewState();
        List<TrackedEdit> ops = new ArrayList<>(2);
        // Keep the selected content and its original line separators in place.
        // Inserting from the end also keeps both original positions valid.
        applyReplaceAndTrack(ops, range.end(), range.end(), String.valueOf(close));
        Position wrappedStart = applyReplaceAndTrack(
            ops, range.start(), range.start(), String.valueOf(open)
        );
        Position wrappedEnd = range.start().line() == range.end().line()
            ? new Position(range.end().line(), range.end().col() + 1)
            : range.end();
        state.setSelectionAnchor(wrappedStart);
        state.setCursor(wrappedEnd);
        commitOperationEdit(EditKind.NONE, before, ops);
    }

    private SelectionRange pairRangeAroundCursor() {
        Position cursor = model.clamp(state.getCursor());
        if (cursor.col() <= 0) {
            return null;
        }
        String line = model.getLine(cursor.line());
        if (cursor.col() >= line.length()
            || !isMatchingPair(line.charAt(cursor.col() - 1), line.charAt(cursor.col()))) {
            return null;
        }
        GraphemeText graphemes = graphemes(line);
        return new SelectionRange(
            new Position(cursor.line(), graphemes.previousBoundary(cursor.col())),
            new Position(cursor.line(), graphemes.nextBoundary(cursor.col()))
        );
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

    private boolean canTerminateMarkdownList(Position cursor) {
        if (languageProfile() != LanguageProfile.MARKDOWN) {
            return false;
        }
        String line = model.getLine(cursor.line());
        int markerEnd = markdownListMarkerEnd(line);
        if (markerEnd < 0 || cursor.col() < markerEnd || !line.substring(markerEnd).isBlank()
            || isInsideMarkdownFence(cursor.line())) {
            return false;
        }
        int indent = leadingIndentLength(line);
        if (textLayout.visualColumnAt(cursor.line(), indent, tabWidth) < 4) {
            return true;
        }
        // Four-space/tab indentation can instead be an indented code block.
        // Only terminate a deeply indented item when a surrounding list is visible.
        int indentColumn = textLayout.visualColumnAt(cursor.line(), indent, tabWidth);
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        for (int previous = cursor.line() - 1; previous >= Math.max(0, cursor.line() - ASSIST_LOOKBACK_LINES); previous--) {
            String text = model.getLine(previous);
            remainingChars -= text.length();
            if (remainingChars < 0 || text.isBlank() || fenceMarker(text.stripLeading()) != null) {
                return false;
            }
            int previousIndent = leadingIndentLength(text);
            if (previousIndent < indent) {
                int contentStart = markdownListPrefixEnd(text);
                if (contentStart < 0) {
                    return false;
                }
                int firstContentColumn = textLayout.visualColumnAt(previous, contentStart, tabWidth);
                while (contentStart < text.length() && (text.charAt(contentStart) == ' ' || text.charAt(contentStart) == '\t')) {
                    contentStart++;
                }
                int contentColumn = textLayout.visualColumnAt(previous, contentStart, tabWidth);
                if (contentColumn - firstContentColumn >= 4 || indentColumn >= contentColumn + 4) {
                    return false;
                }
                if (textLayout.visualColumnAt(previous, previousIndent, tabWidth) < 4) {
                    return true;
                }
                indent = previousIndent;
                indentColumn = textLayout.visualColumnAt(previous, previousIndent, tabWidth);
            }
        }
        return false;
    }

    private static int markdownListMarkerEnd(String line) {
        int markerEnd = markdownListPrefixEnd(line);
        if (markerEnd < 0) {
            return -1;
        }
        int end = markerEnd;
        while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
            end++;
        }
        if (end + 3 <= line.length() && line.charAt(end) == '[' && line.charAt(end + 2) == ']'
            && (line.charAt(end + 1) == ' ' || line.charAt(end + 1) == 'x' || line.charAt(end + 1) == 'X')
            && (end + 3 == line.length() || line.charAt(end + 3) == ' ' || line.charAt(end + 3) == '\t')) {
            return end + 3;
        }
        return markerEnd;
    }

    private static int markdownListPrefixEnd(String line) {
        int start = leadingIndentLength(line);
        if (start >= line.length()) {
            return -1;
        }
        int end = start + 1;
        char marker = line.charAt(start);
        if (marker != '-' && marker != '*' && marker != '+') {
            end = start;
            while (end < line.length() && line.charAt(end) >= '0' && line.charAt(end) <= '9') {
                end++;
            }
            if (end == start || end - start > 9 || end >= line.length()
                || (line.charAt(end) != '.' && line.charAt(end) != ')')) {
                return -1;
            }
            end++;
        }
        if (end >= line.length() || (line.charAt(end) != ' ' && line.charAt(end) != '\t')) {
            return -1;
        }
        return end + 1;
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
            String line = model.getLine(i);
            String fence = fenceMarker(line);
            if (fence == null) {
                continue;
            }
            if (activeFence == null) {
                activeFence = fence;
                continue;
            }
            if (activeFence.charAt(0) == fence.charAt(0) && fence.length() >= activeFence.length()
                && line.stripLeading().substring(fence.length()).isBlank()) {
                activeFence = null;
            }
        }
        return activeFence != null;
    }

    private static String fenceMarker(String line) {
        if (line == null || line.length() < 3) {
            return null;
        }
        int start = 0;
        while (start < line.length() && start < 3 && line.charAt(start) == ' ') {
            start++;
        }
        if (start >= line.length()) {
            return null;
        }
        char ch = line.charAt(start);
        if (ch != '`' && ch != '~') {
            return null;
        }
        int count = 1;
        while (start + count < line.length() && line.charAt(start + count) == ch) {
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

    private SelectionRange indentUnitBackwardRange() {
        Position cursor = model.clamp(state.getCursor());
        if (cursor.col() <= 0) {
            return null;
        }
        String line = model.getLine(cursor.line());
        int col = cursor.col();
        if (!isLeadingWhitespaceUntil(line, col)) {
            return null;
        }
        String currentIndent = line.substring(0, col);
        String reduced = removeOneIndentUnit(currentIndent, cursor.line());
        int drop = currentIndent.length() - reduced.length();
        return drop <= 0 ? null : new SelectionRange(
            new Position(cursor.line(), col - drop), cursor
        );
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

    private TrackedEdit maybeAutoOutdentKeywordLineOp() {
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
        String currentIndent = line.substring(0, leading);
        if (!startsWithDedentKeyword(line, leading, cursorCol, profile)) {
            return null;
        }
        String reduced = removeOneIndentUnit(currentIndent, lineIndex);
        int drop = currentIndent.length() - reduced.length();
        String keyword = leadingKeyword(line.substring(leading));
        if (drop <= 0 || !(profile == LanguageProfile.PYTHON
            ? shouldDedentPythonClause(lineIndex, keyword, currentIndent, reduced)
            : shouldDedentCodeClause(lineIndex, keyword, currentIndent, reduced))) {
            return null;
        }
        Position deleteStart = new Position(lineIndex, 0);
        Position deleteEnd = new Position(lineIndex, drop);
        String deleted = model.getTextRange(deleteStart, deleteEnd);
        model.deleteRange(deleteStart, deleteEnd);
        state.setCursor(new Position(lineIndex, Math.max(0, cursorCol - drop)));
        return new TrackedEdit(EditOp.delete(deleteStart, deleted), List.of(), List.of());
    }

    private boolean shouldDedentPythonClause(int lineIndex, String keyword, String currentIndent, String reduced) {
        int firstLine = Math.max(0, lineIndex - ASSIST_LOOKBACK_LINES);
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        for (int previous = lineIndex - 1; previous >= firstLine; previous--) {
            String text = model.getLine(previous);
            remainingChars -= text.length();
            if (remainingChars < 0) {
                return false;
            }
            String code = IndentationSyntax.codeBeforeCursor(text, text.length(), state.getCurrentFile()).stripTrailing();
            if (code.isBlank()) {
                continue;
            }
            if (IndentationSyntax.maskOrdinaryLiterals(code) == null) {
                return false;
            }
            int leading = leadingIndentLength(text);
            if (leading > currentIndent.length()) {
                continue;
            }
            String indent = text.substring(0, leading);
            if (!currentIndent.startsWith(indent) || leading < reduced.length()) {
                return false;
            }
            String header = code.substring(leading);
            String owner = leadingKeyword(header);
            if (owner.equals("async")) {
                header = header.substring(owner.length()).stripLeading();
                owner = leadingKeyword(header);
            }
            if (owner.equals("def") || owner.equals("class")) {
                return false;
            }
            int colon = pythonClauseColon(header);
            if (colon < 0 || !isPythonBlockIntro(header.substring(0, colon + 1))) {
                if (leading < currentIndent.length() || switch (owner) {
                    case "if", "elif", "else", "for", "while", "with", "try", "except", "finally" -> true;
                    default -> false;
                }) {
                    return false;
                }
                continue;
            }
            boolean compatible = switch (keyword) {
                case "elif" -> owner.equals("if") || owner.equals("elif");
                case "else" -> owner.equals("if") || owner.equals("elif") || owner.equals("for")
                    || owner.equals("while") || owner.equals("try") || owner.equals("except");
                case "except", "finally" -> owner.equals("try") || owner.equals("except");
                case "case" -> owner.equals("case") && !header.substring(owner.length()).stripLeading().startsWith("=");
                default -> false;
            };
            if (compatible) {
                // A clause aligned with its owner or an existing sibling needs no correction.
                return leading == reduced.length()
                    && hasCertainContextBefore(previous, leading, firstLine, remainingChars);
            }
            if (!keyword.equals("finally") || !owner.equals("else") || leading != reduced.length()) {
                return false;
            }
        }
        return false;
    }

    private static int pythonClauseColon(String header) {
        char quote = 0;
        Deque<Character> brackets = new ArrayDeque<>();
        for (int index = 0; index < header.length(); index++) {
            char ch = header.charAt(index);
            if (quote != 0) {
                if (ch == '\\') {
                    index++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                brackets.push(ch);
            } else if (isClosingBracket(ch)) {
                if (brackets.isEmpty() || !isMatchingPair(brackets.pop(), ch)) {
                    return -1;
                }
            } else if (ch == ':' && brackets.isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private boolean shouldDedentCodeClause(int lineIndex, String keyword, String currentIndent, String reduced) {
        int firstLine = Math.max(0, lineIndex - ASSIST_LOOKBACK_LINES);
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        int closedBraces = 0;
        boolean label = keyword.equals("case") || keyword.equals("default");
        for (int previous = lineIndex - 1; previous >= firstLine; previous--) {
            String text = model.getLine(previous);
            remainingChars -= text.length();
            if (remainingChars < 0) {
                return false;
            }
            String code = IndentationSyntax.codeBeforeCursor(text, text.length(), state.getCurrentFile()).stripTrailing();
            if (code.isBlank()) {
                continue;
            }
            if (!label) {
                return text.substring(0, leadingIndentLength(text)).equals(currentIndent)
                    && code.stripLeading().startsWith("}") && startsWithStandaloneClosing(code)
                    && shouldDedentFromClosing(previous, text, text.length(), currentIndent);
            }
            code = IndentationSyntax.maskOrdinaryLiterals(code);
            if (code == null || code.indexOf('/') >= 0) {
                return false;
            }
            for (int index = code.length() - 1; index >= 0; index--) {
                if (code.charAt(index) == '}') {
                    closedBraces++;
                } else if (code.charAt(index) == '{') {
                    if (closedBraces == 0) {
                        return false;
                    }
                    closedBraces--;
                }
            }
            int leading = leadingIndentLength(text);
            if (closedBraces > 0 || leading > currentIndent.length()) {
                continue;
            }
            String indent = text.substring(0, leading);
            if (!currentIndent.startsWith(indent) || leading < reduced.length()) {
                return false;
            }
            String owner = leadingKeyword(code.substring(leading));
            if (owner.equals("case") || owner.equals("default")) {
                return leading == reduced.length()
                    && hasCertainContextBefore(previous, leading, firstLine, remainingChars);
            }
        }
        return false;
    }

    private static String leadingKeyword(String text) {
        int end = 0;
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    private boolean startsWithDedentKeyword(String line, int leading, int cursorCol, LanguageProfile profile) {
        int tokenEnd = leading;
        while (tokenEnd < line.length() && isWordChar(line.charAt(tokenEnd))) {
            tokenEnd++;
        }
        // A token still being typed may grow into an identifier such as "elsewhere".
        if (tokenEnd <= leading || cursorCol <= tokenEnd) {
            return false;
        }
        String keyword = line.substring(leading, tokenEnd);
        if (!profile.dedentKeywords().contains(keyword)) {
            return false;
        }
        if (profile == LanguageProfile.PYTHON && keyword.equals("case")) {
            String before = line.substring(leading, cursorCol).stripTrailing();
            return !before.substring(keyword.length()).stripLeading().startsWith("=") && isPythonBlockIntro(before);
        }
        for (int i = tokenEnd; i < cursorCol; i++) {
            char ch = line.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return i == cursorCol - 1 && (ch == ':' || ch == '('
                    || (profile == LanguageProfile.GENERIC_CODE && ch == '{')
                    || (profile == LanguageProfile.PYTHON && keyword.equals("except") && ch == '*'));
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
        long beforeStateId = currentDocumentStateId;
        Snapshot before = captureSnapshot();
        editOperation.run();
        Snapshot after = captureSnapshot();
        if (after.equals(before)) {
            return;
        }
        long afterStateId = nextDocumentStateId();
        currentDocumentStateId = afterStateId;
        long now = System.nanoTime();
        boolean merged = shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && tryMergeSnapshotEntry(kind, after, afterStateId);
        if (!merged) {
            pushWithLimit(
                undoStack,
                new SnapshotHistoryEntry(kind, before, after, beforeStateId, afterStateId)
            );
        }
        redoStack.clear();
        updateDirtyFromSavepoint();
        lastEditKind = kind;
        lastEditAtNanos = now;
        lastEditCursorAfter = after.cursor();
        gutterDiffComputedRevision = Long.MIN_VALUE;
    }

    private void commitOperationEdit(EditKind kind, ViewState before, List<TrackedEdit> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
        long beforeStateId = currentDocumentStateId;
        ViewState after = captureViewState();
        long afterStateId = nextDocumentStateId();
        currentDocumentStateId = afterStateId;
        long now = System.nanoTime();
        boolean merged = shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && tryMergeOperationEntry(kind, after, ops, afterStateId);
        if (!merged) {
            pushWithLimit(
                undoStack,
                new OperationHistoryEntry(
                    kind,
                    before,
                    after,
                    ops,
                    beforeStateId,
                    afterStateId
                )
            );
        }
        redoStack.clear();
        updateDirtyFromSavepoint();
        lastEditKind = kind;
        lastEditAtNanos = now;
        lastEditCursorAfter = after.cursor();
    }

    private boolean tryMergeSnapshotEntry(EditKind kind, Snapshot after, long afterStateId) {
        HistoryEntry top = undoStack.peek();
        if (!(top instanceof SnapshotHistoryEntry entry) || entry.kind() != kind) {
            return false;
        }
        entry.setAfter(after, afterStateId);
        return true;
    }

    private boolean tryMergeOperationEntry(
        EditKind kind,
        ViewState after,
        List<TrackedEdit> ops,
        long afterStateId
    ) {
        HistoryEntry top = undoStack.peek();
        if (!(top instanceof OperationHistoryEntry entry) || entry.kind() != kind) {
            return false;
        }
        entry.append(ops, after, afterStateId);
        return true;
    }

    private Position applyReplaceAndTrack(
        List<TrackedEdit> ops,
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
        String inserted = normalizeInsertedText(insertedText == null ? "" : insertedText);
        LogicalDocumentLines.requireWellFormedUtf16(inserted);
        if (deletedText.isEmpty() && inserted.isEmpty()) {
            return a;
        }
        List<String> deletedSeparators = List.copyOf(
            model.fileMetadata().lineSeparators().subList(a.line(), b.line())
        );
        Position cursor = model.deleteRange(a, b);
        if (!inserted.isEmpty()) {
            cursor = model.insertText(cursor, inserted);
        }
        List<String> insertedSeparators = List.copyOf(
            model.fileMetadata().lineSeparators().subList(a.line(), cursor.line())
        );
        EditOp.ReplaceRange op = EditOp.replace(a, deletedText, inserted);
        ops.add(new TrackedEdit(op, deletedSeparators, insertedSeparators));
        applyGutterDiffOp(op);
        return cursor;
    }

    private static int comparePositions(Position left, Position right) {
        if (left.line() != right.line()) {
            return Integer.compare(left.line(), right.line());
        }
        return Integer.compare(left.col(), right.col());
    }

    private Position clampCursorBoundary(Position requested) {
        Position clamped = model.clamp(requested);
        String line = model.getLine(clamped.line());
        return new Position(clamped.line(), graphemes(line).floorBoundary(clamped.col()));
    }

    private GraphemeText graphemes(String text) {
        // Strings are immutable: identity also makes direct model edits and undo safe,
        // without retaining layouts for every line or for old document revisions.
        if (cachedGraphemes == null || cachedGraphemes.text() != text) {
            cachedGraphemes = GraphemeText.of(text);
        }
        return cachedGraphemes;
    }

    private Position requireTextBoundary(Position requested) {
        Position clamped = model.clamp(requested);
        String line = model.getLine(clamped.line());
        int offset = clamped.col();
        if (offset > 0
            && offset < line.length()
            && Character.isHighSurrogate(line.charAt(offset - 1))
            && Character.isLowSurrogate(line.charAt(offset))) {
            throw new IllegalArgumentException("Text selection splits a UTF-16 surrogate pair");
        }
        return clamped;
    }

    private List<TextReplacement> validateTextReplacements(List<TextReplacement> replacements) {
        Objects.requireNonNull(replacements, "replacements");
        List<TextReplacement> canonical = new ArrayList<>(replacements.size());
        for (int index = 0; index < replacements.size(); index++) {
            TextReplacement replacement = Objects.requireNonNull(
                replacements.get(index),
                "replacement " + index
            );
            requireTextPosition(replacement.start(), index, "start");
            requireTextPosition(replacement.end(), index, "end");
            try {
                LogicalDocumentLines.requireWellFormedUtf16(replacement.replacement());
            } catch (IllegalArgumentException e) {
                throw invalidReplacement(index, "contains malformed UTF-16 text");
            }
            canonical.add(replacement);
        }
        canonical.sort(
            Comparator.comparing(TextReplacement::start, EditorEngine::comparePositions)
                .thenComparing(TextReplacement::end, EditorEngine::comparePositions)
        );
        for (int index = 1; index < canonical.size(); index++) {
            TextReplacement previous = canonical.get(index - 1);
            TextReplacement current = canonical.get(index);
            if (comparePositions(previous.end(), current.start()) > 0
                || previous.start().equals(current.start())) {
                throw new IllegalArgumentException(
                    "Text replacements must not overlap or share an insertion point"
                );
            }
        }
        return List.copyOf(canonical);
    }

    private void requireTextPosition(Position position, int replacementIndex, String endpoint) {
        if (position.line() >= model.lineCount()) {
            throw invalidReplacement(replacementIndex, endpoint + " line is outside the document");
        }
        String line = model.getLine(position.line());
        if (position.col() > line.length()) {
            throw invalidReplacement(replacementIndex, endpoint + " offset is outside the line");
        }
        int offset = position.col();
        if (offset > 0
            && offset < line.length()
            && Character.isHighSurrogate(line.charAt(offset - 1))
            && Character.isLowSurrogate(line.charAt(offset))) {
            throw invalidReplacement(replacementIndex, endpoint + " splits a surrogate pair");
        }
    }

    private static IllegalArgumentException invalidReplacement(int index, String problem) {
        return new IllegalArgumentException("Replacement " + index + " " + problem);
    }

    private static String normalizeInsertedText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void resetGutterDiffBaselinesOnOpen() {
        cachedGraphemes = null;
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

    private long nextDocumentStateId() {
        return ++nextDocumentStateId;
    }

    private void beginNewDocumentState() {
        currentDocumentStateId = nextDocumentStateId();
    }

    private void markCurrentAsSaved() {
        savedDocumentStateId = currentDocumentStateId;
        state.setDirty(false);
    }

    private void updateDirtyFromSavepoint() {
        state.setDirty(savedDocumentStateId < 0L || currentDocumentStateId != savedDocumentStateId);
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
        EditList savedEdits = BoundedLineDiff.compute(
            algorithm, savedBaselineLines, current, GUTTER_DIFF_MAX_WORK
        );
        applyDiffEdits(savedEdits, lines, savedDiffFlags, savedDeletedMarkers);

        EditList openEdits = openBaselineLines.equals(savedBaselineLines)
            ? savedEdits
            : BoundedLineDiff.compute(algorithm, openBaselineLines, current, GUTTER_DIFF_MAX_WORK);
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

    private ViewState captureViewState() {
        DocumentFileMetadata metadata = model.fileMetadata();
        return new ViewState(
            metadata.charset(),
            metadata.bomKind(),
            metadata.defaultLineSeparator(),
            state.getCursor(),
            state.getTopLine(),
            state.getTopWrapSegment(),
            state.getLeftColumn(),
            state.getLeftVisualColumn(),
            state.getSelectionAnchor()
        );
    }

    private void restoreViewState(ViewState view) {
        DocumentFileMetadata metadata = model.fileMetadata();
        if (!metadata.charset().equals(view.charset())
            || metadata.bomKind() != view.bomKind()
            || !metadata.defaultLineSeparator().equals(view.defaultLineSeparator())) {
            model.restoreFileMetadata(new DocumentFileMetadata(
                view.charset(), view.bomKind(), view.defaultLineSeparator(), metadata.lineSeparators()
            ));
        }
        state.setCursor(model.clamp(view.cursor()));
        state.setTopLine(view.topLine());
        state.setTopWrapSegment(view.topWrapSegment());
        state.setLeftColumn(view.leftColumn());
        state.setLeftVisualColumn(view.leftVisualColumn());
        state.setSelectionAnchor(view.selectionAnchor());
    }

    private Snapshot captureSnapshot() {
        return new Snapshot(
            model.snapshotLines(),
            model.fileMetadata(),
            state.getCursor(),
            state.getTopLine(),
            state.getTopWrapSegment(),
            state.getLeftColumn(),
            state.getLeftVisualColumn(),
            state.getSelectionAnchor()
        );
    }

    private void restoreSnapshot(Snapshot snapshot) {
        model.replaceLines(snapshot.lines(), snapshot.fileMetadata());
        restoreViewState(new ViewState(
            snapshot.fileMetadata().charset(),
            snapshot.fileMetadata().bomKind(),
            snapshot.fileMetadata().defaultLineSeparator(),
            snapshot.cursor(),
            snapshot.topLine(),
            snapshot.topWrapSegment(),
            snapshot.leftColumn(),
            snapshot.leftVisualColumn(),
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
        DocumentFileMetadata fileMetadata,
        Position cursor,
        int topLine,
        int topWrapSegment,
        int leftColumn,
        int leftVisualColumn,
        Position selectionAnchor
    ) {
    }

    private record ViewState(
        Charset charset,
        BomKind bomKind,
        String defaultLineSeparator,
        Position cursor,
        int topLine,
        int topWrapSegment,
        int leftColumn,
        int leftVisualColumn,
        Position selectionAnchor
    ) {
    }

    /** Separator slices are copied so history never retains whole metadata lists. */
    private record TrackedEdit(
        EditOp.ReplaceRange operation,
        List<String> deletedSeparators,
        List<String> insertedSeparators
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
        private final long beforeStateId;
        private long afterStateId;

        private SnapshotHistoryEntry(
            EditKind kind,
            Snapshot before,
            Snapshot after,
            long beforeStateId,
            long afterStateId
        ) {
            this.kind = kind;
            this.before = before;
            this.after = after;
            this.beforeStateId = beforeStateId;
            this.afterStateId = afterStateId;
        }

        EditKind kind() {
            return kind;
        }

        void setAfter(Snapshot after, long nextAfterStateId) {
            this.after = after;
            this.afterStateId = nextAfterStateId;
        }

        @Override
        public void undo() {
            restoreSnapshot(before);
            currentDocumentStateId = beforeStateId;
        }

        @Override
        public void redo() {
            restoreSnapshot(after);
            currentDocumentStateId = afterStateId;
        }
    }

    private final class OperationHistoryEntry implements HistoryEntry {
        private final EditKind kind;
        private final ViewState beforeView;
        private ViewState afterView;
        private final List<TrackedEdit> ops;
        private final long beforeStateId;
        private long afterStateId;

        private OperationHistoryEntry(
            EditKind kind,
            ViewState beforeView,
            ViewState afterView,
            List<TrackedEdit> ops,
            long beforeStateId,
            long afterStateId
        ) {
            this.kind = kind;
            this.beforeView = beforeView;
            this.afterView = afterView;
            this.ops = new ArrayList<>(ops);
            this.beforeStateId = beforeStateId;
            this.afterStateId = afterStateId;
        }

        EditKind kind() {
            return kind;
        }

        void append(
            List<TrackedEdit> extraOps,
            ViewState mergedAfterView,
            long mergedAfterStateId
        ) {
            this.ops.addAll(extraOps);
            this.afterView = mergedAfterView;
            this.afterStateId = mergedAfterStateId;
        }

        @Override
        public void undo() {
            for (int i = ops.size() - 1; i >= 0; i--) {
                TrackedEdit edit = ops.get(i);
                EditOp.ReplaceRange op = edit.operation();
                applyReplace(op.start(), op.insertedText(), op.deletedText(), edit.deletedSeparators());
            }
            restoreViewState(beforeView);
            currentDocumentStateId = beforeStateId;
        }

        @Override
        public void redo() {
            for (TrackedEdit edit : ops) {
                EditOp.ReplaceRange op = edit.operation();
                applyReplace(op.start(), op.deletedText(), op.insertedText(), edit.insertedSeparators());
            }
            restoreViewState(afterView);
            currentDocumentStateId = afterStateId;
        }

        private void applyReplace(
            Position start,
            String deletedText,
            String insertedText,
            List<String> insertedSeparators
        ) {
            Position a = model.clamp(start);
            Position b = advancePosition(a, deletedText);
            Position cursor = model.deleteRange(a, b);
            if (insertedText != null && !insertedText.isEmpty()) {
                model.insertText(cursor, insertedText);
            }
            model.restoreLineSeparators(a.line(), insertedSeparators);
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

    private record LineCommentEdit(int line, int column, int removedLength, int insertedLength) {
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        return query;
    }

    private SearchMatch findForward(Position from, SearchMatcher matcher, boolean wrap) {
        int lineCount = model.lineCount();
        for (int line = from.line(); line < lineCount; line++) {
            String text = model.getLine(line);
            int startCol = line == from.line() ? Math.min(from.col(), text.length()) : 0;
            SearchMatch match = findInLineForward(line, text, matcher, startCol);
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
            SearchMatch match = findInLineForward(line, text, matcher, 0);
            if (match != null && match.start().col() < limit) {
                return match;
            }
        }
        return null;
    }

    private SearchMatch findBackward(Position from, SearchMatcher matcher, boolean wrap) {
        for (int line = from.line(); line >= 0; line--) {
            String text = model.getLine(line);
            int endExclusive = line == from.line() ? Math.min(from.col(), text.length()) : text.length();
            SearchMatch match = findInLineBackward(line, text, matcher, endExclusive);
            if (match != null) {
                return match;
            }
        }
        if (!wrap) {
            return null;
        }
        for (int line = model.lineCount() - 1; line >= from.line(); line--) {
            String text = model.getLine(line);
            int startInclusive = line == from.line() ? Math.min(from.col(), text.length()) : 0;
            SearchMatch match = findInLineBackward(line, text, matcher, text.length());
            if (match != null && match.start().col() >= startInclusive) {
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

    private boolean matchesSelectedText(SearchMatcher matcher, SelectionRange selected) {
        if (selected.start().line() != selected.end().line()) {
            return false;
        }
        String line = model.getLine(selected.start().line());
        return matcher.matchesRange(line, selected.start().col(), selected.end().col());
    }

    private SearchMatch findInLineForward(int line, String text, SearchMatcher matcher, int fromCol) {
        SearchMatcher.Match match = matcher.findFirstAtOrAfter(text, fromCol);
        return match == null ? null
            : new SearchMatch(new Position(line, match.startUtf16()), match.lengthUtf16());
    }

    private SearchMatch findInLineBackward(int line, String text, SearchMatcher matcher, int beforeCol) {
        SearchMatcher.Match match = matcher.findLastBefore(text, beforeCol);
        return match == null ? null
            : new SearchMatch(new Position(line, match.startUtf16()), match.lengthUtf16());
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

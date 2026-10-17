package dev.tuink.core;

import dev.tuink.core.TextModel.ReplacementDelta;
import dev.tuink.unicode.GraphemeText;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;

/**
 * Editing adapter for one attached view of an {@link EditorDocument}.
 * The document owns text, file state and history; this adapter applies its view's editing commands.
 */
public class EditorEngine {
    private static final int MAX_HISTORY = 500;
    private static final long COALESCE_WINDOW_NANOS = 700_000_000L;
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
    private final EditorDocument document;
    private final EditorDocumentHistory history;
    private final EditorGutterDiffState gutterDiff = new EditorGutterDiffState();
    private final TextModel model;
    private final EditorState state;
    private final EditorTextLayout textLayout;
    private final EditorClipboard clipboard;
    private final LongSupplier gutterDiffClock;
    private GraphemeText cachedGraphemes;
    private int tabWidth = 4;
    private TabInsertMode tabInsertMode = TabInsertMode.AUTO;
    private boolean autoIndentEnabled = true;
    private boolean autoPairsEnabled = true;
    private boolean autoListEnabled = true;

    public EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout) {
        this(model, state, textLayout, new EditorClipboard());
    }

    public EditorEngine(
        TextModel model,
        EditorState state,
        EditorTextLayout textLayout,
        EditorClipboard clipboard
    ) {
        this(initialDocument(model, state, textLayout, clipboard), state, textLayout, clipboard);
    }

    EditorEngine(EditorDocument document, EditorState state, EditorTextLayout textLayout, EditorClipboard clipboard) {
        this(document, state, textLayout, clipboard, System::nanoTime);
    }

    EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LongSupplier gutterDiffClock) {
        this(initialDocument(model, state, textLayout, clipboard, gutterDiffClock),
            state, textLayout, clipboard, gutterDiffClock);
    }

    private EditorEngine(EditorDocument document, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LongSupplier gutterDiffClock) {
        this.document = Objects.requireNonNull(document, "document");
        this.history = document.history;
        this.model = document.model();
        this.state = Objects.requireNonNull(state, "state");
        if (textLayout == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to the engine model");
        }
        this.textLayout = textLayout;
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.gutterDiffClock = Objects.requireNonNull(gutterDiffClock, "gutterDiffClock");
        if (document.register(state)) {
            resetGutterDiffBaselinesOnOpen();
            if (state.isDirty()) {
                history.savedDocumentStateId = -1L;
            } else {
                markCurrentAsSaved();
            }
        }
    }

    private static EditorDocument initialDocument(
        TextModel model, EditorState state, EditorTextLayout layout, EditorClipboard clipboard
    ) {
        Objects.requireNonNull(clipboard, "clipboard");
        if (model == null || state == null || layout == null || layout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to the engine model");
        }
        return new EditorDocument(model, state.documentState());
    }

    private static EditorDocument initialDocument(TextModel model, EditorState state, EditorTextLayout layout,
        EditorClipboard clipboard, LongSupplier gutterDiffClock) {
        Objects.requireNonNull(gutterDiffClock, "gutterDiffClock");
        return initialDocument(model, state, layout, clipboard);
    }

    public EditorDocument document() {
        return document;
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
        return state.navigationRevision();
    }

    public int tabWidth() {
        return tabWidth;
    }

    /**
     * Applies a prevalidated batch of document replacements as one atomic undo step.
     *
     * <p>Every range is checked against the same pre-edit model before any mutation.
     * Ranges use exact UTF-16 offsets, may not split surrogate pairs or overlap, and
     * are measured before allocation, then composed in one forward pass. The cursor is
     * moved to the end of the earliest applied replacement.</p>
     *
     * @return {@code true} when logical document content changed
     * @throws IllegalArgumentException when any replacement is invalid
     * @throws DocumentEditLimitException when the result cannot be built with available memory
     */
    public boolean applyTextReplacements(List<TextReplacement> replacements) {
        List<TextReplacement> canonical = validateTextReplacements(replacements);
        DocumentFileMetadata metadata = model.fileMetadata();
        ReplacementBuilder admission = ReplacementBuilder.counting(model.lineCount());
        if (composeTextReplacements(canonical, admission, metadata) == null) {
            return false;
        }
        ReplacementBuilder builder = admission.materializer();
        Position cursor = composeTextReplacements(canonical, builder, metadata);
        applyBuiltReplacement(builder, metadata, cursor);
        return true;
    }

    private Position composeTextReplacements(List<TextReplacement> replacements, ReplacementBuilder builder,
                                            DocumentFileMetadata metadata) {
        Position sourcePosition = new Position(0, 0);
        Position earliestResult = null;
        for (TextReplacement replacement : replacements) {
            ReplacementText text = new ReplacementText(replacement.replacement());
            if (text.matches(model, replacement.start(), replacement.end())) {
                continue;
            }
            appendOriginalRange(builder, sourcePosition, replacement.start(), metadata);
            builder.appendReplacement(text, metadata.defaultLineSeparator());
            if (earliestResult == null) {
                earliestResult = builder.position();
            }
            sourcePosition = replacement.end();
        }
        if (earliestResult != null) {
            int lastLine = model.lineCount() - 1;
            appendOriginalRange(builder, sourcePosition,
                new Position(lastLine, model.getLine(lastLine).length()), metadata);
            builder.finishLine();
        }
        return earliestResult;
    }

    private void applyBuiltReplacement(ReplacementBuilder builder, DocumentFileMetadata metadata, Position cursor) {
        EditorViewMemento after = editedView(new EditorViewMemento(state.viewId(), cursor, state.viewport(), null),
            builder.lines::get);
        state.invalidateNavigation();
        breakCoalescing();
        applyEdit(EditKind.NONE, () -> {
            model.replaceLines(builder.lines, metadata.withLineSeparators(builder.separators));
            state.setCursor(after.cursor());
            state.clearSelection();
        });
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

    /** Moves the primary cursor through the engine-owned mutation boundary. */
    public void moveCursorTo(Position requested) {
        navigateTo(requested, false);
    }

    /**
     * Applies a frontend-resolved navigation target through the selection and history boundary.
     * Even a boundary no-op clears an unextended selection and separates consecutive typing edits.
     */
    public void navigateTo(Position requested, boolean selecting) {
        state.invalidateNavigation();
        Position previous = clampCursorBoundary(state.getCursor());
        moveCursor(clampCursorBoundary(requested), selecting, previous);
    }

    /** Starts a pointer selection without exposing mutable cursor state to a frontend. */
    public void beginSelectionAt(Position requested) {
        state.invalidateNavigation();
        breakCoalescing();
        Position next = clampCursorBoundary(requested);
        state.setSelectionAnchor(next);
        state.setCursor(next);
    }

    /** Extends the current selection to a frontend-resolved text position. */
    public void extendSelectionTo(Position requested) {
        state.invalidateNavigation();
        breakCoalescing();
        if (state.getSelectionAnchor() == null) {
            state.setSelectionAnchor(state.getCursor());
        }
        state.setCursor(clampCursorBoundary(requested));
    }

    /** Restores a cursor/selection snapshot after speculative frontend routing. */
    public void restoreSelection(Position cursor, Position anchor) {
        state.invalidateNavigation();
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
        state.invalidateNavigation();
        history.undoStack.clear();
        history.redoStack.clear();
        breakCoalescing();
        resetGutterDiffBaselinesOnOpen();
        beginNewDocumentState();
        if (snapshot.dirty()) {
            history.savedDocumentStateId = -1L;
            state.setDirty(true);
        } else {
            markCurrentAsSaved();
        }
        document.changed();
    }

    public GutterDiffMarker gutterDiffMarkerForLine(int lineIndex) {
        refreshGutterDiffIfDue();
        return cachedGutterDiffMarkerForLine(lineIndex);
    }

    /** Prepares owner-thread reads without recomputing the diff between lines of a frame. */
    public IntFunction<GutterDiffMarker> gutterDiffMarkers() {
        refreshGutterDiffIfDue();
        return this::cachedGutterDiffMarkerForLine;
    }

    private GutterDiffMarker cachedGutterDiffMarkerForLine(int lineIndex) {
        if (gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            return GutterDiffMarker.NONE;
        }
        if (lineIndex < 0 || lineIndex >= model.lineCount()) {
            return GutterDiffMarker.NONE;
        }
        if (lineIndex < gutterDiff.savedDeletedMarkers.size() && Boolean.TRUE.equals(gutterDiff.savedDeletedMarkers.get(lineIndex))) {
            return new GutterDiffMarker(GutterDiffFlag.DELETED, false);
        }
        if (lineIndex < gutterDiff.savedDiffFlags.size()) {
            GutterDiffFlag direct = gutterDiff.savedDiffFlags.get(lineIndex);
            if (direct != null && direct != GutterDiffFlag.NONE) {
                return new GutterDiffMarker(direct, false);
            }
        }
        if (lineIndex < gutterDiff.openDeletedMarkers.size() && Boolean.TRUE.equals(gutterDiff.openDeletedMarkers.get(lineIndex))) {
            return new GutterDiffMarker(GutterDiffFlag.DELETED, true);
        }
        if (lineIndex < gutterDiff.openDiffFlags.size()) {
            GutterDiffFlag direct = gutterDiff.openDiffFlags.get(lineIndex);
            if (direct != null && direct != GutterDiffFlag.NONE) {
                return new GutterDiffMarker(direct, true);
            }
        }
        return GutterDiffMarker.NONE;
    }

    /** Time until this view's deferred diff is ready, or a negative value when no update is pending. */
    public long nanosUntilGutterDiffRefresh(long nowNanos) {
        if (!needsGutterDiffRefresh()) {
            return -1L;
        }
        if (!gutterDiff.gutterDiffPendingRebuild || gutterDiff.gutterDiffLastRebuildAtNanos == 0L) {
            return 0L;
        }
        long minInterval = model.lineCount() >= GUTTER_DIFF_LARGE_FILE_THRESHOLD_LINES
            ? GUTTER_DIFF_MIN_REBUILD_INTERVAL_LARGE_NANOS
            : GUTTER_DIFF_MIN_REBUILD_INTERVAL_NANOS;
        if (gutterDiff.gutterDiffLastRebuildDurationNanos > 0L) {
            long adaptive = gutterDiff.gutterDiffLastRebuildDurationNanos * GUTTER_DIFF_DURATION_FACTOR;
            minInterval = Math.max(minInterval, Math.min(adaptive, GUTTER_DIFF_MAX_REBUILD_INTERVAL_NANOS));
        }
        return Math.max(0L, minInterval - (nowNanos - gutterDiff.gutterDiffLastRebuildAtNanos));
    }

    /** Completes due derived work; the frontend decides whether a new frame is needed. */
    public boolean refreshGutterDiffIfDue() {
        if (nanosUntilGutterDiffRefresh(gutterDiffClock.getAsLong()) != 0L) {
            return false;
        }
        rebuildGutterDiffFromCurrentState();
        return true;
    }

    public void setTabWidth(int tabWidth) {
        int nextWidth = tabWidth <= 2 ? 2 : 4;
        if (this.tabWidth != nextWidth) {
            state.invalidateNavigation();
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
        if (gutterDiff.gutterDiffMode == nextMode) {
            return;
        }
        gutterDiff.gutterDiffMode = nextMode;
        if (gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            gutterDiff.gutterDiffComputedRevision = model.revision();
            gutterDiff.gutterDiffPendingRebuild = false;
        } else {
            gutterDiff.gutterDiffComputedRevision = Long.MIN_VALUE;
            gutterDiff.gutterDiffPendingRebuild = true;
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
        state.invalidateNavigation();
        state.setTopLine(0);
        state.setLeftColumn(0);
        state.setLeftVisualColumn(0);
        state.setDirty(false);
        state.clearSelection();
        history.undoStack.clear();
        history.redoStack.clear();
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
        state.invalidateNavigation();
        Position p = state.getCursor();
        SelectionRange selection = selecting ? null : currentSelection();
        if (selection != null) {
            moveCursor(clampCursorBoundary(selection.start()), false, p);
            return;
        }
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
        state.invalidateNavigation();
        Position p = state.getCursor();
        SelectionRange selection = selecting ? null : currentSelection();
        if (selection != null) {
            Position end = model.clamp(selection.end());
            int endColumn = graphemes(model.getLine(end.line())).ceilBoundary(end.col());
            moveCursor(new Position(end.line(), endColumn), false, p);
            return;
        }
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
        state.invalidateNavigation();
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
        state.invalidateNavigation();
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
        state.invalidateNavigation();
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
        moveHome(false);
    }

    public void moveHome(boolean selecting) {
        navigateTo(new Position(state.getCursor().line(), 0), selecting);
    }

    public void moveEnd() {
        moveEnd(false);
    }

    public void moveEnd(boolean selecting) {
        Position p = state.getCursor();
        navigateTo(new Position(p.line(), model.getLine(p.line()).length()), selecting);
    }

    public void moveHomeSmart() {
        moveHomeSmart(false);
    }

    public void moveHomeSmart(boolean selecting) {
        Position p = state.getCursor();
        String line = model.getLine(p.line());
        int firstContentCol = firstNonWhitespaceColumn(line);
        int target = p.col() == firstContentCol ? 0 : firstContentCol;
        navigateTo(new Position(p.line(), target), selecting);
    }

    public void moveEndSmart() {
        moveEndSmart(false);
    }

    public void moveEndSmart(boolean selecting) {
        Position p = state.getCursor();
        String line = model.getLine(p.line());
        int trimmedEnd = trailingTrimEndColumn(line);
        int absoluteEnd = line.length();
        int target = p.col() == trimmedEnd ? absoluteEnd : trimmedEnd;
        navigateTo(new Position(p.line(), target), selecting);
    }

    public void moveDocumentStart(boolean selecting) {
        navigateTo(new Position(0, 0), selecting);
    }

    public void moveDocumentEnd(boolean selecting) {
        int last = model.lineCount() - 1;
        navigateTo(new Position(last, model.getLine(last).length()), selecting);
    }

    public void insertChar(char ch) {
        if (Character.isSurrogate(ch)) {
            throw new IllegalArgumentException("A standalone UTF-16 surrogate is not valid input");
        }
        state.invalidateNavigation();
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
        List<ReplacementDelta> ops = new ArrayList<>();
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
            ReplacementDelta outdent = maybeAutoOutdentKeywordLineOp();
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

        state.invalidateNavigation();
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, text);
        state.setCursor(afterInsert);
        ReplacementDelta outdent = maybeAutoOutdentKeywordLineOp();
        if (outdent != null) {
            ops.add(outdent);
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
    }

    public void insertTab() {
        state.invalidateNavigation();
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position cursor = model.clamp(rangeStart);
        String line = model.getLine(cursor.line());
        int col = Math.min(cursor.col(), line.length());
        String indentPrefix = line.substring(0, Math.min(col, leadingIndentLength(line)));
        String tabText = indentationDecisions().unit(cursor.line(), indentPrefix);
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, tabText);
        state.setCursor(afterInsert);
        ReplacementDelta outdent = maybeAutoOutdentKeywordLineOp();
        if (outdent != null) {
            ops.add(outdent);
        }
        state.clearSelection();
        commitOperationEdit(hadSelection ? EditKind.NONE : EditKind.INSERT_CHAR, before, ops);
    }

    public void backspace() {
        state.invalidateNavigation();
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
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
        state.invalidateNavigation();
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
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
        List<ReplacementDelta> ops,
        SelectionRange range
    ) {
        if (range != null) {
            state.setCursor(applyReplaceAndTrack(ops, range.start(), range.end(), ""));
        }
        state.clearSelection();
        commitOperationEdit(kind, before, ops);
    }

    public void insertNewline() {
        state.invalidateNavigation();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position at = model.clamp(rangeStart);
        TextReplacement listTermination = selection == null && isAutoListActive()
            && languageProfile() == LanguageProfile.MARKDOWN ? markdownListDecisions().termination(at) : null;
        if (listTermination != null) {
            state.setCursor(applyReplaceAndTrack(
                ops, listTermination.start(), listTermination.end(), listTermination.replacement()
            ));
            state.clearSelection();
            commitOperationEdit(EditKind.NONE, before, ops);
            return;
        }
        String indent = isAutoIndentActive() ? indentationDecisions().newLineIndent(at) : "";
        String continuation = isAutoListActive() ? continuationAfterNewLine(at) : "";
        String insertText = "\n" + indent + continuation;
        Position afterInsert = applyReplaceAndTrack(ops, rangeStart, rangeEnd, insertText);
        state.setCursor(afterInsert);
        state.clearSelection();
        commitOperationEdit(EditKind.NONE, before, ops);
    }

    public LineCommentResult toggleLineComment() {
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

        int nonBlankCount = 0;
        long copiedUnits = 0;
        long longestLine = 0;
        boolean uncomment = true;
        String commonIndent = null;
        int commonIndentLength = 0;
        for (int lineIndex = firstLine; lineIndex <= lastLine; lineIndex++) {
            String line = model.getLine(lineIndex);
            if (line.isBlank()) continue;
            nonBlankCount++;
            copiedUnits = DocumentEditBudget.add(copiedUnits, line.length() + marker.length() + 1L);
            longestLine = Math.max(longestLine, line.length() + marker.length() + 1L);
            if (lineCommentColumn(line, marker, lineIndex) < 0) uncomment = false;
            int indentLength = leadingIndentLength(line);
            if (commonIndent == null) {
                commonIndent = line;
                commonIndentLength = indentLength;
            } else {
                int shared = 0;
                int limit = Math.min(commonIndentLength, indentLength);
                while (shared < limit && commonIndent.charAt(shared) == line.charAt(shared)) shared++;
                commonIndentLength = shared;
            }
        }
        if (nonBlankCount == 0) {
            return new LineCommentResult(LineCommentAction.NO_CONTENT, 0);
        }
        DocumentEditBudget.currentHeap(model.lineCount())
            .requireLineReplacements(copiedUnits, nonBlankCount, longestLine);

        TextModel.PreparedLineReplacements prepared;
        ViewState before = captureViewState();
        ViewState after;
        Deque<HistoryEntry> nextUndo;
        LineCommentResult result;
        long afterStateId = history.nextDocumentStateId + 1;
        try {
            List<TextReplacement> replacements = new ArrayList<>(nonBlankCount);
            List<LineCommentEdit> positionEdits = new ArrayList<>(nonBlankCount);
            String inserted = uncomment ? "" : marker + " ";
            for (int lineIndex = firstLine; lineIndex <= lastLine; lineIndex++) {
                String line = model.getLine(lineIndex);
                if (line.isBlank()) continue;
                int column = uncomment ? lineCommentColumn(line, marker, lineIndex) : commonIndentLength;
                int removeLength = uncomment ? marker.length() : 0;
                int afterMarker = column + removeLength;
                if (uncomment && afterMarker < line.length() && line.charAt(afterMarker) == ' ') {
                    removeLength++;
                }
                replacements.add(new TextReplacement(new Position(lineIndex, column),
                    new Position(lineIndex, column + removeLength), inserted));
                positionEdits.add(new LineCommentEdit(lineIndex, column, removeLength, inserted.length()));
            }
            prepared = model.prepareLineReplacements(replacements);
            EditorViewMemento afterView = new EditorViewMemento(state.viewId(),
                transformPosition(cursorBefore, positionEdits), before.view().viewport(),
                anchorBefore == null ? null : transformPosition(anchorBefore, positionEdits));
            after = new ViewState(before.document(), editedView(afterView, prepared.buffer()::getLine));
            OperationHistoryEntry entry = new OperationHistoryEntry(EditKind.NONE, before, after,
                prepared.operations(), history.currentDocumentStateId, afterStateId);
            nextUndo = new ArrayDeque<>(history.undoStack);
            pushWithLimit(nextUndo, entry);
            result = new LineCommentResult(
                uncomment ? LineCommentAction.UNCOMMENTED : LineCommentAction.COMMENTED, nonBlankCount
            );
        } catch (OutOfMemoryError exhausted) {
            // The active buffer, view and history have not been touched.
            throw new DocumentEditLimitException();
        }
        model.publishLineReplacements(prepared);
        history.undoStack = nextUndo;
        history.nextDocumentStateId = afterStateId;
        history.currentDocumentStateId = afterStateId;
        history.redoStack.clear();
        state.setCursor(after.cursor());
        state.setSelectionAnchor(after.selectionAnchor());
        state.invalidateNavigation();
        state.setDirty(history.savedDocumentStateId < 0L || afterStateId != history.savedDocumentStateId);
        history.lastEditKind = EditKind.NONE;
        history.lastEditAtNanos = System.nanoTime();
        history.lastEditCursorAfter = after.cursor();
        history.lastEditViewId = state.viewId();
        applyGutterDiffOp(prepared.operations().getFirst().operation());
        document.changed();
        return result;
    }

    public boolean copySelection() {
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        String copied = ClipboardText.copy(model, range.start(), range.end());
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
        String copied = ClipboardText.copy(model, start, end);
        if (copied.isEmpty()) {
            return false;
        }
        clipboard.setText(copied);
        return true;
    }

    public boolean cutSelection() {
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        String copied = ClipboardText.copy(model, range.start(), range.end());
        state.invalidateNavigation();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>(1);
        deleteAndCommit(EditKind.NONE, before, ops, range);
        clipboard.setText(copied);
        return true;
    }

    public boolean cutCurrentLine() {
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
        if (start.equals(end)) {
            return false;
        }
        String copied = ClipboardText.copy(model, start, end);
        state.invalidateNavigation();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>(1);
        deleteAndCommit(EditKind.NONE, before, ops, new SelectionRange(start, end));
        clipboard.setText(copied);
        return true;
    }

    public boolean pasteClipboard() {
        return pasteText(clipboard.text());
    }

    /**
     * Pastes literal text as one undo step without typing assists or changing the workspace clipboard.
     * Oversized results are rejected before changing text, view state or history.
     *
     * @throws DocumentEditLimitException when the replacement cannot be built with available memory
     */
    public boolean pasteText(String pasted) {
        if (pasted == null || pasted.isEmpty()) {
            return false;
        }
        ReplacementText replacement = new ReplacementText(pasted);
        SelectionRange selection = currentSelection();
        Position start = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position end = selection == null ? start : selection.end();
        if (replacement.matches(model, start, end)) {
            state.invalidateNavigation();
            state.setCursor(end);
            state.clearSelection();
            breakCoalescing();
            return true;
        }
        commitPreparedReplacement(start, end, replacement, null, captureViewState());
        return true;
    }

    public boolean selectAll() {
        state.invalidateNavigation();
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
        state.invalidateNavigation();
        if (history.undoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        HistoryEntry previous = history.undoStack.pop();
        previous.undo(this);
        pushWithLimit(history.redoStack, previous);
        updateDirtyFromSavepoint();
        return true;
    }

    public boolean redo() {
        state.invalidateNavigation();
        if (history.redoStack.isEmpty()) {
            return false;
        }
        breakCoalescing();
        HistoryEntry next = history.redoStack.pop();
        next.redo(this);
        pushWithLimit(history.undoStack, next);
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
        ReplacementText replacementText = new ReplacementText(replacement);

        SelectionRange selected = currentSelection();
        ViewState before = captureViewState();
        if (selected == null || !matchesSelectedText(matcher, selected)) {
            // Locate the match without publishing a selection: a refused replacement
            // must preserve even the view and coalescing state from before the command.
            SearchMatch found = findForward(model.clamp(state.getCursor()), matcher, true);
            if (found == null) return false;
            selected = new SelectionRange(found.start(),
                new Position(found.start().line(), found.start().col() + found.length()));
            before = new ViewState(before.document(), new EditorViewMemento(state.viewId(),
                selected.end(), before.view().viewport(), selected.start()));
        }

        Position rangeStart = selected.start();
        Position rangeEnd = selected.end();
        if (replacementText.matches(model, rangeStart, rangeEnd)) {
            // Replacing a match with the exact same text must not manufacture a
            // document revision or an undo entry. Move to the following match so
            // repeated Replace commands still make progress. Resolve that move
            // before publishing a selection or breaking a pending typing group:
            // even a no-op replacement can exhaust the shared search budget.
            SearchMatch next = findForward(rangeEnd, matcher, true);
            breakCoalescing();
            if (next == null) {
                selectMatch(rangeStart, rangeEnd.col() - rangeStart.col());
                return false;
            }
            selectMatch(next.start(), next.length());
            return true;
        }
        commitPreparedReplacement(rangeStart, rangeEnd, replacementText, rangeStart, before);
        return true;
    }

    public int replaceAll(String query, String replacement) {
        return replaceAll(query, replacement, SearchOptions.DEFAULT);
    }

    /** Counts and admits the complete result before allocating it or changing document/view/history state. */
    public int replaceAll(String query, String replacement, SearchOptions options) {
        String needle = normalizeQuery(query);
        if (needle == null) {
            return 0;
        }
        SearchMatcher matcher = SearchMatcher.compile(needle, options);
        ReplacementText replacementText = new ReplacementText(replacement);
        DocumentFileMetadata metadata = model.fileMetadata();
        ReplacementBuilder admission = ReplacementBuilder.counting(model.lineCount());
        ReplacementResult counted = composeSearchReplacements(matcher, replacementText, admission, metadata);
        if (counted.firstChange() != null) {
            ReplacementBuilder builder = admission.materializer();
            ReplacementResult built = composeSearchReplacements(matcher, replacementText, builder, metadata);
            applyBuiltReplacement(builder, metadata, built.firstChange());
        }
        return counted.matchCount();
    }

    private ReplacementResult composeSearchReplacements(SearchMatcher matcher, ReplacementText replacement,
                                                         ReplacementBuilder builder, DocumentFileMetadata metadata) {
        int[] matchCount = {0};
        int[] copiedThrough = {0};
        Position[] earliestResult = {null};
        for (int line = 0; line < model.lineCount(); line++) {
            int sourceLine = line;
            String text = model.getLine(line);
            copiedThrough[0] = 0;
            matcher.scanMatches(text, (start, end) -> {
                if (matchCount[0] == Integer.MAX_VALUE) throw new DocumentEditLimitException();
                matchCount[0]++;
                if (replacement.matches(text, start, end)) {
                    return true;
                }
                if (earliestResult[0] == null) {
                    appendOriginalRange(builder, new Position(0, 0), new Position(sourceLine, start), metadata);
                } else {
                    builder.append(text, copiedThrough[0], start);
                }
                builder.appendReplacement(replacement, metadata.defaultLineSeparator());
                if (earliestResult[0] == null) {
                    earliestResult[0] = builder.position();
                }
                copiedThrough[0] = end;
                return true;
            });
            if (earliestResult[0] != null) {
                builder.append(text, copiedThrough[0], text.length());
                if (line + 1 < model.lineCount()) {
                    builder.newline(metadata.lineSeparators().get(line));
                }
            }
        }
        if (earliestResult[0] != null) builder.finishLine();
        return new ReplacementResult(matchCount[0], earliestResult[0]);
    }

    private record ReplacementResult(int matchCount, Position firstChange) {}

    private int firstNonWhitespaceColumn(String line) {
        GraphemeText graphemes = graphemes(line);
        for (int index = 0; index < graphemes.graphemeCount(); index++) {
            if (!graphemes.isWhitespace(index)) {
                return graphemes.start(index);
            }
        }
        return 0;
    }

    private int trailingTrimEndColumn(String line) {
        GraphemeText graphemes = graphemes(line);
        int end = graphemes.graphemeCount();
        while (end > 0 && graphemes.isWhitespace(end - 1)) {
            end--;
        }
        return end == graphemes.graphemeCount() ? line.length() : graphemes.start(end);
    }

    private IndentationDecisions indentationDecisions() {
        return new IndentationDecisions(model.lineCount(), model::getLine, state.getCurrentFile(), tabWidth, tabInsertMode);
    }

    private MarkdownListDecisions markdownListDecisions() {
        return new MarkdownListDecisions(
            model::getLine, (line, column) -> textLayout.visualColumnAt(line, column, tabWidth)
        );
    }

    private String continuationAfterNewLine(Position cursor) {
        LanguageProfile profile = languageProfile();
        String line = model.getLine(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        String beforeCursor = line.substring(0, col);
        return switch (profile) {
            case YAML -> yamlContinuationMarker(beforeCursor);
            case MARKDOWN -> markdownListDecisions().continuation(cursor.line(), beforeCursor);
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
        List<ReplacementDelta> ops = new ArrayList<>(2);
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
        String reduced = indentationDecisions().removeUnit(currentIndent, cursor.line());
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

    private ReplacementDelta maybeAutoOutdentKeywordLineOp() {
        if (!isAutoIndentActive()) {
            return null;
        }
        Position cursor = model.clamp(state.getCursor());
        int drop = indentationDecisions().keywordOutdent(cursor);
        if (drop <= 0) {
            return null;
        }
        Position deleteStart = new Position(cursor.line(), 0);
        Position deleteEnd = new Position(cursor.line(), drop);
        ReplacementDelta delta = model.replaceRange(deleteStart, deleteEnd, "");
        state.setCursor(new Position(cursor.line(), cursor.col() - drop));
        return delta;
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
        long beforeStateId = history.currentDocumentStateId;
        Snapshot before = captureSnapshot();
        editOperation.run();
        Snapshot after = captureSnapshot();
        if (after.equals(before)) {
            return;
        }
        long afterStateId = nextDocumentStateId();
        history.currentDocumentStateId = afterStateId;
        long now = System.nanoTime();
        boolean merged = shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && tryMergeSnapshotEntry(kind, after, afterStateId);
        if (!merged) {
            pushWithLimit(
                history.undoStack,
                new SnapshotHistoryEntry(kind, before, after, beforeStateId, afterStateId)
            );
        }
        history.redoStack.clear();
        updateDirtyFromSavepoint();
        history.lastEditKind = kind;
        history.lastEditAtNanos = now;
        history.lastEditCursorAfter = after.cursor();
        history.lastEditViewId = state.viewId();
        gutterDiff.gutterDiffComputedRevision = Long.MIN_VALUE;
    }

    private void commitOperationEdit(EditKind kind, ViewState before, List<ReplacementDelta> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
        long beforeStateId = history.currentDocumentStateId;
        ViewState after = captureViewState();
        for (ReplacementDelta delta : ops) {
            if (delta.operation().deletedText().equals(delta.operation().insertedText())) continue;
            EditorViewMemento view = editedView(after.view(), model::getLine);
            if (view != after.view()) {
                after = new ViewState(after.document(), view);
                state.setCursor(view.cursor());
                state.setSelectionAnchor(view.selectionAnchor());
            }
            break;
        }
        long afterStateId = nextDocumentStateId();
        history.currentDocumentStateId = afterStateId;
        long now = System.nanoTime();
        boolean merged = shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && tryMergeOperationEntry(kind, after, ops, afterStateId);
        if (!merged) {
            pushWithLimit(
                history.undoStack,
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
        history.redoStack.clear();
        updateDirtyFromSavepoint();
        history.lastEditKind = kind;
        history.lastEditAtNanos = now;
        history.lastEditCursorAfter = after.cursor();
        history.lastEditViewId = state.viewId();
    }

    /** Prepares the document, compact undo and view before exposing any part of a local replacement. */
    private void commitPreparedReplacement(Position start, Position end, ReplacementText replacement,
                                           Position anchorAfter, ViewState before) {
        TextModel.PreparedReplacement prepared;
        ViewState after;
        Deque<HistoryEntry> nextUndo;
        long afterStateId = history.nextDocumentStateId + 1;
        try {
            prepared = model.prepareReplacement(start, end, replacement);
            after = new ViewState(before.document(), new EditorViewMemento(state.viewId(),
                prepared.delta().endAfter(), before.view().viewport(), anchorAfter));
            after = new ViewState(after.document(), editedView(after.view(), prepared::resultingLine));
            OperationHistoryEntry entry = new OperationHistoryEntry(EditKind.NONE, before, after,
                List.of(prepared.delta()), history.currentDocumentStateId, afterStateId);
            nextUndo = new ArrayDeque<>(history.undoStack);
            pushWithLimit(nextUndo, entry);
        } catch (OutOfMemoryError exhausted) {
            // Preparation only owns temporary data. The active model and history
            // still describe the complete pre-command document.
            throw new DocumentEditLimitException();
        }
        model.publishReplacement(prepared);
        history.undoStack = nextUndo;
        history.nextDocumentStateId = afterStateId;
        history.currentDocumentStateId = afterStateId;
        history.redoStack.clear();
        state.setCursor(after.cursor());
        state.setSelectionAnchor(after.selectionAnchor());
        state.invalidateNavigation();
        state.setDirty(history.savedDocumentStateId < 0L || afterStateId != history.savedDocumentStateId);
        history.lastEditKind = EditKind.NONE;
        history.lastEditAtNanos = System.nanoTime();
        history.lastEditCursorAfter = after.cursor();
        history.lastEditViewId = state.viewId();
        applyGutterDiffOp(prepared.delta().operation());
        document.changed();
    }

    private boolean tryMergeSnapshotEntry(EditKind kind, Snapshot after, long afterStateId) {
        HistoryEntry top = history.undoStack.peek();
        if (!(top instanceof SnapshotHistoryEntry entry) || entry.kind() != kind) {
            return false;
        }
        entry.setAfter(after, afterStateId);
        return true;
    }

    private boolean tryMergeOperationEntry(
        EditKind kind,
        ViewState after,
        List<ReplacementDelta> ops,
        long afterStateId
    ) {
        HistoryEntry top = history.undoStack.peek();
        if (!(top instanceof OperationHistoryEntry entry) || entry.kind() != kind) {
            return false;
        }
        entry.append(ops, after, afterStateId);
        return true;
    }

    private Position applyReplaceAndTrack(
        List<ReplacementDelta> ops,
        Position start,
        Position end,
        String insertedText
    ) {
        ReplacementDelta delta = model.replaceRange(start, end, insertedText);
        if (!delta.isEmpty()) {
            ops.add(delta);
            applyGutterDiffOp(delta.operation());
        }
        return delta.endAfter();
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

    /** Keep subsequent typing after a newly joined cluster without widening an exact text selection. */
    private static EditorViewMemento editedView(EditorViewMemento view, IntFunction<String> lines) {
        Position cursor = view.cursor();
        int column = GraphemeText.ceilBoundary(lines.apply(cursor.line()), cursor.col());
        Position anchor = view.selectionAnchor();
        boolean splitAnchor = anchor != null
            && GraphemeText.ceilBoundary(lines.apply(anchor.line()), anchor.col()) != anchor.col();
        if (column == cursor.col() && !splitAnchor) return view;
        return new EditorViewMemento(view.viewId(), new Position(cursor.line(), column), view.viewport(), null);
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
        Comparator<TextReplacement> order = Comparator.comparing(TextReplacement::start, EditorEngine::comparePositions)
            .thenComparing(TextReplacement::end, EditorEngine::comparePositions);
        boolean sorted = true;
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
            if (index > 0 && order.compare(replacements.get(index - 1), replacement) > 0) sorted = false;
        }
        List<TextReplacement> canonical = replacements;
        if (!sorted) {
            DocumentEditBudget.requireTemporaryReferences(replacements.size());
            canonical = new ArrayList<>(replacements);
            canonical.sort(order);
        }
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
        return canonical;
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
        document.baselines.openLines = snapshot;
        document.baselines.savedLines = snapshot;
        document.baselines.generation++;
        gutterDiff.baselineGeneration = document.baselines.generation;
        clearAllGutterDiffBuffers(model.lineCount());
        gutterDiff.gutterDiffComputedRevision = model.revision();
        gutterDiff.gutterDiffPendingRebuild = false;
        gutterDiff.gutterDiffLastRebuildAtNanos = 0L;
        gutterDiff.gutterDiffLastRebuildDurationNanos = 0L;
    }

    private void resetGutterDiffSavedBaseline() {
        document.baselines.savedLines = List.copyOf(model.snapshotLines());
        document.baselines.generation++;
        gutterDiff.baselineGeneration = document.baselines.generation;
        if (gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            clearAllGutterDiffBuffers(model.lineCount());
            gutterDiff.gutterDiffComputedRevision = model.revision();
            gutterDiff.gutterDiffPendingRebuild = false;
            return;
        }
        rebuildGutterDiffFromCurrentState();
    }

    private long nextDocumentStateId() {
        return ++history.nextDocumentStateId;
    }

    private void beginNewDocumentState() {
        history.currentDocumentStateId = nextDocumentStateId();
    }

    private void markCurrentAsSaved() {
        history.savedDocumentStateId = history.currentDocumentStateId;
        state.setDirty(false);
        document.changed();
    }

    private void updateDirtyFromSavepoint() {
        state.setDirty(history.savedDocumentStateId < 0L || history.currentDocumentStateId != history.savedDocumentStateId);
        document.changed();
    }

    private void clearAllGutterDiffBuffers(int lineCount) {
        initGutterDiffBuffers(gutterDiff.savedDiffFlags, gutterDiff.savedDeletedMarkers, lineCount);
        initGutterDiffBuffers(gutterDiff.openDiffFlags, gutterDiff.openDeletedMarkers, lineCount);
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
        if (gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            clearAllGutterDiffBuffers(model.lineCount());
            gutterDiff.gutterDiffComputedRevision = model.revision();
            gutterDiff.gutterDiffPendingRebuild = false;
            return;
        }
        long startedAt = gutterDiffClock.getAsLong();
        List<String> current = model.snapshotLines();
        int lines = Math.max(1, current.size());
        initGutterDiffBuffers(gutterDiff.savedDiffFlags, gutterDiff.savedDeletedMarkers, lines);
        initGutterDiffBuffers(gutterDiff.openDiffFlags, gutterDiff.openDeletedMarkers, lines);

        DiffAlgorithm algorithm = resolveGutterDiffAlgorithm();
        EditList savedEdits = BoundedLineDiff.compute(
            algorithm, document.baselines.savedLines, current, GUTTER_DIFF_MAX_WORK
        );
        applyDiffEdits(savedEdits, lines, gutterDiff.savedDiffFlags, gutterDiff.savedDeletedMarkers);

        EditList openEdits = document.baselines.openLines.equals(document.baselines.savedLines)
            ? savedEdits
            : BoundedLineDiff.compute(algorithm, document.baselines.openLines, current, GUTTER_DIFF_MAX_WORK);
        applyDiffEdits(openEdits, lines, gutterDiff.openDiffFlags, gutterDiff.openDeletedMarkers);

        gutterDiff.gutterDiffLastRebuildDurationNanos = gutterDiffClock.getAsLong() - startedAt;
        gutterDiff.gutterDiffComputedRevision = model.revision();
        gutterDiff.gutterDiffPendingRebuild = false;
        gutterDiff.gutterDiffLastRebuildAtNanos = gutterDiffClock.getAsLong();
    }

    private void applyGutterDiffOp(EditOp.ReplaceRange op) {
        if (op == null) {
            return;
        }
        if (gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            return;
        }
        gutterDiff.gutterDiffComputedRevision = Long.MIN_VALUE;
        gutterDiff.gutterDiffPendingRebuild = true;
    }

    private boolean needsGutterDiffRefresh() {
        if (gutterDiff.baselineGeneration != document.baselines.generation) {
            gutterDiff.baselineGeneration = document.baselines.generation;
            gutterDiff.gutterDiffComputedRevision = Long.MIN_VALUE;
            gutterDiff.gutterDiffPendingRebuild = true;
        }
        if (gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            return false;
        }
        if (gutterDiff.gutterDiffComputedRevision == model.revision()
            && gutterDiff.savedDiffFlags.size() == model.lineCount()
            && gutterDiff.savedDeletedMarkers.size() == model.lineCount()
            && gutterDiff.openDiffFlags.size() == model.lineCount()
            && gutterDiff.openDeletedMarkers.size() == model.lineCount()) {
            return false;
        }
        return true;
    }

    private DiffAlgorithm resolveGutterDiffAlgorithm() {
        return switch (gutterDiff.gutterDiffMode) {
            case DISABLED -> GUTTER_DIFF_ALGORITHM_MYERS;
            case HISTOGRAM -> GUTTER_DIFF_ALGORITHM_HISTOGRAM;
            case MYERS, STABLE -> GUTTER_DIFF_ALGORITHM_MYERS;
        };
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
                    if (gutterDiff.gutterDiffMode == GutterDiffMode.STABLE) {
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
        return new ViewState(DocumentPreferences.from(model.fileMetadata()), state.viewMemento());
    }

    private void restoreViewState(ViewState view) {
        DocumentFileMetadata metadata = model.fileMetadata();
        DocumentPreferences preferences = view.document();
        if (!metadata.charset().equals(preferences.charset())
            || metadata.bomKind() != preferences.bomKind()
            || !metadata.defaultLineSeparator().equals(preferences.defaultLineSeparator())) {
            model.restoreFileMetadata(new DocumentFileMetadata(
                preferences.charset(), preferences.bomKind(), preferences.defaultLineSeparator(), metadata.lineSeparators()
            ));
        }
        EditorViewMemento memento = view.view();
        if (memento.viewId() == state.viewId()) {
            state.setCursor(model.clamp(memento.cursor()));
            state.setViewport(memento.viewport());
            state.setSelectionAnchor(memento.selectionAnchor());
        } else {
            EditorViewMemento active = new EditorViewMemento(state.viewId(), model.clamp(state.getCursor()),
                state.viewport(), state.getSelectionAnchor() == null ? null : model.clamp(state.getSelectionAnchor()));
            EditorViewMemento normalized = editedView(active, model::getLine);
            state.setCursor(normalized.cursor());
            state.setSelectionAnchor(normalized.selectionAnchor());
        }
    }

    private Snapshot captureSnapshot() {
        return new Snapshot(model.snapshotLines(), model.fileMetadata(), state.viewMemento());
    }

    private void restoreSnapshot(Snapshot snapshot) {
        model.replaceLines(snapshot.lines(), snapshot.fileMetadata());
        restoreViewState(new ViewState(DocumentPreferences.from(snapshot.fileMetadata()), snapshot.view()));
        gutterDiff.gutterDiffComputedRevision = Long.MIN_VALUE;
    }

    private static <T> void pushWithLimit(Deque<T> stack, T entry) {
        stack.push(entry);
        while (stack.size() > MAX_HISTORY) {
            stack.removeLast();
        }
    }

    private record Snapshot(List<String> lines, DocumentFileMetadata fileMetadata, EditorViewMemento view) {
        Position cursor() {
            return view.cursor();
        }

        Position selectionAnchor() {
            return view.selectionAnchor();
        }
    }

    private record DocumentPreferences(Charset charset, BomKind bomKind, String defaultLineSeparator) {
        static DocumentPreferences from(DocumentFileMetadata metadata) {
            return new DocumentPreferences(metadata.charset(), metadata.bomKind(), metadata.defaultLineSeparator());
        }
    }

    private record ViewState(DocumentPreferences document, EditorViewMemento view) {
        Position cursor() {
            return view.cursor();
        }

        Position selectionAnchor() {
            return view.selectionAnchor();
        }
    }

    interface HistoryEntry {
        void undo(EditorEngine engine);

        void redo(EditorEngine engine);
    }

    private static final class SnapshotHistoryEntry implements HistoryEntry {
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
        public void undo(EditorEngine engine) {
            engine.restoreSnapshot(before);
            engine.history.currentDocumentStateId = beforeStateId;
        }

        @Override
        public void redo(EditorEngine engine) {
            engine.restoreSnapshot(after);
            engine.history.currentDocumentStateId = afterStateId;
        }
    }

    private static final class OperationHistoryEntry implements HistoryEntry {
        private final EditKind kind;
        private final ViewState beforeView;
        private ViewState afterView;
        private final List<ReplacementDelta> ops;
        private final long beforeStateId;
        private long afterStateId;

        private OperationHistoryEntry(
            EditKind kind,
            ViewState beforeView,
            ViewState afterView,
            List<ReplacementDelta> ops,
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
            List<ReplacementDelta> extraOps,
            ViewState mergedAfterView,
            long mergedAfterStateId
        ) {
            this.ops.addAll(extraOps);
            this.afterView = mergedAfterView;
            this.afterStateId = mergedAfterStateId;
        }

        @Override
        public void undo(EditorEngine engine) {
            for (int i = ops.size() - 1; i >= 0; i--) {
                replay(engine, ops.get(i).inverse());
            }
            engine.restoreViewState(beforeView);
            engine.history.currentDocumentStateId = beforeStateId;
        }

        @Override
        public void redo(EditorEngine engine) {
            for (ReplacementDelta delta : ops) {
                replay(engine, delta);
            }
            engine.restoreViewState(afterView);
            engine.history.currentDocumentStateId = afterStateId;
        }

        private void replay(EditorEngine engine, ReplacementDelta delta) {
            engine.model.replayReplacement(delta);
            engine.applyGutterDiffOp(delta.operation());
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

    enum EditKind {
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
        if (kind == EditKind.NONE || history.lastEditKind != kind || history.lastEditCursorAfter == null
            || history.lastEditViewId != state.viewId()) {
            return false;
        }
        if (nowNanos - history.lastEditAtNanos > COALESCE_WINDOW_NANOS) {
            return false;
        }
        return beforeCursor.equals(history.lastEditCursorAfter)
            && beforeSelectionAnchor == null;
    }

    private void breakCoalescing() {
        history.lastEditKind = EditKind.NONE;
        history.lastEditAtNanos = 0L;
        history.lastEditCursorAfter = null;
        history.lastEditViewId = -1;
    }
}

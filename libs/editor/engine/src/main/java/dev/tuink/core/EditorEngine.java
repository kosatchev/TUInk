package dev.tuink.core;

import dev.tuink.core.TextModel.ReplacementDelta;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.PreparedFormatChange;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentStorage;
import dev.tuink.editor.storage.ResourceId;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveTarget;
import dev.tuink.editor.storage.StorageWriteException;
import dev.tuink.editor.storage.StorageOperation;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.diff.LineEdit;
import dev.tuink.unicode.GraphemeText;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Function;

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
    private final EditorDocument document;
    private final EditorDocumentHistory history;
    private final EditorGutterDiffState gutterDiff = new EditorGutterDiffState();
    private final TextModel model;
    private final EditorState state;
    private final EditorTextLayout textLayout;
    private final EditorClipboard clipboard;
    private final LineDiff lineDiff;
    private final LongSupplier gutterDiffClock;
    private final MarkdownBlockContext markdownContext;
    private final IndentationContext indentationContext;
    private final SearchNavigation searchNavigation;
    private GraphemeText cachedGraphemes;
    private int tabWidth = 4;
    private TabInsertMode tabInsertMode = TabInsertMode.AUTO;
    private boolean autoIndentEnabled = true;
    private boolean autoPairsEnabled = true;
    private boolean autoListEnabled = true;
    private PreparedKeyboardHistory keyboardHistory;
    private boolean keyboardCommand;
    private String keyboardClipboard;
    private CommandExecution commandExecution;

    public EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout) {
        this(model, state, textLayout, new EditorClipboard());
    }

    public EditorEngine(
        TextModel model,
        EditorState state,
        EditorTextLayout textLayout,
        EditorClipboard clipboard
    ) {
        this(model, state, textLayout, clipboard, LineDiff.none());
    }

    /** A frontend may explicitly attach optional line-diff support to this view. */
    public EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LineDiff lineDiff) {
        this(initialDocument(model, state, textLayout, clipboard, lineDiff), state, textLayout,
            clipboard, lineDiff, System::nanoTime);
    }

    EditorEngine(EditorDocument document, EditorState state, EditorTextLayout textLayout, EditorClipboard clipboard) {
        this(document, state, textLayout, clipboard, LineDiff.none());
    }

    EditorEngine(EditorDocument document, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LineDiff lineDiff) {
        this(document, state, textLayout, clipboard, lineDiff, System::nanoTime);
    }

    EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LongSupplier gutterDiffClock) {
        this(model, state, textLayout, clipboard, LineDiff.none(), gutterDiffClock);
    }

    EditorEngine(TextModel model, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LineDiff lineDiff, LongSupplier gutterDiffClock) {
        this(initialDocument(model, state, textLayout, clipboard, lineDiff, gutterDiffClock),
            state, textLayout, clipboard, lineDiff, gutterDiffClock);
    }

    private EditorEngine(EditorDocument document, EditorState state, EditorTextLayout textLayout,
        EditorClipboard clipboard, LineDiff lineDiff, LongSupplier gutterDiffClock) {
        this.document = Objects.requireNonNull(document, "document");
        this.history = document.history;
        this.model = document.model();
        this.state = Objects.requireNonNull(state, "state");
        if (textLayout == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to the engine model");
        }
        this.textLayout = textLayout;
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.lineDiff = Objects.requireNonNull(lineDiff, "lineDiff");
        this.gutterDiffClock = Objects.requireNonNull(gutterDiffClock, "gutterDiffClock");
        this.markdownContext = new MarkdownBlockContext(model::lineText);
        this.indentationContext = new IndentationContext(model::lineText);
        this.searchNavigation = new SearchNavigation(model);
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
        EditorClipboard clipboard, LineDiff lineDiff) {
        Objects.requireNonNull(lineDiff, "lineDiff");
        return initialDocument(model, state, layout, clipboard);
    }

    private static EditorDocument initialDocument(TextModel model, EditorState state, EditorTextLayout layout,
        EditorClipboard clipboard, LineDiff lineDiff, LongSupplier gutterDiffClock) {
        Objects.requireNonNull(gutterDiffClock, "gutterDiffClock");
        return initialDocument(model, state, layout, clipboard, lineDiff);
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
     * Executes a validated session command against this originating view. Mandatory
     * results are prepared at the same boundary as text/history publication; ordinary
     * input does not capture or publish a whole-document snapshot.
     */
    public DocumentCommandResult.Applied executeCommand(DocumentCommandTarget target, DocumentCommand command) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        if (commandExecution != null) throw new IllegalStateException("A document command is already executing");
        CommandExecution execution = null;
        try {
            execution = new CommandExecution(target);
            commandExecution = execution;
            Boolean performed = dispatchCommand(command);
            if (execution.result == null) {
                execution.performed = performed;
                execution.prepare(model.revision(), state.viewMemento());
            }
            execution.committed = true;
            return execution.result;
        } catch (OutOfMemoryError exhausted) {
            if (execution != null && execution.committed) throw exhausted;
            throw new DocumentEditLimitException();
        } finally {
            if (execution != null && !execution.committed) execution.restore();
            commandExecution = null;
        }
    }

    private Boolean dispatchCommand(DocumentCommand command) {
        if (command instanceof DocumentCommand.SetDocumentFormat format) {
            return applyDocumentFormat(format.prepared(), format.validation());
        }
        if (command instanceof DocumentCommand.Input input) {
            insertInput(input.text());
            return null;
        }
        if (command instanceof DocumentCommand.Paste paste) {
            commandExecution.performed = true;
            return pasteText(paste.text());
        }
        if (command instanceof DocumentCommand.MoveTo move) {
            navigateTo(position(move.position()), move.selecting());
            return null;
        }
        if (command instanceof DocumentCommand.Edit edit) {
            return switch (edit.action()) {
                case INSERT_TAB -> { insertTab(); yield null; }
                case NEWLINE -> { insertNewline(); yield null; }
                case BACKSPACE -> { backspace(); yield null; }
                case DELETE_FORWARD -> { deleteForward(); yield null; }
                case COPY -> state.hasSelection() ? copySelection() : copyCurrentLine();
                case CUT -> state.hasSelection() ? cutSelection() : cutCurrentLine();
                case PASTE -> {
                    commandExecution.performed = true;
                    yield pasteClipboard();
                }
                case SELECT_ALL -> selectAll();
                case UNDO -> {
                    commandExecution.performed = true;
                    yield undo();
                }
                case REDO -> {
                    commandExecution.performed = true;
                    yield redo();
                }
                case TOGGLE_LINE_COMMENT -> {
                    LineCommentResult result = toggleLineComment();
                    if (commandExecution.result == null) commandExecution.comment(result);
                    yield result.lineCount() > 0;
                }
            };
        }
        if (command instanceof DocumentCommand.Navigate navigate) {
            boolean selecting = navigate.selecting();
            switch (navigate.action()) {
                case LEFT -> moveLeft(selecting);
                case RIGHT -> moveRight(selecting);
                case WORD_LEFT -> moveWordLeft(selecting);
                case WORD_RIGHT -> moveWordRight(selecting);
                case HOME -> moveHome(selecting);
                case END -> moveEnd(selecting);
                case HOME_SMART -> moveHomeSmart(selecting);
                case END_SMART -> moveEndSmart(selecting);
                case DOCUMENT_START -> moveDocumentStart(selecting);
                case DOCUMENT_END -> moveDocumentEnd(selecting);
            }
            return null;
        }
        if (command instanceof DocumentCommand.Select select) {
            switch (select.action()) {
                case MOVE -> moveCursorTo(position(select.cursor()));
                case BEGIN -> beginSelectionAt(position(select.cursor()));
                case EXTEND -> extendSelectionTo(position(select.cursor()));
                case RESTORE -> restoreSelection(position(select.cursor()), position(select.anchor()));
                case FINISH -> finishSelection();
                case WORD -> { return selectWordAt(position(select.cursor())); }
            }
            return null;
        }
        if (command instanceof DocumentCommand.Search search) {
            return switch (search.action()) {
                case NEXT -> findNext(search.query(), search.options());
                case PREVIOUS -> findPrevious(search.query(), search.options());
                case REPLACE -> {
                    commandExecution.performed = true;
                    yield replaceCurrentOrNext(search.query(), search.replacement(), search.options());
                }
                case REPLACE_ALL -> {
                    int count = replaceAll(search.query(), search.replacement(), search.options());
                    commandExecution.affectedCount = count;
                    yield count > 0;
                }
            };
        }
        throw new IllegalArgumentException("Unsupported document command: " + command);
    }

    private static Position position(TextPosition position) {
        return position == null ? null : new Position(position.line(), position.utf16Offset());
    }

    /** Captures immutable text; no encoder or resource work runs on the owner. */
    public PreparedFormatChange prepareFormatChange(DocumentFormatSpec spec) {
        document.requireOwnerThread();
        Objects.requireNonNull(spec, "spec");
        try {
            return new FormatPreparation(document, model.revision(), recoveryRevision(),
                model.snapshotTextLines(), model.fileMetadata(), spec);
        } catch (OutOfMemoryError exhausted) {
            throw new DocumentEditLimitException();
        }
    }

    private boolean applyDocumentFormat(PreparedFormatChange prepared, PreparedFormatChange.Validation validation) {
        document.requireOwnerThread();
        if (!(prepared instanceof FormatPreparation plan) || plan.document != document
            || validation != plan.validation || plan.validation == null) {
            throw new IllegalArgumentException("Format validation belongs to another preparation");
        }
        if (plan.consumed || plan.revision != model.revision() || plan.stateRevision != recoveryRevision()) {
            throw new IllegalStateException("Format preparation no longer matches the document");
        }
        if (plan.operation.cancelled()) throw new IllegalStateException("Format preparation was cancelled");
        DocumentFileMetadata after = plan.validation.metadata;
        if (!plan.validation.changed) {
            plan.consumed = true;
            if (commandExecution != null) commandExecution.performed = false;
            return false;
        }
        boolean changed = prepareAndCommitDocumentEdit(() -> {
            DocumentFileMetadata before = model.fileMetadata();
            long afterStateId = history.nextDocumentStateId + 1;
            Deque<HistoryEntry> undo = copyHistory(history.undoStack);
            pushWithLimit(undo, new FormatHistoryEntry(before, after, history.currentDocumentStateId, afterStateId));
            model.restoreFileMetadata(after);
            if (commandExecution != null) commandExecution.performed = true;
            return new PreparedDocumentHistory(undo, new ArrayDeque<>(), afterStateId, afterStateId);
        }, result -> result);
        plan.consumed = true;
        return changed;
    }

    private static final class FormatPreparation implements PreparedFormatChange {
        private final EditorDocument document;
        private final long revision;
        private final long stateRevision;
        private final List<? extends CharSequence> lines;
        private final DocumentFileMetadata original;
        private final DocumentFormatSpec spec;
        private boolean started;
        private volatile FormatValidation validation;
        private StorageOperation operation;
        private boolean consumed;

        private FormatPreparation(EditorDocument document, long revision, long stateRevision,
                                  List<? extends CharSequence> lines, DocumentFileMetadata original,
                                  DocumentFormatSpec spec) {
            this.document = document;
            this.revision = revision;
            this.stateRevision = stateRevision;
            this.lines = lines;
            this.original = original;
            this.spec = spec;
        }

        @Override
        public synchronized Validation perform(StorageOperation operation) throws IOException {
            if (started) throw new IllegalStateException("Format validation has already been performed");
            started = true;
            this.operation = Objects.requireNonNull(operation, "operation");
            operation.checkCancelled();
            try {
                DocumentFileMetadata target = spec.metadata(original, lines.size());
                DocumentCodec.EncodedContent encoded = DocumentCodec.write(OutputStream.nullOutputStream(), lines, target, operation);
                operation.checkCancelled();
                validation = new FormatValidation(encoded.metadata(), !sameFormat(original, encoded.metadata(), operation));
                return validation;
            } catch (UnsupportedCharsetException unavailable) {
                throw new IOException("Requested document encoding unavailable: " + unavailable.getCharsetName(), unavailable);
            }
        }

        private static boolean sameFormat(DocumentFileMetadata first, DocumentFileMetadata second,
                                          StorageOperation operation) throws IOException {
            if (!first.charset().equals(second.charset()) || first.bomKind() != second.bomKind()
                || !first.defaultLineSeparator().equals(second.defaultLineSeparator())) return false;
            if (first.lineSeparators() == second.lineSeparators()) return true;
            var left = first.lineSeparators().iterator();
            var right = second.lineSeparators().iterator();
            int checked = 0;
            while (left.hasNext() && right.hasNext()) {
                if ((checked++ & 8191) == 0) operation.checkCancelled();
                if (!left.next().equals(right.next())) return false;
            }
            return !left.hasNext() && !right.hasNext();
        }
    }

    private static final class FormatValidation implements PreparedFormatChange.Validation {
        private final DocumentFileMetadata metadata;
        private final boolean changed;
        private FormatValidation(DocumentFileMetadata metadata, boolean changed) {
            this.metadata = metadata;
            this.changed = changed;
        }
    }

    /** Allocation seam used to verify that a mandatory result cannot follow the text commit. */
    protected DocumentCommandResult.Applied prepareCommandResult(DocumentCommandTarget target, long revision,
        boolean textChanged, boolean viewChanged, boolean performed, int affectedCount,
        DocumentCommandResult.CommentAction commentAction) {
        return new DocumentCommandResult.Applied(target, revision, textChanged, viewChanged,
            performed, affectedCount, commentAction);
    }

    private void prepareCommandPublication(long revision, EditorViewMemento view) {
        if (commandExecution != null) commandExecution.prepare(revision, view);
    }

    private void prepareCommandPublication(EditorDocument.PreparedChange views) {
        if (commandExecution != null) {
            commandExecution.prepare(model.revision(), views == null ? state.viewMemento() : views.projectedView(state));
        }
    }

    private void commandPublished() {
        if (commandExecution != null) commandExecution.committed = true;
    }

    private final class CommandExecution {
        private final DocumentCommandTarget target;
        private final EditorViewMemento before = state.viewMemento();
        private final long revisionBefore = model.revision();
        private final long navigationBefore = state.navigationRevision();
        private final String clipboardBefore = clipboard.text();
        private final EditKind coalescingKind = history.lastEditKind;
        private final long coalescingAt = history.lastEditAtNanos;
        private final Position coalescingCursor = history.lastEditCursorAfter;
        private final long coalescingView = history.lastEditViewId;
        private Boolean performed;
        private int affectedCount = -1;
        private DocumentCommandResult.CommentAction commentAction = DocumentCommandResult.CommentAction.NONE;
        private DocumentCommandResult.Applied result;
        private boolean committed;

        private CommandExecution(DocumentCommandTarget target) { this.target = target; }

        private void prepare(long revision, EditorViewMemento view) {
            if (result != null) throw new IllegalStateException("A command must publish at most one mutation");
            boolean textChanged = revision != revisionBefore;
            boolean viewChanged = !before.equals(view);
            boolean actual = performed == null ? textChanged || viewChanged : performed;
            result = prepareCommandResult(target, revision, textChanged, viewChanged, actual,
                affectedCount < 0 ? actual ? 1 : 0 : affectedCount, commentAction);
        }

        private void comment(LineCommentResult result) {
            performed = result.lineCount() > 0;
            affectedCount = result.lineCount();
            commentAction = DocumentCommandResult.CommentAction.valueOf(result.action().name());
        }

        private void restore() {
            state.restoreEditState(before, navigationBefore);
            clipboard.setText(clipboardBefore);
            history.lastEditKind = coalescingKind;
            history.lastEditAtNanos = coalescingAt;
            history.lastEditCursorAfter = coalescingCursor;
            history.lastEditViewId = coalescingView;
            cachedGraphemes = null;
            indentationContext.reset();
            markdownContext.reset();
        }
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
        return applyTextReplacements(replacements, Boolean::valueOf);
    }

    /**
     * Prepares an internal result against staged text before publishing text/history.
     * The callback may read staged state but must not mutate or publish it. Its
     * failure aborts the transaction, including allocation of mandatory snapshots.
     */
    public <T> T applyTextReplacements(List<TextReplacement> replacements,
                                     Function<Boolean, T> prepareResult) {
        Objects.requireNonNull(prepareResult, "prepareResult");
        List<TextReplacement> canonical = validateTextReplacements(replacements);
        DocumentFileMetadata metadata = model.fileMetadata();
        ReplacementBuilder admission = ReplacementBuilder.counting(model.lineCount());
        if (composeTextReplacements(canonical, admission, metadata) == null) {
            return prepareResult.apply(false);
        }
        ReplacementBuilder builder = admission.materializer();
        Position cursor = composeTextReplacements(canonical, builder, metadata);
        return applyBuiltReplacement(builder, metadata, cursor, prepareResult);
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
                new Position(lastLine, model.lineLength(lastLine)), metadata);
            builder.finishLine();
        }
        return earliestResult;
    }

    private void applyBuiltReplacement(ReplacementBuilder builder, DocumentFileMetadata metadata, Position cursor) {
        applyBuiltReplacement(builder, metadata, cursor, changed -> null);
    }

    private <T> T applyBuiltReplacement(ReplacementBuilder builder, DocumentFileMetadata metadata, Position cursor,
                                       Function<Boolean, T> prepareResult) {
        EditorViewMemento after = editedView(new EditorViewMemento(state.viewId(), cursor, state.viewport(), null),
            builder.lines::get);
        return commitSnapshotEdit(() -> {
            model.replaceLines(builder.lines, metadata.withLineSeparators(builder.separators));
            state.setCursor(after.cursor());
            state.clearSelection();
        }, prepareResult);
    }

    private void appendOriginalRange(
        ReplacementBuilder builder,
        Position start,
        Position end,
        DocumentFileMetadata metadata
    ) {
        int column = start.col();
        var separators = metadata.lineSeparators().listIterator(start.line());
        for (int line = start.line(); line < end.line(); line++) {
            LineText text = model.lineText(line);
            builder.append(text, column, text.length());
            builder.newline(separators.next());
            column = 0;
        }
        builder.append(model.lineText(end.line()), column, end.col());
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
            state.invalidateNavigation();
        }
    }

    public RecoverySnapshot captureRecoverySnapshot() {
        return RecoverySnapshot.capture(model, state);
    }

    public void restoreRecoverySnapshot(RecoverySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        TextModel.LocalEdit edit = null;
        boolean published = false;
        try {
            edit = model.beginLocalEdit();
            model.replaceLines(snapshot.lines(), snapshot.fileMetadata());
            model.restoreFileBaseline(snapshot.fileBaseline());
            edit.preparePublication();
            List<LineText> baseline = model.snapshotTextLines();
            EditorViewMemento origin = new EditorViewMemento(state.viewId(), model.clamp(snapshot.cursor()),
                snapshot.viewport(), snapshot.selectionAnchor());
            EditorDocument.PreparedChange views = document.prepareChange(model.lineCount(), model::lineText, state);
            prepareGutterDiffCapacity(model.lineCount());
            edit.publish();
            published = true;
            finishRecovery(snapshot, origin, baseline, views);
        } catch (OutOfMemoryError exhausted) {
            if (published) throw exhausted;
            throw new DocumentEditLimitException();
        } finally {
            if (!published && edit != null) edit.abort();
        }
    }

    /** All storage, view clamps and diff capacity belong to the already prepared restore. */
    private void finishRecovery(RecoverySnapshot snapshot, EditorViewMemento origin, List<LineText> baseline,
                                EditorDocument.PreparedChange views) {
        state.setCurrentResource(snapshot.currentResource());
        state.setSaveDisposition(snapshot.saveDisposition());
        state.setViewport(origin.viewport());
        state.setCursor(origin.cursor());
        state.setSelectionAnchor(origin.selectionAnchor());
        state.invalidateNavigation();
        history.undoStack.clear();
        history.redoStack.clear();
        breakCoalescing();
        resetGutterDiffBaselinesOnOpen(baseline);
        beginNewDocumentState();
        history.savedDocumentStateId = snapshot.dirty() ? -1L : history.currentDocumentStateId;
        state.setDirty(snapshot.dirty());
        document.publishChange(views);
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
        if (!lineDiff.available() || gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
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
        if (!lineDiff.available() || gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            gutterDiff.gutterDiffComputedRevision = model.revision();
            gutterDiff.gutterDiffPendingRebuild = false;
        } else {
            invalidateGutterDiff();
        }
    }

    /** Explicit synchronous convenience; interactive hosts run the detached read on a worker. */
    public void open(DocumentResource resource) throws IOException {
        open(resource, true, DocumentOpenMode.DETECT);
    }

    public void openExisting(DocumentResource resource) throws IOException {
        openExisting(resource, DocumentOpenMode.DETECT);
    }

    public void openExisting(DocumentResource resource, DocumentOpenMode mode) throws IOException {
        open(resource, false, mode);
    }

    private void open(DocumentResource resource, boolean allowMissing, DocumentOpenMode mode) throws IOException {
        if (resource == null) throw new IOException("No file selected");
        openPrepared(resource.storage().load(resource, mode, allowMissing));
    }

    /** Publishes a detached read without reopening its resource. */
    public void openPrepared(DocumentLoad prepared) {
        Objects.requireNonNull(prepared, "prepared");
        TextModel.LocalEdit edit = null;
        boolean published = false;
        try {
            edit = model.beginLocalEdit();
            model.applyPreparedLoad(prepared);
            edit.preparePublication();
            List<LineText> baseline = model.snapshotTextLines();
            EditorDocument.PreparedChange views = document.prepareChange(model.lineCount(), model::lineText, state);
            Position start = new Position(0, 0);
            // Capacity changes do not change visible gutter state. All later fills
            // use existing storage and enum/Boolean constants after text publication.
            prepareGutterDiffCapacity(model.lineCount());
            edit.publish();
            published = true;
            finishOpen(prepared.resource(), prepared.disposition(), baseline, start, views);
        } catch (OutOfMemoryError exhausted) {
            if (published) throw exhausted;
            throw new DocumentEditLimitException();
        } finally {
            if (!published && edit != null) edit.abort();
        }
    }

    private void finishOpen(DocumentResource resource, SaveDisposition disposition, List<LineText> baseline,
                            Position start, EditorDocument.PreparedChange views) {
        state.setCurrentResource(resource);
        state.setSaveDisposition(disposition);
        state.setCursor(start);
        state.invalidateNavigation();
        state.setTopLine(0);
        state.setLeftColumn(0);
        state.setLeftVisualColumn(0);
        state.setDirty(false);
        state.clearSelection();
        history.undoStack.clear();
        history.redoStack.clear();
        breakCoalescing();
        resetGutterDiffBaselinesOnOpen(baseline);
        beginNewDocumentState();
        history.savedDocumentStateId = history.currentDocumentStateId;
        document.publishChange(views);
    }

    private void prepareGutterDiffCapacity(int lines) {
        ((ArrayList<GutterDiffFlag>) gutterDiff.savedDiffFlags).ensureCapacity(lines);
        ((ArrayList<Boolean>) gutterDiff.savedDeletedMarkers).ensureCapacity(lines);
        ((ArrayList<GutterDiffFlag>) gutterDiff.openDiffFlags).ensureCapacity(lines);
        ((ArrayList<Boolean>) gutterDiff.openDeletedMarkers).ensureCapacity(lines);
    }

    public void save() throws IOException {
        PreparedSave prepared = prepareSave();
        completeSaved(prepared, prepared.write());
    }

    public void saveAs(DocumentResource resource) throws IOException {
        saveAs(resource, SaveDisposition.CREATE_NEW);
    }

    public void saveAs(DocumentResource resource, SaveDisposition disposition) throws IOException {
        saveAs(resource, disposition, model.fileMetadata());
    }

    /** Explicit, lossless conversion: UTF-8 without a BOM, retaining every existing EOL. */
    public void saveAsUtf8(DocumentResource resource, SaveDisposition disposition) throws IOException {
        DocumentFileMetadata original = model.fileMetadata();
        saveAs(resource, disposition, new DocumentFileMetadata(StandardCharsets.UTF_8, BomKind.NONE,
            original.defaultLineSeparator(), original.lineSeparators()));
    }

    private void saveAs(DocumentResource resource, SaveDisposition disposition, DocumentFileMetadata metadata)
        throws IOException {
        SavePreparation preparation = prepareSaveInput(resource, disposition, metadata);
        PreparedSave prepared = approveSaveAs(preparation.inspectTarget());
        completeSaved(prepared, prepared.write());
    }

    public PreparedSave prepareSave() throws IOException {
        return new PreparedSave(prepareSaveInput(state.getCurrentResource(), state.getSaveDisposition(),
            model.fileMetadata()), new SaveAuthorization.Current());
    }

    public PreparedSave prepareSaveAs(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException {
        return approveSaveAs(prepareSaveAsInput(resource, disposition, utf8).inspectTarget());
    }

    /** Captures owner state only. Inspection runs detached before owner approval. */
    public SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException {
        try {
            DocumentFileMetadata metadata = model.fileMetadata();
            if (utf8) metadata = new DocumentFileMetadata(StandardCharsets.UTF_8, BomKind.NONE,
                metadata.defaultLineSeparator(), metadata.lineSeparators());
            return prepareSaveInput(resource, disposition, metadata);
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("Not enough memory to prepare the save", exhausted);
        }
    }

    private SavePreparation prepareSaveInput(DocumentResource resource, SaveDisposition disposition,
                                             DocumentFileMetadata metadata) throws IOException {
        try {
            return prepareSavePlan(resource, disposition, metadata);
        } catch (OutOfMemoryError | DocumentEditLimitException exhausted) {
            throw new IOException("Not enough memory to prepare the save", exhausted);
        }
    }

    private SavePreparation prepareSavePlan(DocumentResource resource, SaveDisposition disposition,
                                            DocumentFileMetadata metadata) throws IOException {
        if (resource == null) throw new IOException("No file selected");
        boolean formatChanged = !DocumentPreferences.from(model.fileMetadata())
            .equals(DocumentPreferences.from(metadata));
        // Allocate mandatory history before an external commit can succeed.
        Deque<HistoryEntry> preparedHistory = null;
        long convertedStateId = history.nextDocumentStateId + 1;
        if (formatChanged) {
            HistoryEntry conversion = new FormatHistoryEntry(model.fileMetadata(), metadata,
                history.currentDocumentStateId, convertedStateId);
            preparedHistory = new ArrayDeque<>(history.undoStack);
            pushWithLimit(preparedHistory, conversion);
        }
        return new SavePreparation(this, model.prepareSave(resource, metadata, disposition),
            resourceBinding(), model.revision(), history.currentDocumentStateId, preparedHistory, convertedStateId);
    }

    /** Binds the inspected observation to its unchanged owner; performs no provider operation. */
    public PreparedSave approveSaveAs(SaveTargetInspection inspection) throws IOException {
        Objects.requireNonNull(inspection, "inspection");
        SavePreparation preparation = inspection.preparation;
        if (!preparation.belongsToUnchanged(this)) {
            throw new IOException("Document changed while inspecting the Save As target. Choose the target again.");
        }
        return new PreparedSave(preparation, new SaveAuthorization.Approved(inspection.target));
    }

    /** Caller keeps document edits locked until the detached write completes. */
    public void completeSaved(PreparedSave prepared, SavedDocument saved) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(saved, "saved");
        SavePreparation plan = prepared.preparation;
        if (prepared.completed || saved != prepared.receipt || !plan.belongsToUnchanged(this)) {
            throw new IllegalStateException("Saved result does not belong to the unchanged document and write");
        }
        model.completeSave(saved);
        if (plan.convertedHistory != null) {
            history.undoStack = plan.convertedHistory;
            history.redoStack.clear();
            history.nextDocumentStateId = plan.convertedStateId;
            history.currentDocumentStateId = plan.convertedStateId;
        }
        prepared.completed = true;
        state.setCurrentResource(saved.resource());
        state.setSaveDisposition(SaveDisposition.REPLACE_EXISTING);
        state.setDirty(false);
        breakCoalescing();
        document.baselines.savedLines = plan.request.lines();
        document.baselines.generation++;
        invalidateGutterDiff();
        history.savedDocumentStateId = history.currentDocumentStateId;
        gutterDiff.gutterDiffLastRebuildAtNanos = 0L;
        document.recoveryGeneration++;
        document.reflectFileMetadata();
    }

    public ResourceBinding resourceBinding() {
        DocumentResource resource = state.getCurrentResource();
        SaveDisposition disposition = state.getSaveDisposition();
        ResourceVersion version = resource != null && disposition == SaveDisposition.REPLACE_EXISTING
            ? model.fileBaseline() : null;
        return new ResourceBinding(resource, disposition, version);
    }

    public static final class SavePreparation {
        private final EditorEngine owner;
        private final Thread ownerThread;
        private final DocumentSave request;
        private final ResourceBinding originalBinding;
        private final ResourceVersion originalBaseline;
        private final long revision;
        private final long stateId;
        private final Deque<HistoryEntry> convertedHistory;
        private final long convertedStateId;

        private SavePreparation(EditorEngine owner, DocumentSave request, ResourceBinding originalBinding,
                                long revision, long stateId, Deque<HistoryEntry> convertedHistory,
                                long convertedStateId) {
            this.owner = owner;
            this.ownerThread = Thread.currentThread();
            this.request = request;
            this.originalBinding = originalBinding;
            this.originalBaseline = request.baseline();
            this.revision = revision;
            this.stateId = stateId;
            this.convertedHistory = convertedHistory;
            this.convertedStateId = convertedStateId;
        }

        /** Detached preflight; never reads the live owner or modifies a resource. */
        public SaveTargetInspection inspectTarget() throws IOException {
            return inspectTargetWithOperation(StorageOperation.untracked());
        }

        public SaveTargetInspection inspectTargetWithOperation(StorageOperation operation) throws IOException {
            try {
                operation.checkCancelled();
                SaveTarget target = request.resource().storage().inspect(request.resource(), originalBinding, operation);
                operation.checkCancelled();
                if (target.requestedResource().storage() != request.resource().storage()
                    || target.physicalResource().storage() != request.resource().storage()) {
                    throw new IOException("Storage returned a target from another provider");
                }
                return new SaveTargetInspection(this, target);
            } catch (OutOfMemoryError exhausted) {
                throw new IOException("Not enough memory to inspect the Save As target", exhausted);
            }
        }

        public SaveDisposition disposition() { return request.disposition(); }

        private boolean belongsToUnchanged(EditorEngine engine) {
            return owner == engine && Thread.currentThread() == ownerThread && engine.model.revision() == revision
                && engine.history.currentDocumentStateId == stateId
                && Objects.equals(engine.state.getCurrentResource(), originalBinding.resource())
                && engine.state.getSaveDisposition() == originalBinding.disposition()
                && Objects.equals(engine.model.fileBaseline(), originalBaseline);
        }
    }

    /** A provider observation belonging to exactly one captured Save As request. */
    public static final class SaveTargetInspection {
        private final SavePreparation preparation;
        private final SaveTarget target;

        private SaveTargetInspection(SavePreparation preparation, SaveTarget target) {
            this.preparation = preparation;
            this.target = target;
        }

        public DocumentResource requestedResource() { return target.requestedResource(); }
        public DocumentResource physicalResource() { return target.physicalResource(); }
        public boolean exists() { return target.exists(); }
        public boolean currentTarget() { return target.currentTarget(); }
    }

    public static final class PreparedSave {
        private final SavePreparation preparation;
        private final SaveAuthorization authorization;
        private final DocumentStorage storage;
        private final ResourceId expectedResourceId;
        private final StorageWriteException unconfirmedSave;
        private volatile SavedDocument receipt;
        private boolean started;
        private boolean completed;

        private PreparedSave(SavePreparation preparation, SaveAuthorization authorization) {
            this.preparation = preparation;
            this.authorization = authorization;
            storage = preparation.request.resource().storage();
            DocumentResource expected = authorization instanceof SaveAuthorization.Approved approved
                ? approved.target().requestedResource() : preparation.request.resource();
            expectedResourceId = expected.id();
            // A provider may already have published when a receipt cannot be
            // validated, including under heap exhaustion. Prepare this outcome
            // before calling it so that retaining UNKNOWN needs no allocation.
            unconfirmedSave = new StorageWriteException("Storage did not confirm the expected resource and document"
                + ". Verify its contents before retrying; the editor retains your draft.", null,
                StorageWriteException.Outcome.UNKNOWN, expected);
        }

        /** Executes one detached write; its receipt is accepted only by this operation. */
        public synchronized SavedDocument write() throws IOException {
            return writeWithOperation(StorageOperation.untracked());
        }

        public synchronized SavedDocument writeWithOperation(StorageOperation operation) throws IOException {
            if (started) throw new IllegalStateException("A prepared save may be written only once");
            started = true;
            operation.checkCancelled();
            DocumentSave request = preparation.request;
            SavedDocument saved;
            try {
                saved = storage.write(request, authorization, operation);
            } catch (OutOfMemoryError exhausted) {
                throw unconfirmed(exhausted);
            }
            boolean matches;
            try {
                matches = saved != null && saved.resource().storage() == storage
                    && expectedResourceId.equals(saved.resource().id())
                    && saved.metadata().isCompatibleWithLineCount(request.lines().size());
            } catch (RuntimeException | Error invalidReceipt) {
                throw unconfirmed(invalidReceipt);
            }
            if (!matches) throw unconfirmedSave;
            receipt = saved;
            return saved;
        }

        private StorageWriteException unconfirmed(Throwable cause) {
            try { unconfirmedSave.addSuppressed(cause); }
            catch (RuntimeException | Error unavailable) { /* The preallocated outcome still retains the draft. */ }
            return unconfirmedSave;
        }
    }

    /** Includes format/savepoint changes that intentionally do not invalidate text layout. */
    public long recoveryRevision() {
        return model.revision() + document.recoveryGeneration;
    }

    public boolean isCurrentSaveTarget(DocumentResource resource) throws IOException {
        return resource != null && resource.storage().isCurrentTarget(resource, resourceBinding());
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
            LineText line = model.lineText(p.line());
            moveCursor(new Position(p.line(), graphemes(line).previousBoundary(p.col())), selecting, p);
        } else if (p.line() > 0) {
            int prevLen = model.lineLength(p.line() - 1);
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
            int endColumn = graphemes(model.lineText(end.line())).ceilBoundary(end.col());
            moveCursor(new Position(end.line(), endColumn), false, p);
            return;
        }
        int len = model.lineLength(p.line());
        if (p.col() < len) {
            LineText line = model.lineText(p.line());
            moveCursor(new Position(p.line(), graphemes(line).nextBoundary(p.col())), selecting, p);
        } else if (p.line() < model.lineCount() - 1) {
            moveCursor(new Position(p.line() + 1, 0), selecting, p);
        } else {
            moveCursor(p, selecting, p);
        }
    }

    public void moveWordLeft() {
        moveWordLeft(false);
    }

    public void moveWordLeft(boolean selecting) {
        state.invalidateNavigation();
        Position previous = state.getCursor();
        Position p = model.clamp(previous);
        int line = p.line();
        int col = p.col();
        if (line == 0 && col == 0) {
            moveCursor(p, selecting, previous);
            return;
        }
        if (col == 0 && line > 0) {
            line--;
            col = model.lineLength(line);
        }
        LineText text = model.lineText(line);
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
        moveCursor(new Position(line, target), selecting, previous);
    }

    public void moveWordRight() {
        moveWordRight(false);
    }

    public void moveWordRight(boolean selecting) {
        state.invalidateNavigation();
        Position previous = state.getCursor();
        Position p = model.clamp(previous);
        int line = p.line();
        int col = p.col();
        int lastLine = model.lineCount() - 1;
        if (line == lastLine && col >= model.lineLength(line)) {
            moveCursor(p, selecting, previous);
            return;
        }
        LineText text = model.lineText(line);
        if (col >= text.length() && line < lastLine) {
            line++;
            col = 0;
            text = model.lineText(line);
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
        moveCursor(new Position(line, target), selecting, previous);
    }

    public boolean selectWordAt(Position requested) {
        state.invalidateNavigation();
        breakCoalescing();
        Position p = model.clamp(requested);
        LineText text = model.lineText(p.line());
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
        navigateTo(new Position(p.line(), model.lineLength(p.line())), selecting);
    }

    public void moveHomeSmart() {
        moveHomeSmart(false);
    }

    public void moveHomeSmart(boolean selecting) {
        Position p = state.getCursor();
        LineText line = model.lineText(p.line());
        int firstContentCol = firstNonWhitespaceColumn(line);
        int target = p.col() == firstContentCol ? 0 : firstContentCol;
        navigateTo(new Position(p.line(), target), selecting);
    }

    public void moveEndSmart() {
        moveEndSmart(false);
    }

    public void moveEndSmart(boolean selecting) {
        Position p = state.getCursor();
        LineText line = model.lineText(p.line());
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
        navigateTo(new Position(last, model.lineLength(last)), selecting);
    }

    public void insertChar(char ch) {
        keyboardEdit(() -> { insertCharInCommand(ch); return null; });
    }

    private void insertCharInCommand(char ch) {
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

        if (isAutoPairsActive() && shouldAutoPair(ch, rangeStart, rangeEnd)) {
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
        keyboardEdit(() -> { insertInputInCommand(text); return null; });
    }

    private void insertInputInCommand(String text) {
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
        keyboardEdit(() -> { insertTabInCommand(); return null; });
    }

    private void insertTabInCommand() {
        state.invalidateNavigation();
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
        SelectionRange selection = currentSelection();
        Position rangeStart = selection == null ? model.clamp(state.getCursor()) : selection.start();
        Position rangeEnd = selection == null ? rangeStart : selection.end();
        Position cursor = model.clamp(rangeStart);
        LineText line = model.lineText(cursor.line());
        int col = Math.min(cursor.col(), line.length());
        int prefixEnd = 0;
        int prefixLimit = Math.min(col, IndentationContext.MAX_SCAN_CODE_UNITS);
        while (prefixEnd < prefixLimit && (line.charAt(prefixEnd) == ' ' || line.charAt(prefixEnd) == '\t')) prefixEnd++;
        String indentPrefix = line.substring(0, prefixEnd);
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
        keyboardEdit(() -> { backspaceInCommand(); return null; });
    }

    private void backspaceInCommand() {
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
                GraphemeText graphemes = graphemes(model.lineText(cursor.line()));
                int end = graphemes.ceilBoundary(cursor.col());
                range = new SelectionRange(
                    new Position(cursor.line(), graphemes.previousBoundary(end)),
                    new Position(cursor.line(), end)
                );
            } else if (cursor.line() > 0) {
                int previousLine = cursor.line() - 1;
                range = new SelectionRange(
                    new Position(previousLine, model.lineLength(previousLine)),
                    cursor
                );
            }
        }
        deleteAndCommit(hadSelection ? EditKind.NONE : EditKind.BACKSPACE, before, ops, range);
    }

    public void deleteForward() {
        keyboardEdit(() -> { deleteForwardInCommand(); return null; });
    }

    private void deleteForwardInCommand() {
        state.invalidateNavigation();
        boolean hadSelection = state.hasSelection();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>();
        SelectionRange range = currentSelection();
        if (range == null) {
            Position cursor = model.clamp(state.getCursor());
            LineText line = model.lineText(cursor.line());
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
        keyboardEdit(() -> { insertNewlineInCommand(); return null; });
    }

    private void insertNewlineInCommand() {
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
        CommentSyntaxProfile syntax = LanguageProfiles.resolveCommentSyntax(currentNameHint());
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
        CharSequence commonIndent = null;
        int commonIndentLength = 0;
        for (int lineIndex = firstLine; lineIndex <= lastLine; lineIndex++) {
            LineText line = model.lineText(lineIndex);
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
        EditorDocument.PreparedChange views;
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
                LineText line = model.lineText(lineIndex);
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
            after = new ViewState(before.document(), editedView(afterView, prepared.buffer()::lineText));
            OperationHistoryEntry entry = new OperationHistoryEntry(EditKind.NONE, before, after,
                prepared.operations(), history.currentDocumentStateId, afterStateId);
            nextUndo = new ArrayDeque<>(history.undoStack);
            pushWithLimit(nextUndo, entry);
            result = new LineCommentResult(
                uncomment ? LineCommentAction.UNCOMMENTED : LineCommentAction.COMMENTED, nonBlankCount
            );
            views = document.prepareChange(prepared.buffer().lineCount(), prepared.buffer()::lineText, state);
            if (commandExecution != null) {
                commandExecution.comment(result);
                prepareCommandPublication(model.revision() + 1, after.view());
            }
        } catch (OutOfMemoryError exhausted) {
            // The active buffer, view and history have not been touched.
            throw new DocumentEditLimitException();
        }
        model.publishLineReplacements(prepared);
        commandPublished();
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
        invalidateGutterDiff();
        document.publishChange(views);
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
            end = new Position(line, model.lineLength(line));
        }
        String copied = ClipboardText.copy(model, start, end);
        if (copied.isEmpty()) {
            return false;
        }
        clipboard.setText(copied);
        return true;
    }

    public boolean cutSelection() {
        return keyboardEdit(this::cutSelectionInCommand);
    }

    private boolean cutSelectionInCommand() {
        SelectionRange range = currentSelection();
        if (range == null) {
            return false;
        }
        String copied = ClipboardText.copy(model, range.start(), range.end());
        state.invalidateNavigation();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>(1);
        deleteAndCommit(EditKind.NONE, before, ops, range);
        keyboardClipboard = copied;
        return true;
    }

    public boolean cutCurrentLine() {
        return keyboardEdit(this::cutCurrentLineInCommand);
    }

    private boolean cutCurrentLineInCommand() {
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
            end = new Position(line, model.lineLength(line));
        }
        if (start.equals(end)) {
            return false;
        }
        String copied = ClipboardText.copy(model, start, end);
        state.invalidateNavigation();
        ViewState before = captureViewState();
        List<ReplacementDelta> ops = new ArrayList<>(1);
        deleteAndCommit(EditKind.NONE, before, ops, new SelectionRange(start, end));
        keyboardClipboard = copied;
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
        Position end = new Position(lastLine, model.lineLength(lastLine));
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
        return undo(Boolean::valueOf);
    }

    /** Internal session result preparation has the same transaction boundary as text replay. */
    public <T> T undo(Function<Boolean, T> prepareResult) {
        return replayHistory(true, Objects.requireNonNull(prepareResult, "prepareResult"));
    }

    public boolean redo() {
        return redo(Boolean::valueOf);
    }

    public <T> T redo(Function<Boolean, T> prepareResult) {
        return replayHistory(false, Objects.requireNonNull(prepareResult, "prepareResult"));
    }

    public boolean findNext(String query) {
        return findNext(query, SearchOptions.DEFAULT);
    }

    public boolean findNext(String query, SearchOptions options) {
        return findNext(query, options, SearchWorkBudget.current());
    }

    public boolean findNext(String query, SearchOptions options, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        String needle = normalizeQuery(query);
        if (needle == null) {
            return false;
        }
        SearchNavigation navigation = searchNavigation(needle, options, context);
        breakCoalescing();

        Position from = model.clamp(state.getCursor());
        SelectionRange selected = currentSelection();
        if (selected != null && matchesSelectedText(navigation, selected, context)) {
            from = selected.end();
        }

        SearchMatch found = findForward(from, navigation, true, context);
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
        return findPrevious(query, options, SearchWorkBudget.current());
    }

    public boolean findPrevious(String query, SearchOptions options, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        String needle = normalizeQuery(query);
        if (needle == null) {
            return false;
        }
        SearchNavigation navigation = searchNavigation(needle, options, context);
        breakCoalescing();

        Position from = model.clamp(state.getCursor());
        SelectionRange selected = currentSelection();
        if (selected != null && matchesSelectedText(navigation, selected, context)) {
            from = selected.start();
        }

        SearchMatch found = findBackward(from, navigation, true, context);
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
        return replaceCurrentOrNext(query, replacement, options, SearchWorkBudget.current());
    }

    public boolean replaceCurrentOrNext(String query, String replacement, SearchOptions options,
                                        SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        String needle = normalizeQuery(query);
        if (needle == null) {
            return false;
        }
        SearchNavigation navigation = searchNavigation(needle, options, context);
        ReplacementText replacementText = new ReplacementText(replacement);

        SelectionRange selected = currentSelection();
        ViewState before = captureViewState();
        if (selected == null || !matchesSelectedText(navigation, selected, context)) {
            // Locate the match without publishing a selection: a refused replacement
            // must preserve even the view and coalescing state from before the command.
            SearchMatch found = findForward(model.clamp(state.getCursor()), navigation, true, context);
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
            SearchMatch next = findForward(rangeEnd, navigation, true, context);
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
        return replaceAll(query, replacement, options, SearchWorkBudget.current());
    }

    public int replaceAll(String query, String replacement, SearchOptions options, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        String needle = normalizeQuery(query);
        if (needle == null) {
            return 0;
        }
        SearchMatcher matcher = SearchMatcher.compile(needle, options, context);
        ReplacementText replacementText = new ReplacementText(replacement);
        DocumentFileMetadata metadata = model.fileMetadata();
        ReplacementBuilder admission = ReplacementBuilder.counting(model.lineCount());
        ReplacementResult counted = composeSearchReplacements(matcher, replacementText, admission, metadata, context);
        if (commandExecution != null) {
            commandExecution.performed = counted.matchCount() > 0;
            commandExecution.affectedCount = counted.matchCount();
        }
        if (counted.firstChange() != null) {
            ReplacementBuilder builder = admission.materializer();
            ReplacementResult built = composeSearchReplacements(matcher, replacementText, builder, metadata, context);
            applyBuiltReplacement(builder, metadata, built.firstChange());
        }
        return counted.matchCount();
    }

    private ReplacementResult composeSearchReplacements(SearchMatcher matcher, ReplacementText replacement,
                                                         ReplacementBuilder builder, DocumentFileMetadata metadata,
                                                         SearchOperationContext context) {
        int[] matchCount = {0};
        int[] copiedThrough = {0};
        Position[] earliestResult = {null};
        var separators = metadata.lineSeparators().iterator();
        for (int line = 0; line < model.lineCount(); line++) {
            int sourceLine = line;
            LineText text = model.lineText(line);
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
            }, context);
            String separator = separators.hasNext() ? separators.next() : null;
            if (earliestResult[0] != null) {
                builder.append(text, copiedThrough[0], text.length());
                if (separator != null) builder.newline(separator);
            }
        }
        if (earliestResult[0] != null) builder.finishLine();
        return new ReplacementResult(matchCount[0], earliestResult[0]);
    }

    private record ReplacementResult(int matchCount, Position firstChange) {}

    private int firstNonWhitespaceColumn(CharSequence line) {
        GraphemeText graphemes = graphemes(line);
        for (int index = 0; index < graphemes.graphemeCount(); index++) {
            if (!graphemes.isWhitespace(index)) {
                return graphemes.start(index);
            }
        }
        return 0;
    }

    private int trailingTrimEndColumn(CharSequence line) {
        GraphemeText graphemes = graphemes(line);
        int end = graphemes.graphemeCount();
        while (end > 0 && graphemes.isWhitespace(end - 1)) {
            end--;
        }
        return end == graphemes.graphemeCount() ? line.length() : graphemes.start(end);
    }

    private IndentationDecisions indentationDecisions() {
        return new IndentationDecisions(model.lineCount(), model::lineText, currentNameHint(), tabWidth,
            tabInsertMode, indentationContext());
    }

    private IndentationContext indentationContext() {
        indentationContext.synchronize(model.contentEpoch(), model.revision(),
            model.firstChangedLineSince(indentationContext.revision()), model.lineCount(), currentNameHint());
        return indentationContext;
    }

    private MarkdownListDecisions markdownListDecisions() {
        markdownContext.synchronize(
            model.contentEpoch(), model.revision(),
            model.firstChangedLineSince(markdownContext.revision()), model.lineCount()
        );
        return new MarkdownListDecisions(model::lineText, markdownContext::allowsListAssist);
    }

    private String continuationAfterNewLine(Position cursor) {
        LanguageProfile profile = languageProfile();
        LineText line = model.lineText(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        return switch (profile) {
            case YAML -> yamlContinuationMarker(cursor.line(), line, col);
            case MARKDOWN -> markdownListDecisions().continuation(cursor.line(), line.subSequence(0, col));
            default -> "";
        };
    }

    private boolean shouldAutoPair(char ch, Position rangeStart, Position rangeEnd) {
        if (!isOpeningPair(ch)) {
            return false;
        }
        LineText leftLine = model.lineText(rangeStart.line());
        LineText rightLine = model.lineText(rangeEnd.line());
        int leftCol = rangeStart.col();
        int rightCol = rangeEnd.col();

        if ((ch == '\'' || ch == '"') && isEscaped(leftLine, leftCol)) {
            return false;
        }
        if (ch == '\'') {
            boolean leftWord = leftCol > 0
                && isWordGrapheme(leftLine, graphemes(leftLine).previousBoundary(leftCol), leftCol);
            boolean rightWord = rightCol < rightLine.length()
                && isWordGrapheme(rightLine, rightCol, graphemes(rightLine).nextBoundary(rightCol));
            if (leftWord || rightWord) {
                return false;
            }
        }
        if (rightCol >= rightLine.length()) {
            return true;
        }
        char next = rightLine.charAt(rightCol);
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
        LineText line = model.lineText(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        if (col >= line.length()) {
            return false;
        }
        return line.charAt(col) == ch
            && ((ch != '\'' && ch != '"') || !isEscaped(line, col));
    }

    private static boolean isEscaped(CharSequence line, int column) {
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
        for (int lineIndex = range.start().line(); lineIndex <= range.end().line(); lineIndex++) {
            LineText line = model.lineText(lineIndex);
            int from = lineIndex == range.start().line() ? range.start().col() : 0;
            int to = lineIndex == range.end().line() ? range.end().col() : line.length();
            int quote = line.indexOf('\'', from);
            if (quote >= 0 && quote < to) {
                return false;
            }
        }
        return true;
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
        LineText line = model.lineText(cursor.line());
        if (cursor.col() >= line.length()
            || !isMatchingPair(line.charAt(cursor.col() - 1), line.charAt(cursor.col()))) {
            return null;
        }
        char left = line.charAt(cursor.col() - 1);
        if ((left == '\'' || left == '"') && isEscaped(line, cursor.col() - 1)) {
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

    private String yamlContinuationMarker(int lineIndex, LineText line, int column) {
        if (column > IndentationContext.MAX_SCAN_CODE_UNITS) return "";
        int leading = 0;
        while (leading < column && (line.charAt(leading) == ' ' || line.charAt(leading) == '\t')) leading++;
        if (leading + 2 > column || !line.startsWith("- ", leading)) {
            return "";
        }
        String code = indentationContext().codeBeforeCursor(lineIndex, column);
        // Both the marker and the insertion point must be outside a scalar.
        // A quote can close later on this line while its initial "- " remains
        // literal; conversely, a real marker can introduce an unfinished scalar.
        if (code == null || !code.startsWith("- ", leading)) return "";
        String rest = line.substring(leading, column);
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
        LineText line = model.lineText(cursor.line());
        int col = cursor.col();
        if (col > IndentationContext.MAX_SCAN_CODE_UNITS) return null;
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

    private static boolean isLeadingWhitespaceUntil(CharSequence line, int col) {
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

    private static int leadingIndentLength(CharSequence line) {
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

    private String currentNameHint() {
        DocumentResource resource = state.getCurrentResource();
        return resource == null ? null : resource.nameHint();
    }

    private LanguageProfile languageProfile() {
        return LanguageProfiles.resolve(currentNameHint());
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

    private void commitSnapshotEdit(Runnable editOperation) {
        commitSnapshotEdit(editOperation, changed -> null);
    }

    private <T> T commitSnapshotEdit(Runnable editOperation, Function<Boolean, T> prepareResult) {
        return prepareAndCommitDocumentEdit(() -> {
            Snapshot before = captureSnapshot();
            editOperation.run();
            Snapshot after = captureSnapshot();
            if (after.equals(before)) return null;
            long afterStateId = history.nextDocumentStateId + 1;
            Deque<HistoryEntry> undo = copyHistory(history.undoStack);
            pushWithLimit(undo, new SnapshotHistoryEntry(before, after,
                history.currentDocumentStateId, afterStateId));
            return new PreparedDocumentHistory(undo, new ArrayDeque<>(), afterStateId, afterStateId);
        }, prepareResult);
    }

    private <T> T replayHistory(boolean undoing, Function<Boolean, T> prepareResult) {
        HistoryEntry entry = (undoing ? history.undoStack : history.redoStack).peek();
        if (entry == null) {
            T result;
            try {
                result = prepareResult.apply(false);
            } catch (OutOfMemoryError exhausted) {
                throw new DocumentEditLimitException();
            }
            state.invalidateNavigation();
            return result;
        }
        return prepareAndCommitDocumentEdit(() -> {
            long stateId = undoing ? entry.undo(this) : entry.redo(this);
            Deque<HistoryEntry> undo = copyHistory(history.undoStack);
            Deque<HistoryEntry> redo = copyHistory(history.redoStack);
            (undoing ? undo : redo).pop();
            pushWithLimit(undoing ? redo : undo, entry);
            return new PreparedDocumentHistory(undo, redo, stateId, history.nextDocumentStateId);
        }, prepareResult);
    }

    private static Deque<HistoryEntry> copyHistory(Deque<HistoryEntry> source) {
        Deque<HistoryEntry> copy = new ArrayDeque<>(MAX_HISTORY + 1);
        copy.addAll(source);
        return copy;
    }

    /** Isolates bulk edits and history replay until text, stacks and all attached views are ready. */
    private <T> T prepareAndCommitDocumentEdit(Supplier<PreparedDocumentHistory> preparation,
                                              Function<Boolean, T> prepareResult) {
        EditorViewMemento before = state.viewMemento();
        long navigationBefore = state.navigationRevision();
        TextModel.LocalEdit edit = null;
        boolean published = false;
        try {
            edit = model.beginLocalEdit();
            PreparedDocumentHistory prepared = preparation.get();
            if (prepared == null) return prepareResult.apply(false);
            edit.preparePublication();
            EditorDocument.PreparedChange views = document.prepareChange();
            T result = prepareResult.apply(true);
            prepareCommandPublication(views);
            edit.publish();
            published = true;
            commandPublished();
            history.undoStack = prepared.undo();
            history.redoStack = prepared.redo();
            history.currentDocumentStateId = prepared.stateId();
            history.nextDocumentStateId = prepared.nextStateId();
            state.setDirty(history.savedDocumentStateId < 0L
                || prepared.stateId() != history.savedDocumentStateId);
            state.invalidateNavigation();
            breakCoalescing();
            invalidateGutterDiff();
            document.publishChange(views);
            return result;
        } catch (OutOfMemoryError exhausted) {
            if (published) throw exhausted;
            throw new DocumentEditLimitException();
        } finally {
            if (!published) {
                if (edit != null) edit.abort();
                state.restoreEditState(before, navigationBefore);
                cachedGraphemes = null;
                indentationContext.reset();
                markdownContext.reset();
            }
        }
    }

    private record PreparedDocumentHistory(Deque<HistoryEntry> undo, Deque<HistoryEntry> redo,
        long stateId, long nextStateId) {}

    /** Runs typing assists against a local overlay, then publishes prepared text, history and views. */
    private <T> T keyboardEdit(Supplier<T> action) {
        if (keyboardCommand) return action.get();
        EditorViewMemento before = state.viewMemento();
        long navigationBefore = state.navigationRevision();
        TextModel.LocalEdit edit = null;
        boolean published = false;
        try {
            edit = model.beginLocalEdit();
            keyboardCommand = true;
            T result = action.get();
            edit.preparePublication();
            EditorDocument.PreparedChange views = keyboardHistory == null ? null : document.prepareChange();
            if (commandExecution != null && result instanceof Boolean performed) {
                commandExecution.performed = performed;
            }
            prepareCommandPublication(views);
            edit.publish();
            published = true;
            commandPublished();
            if (keyboardHistory != null) {
                publishKeyboardHistory(keyboardHistory);
                document.publishChange(views);
            }
            if (keyboardClipboard != null) clipboard.setText(keyboardClipboard);
            return result;
        } catch (OutOfMemoryError exhausted) {
            if (published) throw exhausted;
            throw new DocumentEditLimitException();
        } finally {
            if (!published) {
                if (edit != null) edit.abort();
                state.restoreEditState(before, navigationBefore);
                cachedGraphemes = null;
                indentationContext.reset();
                markdownContext.reset();
            }
            keyboardCommand = false;
            keyboardHistory = null;
            keyboardClipboard = null;
        }
    }

    private PreparedKeyboardHistory prepareKeyboardHistory(EditKind kind, ViewState before, ViewState after,
                                                           List<ReplacementDelta> operations) {
        long afterStateId = history.nextDocumentStateId + 1;
        long now = System.nanoTime();
        OperationHistoryEntry merged = null;
        Deque<HistoryEntry> undo = null;
        if (shouldCoalesce(kind, before.cursor(), before.selectionAnchor(), now)
            && history.undoStack.peek() instanceof OperationHistoryEntry previous && previous.kind() == kind) {
            previous.prepareAppend(operations.size());
            merged = previous;
        } else {
            OperationHistoryEntry entry = new OperationHistoryEntry(kind, before, after, operations,
                history.currentDocumentStateId, afterStateId);
            undo = new ArrayDeque<>(MAX_HISTORY + 1);
            undo.addAll(history.undoStack);
            pushWithLimit(undo, entry);
        }
        return new PreparedKeyboardHistory(kind, after, operations, afterStateId, now, merged, undo);
    }

    /** Capacity and every object reachable by this publication were allocated before the text commit. */
    private void publishKeyboardHistory(PreparedKeyboardHistory prepared) {
        if (prepared.merged() == null) history.undoStack = prepared.undo();
        else prepared.merged().append(prepared.operations(), prepared.after(), prepared.stateId());
        history.nextDocumentStateId = prepared.stateId();
        history.currentDocumentStateId = prepared.stateId();
        history.redoStack.clear();
        state.setDirty(history.savedDocumentStateId < 0L || prepared.stateId() != history.savedDocumentStateId);
        history.lastEditKind = prepared.kind();
        history.lastEditAtNanos = prepared.atNanos();
        history.lastEditCursorAfter = prepared.after().cursor();
        history.lastEditViewId = state.viewId();
        invalidateGutterDiff();
    }

    private record PreparedKeyboardHistory(EditKind kind, ViewState after, List<ReplacementDelta> operations,
        long stateId, long atNanos, OperationHistoryEntry merged, Deque<HistoryEntry> undo) {}

    private void commitOperationEdit(EditKind kind, ViewState before, List<ReplacementDelta> ops) {
        if (ops == null || ops.isEmpty()) {
            return;
        }
        ViewState after = captureViewState();
        for (ReplacementDelta delta : ops) {
            if (delta.operation().deletedText().equals(delta.operation().insertedText())) continue;
            EditorViewMemento view = editedView(after.view(), model::lineText);
            if (view != after.view()) {
                after = new ViewState(after.document(), view);
                state.setCursor(view.cursor());
                state.setSelectionAnchor(view.selectionAnchor());
            }
            break;
        }
        if (!keyboardCommand) throw new IllegalStateException("Operation edits require an isolated command");
        keyboardHistory = prepareKeyboardHistory(kind, before, after, ops);
    }

    /** Prepares the document, compact undo and view before exposing any part of a local replacement. */
    private void commitPreparedReplacement(Position start, Position end, ReplacementText replacement,
                                           Position anchorAfter, ViewState before) {
        TextModel.PreparedReplacement prepared;
        EditorDocument.PreparedChange views;
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
            int lineCount = prepared.buffer() == null ? model.lineCount() : prepared.buffer().lineCount();
            views = document.prepareChange(lineCount, prepared::resultingLine, state);
            prepareCommandPublication(model.revision() + 1, after.view());
        } catch (OutOfMemoryError exhausted) {
            // Preparation only owns temporary data. The active model and history
            // still describe the complete pre-command document.
            throw new DocumentEditLimitException();
        }
        model.publishReplacement(prepared);
        commandPublished();
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
        invalidateGutterDiff();
        document.publishChange(views);
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
            if (!keyboardCommand) invalidateGutterDiff();
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
        LineText line = model.lineText(clamped.line());
        return new Position(clamped.line(), graphemes(line).floorBoundary(clamped.col()));
    }

    /** Keep subsequent typing after a newly joined cluster without widening an exact text selection. */
    private EditorViewMemento editedView(EditorViewMemento view, IntFunction<? extends CharSequence> lines) {
        Position cursor = view.cursor();
        int column = graphemes(lines.apply(cursor.line())).ceilBoundary(cursor.col());
        Position anchor = view.selectionAnchor();
        boolean splitAnchor = anchor != null
            && graphemes(lines.apply(anchor.line())).ceilBoundary(anchor.col()) != anchor.col();
        if (column == cursor.col() && !splitAnchor) return view;
        return new EditorViewMemento(view.viewId(), new Position(cursor.line(), column), view.viewport(), null);
    }

    private GraphemeText graphemes(CharSequence text) {
        if (cachedGraphemes != null && cachedGraphemes.sequence() == text) return cachedGraphemes;
        LineText.Edit edit = text instanceof LineText next ? next.edit() : null;
        if (cachedGraphemes != null && cachedGraphemes.sequence() instanceof LineText previous
            && edit != null && edit.sourceIdentity() == previous.identity()) {
            cachedGraphemes = cachedGraphemes.updated(text, edit.start(), edit.removedLength(), edit.insertedLength());
        } else cachedGraphemes = GraphemeText.of(text);
        return cachedGraphemes;
    }

    private Position requireTextBoundary(Position requested) {
        Position clamped = model.clamp(requested);
        LineText line = model.lineText(clamped.line());
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
        LineText line = model.lineText(position.line());
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
        resetGutterDiffBaselinesOnOpen(model.snapshotTextLines());
    }

    private void resetGutterDiffBaselinesOnOpen(List<LineText> snapshot) {
        cachedGraphemes = null;
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
        if (!lineDiff.available() || gutterDiff.gutterDiffMode == GutterDiffMode.DISABLED) {
            clearAllGutterDiffBuffers(model.lineCount());
            gutterDiff.gutterDiffComputedRevision = model.revision();
            gutterDiff.gutterDiffPendingRebuild = false;
            return;
        }
        long startedAt = gutterDiffClock.getAsLong();
        List<LineText> current = model.snapshotTextLines();
        int lines = Math.max(1, current.size());
        initGutterDiffBuffers(gutterDiff.savedDiffFlags, gutterDiff.savedDeletedMarkers, lines);
        initGutterDiffBuffers(gutterDiff.openDiffFlags, gutterDiff.openDeletedMarkers, lines);

        LineDiff.Algorithm algorithm = resolveGutterDiffAlgorithm();
        List<? extends CharSequence> currentLines = diffLines(current);
        List<LineEdit> savedEdits = lineDiff.compute(
            diffLines(document.baselines.savedLines), currentLines, algorithm, GUTTER_DIFF_MAX_WORK
        );
        applyDiffEdits(savedEdits, lines, gutterDiff.savedDiffFlags, gutterDiff.savedDeletedMarkers);

        List<LineEdit> openEdits = document.baselines.openLines == document.baselines.savedLines
            ? savedEdits
            : lineDiff.compute(diffLines(document.baselines.openLines), currentLines, algorithm, GUTTER_DIFF_MAX_WORK);
        applyDiffEdits(openEdits, lines, gutterDiff.openDiffFlags, gutterDiff.openDeletedMarkers);

        gutterDiff.gutterDiffLastRebuildDurationNanos = gutterDiffClock.getAsLong() - startedAt;
        gutterDiff.gutterDiffComputedRevision = model.revision();
        gutterDiff.gutterDiffPendingRebuild = false;
        gutterDiff.gutterDiffLastRebuildAtNanos = gutterDiffClock.getAsLong();
    }

    /** Publication only marks derived work; provider calls belong to diff refresh. */
    private void invalidateGutterDiff() {
        gutterDiff.gutterDiffComputedRevision = Long.MIN_VALUE;
        gutterDiff.gutterDiffPendingRebuild = true;
    }

    private boolean needsGutterDiffRefresh() {
        if (!lineDiff.available()) return false;
        if (gutterDiff.baselineGeneration != document.baselines.generation) {
            gutterDiff.baselineGeneration = document.baselines.generation;
            invalidateGutterDiff();
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

    private LineDiff.Algorithm resolveGutterDiffAlgorithm() {
        return switch (gutterDiff.gutterDiffMode) {
            case DISABLED, MYERS, STABLE -> LineDiff.Algorithm.MYERS;
            case HISTOGRAM -> LineDiff.Algorithm.HISTOGRAM;
        };
    }

    /** Exposes shared immutable values to providers without a core dependency or text copies. */
    private static List<? extends CharSequence> diffLines(List<? extends CharSequence> lines) {
        return new AbstractList<CharSequence>() {
            @Override public int size() { return lines.size(); }
            @Override public CharSequence get(int index) {
                CharSequence line = lines.get(index);
                return line instanceof LineText text ? text.immutableText() : line;
            }
        };
    }

    private void applyDiffEdits(
        List<LineEdit> edits,
        int currentLineCount,
        List<GutterDiffFlag> flags,
        List<Boolean> deletedMarkers
    ) {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        for (LineEdit edit : edits) {
            int deleted = Math.max(0, edit.beforeEnd() - edit.beforeStart());
            int added = Math.max(0, edit.afterEnd() - edit.afterStart());
            int beginCurrent = Math.max(0, edit.afterStart());
            switch (edit.type()) {
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
        } else if (!state.hasSelection()) {
            EditorViewMemento active = new EditorViewMemento(state.viewId(), model.clamp(state.getCursor()),
                state.viewport(), state.getSelectionAnchor() == null ? null : model.clamp(state.getSelectionAnchor()));
            EditorViewMemento normalized = editedView(active, model::lineText);
            state.setCursor(normalized.cursor());
            state.setSelectionAnchor(normalized.selectionAnchor());
        }
        // Foreign selections keep exact search ranges; an unselected caret
        // resumes at a grapheme boundary. Publication also clamps invalid ranges.
    }

    private Snapshot captureSnapshot() {
        return new Snapshot(model.snapshotLines(), model.fileMetadata(), state.viewMemento());
    }

    private void restoreSnapshot(Snapshot snapshot) {
        model.replaceLines(snapshot.lines(), snapshot.fileMetadata());
        restoreViewState(new ViewState(DocumentPreferences.from(snapshot.fileMetadata()), snapshot.view()));
    }

    private static <T> void pushWithLimit(Deque<T> stack, T entry) {
        stack.push(entry);
        while (stack.size() > MAX_HISTORY) {
            stack.removeLast();
        }
    }

    private record Snapshot(List<String> lines, DocumentFileMetadata fileMetadata, EditorViewMemento view) {}

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
        long undo(EditorEngine engine);

        long redo(EditorEngine engine);
    }

    /** Format operations alone retain a complete separator layout, without copying text. */
    private record FormatHistoryEntry(DocumentFileMetadata before, DocumentFileMetadata after,
                                      long beforeStateId, long afterStateId) implements HistoryEntry {
        @Override public long undo(EditorEngine engine) {
            engine.model.restoreFileMetadata(before);
            return beforeStateId;
        }
        @Override public long redo(EditorEngine engine) {
            engine.model.restoreFileMetadata(after);
            return afterStateId;
        }
    }

    private static final class SnapshotHistoryEntry implements HistoryEntry {
        private final Snapshot before;
        private final Snapshot after;
        private final long beforeStateId;
        private final long afterStateId;

        private SnapshotHistoryEntry(
            Snapshot before,
            Snapshot after,
            long beforeStateId,
            long afterStateId
        ) {
            this.before = before;
            this.after = after;
            this.beforeStateId = beforeStateId;
            this.afterStateId = afterStateId;
        }

        @Override
        public long undo(EditorEngine engine) {
            engine.restoreSnapshot(before);
            return beforeStateId;
        }

        @Override
        public long redo(EditorEngine engine) {
            engine.restoreSnapshot(after);
            return afterStateId;
        }
    }

    private static final class OperationHistoryEntry implements HistoryEntry {
        private final EditKind kind;
        private final ViewState beforeView;
        private ViewState afterView;
        private final ArrayList<ReplacementDelta> ops;
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
            for (int index = 0; index < extraOps.size(); index++) this.ops.add(extraOps.get(index));
            this.afterView = mergedAfterView;
            this.afterStateId = mergedAfterStateId;
        }

        void prepareAppend(int count) {
            ops.ensureCapacity(Math.addExact(ops.size(), count));
        }

        @Override
        public long undo(EditorEngine engine) {
            for (int i = ops.size() - 1; i >= 0; i--) {
                replay(engine, ops.get(i).inverse());
            }
            engine.restoreViewState(beforeView);
            return beforeStateId;
        }

        @Override
        public long redo(EditorEngine engine) {
            for (ReplacementDelta delta : ops) {
                replay(engine, delta);
            }
            engine.restoreViewState(afterView);
            return afterStateId;
        }

        private void replay(EditorEngine engine, ReplacementDelta delta) {
            engine.model.replayReplacement(delta);
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

    private static int lineCommentColumn(LineText line, String marker, int lineIndex) {
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

    SearchNavigation searchNavigation(String query, SearchOptions options) {
        return searchNavigation(query, options, SearchWorkBudget.current());
    }

    SearchNavigation searchNavigation(String query, SearchOptions options, SearchOperationContext context) {
        searchNavigation.configure(query, options, context);
        return searchNavigation;
    }

    SearchNavigation searchNavigation(SearchNavigation query) {
        searchNavigation.configureLike(query);
        return searchNavigation;
    }

    private SearchMatch findForward(Position from, SearchNavigation navigation, boolean wrap,
                                    SearchOperationContext context) {
        int lineCount = model.lineCount();
        for (int line = from.line(); line < lineCount; line++) {
            LineText text = model.lineText(line);
            int startCol = line == from.line() ? Math.min(from.col(), text.length()) : 0;
            SearchMatch match = findInLineForward(line, navigation, startCol, context);
            if (match != null) {
                return match;
            }
        }
        if (!wrap) {
            return null;
        }
        for (int line = 0; line <= from.line(); line++) {
            LineText text = model.lineText(line);
            int limit = line == from.line() ? Math.min(from.col(), text.length()) : text.length();
            SearchMatch match = findInLineForward(line, navigation, 0, context);
            if (match != null && match.start().col() < limit) {
                return match;
            }
        }
        return null;
    }

    private SearchMatch findBackward(Position from, SearchNavigation navigation, boolean wrap,
                                     SearchOperationContext context) {
        for (int line = from.line(); line >= 0; line--) {
            LineText text = model.lineText(line);
            int endExclusive = line == from.line() ? Math.min(from.col(), text.length()) : text.length();
            SearchMatch match = findInLineBackward(line, navigation, endExclusive, context);
            if (match != null) {
                return match;
            }
        }
        if (!wrap) {
            return null;
        }
        for (int line = model.lineCount() - 1; line >= from.line(); line--) {
            LineText text = model.lineText(line);
            int startInclusive = line == from.line() ? Math.min(from.col(), text.length()) : 0;
            SearchMatch match = findInLineBackward(line, navigation, text.length(), context);
            if (match != null && match.start().col() >= startInclusive) {
                return match;
            }
        }
        return null;
    }

    private void selectMatch(Position start, int length) {
        Position safeStart = model.clamp(start);
        int maxCol = model.lineLength(safeStart.line());
        int endCol = Math.min(maxCol, safeStart.col() + Math.max(length, 0));
        Position end = new Position(safeStart.line(), endCol);
        state.setSelectionAnchor(safeStart);
        state.setCursor(end);
        state.invalidateNavigation();
    }

    private boolean matchesSelectedText(SearchNavigation navigation, SelectionRange selected,
                                         SearchOperationContext context) {
        if (selected.start().line() != selected.end().line()) {
            return false;
        }
        return navigation.matchesRange(selected.start().line(), selected.start().col(), selected.end().col(), context);
    }

    private SearchMatch findInLineForward(int line, SearchNavigation navigation, int fromCol,
                                         SearchOperationContext context) {
        SearchMatcher.Match match = navigation.firstAtOrAfter(line, fromCol, context);
        return match == null ? null
            : new SearchMatch(new Position(line, match.startUtf16()), match.lengthUtf16());
    }

    private SearchMatch findInLineBackward(int line, SearchNavigation navigation, int beforeCol,
                                          SearchOperationContext context) {
        SearchMatcher.Match match = navigation.lastBefore(line, beforeCol, context);
        return match == null ? null
            : new SearchMatch(new Position(line, match.startUtf16()), match.lengthUtf16());
    }

    private static boolean isWhitespaceGrapheme(CharSequence text, int start, int end) {
        if (start >= end) {
            return false;
        }
        for (int i = start; i < end;) {
            int codePoint = Character.codePointAt(text, i);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isWordGrapheme(CharSequence text, int start, int end) {
        if (start >= end) {
            return false;
        }
        int codePoint = Character.codePointAt(text, start);
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static int graphemeGroup(CharSequence text, int start, int end) {
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

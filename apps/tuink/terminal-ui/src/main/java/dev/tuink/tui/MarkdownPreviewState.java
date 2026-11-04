package dev.tuink.tui;

import dev.tuink.core.SearchLimitException;
import dev.tuink.core.SearchMatcher;
import dev.tuink.core.SearchOperationContext;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.markdown.preview.PreviewCoordinator;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownTextSurface;
import dev.tuink.render.surface.TextSurfaceInteraction;
import dev.tuink.render.surface.TextSurfacePointer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.TextField;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.FutureTask;

/** View-local mode, request lifetime and viewport. Never shared with an editor cursor. */
final class MarkdownPreviewState implements AutoCloseable {
    static final int SEARCH_QUERY_MAX_UTF16 = 4096;
    static final int SEARCH_MATCH_LIMIT = 10_000;
    final PreviewCoordinator coordinator = new PreviewCoordinator();
    final PreviewSelection selection = new PreviewSelection();
    boolean active;
    boolean closed;
    int top;
    int left;
    int anchorSourceLine;
    int requestedWidth;
    DocumentId requestedId;
    long requestedRevision = -1;
    MarkdownLayout.Result result;
    MarkdownGeometry geometry;
    FutureTask<MarkdownLayout.Result> pending;
    PreviewCoordinator.Request request;
    String error = "";
    Rect bounds = new Rect(0, 0, 0, 0);
    String searchQuery = "";
    final TextField searchField = new TextField();
    boolean searchVisible;
    List<PreviewSelection.Range> searchMatches = List.of();
    int activeMatch = -1;
    SearchFailure searchFailure = SearchFailure.NONE;
    private SearchOptions searchOptions = SearchController.toSearchOptions(SearchController.SearchUiOptions.DEFAULT);
    private final long searchWorkLimit;
    private DocumentId selectionDocument;
    private long selectionRevision = -1;
    private int viewportAnchor = -1;
    private int viewportAnchorDelta;
    private int caretAnchor = -1;
    private int caretAnchorDelta;
    private int caretColumn;
    private MarkdownPreviewService service;
    private FrozenDocumentSource creationEnvelope;
    private FrozenDocumentSource acceptedEnvelope;
    private FrozenDocumentSource pendingEnvelope;
    private MarkdownPreviewBudget.Lease creationRetention;
    private MarkdownPreviewBudget.Lease acceptedRetention;
    private MarkdownPreviewBudget.Lease pendingRetention;
    private MarkdownPreviewBudget.Lease resultRetention;
    private long linkGeneration = Long.MIN_VALUE;
    private long pendingLinkGeneration;
    private long lifecycleGeneration;
    private long pendingLifecycleGeneration;
    private final TextSurfacePointer<Integer> pointer = new TextSurfacePointer<>((first, second) -> first.equals(second));

    MarkdownPreviewState() { this(SearchPresentation.INTERACTIVE_WILDCARD_WORK_LIMIT); }
    MarkdownPreviewState(long searchWorkLimit) {
        if (searchWorkLimit < 0) throw new IllegalArgumentException("Search work limit must not be negative");
        this.searchWorkLimit = searchWorkLimit;
    }

    /** Admission happens before a new preview is inserted into workspace membership. */
    boolean initialize(FrozenDocumentSource input, long generation, MarkdownPreviewService service) {
        Objects.requireNonNull(input); Objects.requireNonNull(service);
        if (closed) throw new IllegalStateException("Preview is closed");
        if (generation < linkGeneration) return false;
        if (this.service != null && this.service != service) throw new IllegalStateException("Preview belongs to another window");
        if (creationEnvelope == null && acceptedEnvelope == null) {
            try { creationRetention = service.retainSource(input); }
            catch (MarkdownPreviewBudget.Refused refused) { error = refused.getMessage(); return false; }
            creationEnvelope = input;
        }
        this.service = service;
        if (linkGeneration != generation) { captureAnchor(); cancel(); linkGeneration = generation; }
        return true;
    }

    boolean activate(FrozenDocumentSource input, long generation, Rect bounds, MarkdownPreviewService service) {
        if (!initialize(input, generation, service)) return false;
        active = true;
        refresh(input, generation, bounds, service, true);
        return true;
    }

    void refresh(FrozenDocumentSource input, long generation, Rect bounds, MarkdownPreviewService service) {
        refresh(input, generation, bounds, service, false);
    }
    private void refresh(FrozenDocumentSource input, long generation, Rect bounds, MarkdownPreviewService service, boolean immediate) {
        if (!this.bounds.equals(bounds)) cancelPointerInteraction();
        this.bounds = bounds;
        if (!active || closed || !initialize(input, generation, service)) return;
        collect(input.text().documentId(), input.text().revision());
        int width = Math.max(1, Math.min(1024, bounds.width() - 1));
        boolean sameText = input.text().documentId().equals(requestedId) && input.text().revision() == requestedRevision;
        if (sameText && width == requestedWidth && pending == null && result == null && !error.isEmpty()) return;
        if (sameText && width == requestedWidth && pending == null && result != null) {
            if (!sameEnvelope(acceptedEnvelope, input)) {
                MarkdownPreviewBudget.Lease retained;
                try { retained = service.replaceSource(acceptedRetention, input); }
                catch (MarkdownPreviewBudget.Refused refused) { error = refused.getMessage(); return; }
                acceptedRetention = retained; acceptedEnvelope = input;
            }
            error = ""; clamp(); keepCaretInViewport(); return;
        }
        if (sameText && width == requestedWidth && pending != null && sameEnvelope(pendingEnvelope, input)) {
            clamp(); return;
        }
        MarkdownPreviewBudget.Lease retained;
        try { retained = service.retainSource(input); }
        catch (MarkdownPreviewBudget.Refused refused) { error = refused.getMessage(); return; }
        captureAnchor(); cancel(); releaseResult();
        pendingRetention = retained; pendingEnvelope = input;
        requestedId = input.text().documentId(); requestedRevision = input.text().revision(); requestedWidth = width;
        pendingLinkGeneration = generation; pendingLifecycleGeneration = lifecycleGeneration;
        request = coordinator.prepare(input.text()); error = "";
        pending = service.submit(input.text(), width, immediate || acceptedEnvelope == null ? 0 : 50);
    }

    void suspend() {
        if (closed) return;
        captureAnchor(); active = false; cancel(); releaseResult();
        searchField.cancelMouseSelection(); searchMatches = List.of(); selection.releaseIndex();
    }
    FrozenDocumentSource acceptedEnvelope() { return acceptedEnvelope; }
    FrozenDocumentSource sourceForFreeze() {
        FrozenDocumentSource source = acceptedEnvelope != null ? acceptedEnvelope : creationEnvelope;
        if (source == null) throw new IllegalStateException("Preview has no admitted source snapshot");
        return source;
    }
    /** Only a confirmed storage receipt may retarget the retained snapshot. */
    void updateBinding(ResourceBinding binding, DocumentOpenMode reopenMode, long generation) {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(reopenMode);
        if (closed || generation < linkGeneration) return;
        captureAnchor(); cancel(); linkGeneration = generation;
        if (acceptedEnvelope != null) acceptedEnvelope = acceptedEnvelope.withBinding(binding, reopenMode);
        if (creationEnvelope != null) creationEnvelope = creationEnvelope.withBinding(binding, reopenMode);
    }
    /** Frozen-save receipts may change format while retaining exactly the captured text. */
    boolean updateSavedSource(FrozenDocumentSource saved, long generation) {
        Objects.requireNonNull(saved);
        if (closed || generation < linkGeneration) return false;
        var current = sourceForFreeze();
        if (current.text() != saved.text()) throw new IllegalArgumentException("Saved receipt belongs to another source snapshot");
        captureAnchor(); cancel(); linkGeneration = generation;
        try {
            if (acceptedEnvelope != null) {
                acceptedRetention = service.replaceSource(acceptedRetention, saved); acceptedEnvelope = saved;
            } else {
                creationRetention = service.replaceSource(creationRetention, saved); creationEnvelope = saved;
            }
            error = ""; return true;
        } catch (MarkdownPreviewBudget.Refused refused) { error = refused.getMessage(); return false; }
    }
    private static boolean sameEnvelope(FrozenDocumentSource a, FrozenDocumentSource b) {
        return a != null && a.text().documentId().equals(b.text().documentId()) && a.text().revision() == b.text().revision()
            && a.metadata() == b.metadata() && a.binding().equals(b.binding()) && a.reopenMode() == b.reopenMode();
    }

    boolean collect(DocumentId id, long revision) {
        if (pending == null || !pending.isDone()) return false;
        try {
            var finished = pending.get();
            boolean typed = pendingEnvelope != null;
            if (coordinator.accepts(request, id, revision) && (!typed || active
                && pendingLinkGeneration == linkGeneration && pendingLifecycleGeneration == lifecycleGeneration)) {
                cancelPointerInteraction();
                boolean preserve = id.equals(selectionDocument) && revision == selectionRevision;
                if (geometry != null) { geometry.close(); geometry = null; }
                if (resultRetention != null) resultRetention.close();
                resultRetention = pending instanceof MarkdownPreviewService.Task task ? task.takeRetention() : null;
                if (typed) {
                    if (acceptedRetention != null) acceptedRetention.close();
                    acceptedRetention = pendingRetention; pendingRetention = null;
                    acceptedEnvelope = pendingEnvelope;
                    if (creationRetention != null) { creationRetention.close(); creationRetention = null; creationEnvelope = null; }
                }
                result = finished;
                geometry = new MarkdownGeometry(result, service == null ? bytes -> null : service.renderMemoryBudget());
                top = preserve && viewportAnchor >= 0
                    ? TextSurfaceInteraction.anchorRow(textSurface(), viewportAnchor, viewportAnchorDelta)
                    : result.rowForSource(anchorSourceLine);
                if (preserve) {
                    selection.bindGeometry(geometry);
                    selection.reconcile(result.document().projection().text());
                    if (caretAnchor >= 0) selection.restoreVisual(result,
                        TextSurfaceInteraction.anchorRow(textSurface(), caretAnchor, caretAnchorDelta), caretColumn);
                }
                else {
                    selection.clear(); selection.bindGeometry(geometry); selection.selectAt(result, Math.max(0, top), 0, false);
                    selectionDocument = id; selectionRevision = revision;
                }
                rebuildSearch();
                clamp();
            }
        } catch (java.util.concurrent.CancellationException cancelled) {
            requestedRevision = -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); error = "Preview interrupted";
        } catch (java.util.concurrent.ExecutionException failure) {
            if (coordinator.accepts(request, id, revision)) error = "Preview unavailable: " + failure.getCause().getMessage();
        } finally {
            if (pending instanceof MarkdownPreviewService.Task task) task.close();
            if (pendingRetention != null) { pendingRetention.close(); pendingRetention = null; }
            pendingEnvelope = null; pending = null; request = null; coordinator.cancel();
        }
        return true;
    }
    /** Viewport-only panning carries the visual caret, keeping the next key in the visible area. */
    void scroll(int rows, int columns) {
        if (!ready()) return;
        var next = TextSurfaceInteraction.scrollBy(viewport(), result.lines().size(), result.extent(), rows, columns);
        scrollTo(next.top(), next.left());
    }
    void clamp() {
        if (result == null) { top = 0; left = 0; return; }
        applyViewport(TextSurfaceInteraction.clamp(viewport(), result.lines().size(), result.extent()));
    }
    private TextSurfaceInteraction.Viewport viewport() {
        var content = contentBounds();
        return new TextSurfaceInteraction.Viewport(top, left, content.height(), content.width());
    }
    private void applyViewport(TextSurfaceInteraction.Viewport viewport) { top = viewport.top(); left = viewport.left(); }
    private MarkdownTextSurface textSurface() { return new MarkdownTextSurface(result, geometry); }

    PreviewScrollbars scrollbars() { return PreviewScrollbars.from(bounds, result, searchVisible, top, left); }
    Rect contentBounds() { return scrollbars().layout().contentBounds(); }
    boolean pointerCaptureActive() { return pointer.capturing(); }
    void cancelPointerInteraction() { pointer.cancel(); }

    private void scrollTo(int nextTop, int nextLeft) {
        var caret = selection.visualPosition(result);
        var pan = TextSurfaceInteraction.pan(textSurface(), viewport(), new TextSurfaceInteraction.Visual(caret.row(), caret.column()),
            nextTop, nextLeft, TextSurfaceInteraction.PanCaret.TRANSLATE_VISUAL);
        int rows = pan.viewport().top() - top, columns = pan.viewport().left() - left;
        applyViewport(pan.viewport());
        selection.pan(result, pan, rows > 0 || rows == 0 && columns >= 0); activeMatch = -1;
    }

    /** Scrollbar hit testing and painting consume the same immutable placement. */
    boolean pointer(PointerEvent event) {
        if (!ready()) {
            boolean captured = pointer.capturing();
            if (event.kind() == PointerEvent.Kind.RELEASE) pointer.finishSelection();
            return captured || bounds.contains(event.column(), event.row());
        }
        var bars = scrollbars();
        var placement = new TextSurfacePointer.Placement<Integer>(bounds, bars.layout(), bars.vertical(), bars.horizontal(),
            textSurface(), viewport(), result.lines().size(), result.extent(), TextSurfacePointer.Policy.preview());
        var gesture = pointer.handle(placement, event,
            event.modifiers().contains(dev.tuiframe.input.KeyStroke.Modifier.SHIFT), System.nanoTime());
        switch (gesture.action()) {
            case SCROLL -> scrollTo(gesture.viewport().top(), gesture.viewport().left());
            case BEGIN, EXTEND, WORD -> {
                if (gesture.viewport().top() != top || gesture.viewport().left() != left) applyViewport(gesture.viewport());
                selection.selectAtColumn(result, gesture.visual().row(), gesture.visual().column(),
                    gesture.action() == TextSurfacePointer.Action.EXTEND);
                activeMatch = -1; revealCaret();
            }
            default -> { }
        }
        return gesture.consumed();
    }

    boolean ready() {
        return active && !closed && result != null && result.document().documentId().equals(requestedId)
            && result.document().revision() == requestedRevision && result.columns() == requestedWidth;
    }
    int cursorOffset() { return ready() ? selection.targetOffset(result) : selection.caret(); }
    int selectionStart() { return selection.start(); }
    int selectionEnd() { return selection.end(); }
    String selectedText() { return ready() ? selection.selectedText(result.document().projection().text()) : ""; }
    void selectAll() {
        if (!ready()) return;
        selection.selectAll(result.document().projection().text()); activeMatch = -1;
    }
    void selectAt(int row, int utf16Offset, boolean extend) {
        if (!ready() || result.lines().isEmpty()) return;
        selection.selectAt(result, row, utf16Offset, extend); activeMatch = -1; revealCaret();
    }
    void pointerAt(int windowRow, int windowColumn, boolean extend) {
        if (!ready() || result.lines().isEmpty()) return;
        var content = contentBounds();
        if (content.isEmpty()) return;
        // Captured drags may continue outside the content; clamp their row before the common hit conversion.
        int localRow = Math.clamp(windowRow - content.y(), 0, content.height() - 1);
        var hit = TextSurfaceInteraction.hit(viewport(), localRow, windowColumn - content.x());
        selection.selectAtColumn(result, hit.row(), hit.column(), extend); activeMatch = -1; revealCaret();
    }
    void moveSelection(PreviewSelection.Move move, boolean extend) {
        if (!ready() || result.lines().isEmpty()) return;
        int page = Math.max(1, contentBounds().height() - 1);
        selection.move(result, move, extend, page);
        if (move == PreviewSelection.Move.PAGE_UP || move == PreviewSelection.Move.PAGE_DOWN) {
            top = TextSurfaceInteraction.pageTop(top, move == PreviewSelection.Move.PAGE_UP ? -page : page,
                result.lines().size(), contentBounds().height(), true);
            clamp(); keepCaretInViewport();
        } else revealCaret();
        activeMatch = -1;
    }
    void beginSearch() {
        cancelPointerInteraction(); searchVisible = true; searchField.selectAll();
        if (ready()) { clamp(); keepCaretInViewport(); }
    }
    void setSearchQuery(String query) {
        String next = Objects.requireNonNullElse(query, "");
        if (next.length() > SEARCH_QUERY_MAX_UTF16) {
            int end = Character.isHighSurrogate(next.charAt(SEARCH_QUERY_MAX_UTF16 - 1))
                ? SEARCH_QUERY_MAX_UTF16 - 1 : SEARCH_QUERY_MAX_UTF16;
            next = next.substring(0, end);
        }
        if (searchQuery.equals(next)) return;
        searchQuery = next;
        if (!searchField.text().equals(next)) searchField.set(next);
        activeMatch = -1; rebuildSearch();
    }
    void setSearchOptions(SearchOptions options) {
        SearchOptions next = Objects.requireNonNull(options);
        if (searchOptions.equals(next)) return;
        searchOptions = next; activeMatch = -1; rebuildSearch();
    }
    void nextMatch(boolean backward) {
        if (!ready() || searchMatches.isEmpty()) return;
        if (activeMatch >= 0) activeMatch = Math.floorMod(activeMatch + (backward ? -1 : 1), searchMatches.size());
        else {
            activeMatch = backward ? searchMatches.size() - 1 : 0;
            if (backward) {
                for (int i = searchMatches.size() - 1; i >= 0; i--)
                    if (searchMatches.get(i).end() <= selection.caret()) { activeMatch = i; break; }
            } else {
                for (int i = 0; i < searchMatches.size(); i++)
                    if (searchMatches.get(i).start() >= selection.caret()) { activeMatch = i; break; }
            }
        }
        var match = searchMatches.get(activeMatch);
        selection.selectRange(result.document().projection().text(), match.start(), match.end());
        revealCaret();
    }
    private void rebuildSearch() {
        searchFailure = SearchFailure.NONE;
        if (!ready() || searchQuery.isEmpty()) { searchMatches = List.of(); activeMatch = -1; return; }
        String text = result.document().projection().text();
        try {
            var context = SearchOperationContext.bounded(searchWorkLimit);
            var matcher = SearchMatcher.compile(searchQuery, searchOptions, context);
            List<PreviewSelection.Range> matches = new ArrayList<>();
            // The shared matcher searches one logical line, just as it does in
            // Source. A CharBuffer exposes a bounded view without copying the
            // projection or letting wildcard stars consume line separators.
            for (int start = 0; start < text.length() && matches.size() < SEARCH_MATCH_LIMIT;) {
                int end = text.indexOf('\n', start);
                if (end < 0) end = text.length();
                for (var match : matcher.findAll(CharBuffer.wrap(text, start, end),
                    SEARCH_MATCH_LIMIT - matches.size(), context)) {
                    matches.add(new PreviewSelection.Range(start + match.startUtf16(), start + match.endUtf16()));
                }
                start = end + 1;
            }
            searchMatches = List.copyOf(matches);
            activeMatch = activeMatch >= matches.size() ? -1 : activeMatch;
        } catch (SearchLimitException limit) {
            // A failed search never turns into an incomplete match count or a
            // layout error. Query, accepted document, selection and viewport
            // remain available; the failure is cached until a search input changes.
            searchMatches = List.of(); activeMatch = -1;
            searchFailure = new SearchFailure(limit.summary(), limit.getMessage());
        }
    }
    record SearchFailure(String summary, String message) {
        static final SearchFailure NONE = new SearchFailure("", "");
    }
    private void revealCaret() {
        var position = selection.visualPosition(result);
        applyViewport(TextSurfaceInteraction.reveal(viewport(), new TextSurfaceInteraction.Visual(position.row(), position.column()),
            result.lines().size(), result.extent(), 0, true, true));
    }
    private void keepCaretInViewport() {
        if (result.lines().isEmpty()) return;
        var caret = selection.visualPosition(result);
        var visible = TextSurfaceInteraction.keepVisible(new TextSurfaceInteraction.Visual(caret.row(), caret.column()), viewport());
        if (visible.row() != caret.row() || visible.column() != caret.column())
            selection.restoreVisual(result, visible.row(), visible.column());
    }
    private void captureAnchor() {
        if (result == null || result.lines().isEmpty()) return;
        int row = Math.max(0, Math.min(top, result.lines().size() - 1));
        var source = result.lines().get(row).source();
        if (source != null) anchorSourceLine = source.start().line();
        viewportAnchor = PreviewSelection.offsetAt(result, row, 0);
        viewportAnchorDelta = row - PreviewSelection.position(result, viewportAnchor).row();
        var caret = selection.visualPosition(result);
        caretAnchor = selection.targetOffset(result);
        caretAnchorDelta = caret.row() - PreviewSelection.position(result, caretAnchor).row();
        caretColumn = caret.column();
    }
    static final String SEARCH_PREFIX = "Find: ";
    TextField.Placement searchPlacement() {
        int prefix = Math.min(CellText.width(SEARCH_PREFIX), Math.max(0, bounds.width() - 1));
        return searchField.layout(new Rect(bounds.x() + prefix, bounds.y(),
            Math.max(0, bounds.width() - prefix), Math.min(1, bounds.height())));
    }
    Placement placement(Placement.Focus focus) {
        return new Placement(bounds, ready() ? result : null, top, left, pending != null, error,
            selection.snapshot(), searchMatches, activeMatch, searchQuery, searchVisible,
            ready() ? selection.visualPosition(result) : null, searchVisible ? searchPlacement() : null,
            ready() ? geometry : null, focus, searchFailure);
    }
    record Placement(Rect bounds, MarkdownLayout.Result result, int top, int left, boolean loading, String error,
                     PreviewSelection.Snapshot selection, List<PreviewSelection.Range> searchMatches,
                     int activeMatch, String searchQuery, boolean searchVisible,
                     PreviewSelection.VisualPosition caret, TextField.Placement searchInput, MarkdownGeometry geometry,
                     Focus focus, SearchFailure searchFailure) {
        enum Focus { QUERY, CONTENT, NONE }
        Placement {
            searchMatches = List.copyOf(searchMatches);
            Objects.requireNonNull(focus); Objects.requireNonNull(searchFailure);
            if (searchVisible != (searchInput != null))
                throw new IllegalArgumentException("A visible Find row requires its real field placement");
            if (focus == Focus.QUERY && !searchVisible)
                throw new IllegalArgumentException("A hidden Find field cannot own the cursor");
        }
        Placement withFocus(Focus next) {
            return new Placement(bounds, result, top, left, loading, error, selection, searchMatches, activeMatch,
                searchQuery, searchVisible, caret, searchInput, geometry, next, searchFailure);
        }
        boolean queryFocused() { return focus == Focus.QUERY; }
        boolean contentFocused() { return focus == Focus.CONTENT; }
        PreviewScrollbars scrollbars() { return PreviewScrollbars.from(bounds, result, searchVisible, top, left); }
        Rect contentBounds() { return scrollbars().layout().contentBounds(); }
    }
    private void cancel() {
        cancelPointerInteraction();
        lifecycleGeneration++;
        coordinator.cancel();
        if (pending instanceof MarkdownPreviewService.Task task) task.close();
        else if (pending != null) pending.cancel(true);
        if (pendingRetention != null) { pendingRetention.close(); pendingRetention = null; }
        pendingEnvelope = null;
        pending = null;
        request = null;
        requestedRevision = -1;
    }
    private void releaseResult() {
        cancelPointerInteraction();
        selection.releaseIndex();
        if (geometry != null) { geometry.close(); geometry = null; }
        result = null;
        if (resultRetention != null) { resultRetention.close(); resultRetention = null; }
    }
    @Override public void close() {
        if (closed) return;
        cancel(); coordinator.close(); releaseResult(); selection.releaseIndex(); searchMatches = List.of(); searchField.clear();
        if (acceptedRetention != null) acceptedRetention.close();
        if (creationRetention != null) creationRetention.close();
        acceptedRetention = null; creationRetention = null; acceptedEnvelope = null; creationEnvelope = null;
        active = false; closed = true;
    }
}

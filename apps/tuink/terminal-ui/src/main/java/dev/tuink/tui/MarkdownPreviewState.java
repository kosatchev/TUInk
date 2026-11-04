package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.markdown.preview.PreviewCoordinator;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.widget.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.FutureTask;

/** View-local mode, request lifetime and viewport. Never shared with an editor cursor. */
final class MarkdownPreviewState implements AutoCloseable {
    static final int SEARCH_QUERY_MAX_UTF16 = 4096;
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
    boolean searchEditing;
    List<PreviewSelection.Range> searchMatches = List.of();
    int activeMatch = -1;
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
    private boolean pointerSelecting;
    private Scrollbar.Drag scrollDrag;

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
                    ? PreviewSelection.position(result, viewportAnchor).row() + viewportAnchorDelta
                    : result.rowForSource(anchorSourceLine);
                if (preserve) {
                    selection.bindGeometry(geometry);
                    selection.reconcile(result.document().projection().text());
                    if (caretAnchor >= 0) selection.restoreVisual(result,
                        PreviewSelection.position(result, caretAnchor).row() + caretAnchorDelta, caretColumn);
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
        var content = contentBounds();
        scrollTo(ScrollInteraction.scrollBy(top, rows, content.height(), result.lines().size()),
            ScrollInteraction.scrollBy(left, columns, content.width(), result.extent()));
    }
    void clamp() {
        if (result == null) { top = 0; left = 0; return; }
        var content = contentBounds();
        top = ScrollInteraction.clampOffset(top, content.height(), result.lines().size());
        left = ScrollInteraction.clampOffset(left, content.width(), result.extent());
    }
    PreviewScrollbars scrollbars() { return PreviewScrollbars.from(bounds, result, searchEditing, top, left); }
    Rect contentBounds() { return scrollbars().layout().contentBounds(); }
    boolean pointerCaptureActive() { return pointerSelecting || scrollDrag != null; }
    void cancelPointerInteraction() { pointerSelecting = false; scrollDrag = null; }

    private void scrollTo(int nextTop, int nextLeft) {
        int oldTop = top, oldLeft = left;
        top = nextTop; left = nextLeft; clamp();
        selection.pan(result, top - oldTop, left - oldLeft);
        keepCaretInViewport(); activeMatch = -1;
    }

    /** Scrollbar hit testing and painting consume the same immutable placement. */
    boolean pointer(PointerEvent event) {
        boolean capturing = pointerCaptureActive();
        if (event.kind() == PointerEvent.Kind.RELEASE) {
            cancelPointerInteraction();
            return capturing || bounds.contains(event.column(), event.row());
        }
        if (!ready()) return bounds.contains(event.column(), event.row());
        boolean drag = event.button() == PointerEvent.Button.LEFT
            && (event.kind() == PointerEvent.Kind.DRAG || event.kind() == PointerEvent.Kind.MOVE);
        if (!bounds.contains(event.column(), event.row()) && !(drag && capturing)) {
            if (event.kind() == PointerEvent.Kind.PRESS) cancelPointerInteraction();
            return false;
        }
        switch (event.kind()) {
            case SCROLL_UP -> scroll(-3, 0);
            case SCROLL_DOWN -> scroll(3, 0);
            case SCROLL_LEFT -> scroll(0, -3);
            case SCROLL_RIGHT -> scroll(0, 3);
            case PRESS -> {
                cancelPointerInteraction();
                if (event.button() != PointerEvent.Button.LEFT) return true;
                var placement = scrollbars();
                Point point = new Point(event.column(), event.row());
                for (Scrollbar bar : new Scrollbar[] { placement.vertical(), placement.horizontal() }) {
                    if (bar == null) continue;
                    var press = bar.press(point);
                    if (press.isPresent()) {
                        if (bar.axis() == Scrollbar.Axis.VERTICAL) scrollTo(press.get().offset(), left);
                        else scrollTo(top, press.get().offset());
                        scrollDrag = press.get().drag();
                        return true;
                    }
                }
                if (placement.layout().contentBounds().contains(point)) {
                    pointerSelecting = true;
                    pointerAt(event.row(), event.column(), event.modifiers().contains(dev.tuiframe.input.KeyStroke.Modifier.SHIFT));
                }
            }
            case DRAG, MOVE -> {
                if (drag && scrollDrag != null) {
                    var placement = scrollbars();
                    var bar = scrollDrag.axis() == Scrollbar.Axis.VERTICAL ? placement.vertical() : placement.horizontal();
                    if (bar == null) cancelPointerInteraction();
                    else if (scrollDrag.axis() == Scrollbar.Axis.VERTICAL)
                        scrollTo(bar.drag(new Point(event.column(), event.row()), scrollDrag), left);
                    else scrollTo(top, bar.drag(new Point(event.column(), event.row()), scrollDrag));
                } else if (drag && pointerSelecting) pointerAt(event.row(), event.column(), true);
            }
            default -> { }
        }
        return true;
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
        int row = Math.max(0, Math.min(result.lines().size() - 1, top + windowRow - content.y()));
        int visual = Math.max(0, windowColumn - content.x() + left);
        selection.selectAtColumn(result, row, visual, extend); activeMatch = -1; revealCaret();
    }
    void moveSelection(PreviewSelection.Move move, boolean extend) {
        if (!ready() || result.lines().isEmpty()) return;
        int page = Math.max(1, contentBounds().height() - 1);
        selection.move(result, move, extend, page);
        if (move == PreviewSelection.Move.PAGE_UP || move == PreviewSelection.Move.PAGE_DOWN) {
            top = Math.max(0, top + (move == PreviewSelection.Move.PAGE_UP ? -page : page));
            clamp(); keepCaretInViewport();
        } else revealCaret();
        activeMatch = -1;
    }
    void beginSearch() {
        cancelPointerInteraction(); searchEditing = true; searchField.selectAll();
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
        if (!ready() || searchQuery.isEmpty()) { searchMatches = List.of(); activeMatch = -1; return; }
        String text = result.document().projection().text();
        List<PreviewSelection.Range> matches = new ArrayList<>();
        for (int from = 0; matches.size() < 10_000 && from <= text.length() - searchQuery.length();) {
            int match = text.indexOf(searchQuery, from);
            if (match < 0) break;
            matches.add(new PreviewSelection.Range(match, match + searchQuery.length()));
            from = match + Math.max(1, searchQuery.length());
        }
        searchMatches = List.copyOf(matches);
        activeMatch = activeMatch >= matches.size() ? -1 : activeMatch;
    }
    private void revealCaret() {
        var position = selection.visualPosition(result);
        var content = contentBounds();
        int rows = Math.max(1, content.height()), columns = Math.max(1, content.width());
        if (position.row() < top) top = position.row();
        else if (position.row() >= top + rows) top = position.row() - rows + 1;
        int column = position.column();
        if (column < left) left = column;
        else if (column >= left + columns) left = column - columns + 1;
        clamp();
    }
    private void keepCaretInViewport() {
        if (result.lines().isEmpty()) return;
        var caret = selection.visualPosition(result);
        var content = contentBounds();
        int row = Math.max(top, Math.min(caret.row(), top + Math.max(1, content.height()) - 1));
        int column = Math.max(left, Math.min(caret.column(), left + Math.max(1, content.width()) - 1));
        if (row != caret.row() || column != caret.column()) selection.restoreVisual(result, row, column);
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
    Placement placement() {
        return new Placement(bounds, ready() ? result : null, top, left, pending != null, error,
            selection.snapshot(), searchMatches, activeMatch, searchQuery, searchEditing,
            ready() ? selection.visualPosition(result) : null, searchEditing ? searchPlacement() : null,
            ready() ? geometry : null);
    }
    record Placement(Rect bounds, MarkdownLayout.Result result, int top, int left, boolean loading, String error,
                     PreviewSelection.Snapshot selection, List<PreviewSelection.Range> searchMatches,
                     int activeMatch, String searchQuery, boolean searchEditing,
                     PreviewSelection.VisualPosition caret, TextField.Placement searchInput, MarkdownGeometry geometry) {
        Placement { searchMatches = List.copyOf(searchMatches); }
        PreviewScrollbars scrollbars() { return PreviewScrollbars.from(bounds, result, searchEditing, top, left); }
        Rect contentBounds() { return scrollbars().layout().contentBounds(); }
        Placement(Rect bounds, MarkdownLayout.Result result, int top, int left, boolean loading, String error,
                  PreviewSelection.Snapshot selection, List<PreviewSelection.Range> searchMatches,
                  int activeMatch, String searchQuery, boolean searchEditing,
                  PreviewSelection.VisualPosition caret, TextField.Placement searchInput) {
            this(bounds, result, top, left, loading, error, selection, searchMatches, activeMatch,
                searchQuery, searchEditing, caret, searchInput, null);
        }
        Placement(Rect bounds, MarkdownLayout.Result result, int top, int left, boolean loading, String error,
                  PreviewSelection.Snapshot selection, List<PreviewSelection.Range> searchMatches,
                  int activeMatch, String searchQuery, boolean searchEditing) {
            this(bounds, result, top, left, loading, error, selection, searchMatches, activeMatch,
                searchQuery, searchEditing, null, null);
        }
        Placement(Rect bounds, MarkdownLayout.Result result, int top, int left, boolean loading, String error) {
            this(bounds, result, top, left, loading, error, PreviewSelection.Snapshot.EMPTY, List.of(), -1, "", false);
        }
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

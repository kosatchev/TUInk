package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;

/**
 * Compatibility facade over one document state and one independent view state.
 */
public class EditorState {
    public static final String LINE_SEPARATOR_LF = "\n";
    public static final String LINE_SEPARATOR_CRLF = "\r\n";
    public static final String LINE_SEPARATOR_CR = "\r";

    private final EditorDocumentState document;
    private final EditorViewState view;

    public EditorState() {
        this(new EditorDocumentState(), new EditorViewState());
    }

    EditorState(EditorDocumentState document, EditorViewState view) {
        this.document = java.util.Objects.requireNonNull(document, "document");
        this.view = java.util.Objects.requireNonNull(view, "view");
    }

    EditorDocumentState documentState() {
        return document;
    }

    public long viewId() {
        return view.id;
    }

    boolean detachedView() { return view.detached; }
    void detachView() { view.detached = true; }

    public EditorViewMemento viewMemento() {
        return new EditorViewMemento(view.id, view.cursor, viewport(), view.selectionAnchor);
    }

    void invalidateNavigation() {
        view.navigationRevision++;
    }

    long navigationRevision() {
        return view.navigationRevision;
    }

    /** Restores a rejected speculative command without allocating or advancing navigation. */
    void restoreEditState(EditorViewMemento before, long navigation) {
        view.cursor = before.cursor();
        view.selectionAnchor = before.selectionAnchor();
        ViewportState viewport = before.viewport();
        view.topLine = viewport.topLine();
        view.topWrapSegment = viewport.topWrapSegment();
        view.leftColumn = viewport.leftColumn();
        view.leftVisualColumn = viewport.leftVisualColumn();
        view.navigationRevision = navigation;
    }

    public Position getCursor() {
        return view.cursor;
    }

    /**
     * Core-internal mutation hook. Production callers outside {@code dev.tuink.core}
     * must use {@link EditorEngine} so movement/coalescing state stays coherent.
     */
    public void setCursor(Position cursor) {
        view.cursor = cursor;
    }

    public int getTopLine() {
        return view.topLine;
    }

    public void setTopLine(int topLine) {
        view.topLine = Math.max(0, topLine);
        view.topWrapSegment = 0;
    }

    public int getTopWrapSegment() {
        return view.topWrapSegment;
    }

    public void setTopWrapSegment(int topWrapSegment) {
        view.topWrapSegment = Math.max(0, topWrapSegment);
    }

    public int getLeftColumn() {
        return view.leftColumn;
    }

    public void setLeftColumn(int leftColumn) {
        view.leftColumn = Math.max(0, leftColumn);
    }

    public int getLeftVisualColumn() {
        return view.leftVisualColumn;
    }

    public void setLeftVisualColumn(int leftVisualColumn) {
        view.leftVisualColumn = Math.max(0, leftVisualColumn);
    }

    public ViewportState viewport() {
        return new ViewportState(view.topLine, view.topWrapSegment, view.leftColumn, view.leftVisualColumn);
    }

    public void setViewport(ViewportState viewport) {
        if (viewport == null) {
            return;
        }
        setTopLine(viewport.topLine());
        setTopWrapSegment(viewport.topWrapSegment());
        setLeftColumn(viewport.leftColumn());
        setLeftVisualColumn(viewport.leftVisualColumn());
    }

    public boolean isDirty() {
        return document.dirty;
    }

    public void setDirty(boolean dirty) {
        document.dirty = dirty;
    }

    public DocumentResource getCurrentResource() {
        return document.currentResource;
    }

    public void setCurrentResource(DocumentResource currentResource) {
        document.currentResource = currentResource;
    }

    /** Resource commit intent retained until this document successfully owns its path. */
    public SaveDisposition getSaveDisposition() {
        return document.saveDisposition;
    }

    public void setSaveDisposition(SaveDisposition saveDisposition) {
        document.saveDisposition = saveDisposition == null
            ? SaveDisposition.CREATE_NEW
            : saveDisposition;
    }

    public Charset getFileEncoding() {
        return document.fileEncoding;
    }

    public void setFileEncoding(Charset fileEncoding) {
        document.fileEncoding = fileEncoding == null ? StandardCharsets.UTF_8 : fileEncoding;
    }

    public String getLineSeparator() {
        return document.lineSeparator;
    }

    public void setLineSeparator(String lineSeparator) {
        if (LINE_SEPARATOR_CRLF.equals(lineSeparator)) {
            document.lineSeparator = LINE_SEPARATOR_CRLF;
            return;
        }
        if (LINE_SEPARATOR_CR.equals(lineSeparator)) {
            document.lineSeparator = LINE_SEPARATOR_CR;
            return;
        }
        document.lineSeparator = LINE_SEPARATOR_LF;
    }

    public Position getSelectionAnchor() {
        return view.selectionAnchor;
    }

    /** Core-internal mutation hook; outer layers route selection through {@link EditorEngine}. */
    public void setSelectionAnchor(Position selectionAnchor) {
        view.selectionAnchor = selectionAnchor;
    }

    /** Core-internal mutation hook; outer layers route selection through {@link EditorEngine}. */
    public void clearSelection() {
        view.selectionAnchor = null;
    }

    public boolean hasSelection() {
        return view.selectionAnchor != null && !view.selectionAnchor.equals(view.cursor);
    }
}

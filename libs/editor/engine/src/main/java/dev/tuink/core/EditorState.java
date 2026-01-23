package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Mutable editor state independent from terminal specifics.
 */
public class EditorState {
    private Position cursor = new Position(0, 0);
    private int topLine;
    private int leftColumn;
    private int leftVisualColumn;
    private boolean dirty;
    private Path currentFile;
    private Charset fileEncoding = StandardCharsets.UTF_8;
    private Position selectionAnchor;

    public Position getCursor() {
        return cursor;
    }

    public void setCursor(Position cursor) {
        this.cursor = cursor;
    }

    public int getTopLine() {
        return topLine;
    }

    public void setTopLine(int topLine) {
        this.topLine = Math.max(0, topLine);
    }

    public int getLeftColumn() {
        return leftColumn;
    }

    public void setLeftColumn(int leftColumn) {
        this.leftColumn = Math.max(0, leftColumn);
    }

    public int getLeftVisualColumn() {
        return leftVisualColumn;
    }

    public void setLeftVisualColumn(int leftVisualColumn) {
        this.leftVisualColumn = Math.max(0, leftVisualColumn);
    }

    public ViewportState viewport() {
        return new ViewportState(topLine, leftColumn, leftVisualColumn);
    }

    public void setViewport(ViewportState viewport) {
        if (viewport == null) {
            return;
        }
        setTopLine(viewport.topLine());
        setLeftColumn(viewport.leftColumn());
        setLeftVisualColumn(viewport.leftVisualColumn());
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public Path getCurrentFile() {
        return currentFile;
    }

    public void setCurrentFile(Path currentFile) {
        this.currentFile = currentFile;
    }

    public Charset getFileEncoding() {
        return fileEncoding;
    }

    public void setFileEncoding(Charset fileEncoding) {
        this.fileEncoding = fileEncoding == null ? StandardCharsets.UTF_8 : fileEncoding;
    }

    public Position getSelectionAnchor() {
        return selectionAnchor;
    }

    public void setSelectionAnchor(Position selectionAnchor) {
        this.selectionAnchor = selectionAnchor;
    }

    public void clearSelection() {
        this.selectionAnchor = null;
    }

    public boolean hasSelection() {
        return selectionAnchor != null && !selectionAnchor.equals(cursor);
    }
}

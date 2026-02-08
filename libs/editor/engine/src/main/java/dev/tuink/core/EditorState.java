package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Mutable editor state independent from terminal specifics.
 */
public class EditorState {
    public static final String LINE_SEPARATOR_LF = "\n";
    public static final String LINE_SEPARATOR_CRLF = "\r\n";
    public static final String LINE_SEPARATOR_CR = "\r";

    private Position cursor = new Position(0, 0);
    private int topLine;
    private int topWrapSegment;
    private int leftColumn;
    private int leftVisualColumn;
    private boolean dirty;
    private Path currentFile;
    private Charset fileEncoding = StandardCharsets.UTF_8;
    private String lineSeparator = LINE_SEPARATOR_LF;
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
        this.topWrapSegment = 0;
    }

    public int getTopWrapSegment() {
        return topWrapSegment;
    }

    public void setTopWrapSegment(int topWrapSegment) {
        this.topWrapSegment = Math.max(0, topWrapSegment);
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
        return new ViewportState(topLine, topWrapSegment, leftColumn, leftVisualColumn);
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

    public String getLineSeparator() {
        return lineSeparator;
    }

    public void setLineSeparator(String lineSeparator) {
        if (LINE_SEPARATOR_CRLF.equals(lineSeparator)) {
            this.lineSeparator = LINE_SEPARATOR_CRLF;
            return;
        }
        if (LINE_SEPARATOR_CR.equals(lineSeparator)) {
            this.lineSeparator = LINE_SEPARATOR_CR;
            return;
        }
        this.lineSeparator = LINE_SEPARATOR_LF;
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

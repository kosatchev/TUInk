package dev.tuink.core;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Simple list-based text storage.
 */
public class SimpleLineBuffer implements TextBuffer {
    private LineText[] lines = new LineText[10];
    private int size = 1;

    public SimpleLineBuffer() {
        lines[0] = LineText.of("");
    }

    @Override
    public TextBuffer createEmptySibling() {
        return new SimpleLineBuffer();
    }

    @Override
    public int lineCount() {
        return size;
    }

    @Override
    public String getLine(int line) {
        return lines[Objects.checkIndex(line, size)].toString();
    }

    @Override public LineText lineText(int line) {
        return supportsPreparedPublication() ? lines[Objects.checkIndex(line, size)] : LineText.of(getLine(line));
    }

    @Override
    public void setLine(int line, String text) {
        lines[Objects.checkIndex(line, size)] = LineText.of(text);
    }

    @Override public void setLineText(int line, LineText text) {
        if (!supportsPreparedPublication()) setLine(line, text.toString());
        else lines[Objects.checkIndex(line, size)] = Objects.requireNonNull(text, "text");
    }

    @Override
    public void insertLine(int index, String text) {
        insertPreparedLine(index, LineText.of(text));
    }

    @Override public void insertLineText(int index, LineText text) {
        if (!supportsPreparedPublication()) insertLine(index, text.toString());
        else insertPreparedLine(index, text);
    }

    private void insertPreparedLine(int index, LineText text) {
        Objects.checkFromToIndex(index, index, size);
        reserveLines(Math.addExact(size, 1));
        System.arraycopy(lines, index, lines, index + 1, size - index);
        lines[index] = text;
        size++;
    }

    @Override
    public void insertLines(int index, List<String> text) {
        Objects.checkFromToIndex(index, index, size);
        LineText[] inserted = text.stream().map(LineText::of).toArray(LineText[]::new);
        reserveLines(Math.addExact(size, inserted.length));
        replacePreparedLines(index, 0, inserted);
    }

    @Override public void insertLineTexts(int index, List<LineText> text) {
        if (!supportsPreparedPublication()) {
            insertLines(index, text.stream().map(LineText::toString).toList());
            return;
        }
        Objects.checkFromToIndex(index, index, size);
        LineText[] inserted = text.toArray(LineText[]::new);
        reserveLines(Math.addExact(size, inserted.length));
        replacePreparedLines(index, 0, inserted);
    }

    @Override
    public String removeLine(int index) {
        String removed = getLine(index);
        System.arraycopy(lines, index + 1, lines, index, size - index - 1);
        lines[--size] = null;
        return removed;
    }

    @Override
    public void removeLines(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, size);
        int remaining = size - toIndex;
        System.arraycopy(lines, toIndex, lines, fromIndex, remaining);
        int nextSize = size - (toIndex - fromIndex);
        Arrays.fill(lines, nextSize, size, null);
        size = nextSize;
    }

    @Override
    public void clear() {
        Arrays.fill(lines, 0, size, null);
        lines[0] = LineText.of("");
        size = 1;
    }

    /** Reserves a local edit's peak capacity before any document text is published. */
    protected void reserveLines(int required) {
        if (required > Integer.MAX_VALUE - 8) throw new OutOfMemoryError("Too many document lines");
        if (required > lines.length) {
            lines = Arrays.copyOf(lines, (int) additionalCapacity(required));
        }
    }

    long additionalCapacity(int required) {
        return required <= lines.length ? 0
            : Math.min(Integer.MAX_VALUE - 8L, Math.max(required, lines.length + (long) lines.length / 2));
    }

    /**
     * Subclasses with their own storage or mutation hooks use an independent sibling.
     * A subclass may opt in only when publishing the inherited storage directly is safe.
     */
    protected boolean supportsPreparedPublication() { return getClass() == SimpleLineBuffer.class; }

    /** All ranges, strings and capacity were checked during preparation; this cannot allocate. */
    final void replacePreparedLines(int index, int removed, LineText[] inserted) {
        int nextSize = size - removed + inserted.length;
        System.arraycopy(lines, index + removed, lines, index + inserted.length, size - index - removed);
        System.arraycopy(inserted, 0, lines, index, inserted.length);
        if (nextSize < size) Arrays.fill(lines, nextSize, size, null);
        size = nextSize;
    }
}

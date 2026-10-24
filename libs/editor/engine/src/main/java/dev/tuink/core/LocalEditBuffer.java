package dev.tuink.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** A command's small overlay of line edits; the source remains untouched until publication. */
final class LocalEditBuffer implements TextBuffer {
    private static final LineText[] EMPTY = new LineText[0];
    private final TextBuffer source;
    private final List<LineEdit> edits = new ArrayList<>();
    private int size;
    private int peakSize;

    LocalEditBuffer(TextBuffer source) {
        this.source = source;
        size = source.lineCount();
        peakSize = size;
    }

    @Override public TextBuffer createEmptySibling() { return source.createEmptySibling(); }
    @Override public int lineCount() { return size; }

    /**
     * Captures all references in one pass over the source followed by edit replay.
     * A snapshot must not resolve every untouched line through the complete edit
     * log: a coalesced history entry can contain thousands of same-line edits.
     */
    List<LineText> snapshotTextLines() {
        List<LineText> lines = new ArrayList<>(peakSize);
        for (int line = 0; line < source.lineCount(); line++) lines.add(source.lineText(line));
        for (LineEdit edit : edits) {
            if (edit.removed() == edit.inserted().length) {
                for (int offset = 0; offset < edit.removed(); offset++) {
                    lines.set(edit.index() + offset, edit.inserted()[offset]);
                }
            } else {
                lines.subList(edit.index(), edit.index() + edit.removed()).clear();
                lines.addAll(edit.index(), Arrays.asList(edit.inserted()));
            }
        }
        return List.copyOf(lines);
    }

    @Override public String getLine(int line) {
        return lineText(line).toString();
    }

    @Override public LineText lineText(int line) {
        Objects.checkIndex(line, size);
        int sourceLine = line;
        for (int index = edits.size() - 1; index >= 0; index--) {
            LineEdit edit = edits.get(index);
            if (sourceLine < edit.index()) continue;
            if (sourceLine < edit.index() + edit.inserted().length) {
                return edit.inserted()[sourceLine - edit.index()];
            }
            sourceLine += edit.removed() - edit.inserted().length;
        }
        return source.lineText(sourceLine);
    }

    @Override public void setLine(int line, String text) {
        setLineText(line, LineText.of(text));
    }

    @Override public void setLineText(int line, LineText text) {
        Objects.checkIndex(line, size);
        replace(line, 1, new LineText[] {text});
    }

    @Override public void insertLine(int index, String text) {
        insertLineText(index, LineText.of(text));
    }

    @Override public void insertLineText(int index, LineText text) { replace(index, 0, new LineText[] {text}); }

    @Override public void insertLines(int index, List<String> text) {
        replace(index, 0, text.stream().map(LineText::of).toArray(LineText[]::new));
    }

    @Override public void insertLineTexts(int index, List<LineText> text) {
        replace(index, 0, text.toArray(LineText[]::new));
    }

    @Override public String removeLine(int index) {
        String removed = getLine(index);
        replace(index, 1, EMPTY);
        return removed;
    }

    @Override public void removeLines(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, size);
        replace(fromIndex, toIndex - fromIndex, EMPTY);
    }

    @Override public void clear() { replace(0, size, new LineText[] {LineText.of("")}); }

    private void replace(int index, int removed, LineText[] inserted) {
        Objects.checkFromIndexSize(index, removed, size);
        int resultSize = Math.addExact(size - removed, inserted.length);
        edits.add(new LineEdit(index, removed, inserted));
        size = resultSize;
        peakSize = Math.max(peakSize, size);
    }

    long additionalCapacity(int resultSize) {
        return source instanceof SimpleLineBuffer simple ? simple.additionalCapacity(resultSize) : resultSize;
    }

    boolean supportsLocalPublication() {
        return source instanceof SimpleLineBuffer simple && simple.supportsPreparedPublication();
    }

    TextBuffer preparePublication() {
        if (edits.isEmpty()) return source;
        if (supportsLocalPublication()) {
            SimpleLineBuffer simple = (SimpleLineBuffer) source;
            simple.reserveLines(peakSize);
            return source;
        }
        TextBuffer prepared = Objects.requireNonNull(source.createEmptySibling(), "buffer.createEmptySibling()");
        if (prepared == source || prepared.lineCount() != 1 || !prepared.getLine(0).isEmpty()) {
            throw new IllegalArgumentException("createEmptySibling() must return an independent empty buffer");
        }
        List<LineText> lines = snapshotTextLines();
        prepared.setLineText(0, lines.get(0));
        for (int line = 1; line < lines.size(); line++) prepared.insertLineText(line, lines.get(line));
        return prepared;
    }

    void publishTo(TextBuffer prepared) {
        if (prepared == source && !edits.isEmpty()) {
            SimpleLineBuffer simple = (SimpleLineBuffer) source;
            for (int index = 0; index < edits.size(); index++) {
                LineEdit edit = edits.get(index);
                simple.replacePreparedLines(edit.index(), edit.removed(), edit.inserted());
            }
        }
    }

    private record LineEdit(int index, int removed, LineText[] inserted) {}
}

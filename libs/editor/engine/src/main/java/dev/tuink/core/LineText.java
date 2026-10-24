package dev.tuink.core;

import dev.tuink.editor.document.ImmutableText;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Core compatibility facade over the shared immutable UTF-16 text value. */
public final class LineText implements CharSequence {
    static final int BLOCK_SIZE = ImmutableText.BLOCK_SIZE;
    private static final Map<ImmutableText, LineText> SHORT_TEXTS = new IdentityHashMap<>();
    private final ImmutableText text;
    private final Edit edit;

    private LineText(ImmutableText text) {
        this.text = text;
        ImmutableText.Edit source = text.edit();
        this.edit = source == null ? null : new Edit(source.sourceIdentity(), source.start(),
            source.removedLength(), source.insertedLength(), source.copiedCodeUnits(), source.allocatedNodes());
    }

    public static LineText of(CharSequence source) {
        Objects.requireNonNull(source, "source");
        if (source instanceof LineText text) return text;
        ImmutableText value = ImmutableText.of(source);
        if (value.length() > 256) return new LineText(value);
        synchronized (SHORT_TEXTS) {
            LineText cached = SHORT_TEXTS.get(value);
            if (cached != null) return cached;
            LineText result = new LineText(value);
            if (SHORT_TEXTS.size() == 256) SHORT_TEXTS.clear();
            SHORT_TEXTS.put(value, result);
            return result;
        }
    }

    /** A safely shareable value; it exposes no live document or mutable buffer. */
    public ImmutableText immutableText() { return text; }
    public long identity() { return text.identity(); }
    public Edit edit() { return edit; }
    public boolean simpleAscii() { return text.simpleAscii(); }
    String cachedString() { return text.cachedString(); }
    @Override public int length() { return text.length(); }
    @Override public boolean isEmpty() { return text.length() == 0; }
    @Override public char charAt(int index) { return text.charAt(index); }
    public int codePointAt(int index) { return text.codePointAt(index); }
    public int codePointBefore(int index) { return text.codePointBefore(index); }
    public int codePointCount(int start, int end) { return text.codePointCount(start, end); }
    public int offsetByCodePoints(int offset, int count) { return text.offsetByCodePoints(offset, count); }

    @Override public LineText subSequence(int start, int end) {
        ImmutableText value = text.subSequence(start, end);
        return value == text ? this : new LineText(value);
    }

    public LineText replace(int start, int end, CharSequence inserted) {
        try {
            ImmutableText value = text.replace(start, end,
                inserted instanceof LineText line ? line.text : inserted);
            return value == text ? this : new LineText(value);
        } catch (ImmutableText.CapacityExceededException tooLarge) {
            throw new DocumentEditLimitException();
        }
    }

    public String sliceString(int start, int end) { return text.sliceString(start, end); }
    public String substring(int start, int end) { return text.substring(start, end); }
    public String substring(int start) { return text.substring(start); }
    public void getChars(int start, int end, char[] target, int targetStart) {
        text.getChars(start, end, target, targetStart);
    }
    public int indexOf(char character, int from) { return text.indexOf(character, from); }
    public int indexOf(char character) { return text.indexOf(character); }
    public boolean startsWith(String prefix, int from) { return text.startsWith(prefix, from); }
    public boolean startsWith(String prefix) { return text.startsWith(prefix); }
    public boolean isBlank() { return text.isBlank(); }
    public boolean contentEquals(CharSequence other) {
        return text.contentEquals(other instanceof LineText line ? line.text : other);
    }
    @Override public boolean equals(Object other) {
        return other instanceof LineText line && text.equals(line.text);
    }
    @Override public int hashCode() { return text.hashCode(); }
    @Override public String toString() { return text.toString(); }

    public record Edit(long sourceIdentity, int start, int removedLength, int insertedLength,
                       long copiedCodeUnits, int allocatedNodes) {}
    public record Work(long copiedCodeUnits, long materializedCodeUnits, long allocatedNodes) {}
    public static Work work() {
        ImmutableText.Work work = ImmutableText.work();
        return new Work(work.copiedCodeUnits(), work.materializedCodeUnits(), work.allocatedNodes());
    }
    int treeHeight() { return text.treeHeight(); }
    int retainedNodes() { return text.retainedNodes(); }
}

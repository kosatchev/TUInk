package dev.tuink.editor.diff;

/** Half-open changed line ranges in the previous and current text. */
public record LineEdit(int beforeStart, int beforeEnd, int afterStart, int afterEnd) {
    public enum Type { EMPTY, INSERT, DELETE, REPLACE }

    public LineEdit {
        if (beforeStart < 0 || beforeEnd < beforeStart || afterStart < 0 || afterEnd < afterStart) {
            throw new IllegalArgumentException("Diff ranges must be non-negative and ordered");
        }
    }

    public Type type() {
        if (beforeStart == beforeEnd) return afterStart == afterEnd ? Type.EMPTY : Type.INSERT;
        return afterStart == afterEnd ? Type.DELETE : Type.REPLACE;
    }
}

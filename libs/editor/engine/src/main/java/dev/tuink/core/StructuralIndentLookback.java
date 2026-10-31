package dev.tuink.core;

import java.util.Objects;
import java.util.function.IntFunction;

/** Whole-line reverse window for structural typing assists, separate from lexical scanning. */
final class StructuralIndentLookback {
    static final int MAX_LINES = 256;
    static final int MAX_CODE_UNITS = 16_384;

    private final IntFunction<? extends CharSequence> lines;
    private final int firstLine;
    private int lineIndex;
    private int remaining = MAX_CODE_UNITS;
    private CharSequence text;

    StructuralIndentLookback(int beforeLine, IntFunction<? extends CharSequence> lines) {
        this.lines = Objects.requireNonNull(lines, "lines");
        firstLine = Math.max(0, beforeLine - MAX_LINES);
        lineIndex = beforeLine;
    }

    /** Charges a complete previous line before admitting it, without inspecting its contents. */
    boolean previous() {
        text = null;
        if (lineIndex <= firstLine) {
            return false;
        }
        CharSequence candidate = lines.apply(lineIndex - 1);
        int length = candidate.length();
        if (length > remaining) {
            lineIndex = firstLine;
            return false;
        }
        remaining -= length;
        lineIndex--;
        text = candidate;
        return true;
    }

    /** Index of the line admitted by the latest successful {@link #previous()}. */
    int lineIndex() {
        return lineIndex;
    }

    /** Original source, available only after {@link #previous()} returns true. */
    CharSequence text() {
        return text;
    }
}

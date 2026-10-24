package dev.tuink.core;

/** Logical-LF comparison over caller text without allocating a normalized copy. */
final class ReplacementText {
    private final String text;
    private final int lineBreaks;
    private final int firstEnd;
    private final int lastStart;
    private final long interiorUnits;
    private final int longestInteriorLine;

    ReplacementText(String text) {
        this.text = text == null ? "" : text;
        LogicalDocumentLines.requireWellFormedUtf16(this.text);
        int breaks = 0;
        int first = this.text.length();
        int start = 0;
        long interior = 0;
        int longest = 0;
        for (int index = 0; index < this.text.length(); index++) {
            char character = this.text.charAt(index);
            if (character != '\r' && character != '\n') continue;
            if (breaks == 0) first = index;
            else {
                interior += index - start;
                longest = Math.max(longest, index - start);
            }
            breaks++;
            if (character == '\r' && index + 1 < this.text.length() && this.text.charAt(index + 1) == '\n') index++;
            start = index + 1;
        }
        lineBreaks = breaks;
        firstEnd = first;
        lastStart = start;
        interiorUnits = interior;
        longestInteriorLine = longest;
    }

    String text() {
        return text;
    }

    int lineBreaks() { return lineBreaks; }
    int firstEnd() { return firstEnd; }
    int lastStart() { return lastStart; }
    long interiorUnits() { return interiorUnits; }
    int longestInteriorLine() { return longestInteriorLine; }

    boolean matches(CharSequence source, int start, int end) {
        if (lineBreaks != 0 || end - start != text.length()) return false;
        for (int index = 0; index < text.length(); index++) if (source.charAt(start + index) != text.charAt(index)) return false;
        return true;
    }

    boolean matches(TextModel model, Position start, Position end) {
        if (start.line() == end.line()) return matches(model.lineText(start.line()), start.col(), end.col());
        if (lineBreaks == 0) return false;
        int line = start.line();
        int column = start.col();
        CharSequence source = model.lineText(line);
        for (int index = 0; index < text.length(); index++) {
            char inserted = text.charAt(index);
            if (inserted == '\r') {
                inserted = '\n';
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
            }
            if (line == end.line() && column >= end.col()) return false;
            char original;
            if (column == source.length() && line < end.line()) {
                original = '\n';
                source = model.lineText(++line);
                column = 0;
            } else {
                original = source.charAt(column++);
            }
            if (original != inserted) return false;
        }
        return line == end.line() && column == end.col();
    }
}

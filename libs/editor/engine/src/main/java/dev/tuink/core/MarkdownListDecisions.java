package dev.tuink.core;

import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;

/** Decides Markdown list changes from text and indentation columns without applying edits. */
final class MarkdownListDecisions {
    private static final int ASSIST_LOOKBACK_LINES = 256;
    private static final int ASSIST_LOOKBACK_CHARS = 16_384;
    private final IntFunction<String> lines;
    private final IntBinaryOperator visualColumn;

    MarkdownListDecisions(IntFunction<String> lines, IntBinaryOperator visualColumn) {
        this.lines = lines;
        this.visualColumn = visualColumn;
    }

    /** Replaces an empty marker on its current line, preserving the indentation. */
    TextReplacement termination(Position cursor) {
        if (!canTerminate(cursor)) {
            return null;
        }
        String line = lines.apply(cursor.line());
        return new TextReplacement(
            new Position(cursor.line(), leadingIndentLength(line)),
            new Position(cursor.line(), line.length()),
            ""
        );
    }

    String continuation(int lineIndex, String beforeCursor) {
        if (isInsideMarkdownFence(lineIndex)) {
            return "";
        }
        int leading = leadingIndentLength(beforeCursor);
        if (leading >= beforeCursor.length()) {
            return "";
        }
        String rest = beforeCursor.substring(leading);

        String unordered = markdownUnorderedContinuation(rest);
        if (!unordered.isEmpty()) {
            return unordered;
        }
        return markdownOrderedContinuation(rest);
    }

    private boolean canTerminate(Position cursor) {
        String line = lines.apply(cursor.line());
        int markerEnd = markdownListMarkerEnd(line);
        if (markerEnd < 0 || cursor.col() < markerEnd || !line.substring(markerEnd).isBlank()
            || isInsideMarkdownFence(cursor.line())) {
            return false;
        }
        int indent = leadingIndentLength(line);
        if (visualColumn.applyAsInt(cursor.line(), indent) < 4) {
            return true;
        }
        // Four-space/tab indentation can instead be an indented code block.
        // Only terminate a deeply indented item when a surrounding list is visible.
        int indentColumn = visualColumn.applyAsInt(cursor.line(), indent);
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        for (int previous = cursor.line() - 1; previous >= Math.max(0, cursor.line() - ASSIST_LOOKBACK_LINES); previous--) {
            String text = lines.apply(previous);
            remainingChars -= text.length();
            if (remainingChars < 0 || text.isBlank() || fenceMarker(text.stripLeading()) != null) {
                return false;
            }
            int previousIndent = leadingIndentLength(text);
            if (previousIndent < indent) {
                int contentStart = markdownListPrefixEnd(text);
                if (contentStart < 0) {
                    return false;
                }
                int firstContentColumn = visualColumn.applyAsInt(previous, contentStart);
                while (contentStart < text.length() && (text.charAt(contentStart) == ' ' || text.charAt(contentStart) == '\t')) {
                    contentStart++;
                }
                int contentColumn = visualColumn.applyAsInt(previous, contentStart);
                if (contentColumn - firstContentColumn >= 4 || indentColumn >= contentColumn + 4) {
                    return false;
                }
                if (visualColumn.applyAsInt(previous, previousIndent) < 4) {
                    return true;
                }
                indent = previousIndent;
                indentColumn = visualColumn.applyAsInt(previous, previousIndent);
            }
        }
        return false;
    }

    private static int markdownListMarkerEnd(String line) {
        int markerEnd = markdownListPrefixEnd(line);
        if (markerEnd < 0) {
            return -1;
        }
        int end = markerEnd;
        while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
            end++;
        }
        if (end + 3 <= line.length() && line.charAt(end) == '[' && line.charAt(end + 2) == ']'
            && (line.charAt(end + 1) == ' ' || line.charAt(end + 1) == 'x' || line.charAt(end + 1) == 'X')
            && (end + 3 == line.length() || line.charAt(end + 3) == ' ' || line.charAt(end + 3) == '\t')) {
            return end + 3;
        }
        return markerEnd;
    }

    private static int markdownListPrefixEnd(String line) {
        int start = leadingIndentLength(line);
        if (start >= line.length()) {
            return -1;
        }
        int end = start + 1;
        char marker = line.charAt(start);
        if (marker != '-' && marker != '*' && marker != '+') {
            end = start;
            while (end < line.length() && line.charAt(end) >= '0' && line.charAt(end) <= '9') {
                end++;
            }
            if (end == start || end - start > 9 || end >= line.length()
                || (line.charAt(end) != '.' && line.charAt(end) != ')')) {
                return -1;
            }
            end++;
        }
        if (end >= line.length() || (line.charAt(end) != ' ' && line.charAt(end) != '\t')) {
            return -1;
        }
        return end + 1;
    }

    private String markdownUnorderedContinuation(String rest) {
        if (rest.startsWith("- [ ] ") || rest.startsWith("- [x] ") || rest.startsWith("- [X] ")) {
            String tail = rest.substring(6).trim();
            return tail.isEmpty() ? "" : "- [ ] ";
        }
        if (rest.length() < 2) {
            return "";
        }
        char marker = rest.charAt(0);
        if (marker != '-' && marker != '*' && marker != '+') {
            return "";
        }
        if (rest.charAt(1) != ' ') {
            return "";
        }
        String tail = rest.substring(2).trim();
        return tail.isEmpty() ? "" : marker + " ";
    }

    private String markdownOrderedContinuation(String rest) {
        int sep = -1;
        char delimiter = '.';
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '.' || ch == ')') {
                sep = i;
                delimiter = ch;
                break;
            }
            if (!Character.isDigit(ch)) {
                return "";
            }
        }
        if (sep <= 0 || sep >= rest.length() - 1) {
            return "";
        }
        for (int i = 0; i < sep; i++) {
            if (!Character.isDigit(rest.charAt(i))) {
                return "";
            }
        }
        if (rest.charAt(sep + 1) != ' ') {
            return "";
        }
        String tail = rest.substring(sep + 2).trim();
        if (tail.isEmpty()) {
            return "";
        }
        try {
            int number = Integer.parseInt(rest.substring(0, sep));
            return Math.max(1, number + 1) + String.valueOf(delimiter) + " ";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private boolean isInsideMarkdownFence(int lineIndex) {
        String activeFence = null;
        for (int i = 0; i <= lineIndex; i++) {
            String line = lines.apply(i);
            String fence = fenceMarker(line);
            if (fence == null) {
                continue;
            }
            if (activeFence == null) {
                activeFence = fence;
                continue;
            }
            if (activeFence.charAt(0) == fence.charAt(0) && fence.length() >= activeFence.length()
                && line.stripLeading().substring(fence.length()).isBlank()) {
                activeFence = null;
            }
        }
        return activeFence != null;
    }

    private static String fenceMarker(String line) {
        if (line == null || line.length() < 3) {
            return null;
        }
        int start = 0;
        while (start < line.length() && start < 3 && line.charAt(start) == ' ') {
            start++;
        }
        if (start >= line.length()) {
            return null;
        }
        char ch = line.charAt(start);
        if (ch != '`' && ch != '~') {
            return null;
        }
        int count = 1;
        while (start + count < line.length() && line.charAt(start + count) == ch) {
            count++;
        }
        if (count < 3) {
            return null;
        }
        return String.valueOf(ch).repeat(count);
    }

    private static int leadingIndentLength(String line) {
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            i++;
        }
        return i;
    }

}

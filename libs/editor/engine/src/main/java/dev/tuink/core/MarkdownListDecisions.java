package dev.tuink.core;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/** Proposes Markdown list edits using read-only text and block context. */
final class MarkdownListDecisions {
    private final IntFunction<String> lines;
    private final IntPredicate allowsListAssist;

    MarkdownListDecisions(IntFunction<String> lines, IntPredicate allowsListAssist) {
        this.lines = lines;
        this.allowsListAssist = allowsListAssist;
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
        int leading = leadingIndentLength(beforeCursor);
        if (leading >= beforeCursor.length()) {
            return "";
        }
        String rest = beforeCursor.substring(leading);

        String continuation = markdownContinuation(rest);
        return !continuation.isEmpty() && allowsListAssist.test(lineIndex) ? continuation : "";
    }

    private boolean canTerminate(Position cursor) {
        String line = lines.apply(cursor.line());
        int markerEnd = markdownListMarkerEnd(line);
        return markerEnd >= 0 && cursor.col() >= markerEnd && line.substring(markerEnd).isBlank()
            && allowsListAssist.test(cursor.line());
    }

    private static int markdownListMarkerEnd(String line) {
        int markerEnd = markdownListPrefixEnd(line);
        if (markerEnd < 0) {
            return -1;
        }
        int end = skipIndent(line, markerEnd);
        int taskEnd = markdownTaskEnd(line, end);
        return taskEnd >= 0 ? taskEnd : markerEnd;
    }

    private static int markdownTaskEnd(String line, int start) {
        int end = start + 3;
        if (end <= line.length() && line.charAt(start) == '[' && line.charAt(start + 2) == ']'
            && (line.charAt(start + 1) == ' ' || line.charAt(start + 1) == 'x' || line.charAt(start + 1) == 'X')
            && (end == line.length() || line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
            return end;
        }
        return -1;
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

    private static String markdownContinuation(String rest) {
        int prefixEnd = markdownListPrefixEnd(rest);
        if (prefixEnd < 0) {
            return "";
        }
        // The validated prefix includes the first required padding character.
        int markerEnd = prefixEnd - 1;
        int contentStart = skipIndent(rest, prefixEnd);
        int taskEnd = markdownTaskEnd(rest, contentStart);
        int textStart = taskEnd < 0 ? contentStart : skipIndent(rest, taskEnd);
        if (rest.substring(textStart).isBlank()) {
            return "";
        }

        String marker = rest.substring(0, markerEnd);
        char first = marker.charAt(0);
        if (first >= '0' && first <= '9') {
            int digitCount = marker.length() - 1;
            int number = Integer.parseInt(marker.substring(0, digitCount));
            // CommonMark permits at most nine digits in an ordered marker.
            if (number == 999_999_999) {
                return "";
            }
            String next = Integer.toString(number + 1);
            marker = "0".repeat(Math.max(0, digitCount - next.length())) + next + marker.charAt(digitCount);
        }
        String continuation = marker + rest.substring(markerEnd, contentStart);
        return taskEnd < 0 ? continuation : continuation + "[ ]" + rest.substring(taskEnd, textStart);
    }

    private static int leadingIndentLength(String line) {
        return skipIndent(line, 0);
    }

    private static int skipIndent(String line, int start) {
        int i = start;
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

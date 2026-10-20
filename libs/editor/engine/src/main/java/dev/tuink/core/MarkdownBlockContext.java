package dev.tuink.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Incremental block context for list assists. A bounded set of checkpoints and the
 * last queried line retain parser state; intervening lines are streamed, not cached.
 * Checkpoints describe the state before their line, so editing that line preserves
 * its incoming state. The engine supplies revision changes and read-only text.
 */
final class MarkdownBlockContext {
    private static final int CHECKPOINT_INTERVAL = 256;
    private static final int MAX_CHECKPOINTS = 128;
    private final IntFunction<String> lines;
    private final Map<Integer, State> checkpoints = new LinkedHashMap<>();
    private int cursorLine = -1;
    private State cursorBefore;
    private LineContext cursorContext;
    private long contentEpoch = Long.MIN_VALUE;
    private long revision = -1;
    private int lineCount;

    MarkdownBlockContext(IntFunction<String> lines) {
        this.lines = Objects.requireNonNull(lines, "lines");
    }

    long revision() {
        return revision;
    }

    void reset() {
        checkpoints.clear();
        clearCursor();
        contentEpoch = Long.MIN_VALUE;
        revision = -1;
    }

    /** Invalidates the changed suffix, retaining the lexical state before it. */
    void synchronize(long nextEpoch, long nextRevision, int firstChangedLine, int nextLineCount) {
        if (nextLineCount < 1 || firstChangedLine < 0) {
            throw new IllegalArgumentException("Invalid Markdown document bounds");
        }
        if (contentEpoch != nextEpoch || nextRevision < revision) {
            checkpoints.clear();
            clearCursor();
        } else if (revision != nextRevision) {
            invalidateFrom(firstChangedLine);
        }
        invalidateFrom(nextLineCount);
        contentEpoch = nextEpoch;
        revision = nextRevision;
        lineCount = nextLineCount;
    }

    boolean allowsListAssist(int line) {
        if (line < 0 || line >= lineCount) {
            throw new IndexOutOfBoundsException("Markdown line: " + line);
        }
        if (cursorLine == line && cursorContext != null) {
            return cursorContext.allowsListAssist();
        }

        int next = 0;
        State before = State.EMPTY;
        for (Map.Entry<Integer, State> checkpoint : checkpoints.entrySet()) {
            if (checkpoint.getKey() <= line && checkpoint.getKey() >= next) {
                next = checkpoint.getKey();
                before = checkpoint.getValue();
            }
        }
        if (cursorLine >= next && cursorLine <= line) {
            next = cursorLine;
            before = cursorBefore;
            if (cursorContext != null && next < line) {
                before = cursorContext.after();
                next++;
            }
        }
        while (true) {
            if (next % CHECKPOINT_INTERVAL == 0) {
                remember(next, before);
            }
            LineContext context = parse(lines.apply(next), before);
            if (next == line) {
                cursorLine = line;
                cursorBefore = before;
                cursorContext = context;
                return context.allowsListAssist();
            }
            before = context.after();
            next++;
        }
    }

    private void remember(int line, State before) {
        checkpoints.put(line, before);
        if (checkpoints.size() > MAX_CHECKPOINTS) {
            checkpoints.remove(checkpoints.keySet().iterator().next());
        }
    }

    private void invalidateFrom(int firstChangedLine) {
        checkpoints.keySet().removeIf(line -> line > firstChangedLine);
        if (cursorLine > firstChangedLine) {
            clearCursor();
        } else if (cursorLine == firstChangedLine) {
            cursorContext = null;
        }
    }

    private void clearCursor() {
        cursorLine = -1;
        cursorBefore = null;
        cursorContext = null;
    }

    private LineContext parse(String text, State before) {
        int start = skipIndent(text, 0);
        if (start == text.length()) {
            // Blank lines may occur inside both loose lists and fenced code.
            return new LineContext(before, false);
        }
        int column = indentColumn(text, 0, start, 0);
        Container container = before.container();
        while (container != null && column < container.contentColumn()) {
            container = container.parent();
        }
        Fence fence = before.fence();
        if (fence != null && fence.container() == container) {
            int base = contentColumn(container);
            FenceMarker marker = fenceMarker(text, start);
            boolean closing = column - base < 4 && marker != null
                && marker.character() == fence.character() && marker.length() >= fence.length()
                && text.substring(marker.end()).isBlank();
            return new LineContext(new State(container, closing ? null : fence), false);
        }
        // A dedent out of a list also ends the fence belonging to that list item.
        if (column - contentColumn(container) >= 4) {
            return new LineContext(new State(container, null), false);
        }

        boolean listItem = false;
        ThematicBreak thematicBreak = thematicBreakSuffix(text);
        while (true) {
            if (thematicBreak != null && thematicBreak.contains(start)) {
                return new LineContext(new State(container, null), listItem);
            }
            FenceMarker marker = fenceMarker(text, start);
            if (marker != null && (marker.character() != '`'
                || text.indexOf('`', marker.end()) < 0)) {
                Fence opened = new Fence(marker.character(), marker.length(), container);
                return new LineContext(new State(container, opened), false);
            }
            int markerEnd = listMarkerEnd(text, start);
            if (markerEnd < 0) {
                return new LineContext(new State(container, null), listItem);
            }
            int contentStart = skipIndent(text, markerEnd);
            int markerColumn = column + markerEnd - start;
            int contentColumn = indentColumn(text, markerEnd, contentStart, markerColumn);
            // More than four columns after a marker starts indented code inside
            // the item; only the first padding character belongs to the marker.
            boolean code = contentColumn - markerColumn > 4;
            int itemColumn = code || contentStart == text.length()
                ? indentColumn(text, markerEnd, markerEnd + 1, markerColumn) : contentColumn;
            container = new Container(container, itemColumn);
            if (code && contentStart < text.length()) {
                return new LineContext(new State(container, null), false);
            }
            listItem = true;
            if (contentStart == text.length()) {
                return new LineContext(new State(container, null), true);
            }
            // A fence or another marker may start directly in an item's content.
            start = contentStart;
            column = contentColumn;
        }
    }

    private static int contentColumn(Container container) {
        return container == null ? 0 : container.contentColumn();
    }

    private static int skipIndent(String text, int from) {
        int end = from;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return end;
    }

    /** Markdown grammar uses four-column tab stops independently of a view's display width. */
    private static int indentColumn(String text, int from, int to, int column) {
        for (int offset = from; offset < to; offset++) {
            column = text.charAt(offset) == '\t' ? column + 4 - column % 4 : column + 1;
        }
        return column;
    }

    private static int listMarkerEnd(String text, int start) {
        int end = start;
        char first = text.charAt(start);
        if (first == '-' || first == '*' || first == '+') {
            end++;
        } else {
            while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
                end++;
            }
            if (end == start || end - start > 9 || end == text.length()
                || (text.charAt(end) != '.' && text.charAt(end) != ')')) {
                return -1;
            }
            end++;
        }
        return end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t') ? end : -1;
    }

    /** Precompute once so nested marker-like text cannot repeatedly rescan the same suffix. */
    private static ThematicBreak thematicBreakSuffix(String text) {
        int end = text.length() - 1;
        while (end >= 0 && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end--;
        }
        if (end < 0) {
            return null;
        }
        char marker = text.charAt(end);
        if (marker != '-' && marker != '*' && marker != '_') {
            return null;
        }
        int count = 0;
        int thirdFromEnd = -1;
        int first = end;
        for (; first >= 0; first--) {
            char character = text.charAt(first);
            if (character == marker) {
                if (++count == 3) {
                    thirdFromEnd = first;
                }
            } else if (character != ' ' && character != '\t') {
                break;
            }
        }
        return thirdFromEnd < 0 ? null : new ThematicBreak(first + 1, thirdFromEnd);
    }

    private static FenceMarker fenceMarker(String text, int start) {
        char character = text.charAt(start);
        if (character != '`' && character != '~') {
            return null;
        }
        int end = start + 1;
        while (end < text.length() && text.charAt(end) == character) {
            end++;
        }
        return end - start >= 3 ? new FenceMarker(character, end - start, end) : null;
    }

    private record Container(Container parent, int contentColumn) {}

    private record Fence(char character, int length, Container container) {}

    private record FenceMarker(char character, int length, int end) {}

    private record State(Container container, Fence fence) {
        private static final State EMPTY = new State(null, null);
    }

    private record LineContext(State after, boolean allowsListAssist) {}

    private record ThematicBreak(int first, int last) {
        private boolean contains(int start) {
            return start >= first && start <= last;
        }
    }
}

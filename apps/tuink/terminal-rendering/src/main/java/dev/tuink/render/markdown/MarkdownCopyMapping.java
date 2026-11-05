package dev.tuink.render.markdown;

import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.TextProjection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Independent copy cursors: hidden wrap whitespace must not change caret/search run mapping. */
final class MarkdownCopyMapping {
    record Range(int start, int end) {}
    private static final Range NONE = new Range(-1, -1);
    private final Map<TextRange, Group> groups = new HashMap<>();

    MarkdownCopyMapping(TextProjection projection) {
        for (TextProjection.Mapping mapping : projection.mappings()) {
            groups.computeIfAbsent(mapping.source(), unused -> new Group()).cursors.add(
                new Cursor(mapping.start(), projection.text().substring(mapping.start(), mapping.end())));
        }
    }

    Range match(TextRange source, String text, boolean atomic) {
        Group group = groups.get(source);
        if (group == null || text.isEmpty()) return NONE;
        for (int index = group.next; index < group.cursors.size(); index++) {
            Cursor cursor = group.cursors.get(index);
            if (cursor.consumed == cursor.text.length()) {
                if (index == group.next) group.next++;
                continue;
            }
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Copy mapping cancelled");
            int start = cursor.text.indexOf(text, cursor.consumed), end;
            if (start >= 0) end = start + text.length();
            else if (text.isBlank()) {
                start = cursor.consumed;
                while (start < cursor.text.length() && !Character.isWhitespace(cursor.text.charAt(start))) start++;
                if (start == cursor.text.length()) continue;
                end = start + 1;
                while (end < cursor.text.length() && Character.isWhitespace(cursor.text.charAt(end))) end++;
            } else continue;
            cursor.consumed = end;
            if (end == cursor.text.length() && index == group.next) group.next++;
            return atomic ? new Range(cursor.start, cursor.start + cursor.text.length())
                : new Range(cursor.start + start, cursor.start + end);
        }
        return NONE;
    }

    private static final class Group {
        final List<Cursor> cursors = new ArrayList<>();
        int next;
    }
    private static final class Cursor {
        final int start;
        final String text;
        int consumed;
        Cursor(int start, String text) { this.start = start; this.text = text; }
    }
}

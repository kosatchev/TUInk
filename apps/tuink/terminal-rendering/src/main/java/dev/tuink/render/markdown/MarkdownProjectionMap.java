package dev.tuink.render.markdown;

import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Associates terminal runs with stable semantic offsets, independent of resize geometry. */
public final class MarkdownProjectionMap {
    private MarkdownProjectionMap() {}

    public static List<MarkdownLayout.Line> annotate(MarkdownDocument document, List<MarkdownLayout.Line> lines) {
        Map<TextRange, Group> groups = new HashMap<>();
        TextProjection projection = document.projection();
        for (TextProjection.Mapping mapping : projection.mappings()) {
            groups.computeIfAbsent(mapping.source(), unused -> new Group()).cursors.add(
                new Cursor(mapping, projection.text().substring(mapping.start(), mapping.end())));
        }
        List<MarkdownLayout.Line> result = new ArrayList<>(lines.size());
        for (MarkdownLayout.Line line : lines) {
            check();
            List<MarkdownLayout.Run> runs = new ArrayList<>(line.runs().size());
            int semanticHint = -1;
            for (MarkdownLayout.Run run : line.runs()) {
                int start = Math.min(line.text().length(), run.start());
                int end = Math.min(line.text().length(), run.end());
                if (start == end) continue;
                Group group = run.source() == null ? null : groups.get(run.source());
                Match match = null;
                if (group != null && run.role() != MarkdownLayout.Role.TABLE && run.role() != MarkdownLayout.Role.QUOTE
                    && run.role() != MarkdownLayout.Role.DIAGNOSTIC) {
                    if (run.role() == MarkdownLayout.Role.MATH || run.role() == MarkdownLayout.Role.DIAGRAM) {
                        // A displayed expression/diagram is atomic; every row points to its original semantic value.
                        TextProjection.Mapping mapping = group.cursors.get(0).mapping;
                        match = new Match(mapping.start(), mapping.end());
                    } else {
                        String value = line.text().substring(start, end);
                        if (line.kind().equals("code") || line.kind().equals("raw")) {
                            // The drawing run may include a quote/list prefix which is absent from copyText.
                            if (line.copyText().isEmpty()) value = "";
                            else if (value.endsWith(line.copyText())) {
                                start = end - line.copyText().length(); value = line.copyText();
                            }
                        }
                        if (!value.isEmpty()) match = group.match(value, semanticHint);
                    }
                }
                if (match != null) semanticHint = match.end;
                runs.add(new MarkdownLayout.Run(start, end, run.role(), run.source(),
                    match == null ? -1 : match.start, match == null ? -1 : match.end));
            }
            result.add(new MarkdownLayout.Line(line.text(), runs, line.copyText(), line.source(), line.kind()));
        }
        return List.copyOf(result);
    }

    private record Match(int start, int end) {}
    private static final class Cursor {
        final TextProjection.Mapping mapping;
        final String value;
        int consumed;
        Cursor(TextProjection.Mapping mapping, String value) { this.mapping = mapping; this.value = value; }
        Match match(String visible, int hint) {
            int from = Math.max(consumed, Math.max(0, hint - mapping.start()));
            int start = value.indexOf(visible, from);
            int end;
            if (start >= 0) end = start + visible.length();
            else if (visible.isBlank()) {
                start = from;
                while (start < value.length() && !Character.isWhitespace(value.charAt(start))) start++;
                if (start == value.length()) return null;
                end = start + 1;
                while (end < value.length() && Character.isWhitespace(value.charAt(end))) end++;
            } else return null;
            consumed = end;
            return new Match(mapping.start() + start, mapping.start() + end);
        }
    }
    private static final class Group {
        final List<Cursor> cursors = new ArrayList<>();
        int next;
        Match match(String value, int hint) {
            for (int index = next; index < cursors.size(); index++) {
                Cursor cursor = cursors.get(index);
                if (cursor.mapping.end() <= hint) cursor.consumed = cursor.value.length();
                if (cursor.consumed == cursor.value.length()) { if (index == next) next++; continue; }
                Match result = cursor.match(value, hint);
                if (result != null) {
                    if (cursor.consumed == cursor.value.length() && index == next) next++;
                    return result;
                }
            }
            // Some inline boxes expand tabs or otherwise use coarse spans. Snap these to the source-backed atom.
            if (cursors.size() == 1) {
                Cursor cursor = cursors.get(0);
                if (!cursor.mapping.exact()) return new Match(cursor.mapping.start(), cursor.mapping.end());
            }
            return null;
        }
    }
    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Projection mapping cancelled");
    }
}

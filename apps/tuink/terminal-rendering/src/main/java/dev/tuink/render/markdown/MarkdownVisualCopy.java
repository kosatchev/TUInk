package dev.tuink.render.markdown;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.render.PreparedRowPainter;
import dev.tuiframe.text.TerminalText;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownInline;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Bounded plain-text export of an already prepared Markdown layout, independent of its viewport. */
public final class MarkdownVisualCopy {
    public static final int DEFAULT_MAX_UTF16 = 8 * 1024 * 1024;
    private static final String SPACES = "                                                                ";
    private MarkdownVisualCopy() {}

    /** Refuses the entire export before publishing any clipboard value. */
    public static final class LimitException extends RuntimeException {
        public LimitException() { super("Rendered Markdown exceeds the clipboard text budget"); }
    }

    public static String copy(MarkdownLayout.Result result, int start, int end) {
        return copy(result, start, end, DEFAULT_MAX_UTF16);
    }
    public static String copy(MarkdownLayout.Result result, int start, int end, int maxUtf16) {
        Objects.requireNonNull(result);
        if (maxUtf16 < 0) throw new IllegalArgumentException("Negative copy budget");
        int length = result.document().projection().text().length();
        start = Math.max(0, Math.min(length, start)); end = Math.max(0, Math.min(length, end));
        if (start >= end) return "";
        if (result.copySections().isEmpty()) throw new IllegalArgumentException("Layout has no visual copy plan");
        return export(result, start, end, start == 0 && end == length, maxUtf16);
    }
    public static String copyAll(MarkdownLayout.Result result) { return copyAll(result, DEFAULT_MAX_UTF16); }
    public static String copyAll(MarkdownLayout.Result result, int maxUtf16) {
        Objects.requireNonNull(result);
        if (maxUtf16 < 0) throw new IllegalArgumentException("Negative copy budget");
        if (result.copySections().isEmpty()) {
            Output out = new Output(maxUtf16);
            appendRows(out, result, 0, result.lines().size());
            return out.toString();
        }
        return export(result, 0, result.document().projection().text().length(), true, maxUtf16);
    }

    private static String export(MarkdownLayout.Result result, int start, int end, boolean all, int maxUtf16) {
        List<MarkdownLayout.CopySection> sections = result.copySections();
        int first = -1, last = -1;
        for (int index = 0; index < sections.size(); index++) {
            check();
            var section = sections.get(index);
            if (all || intersects(start, end, section.projectionStart(), section.projectionEnd())) {
                if (first < 0) first = index;
                last = index;
            }
        }
        if (first < 0) return "";
        Output out = new Output(maxUtf16);
        MarkdownLayout.CopySection previous = null;
        SourceOwnership ownership = null;
        for (int index = first; index <= last; index++) {
            check();
            var section = sections.get(index);
            if (previous != null) {
                out.append('\n');
                for (int row = previous.endRow(); row < section.firstRow(); row++) {
                    appendVisual(out, result.lines().get(row).text(), 0, result.lines().get(row).text().length()); out.append('\n');
                }
            }
            switch (section.mode()) {
                case FLOW -> flow(out, section, start, end, all);
                case ROWS -> rows(out, result, section, start, end, all);
                case TABLE -> {
                    if (!all && ownership == null) ownership = new SourceOwnership(result, start, end);
                    table(out, result, section, start, end, all, ownership);
                }
                case ATOMIC, DECORATION -> appendRows(out, result, section.firstRow(), section.endRow());
            }
            previous = section;
        }
        // A selected explicit hard break is semantic content even when the following line is unselected.
        var tail = sections.get(last);
        if (tail.mode() == MarkdownLayout.CopyMode.FLOW && !tail.pieces().isEmpty()) {
            var piece = tail.pieces().get(tail.pieces().size() - 1);
            if (piece.hardBreak() && (all || intersects(start, end, piece.projectionStart(), piece.projectionEnd()))) out.append('\n');
        }
        return out.toString();
    }

    private record Selected(MarkdownLayout.Box box) {}
    private static void flow(Output out, MarkdownLayout.CopySection section, int start, int end, boolean all) {
        var selected = new ArrayList<Selected>();
        int baseline = 0, below = 0;
        long normalChars = 0;
        boolean whole = all || start <= section.projectionStart() && end >= section.projectionEnd();
        for (var piece : section.pieces()) {
            check();
            if (piece.hardBreak()) continue;
            if (!(whole && piece.projectionStart() < 0)
                && !intersects(start, end, piece.projectionStart(), piece.projectionEnd()) && !all) continue;
            MarkdownLayout.Box box = piece.box();
            if (!piece.atomic()) {
                String text = box.lines().get(0);
                int from = all || piece.projectionStart() < 0 ? 0 : Math.max(0, start - piece.projectionStart());
                int to = all || piece.projectionStart() < 0 ? text.length() : Math.min(text.length(), end - piece.projectionStart());
                from = floor(text, Math.max(0, Math.min(text.length(), from)));
                to = ceil(text, Math.max(from, Math.min(text.length(), to)));
                int remaining = (int)Math.max(0, out.max - out.text.length() - normalChars);
                text = piece.tabColumn() >= 0 ? expandedSlice(text, from, to, piece.tabColumn(), remaining)
                    : piece.normalizeWhitespace() ? normalize(text, from, to, remaining) : boundedSlice(text, from, to, remaining);
                normalChars += text.length();
                box = new MarkdownLayout.Box(List.of(text), 0);
            }
            selected.add(new Selected(box));
            baseline = Math.max(baseline, box.baseline());
            below = Math.max(below, box.lines().size() - box.baseline() - 1);
        }
        int height = baseline + below + 1;
        // Sparse baseline composition is linear in supplied box rows + emitted text, not height * atoms.
        StringBuilder[] rows = new StringBuilder[height];
        int[] cells = new int[height];
        long retained = height - 1L;
        for (int y = 0; y < height; y++) {
            String prefix = y == 0 ? section.firstPrefix() : section.continuationPrefix();
            retained += prefix.length(); out.reserve(retained);
            rows[y] = new StringBuilder(prefix); cells[y] = CellText.width(prefix);
        }
        int x = 0;
        for (Selected piece : selected) {
            check();
            int pieceWidth = 0;
            for (int line = 0; line < piece.box().lines().size(); line++) {
                int y = baseline - piece.box().baseline() + line;
                String text = piece.box().lines().get(line);
                int prefixCells = CellText.width(y == 0 ? section.firstPrefix() : section.continuationPrefix());
                text = expandTabs(text, prefixCells + x, out.max - out.text.length());
                int textCells = CellText.width(text);
                pieceWidth = Math.max(pieceWidth, textCells);
                if (text.isEmpty()) continue;
                int padding = Math.max(0, prefixCells + x - cells[y]);
                retained += padding + (long)text.length(); out.reserve(retained);
                spaces(rows[y], padding); rows[y].append(text);
                cells[y] = prefixCells + x + textCells;
            }
            if ((long)x + pieceWidth > Integer.MAX_VALUE) throw new LimitException();
            x += pieceWidth;
        }
        for (int y = 0; y < height; y++) {
            if (y > 0) out.append('\n');
            // Layout removes trailing box padding; spaces inside the baseline text remain significant.
            int length = rows[y].length();
            if (y != baseline) while (length > 0 && Character.isWhitespace(rows[y].charAt(length - 1))) length--;
            out.append(rows[y], 0, length);
        }
    }

    private static void rows(Output out, MarkdownLayout.Result result, MarkdownLayout.CopySection section,
                             int start, int end, boolean all) {
        if (all || start <= section.projectionStart() && end >= section.projectionEnd()) {
            appendRows(out, result, section.firstRow(), section.endRow()); return;
        }
        int firstBody = section.endRow() - section.pieces().size();
        boolean written = false;
        if (start <= section.projectionStart()) {
            for (int row = section.firstRow(); row < firstBody; row++) {
                if (written) out.append('\n');
                appendVisual(out, result.lines().get(row).text(), 0, result.lines().get(row).text().length()); written = true;
            }
        }
        for (int index = 0; index < section.pieces().size(); index++) {
            check();
            var piece = section.pieces().get(index);
            boolean newline = index + 1 < section.pieces().size()
                && start <= piece.projectionEnd() && end > piece.projectionEnd();
            boolean content = intersects(start, end, piece.projectionStart(), piece.projectionEnd());
            if (!content && !newline) continue;
            String body = piece.box().lines().get(0);
            String text = result.lines().get(firstBody + index).text();
            if (written) out.append('\n');
            int prefix = Math.max(0, text.length() - body.length());
            appendVisual(out, text, 0, prefix);
            if (content) appendVisual(out, text, prefix + floor(body, Math.max(0, start - piece.projectionStart())),
                prefix + ceil(body, Math.min(body.length(), end - piece.projectionStart())));
            written = true;
            if (newline && end == piece.projectionEnd() + 1) { out.append('\n'); written = false; }
        }
    }

    private static void table(Output out, MarkdownLayout.Result result, MarkdownLayout.CopySection section,
                              int start, int end, boolean all, SourceOwnership ownership) {
        if (all || start <= section.projectionStart() && end >= section.projectionEnd()) {
            appendRows(out, result, section.firstRow(), section.endRow()); return;
        }
        int first = -1, last = -1;
        for (int row = section.firstRow(); row < section.endRow(); row++) {
            check();
            for (var run : result.lines().get(row).runs()) if (intersects(start, end, run.projectionStart(), run.projectionEnd())) {
                if (first < 0) first = row; last = row; break;
            }
        }
        if (first < 0) return;
        out.append(result.lines().get(section.firstRow()).text());
        for (int row = first; row <= last; row++) {
            out.append('\n');
            clippedGeometryRow(out, result.lines().get(row), start, end, ownership);
        }
        out.append('\n'); out.append(result.lines().get(section.endRow() - 1).text());
    }
    private static void clippedGeometryRow(Output out, MarkdownLayout.Line line, int start, int end,
                                           SourceOwnership ownership) {
        int position = 0;
        for (var run : line.runs()) {
            check();
            int from = Math.max(position, Math.min(line.text().length(), run.start()));
            int to = Math.min(line.text().length(), run.end());
            if (from >= to) continue;
            out.append(line.text(), position, from);
            if (run.projectionStart() < 0) {
                if (structural(run) || run.role() == MarkdownLayout.Role.QUOTE && ownership.containsSelected(run.source()))
                    out.append(line.text(), from, to);
                else out.spaces(CellText.width(line.text().substring(from, to)));
            } else if (!intersects(start, end, run.projectionStart(), run.projectionEnd())) out.spaces(CellText.width(line.text().substring(from, to)));
            else if (run.role() == MarkdownLayout.Role.MATH || run.role() == MarkdownLayout.Role.DIAGRAM
                || run.projectionEnd() - run.projectionStart() != to - from) out.append(line.text(), from, to);
            else {
                String value = line.text().substring(from, to);
                int left = floor(value, Math.max(0, start - run.projectionStart()));
                int right = ceil(value, Math.min(value.length(), end - run.projectionStart()));
                out.spaces(CellText.width(value.substring(0, left)));
                out.append(value, left, right);
                out.spaces(CellText.width(value.substring(right)));
            }
            position = to;
        }
        out.append(line.text(), position, line.text().length());
    }
    /** Source containment assigns an image's decorative glyphs to that image, never its whole cell. */
    private static final class SourceOwnership {
        final List<TextRange> sources = new ArrayList<>();
        final TextPosition[] minimumEnd;
        SourceOwnership(MarkdownLayout.Result result, int start, int end) {
            for (var mapping : result.document().projection().mappings()) {
                check();
                if (intersects(start, end, mapping.start(), mapping.end())) sources.add(mapping.source());
            }
            sources.sort(Comparator.comparing(TextRange::start));
            minimumEnd = new TextPosition[sources.size()];
            TextPosition minimum = null;
            for (int index = sources.size() - 1; index >= 0; index--) {
                TextPosition next = sources.get(index).end();
                if (minimum == null || next.compareTo(minimum) < 0) minimum = next;
                minimumEnd[index] = minimum;
            }
        }
        boolean containsSelected(TextRange owner) {
            if (owner == null) return false;
            int low = 0, high = sources.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (sources.get(middle).start().compareTo(owner.start()) < 0) low = middle + 1;
                else high = middle;
            }
            // end >= start means a suffix-minimum <= owner.end also satisfies the upper start bound.
            return low < minimumEnd.length && minimumEnd[low].compareTo(owner.end()) <= 0;
        }
    }
    private static boolean structural(MarkdownLayout.Run run) {
        return run.role() == MarkdownLayout.Role.TABLE || run.role() == MarkdownLayout.Role.QUOTE_MARKER;
    }
    private static void appendRows(Output out, MarkdownLayout.Result result, int first, int end) {
        for (int row = first; row < end; row++) {
            check(); if (row > first) out.append('\n');
            String text = result.lines().get(row).text(); appendVisual(out, text, 0, text.length());
        }
    }
    private static String expandedSlice(String text, int from, int to, int column, int max) {
        StringBuilder result = new StringBuilder(Math.min(to - from, max));
        var cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) {
            check();
            int cells = cursor.isTab() ? 4 - column % 4 : Math.max(1, cursor.width());
            if (cursor.start() < to && cursor.end() > from) {
                int chars = cursor.isTab() ? cells : cursor.end() - cursor.start();
                if (chars > max - result.length()) throw new LimitException();
                if (cursor.isTab()) spaces(result, cells);
                else result.append(text, cursor.start(), cursor.end());
            }
            column += cells;
            if (cursor.end() >= to) break;
        }
        return result.toString();
    }
    private static String expandTabs(String text, int column, int max) {
        if (text.indexOf('\t') < 0) return text;
        StringBuilder result = new StringBuilder(Math.min(text.length(), max));
        var cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) {
            check();
            int cells = cursor.isTab() ? 4 - column % 4 : Math.max(1, cursor.width());
            int chars = cursor.isTab() ? cells : cursor.end() - cursor.start();
            if (chars > max - result.length()) throw new LimitException();
            if (cursor.isTab()) spaces(result, cells);
            else result.append(text, cursor.start(), cursor.end());
            column += cells;
        }
        return result.toString();
    }
    /** Tabs share the display's four-cell stops; phase is relative to the complete prepared row. */
    private static void appendVisual(Output out, String text, int start, int end) {
        if (text.indexOf('\t') < 0) { out.append(text, start, end); return; }
        var cursor = TerminalText.graphemeCursor(text);
        int column = 0;
        while (cursor.advance()) {
            check();
            int cells = cursor.isTab() ? 4 - column % 4 : Math.max(1, cursor.width());
            if (cursor.start() < end && cursor.end() > start) {
                if (cursor.isTab()) out.spaces(cells);
                else out.append(text, cursor.start(), cursor.end());
            }
            column += cells;
            if (cursor.end() >= end) break;
        }
    }
    private static boolean intersects(int start, int end, int from, int to) {
        return from >= 0 && start < to && end > from;
    }
    private static String slice(String text, int start, int end) {
        int from = floor(text, Math.max(0, Math.min(text.length(), start)));
        int to = ceil(text, Math.max(from, Math.min(text.length(), end)));
        return text.substring(from, to);
    }
    private static int floor(String text, int offset) {
        if (offset <= 0) return 0;
        var cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) { if (offset < cursor.end()) return cursor.start(); if (offset == cursor.end()) return offset; }
        return text.length();
    }
    private static int ceil(String text, int offset) {
        if (offset <= 0) return 0;
        var cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) if (offset <= cursor.end()) return cursor.end();
        return text.length();
    }
    private static String boundedSlice(String text, int from, int to, int max) {
        if (to - from > max) throw new LimitException();
        return text.substring(from, to);
    }
    private static String normalize(String text, int from, int to, int max) {
        int length = 0;
        boolean whitespace = false, changed = false;
        for (int offset = from; offset < to; offset++) {
            if ((offset & 4095) == 0) check();
            char c = text.charAt(offset);
            boolean next = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 11;
            if (!next || !whitespace) length++;
            changed |= next && (c != ' ' || whitespace);
            if (length > max) throw new LimitException();
            whitespace = next;
        }
        if (!changed) return text.substring(from, to);
        StringBuilder result = new StringBuilder(length);
        whitespace = false;
        for (int offset = from; offset < to; offset++) {
            char c = text.charAt(offset);
            boolean next = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 11;
            if (!next) result.append(c);
            else if (!whitespace) result.append(' ');
            whitespace = next;
        }
        return result.toString();
    }
    private static void spaces(StringBuilder target, int count) {
        while (count > 0) { int next = Math.min(count, SPACES.length()); target.append(SPACES, 0, next); count -= next; }
    }
    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Markdown copy cancelled");
    }
    private static final class Output {
        final StringBuilder text = new StringBuilder();
        final int max;
        Output(int max) { this.max = max; }
        void reserve(long extra) { if (extra < 0 || extra > max - (long)text.length()) throw new LimitException(); }
        void append(char c) { reserve(1); text.append(c); }
        void append(CharSequence value) { append(value, 0, value.length()); }
        void append(CharSequence value, int start, int end) { reserve(end - (long)start); text.append(value, start, end); }
        void spaces(int count) { reserve(count); MarkdownVisualCopy.spaces(text, count); }
        @Override public String toString() {
            // Keep source offsets and grapheme context intact until selection, tab expansion and
            // row composition are complete. Every export path then uses the painter's glyphs;
            // only the line breaks introduced by the copy plan remain literal controls.
            Output displayed = new Output(max);
            int start = 0;
            for (int end = 0; end <= text.length(); end++) {
                if (end != text.length() && text.charAt(end) != '\n') continue;
                check();
                CharSequence row = TerminalText.slice(text, start, end);
                var cursor = TerminalText.graphemeCursor(row);
                int column = 0;
                while (cursor.advance()) {
                    check();
                    int cells = cursor.isTab() ? 4 - column % 4 : Math.max(1, cursor.width());
                    if (cursor.isTab()) displayed.spaces(cells);
                    else displayed.append(PreparedRowPainter.display(row, cursor.start(), cursor.end()));
                    column += cells;
                }
                if (end < text.length()) displayed.append('\n');
                start = end + 1;
            }
            return displayed.text.toString();
        }
    }

    /** Additional retained copy-plan bytes, excluding strings already held by the model or ready lines. */
    public static long retainedBytes(MarkdownLayout.Result result) {
        if (result.copySections().isEmpty()) return 0;
        var strings = new IdentityHashMap<String, Boolean>();
        strings.put(result.document().projection().text(), Boolean.TRUE);
        seedBlocks(strings, result.document().blocks());
        for (var line : result.lines()) { strings.put(line.text(), Boolean.TRUE); strings.put(line.copyText(), Boolean.TRUE); }
        var objects = new IdentityHashMap<Object, Boolean>();
        long bytes = 24L + 8L * result.copySections().size();
        for (var section : result.copySections()) {
            bytes += 96L + 24L + 8L * section.pieces().size();
            bytes += stringBytes(strings, section.firstPrefix()) + stringBytes(strings, section.continuationPrefix());
            for (var piece : section.pieces()) {
                bytes += 48;
                var box = piece.box();
                if (objects.put(box, Boolean.TRUE) == null) {
                    bytes += 48L + 8L * box.lines().size();
                    for (String line : box.lines()) bytes += stringBytes(strings, line);
                }
            }
        }
        return bytes;
    }
    private static long stringBytes(IdentityHashMap<String, Boolean> strings, String value) {
        return strings.put(value, Boolean.TRUE) == null ? 40L + 2L * value.length() : 0;
    }
    private static void seedBlocks(IdentityHashMap<String, Boolean> strings, List<MarkdownBlock> blocks) {
        for (var block : blocks) {
            if (block instanceof MarkdownBlock.Heading n) seedInlines(strings, n.content());
            else if (block instanceof MarkdownBlock.Paragraph n) seedInlines(strings, n.content());
            else if (block instanceof MarkdownBlock.Quote n) seedBlocks(strings, n.blocks());
            else if (block instanceof MarkdownBlock.ListBlock n) for (var item : n.items()) seedBlocks(strings, item.blocks());
            else if (block instanceof MarkdownBlock.Code n) { strings.put(n.text(), Boolean.TRUE); strings.put(n.language(), Boolean.TRUE); }
            else if (block instanceof MarkdownBlock.Math n) strings.put(n.expression(), Boolean.TRUE);
            else if (block instanceof MarkdownBlock.Mermaid n) strings.put(n.code(), Boolean.TRUE);
            else if (block instanceof MarkdownBlock.Raw n) strings.put(n.text(), Boolean.TRUE);
            else if (block instanceof MarkdownBlock.Table n) for (var row : n.rows()) for (var cell : row.cells()) seedInlines(strings, cell.content());
        }
    }
    private static void seedInlines(IdentityHashMap<String, Boolean> strings, List<MarkdownInline> inlines) {
        for (var inline : inlines) {
            if (inline instanceof MarkdownInline.Text n) strings.put(n.text(), Boolean.TRUE);
            else if (inline instanceof MarkdownInline.Code n) strings.put(n.text(), Boolean.TRUE);
            else if (inline instanceof MarkdownInline.Math n) strings.put(n.expression(), Boolean.TRUE);
            else if (inline instanceof MarkdownInline.Styled n) seedInlines(strings, n.content());
            else if (inline instanceof MarkdownInline.Link n) { strings.put(n.destination(), Boolean.TRUE); strings.put(n.title(), Boolean.TRUE); seedInlines(strings, n.content()); }
        }
    }
}

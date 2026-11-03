package dev.tuink.render.markdown;

import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.text.TerminalText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Detached terminal layout; contains no mutable editor or UI state. */
public final class MarkdownLayout {
    public enum Role { TEXT, HEADING, STRONG, EMPHASIS, CODE, LINK, QUOTE, TABLE, MATH, DIAGRAM, DIAGNOSTIC }
    public record Run(int start, int end, Role role, TextRange source, int projectionStart, int projectionEnd) {
        public Run {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid visual run");
            if (!((projectionStart == -1 && projectionEnd == -1) || (projectionStart >= 0 && projectionEnd >= projectionStart)))
                throw new IllegalArgumentException("Invalid semantic run");
        }
        public Run(int start, int end, Role role, TextRange source) { this(start, end, role, source, -1, -1); }
    }
    public record Line(String text, List<Run> runs, String copyText, TextRange source, String kind) {
        public Line { runs = List.copyOf(runs); }
    }
    public record Result(MarkdownDocument document, List<Line> lines, int columns, int extent) {
        public Result { lines = List.copyOf(lines); }
        public int rowForSource(int sourceLine) {
            int nearest = 0;
            for (int i = 0; i < lines.size(); i++) {
                TextRange range = lines.get(i).source();
                if (range == null) continue;
                if (range.start().line() <= sourceLine) nearest = i;
                if (range.start().line() <= sourceLine && range.end().line() >= sourceLine) return i;
            }
            return nearest;
        }
        public int rowForProjectionOffset(int offset) {
            int nearest = 0;
            long distance = Long.MAX_VALUE;
            for (int row = 0; row < lines.size(); row++) {
                for (Run run : lines.get(row).runs()) {
                    if (run.projectionStart() < 0) continue;
                    if (offset >= run.projectionStart() && offset < run.projectionEnd()) return row;
                    long next = Math.min(Math.abs((long)offset - run.projectionStart()), Math.abs((long)offset - run.projectionEnd()));
                    if (next < distance) { distance = next; nearest = row; }
                }
            }
            return nearest;
        }
    }
    public record Box(List<String> lines, int baseline) {
        public Box { lines = List.copyOf(lines); if (lines.isEmpty() || baseline < 0 || baseline >= lines.size()) throw new IllegalArgumentException("Box baseline"); }
    }
    public interface EmbeddedRenderer {
        default Box math(String source) { return new Box(List.of(source), 0); }
        default List<String> mermaid(String source, int width) { return source.lines().toList(); }
    }
    private static final Pattern WORDS = Pattern.compile("\\s+|\\S+");
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_LINE_CELLS = 4096;
    private final int width;
    private final EmbeddedRenderer embedded;
    private final List<Line> lines = new ArrayList<>();
    private int extent;

    public MarkdownLayout(int width) { this(width, new EmbeddedRenderer() {}); }
    public MarkdownLayout(int width, EmbeddedRenderer embedded) {
        this.width = Math.max(1, Math.min(1024, width));
        this.embedded = Objects.requireNonNull(embedded);
    }
    public Result layout(MarkdownDocument document) {
        lines.clear(); extent = 0;
        blocks(document.blocks(), "", 0);
        for (var diagnostic : document.diagnostics()) add("[!] " + diagnostic.message(), Role.DIAGNOSTIC, null, "diagnostic", "");
        return new Result(document, MarkdownProjectionMap.annotate(document, lines), width, extent);
    }
    private void blocks(List<MarkdownBlock> blocks, String prefix, int depth) {
        if (depth > 96) throw new IllegalArgumentException("Markdown layout nesting limit");
        for (MarkdownBlock block : blocks) {
            check();
            if (block instanceof MarkdownBlock.Heading n) paragraph(n.content(), prefix, Role.HEADING, n.source(), "heading");
            else if (block instanceof MarkdownBlock.Paragraph n) paragraph(n.content(), prefix, Role.TEXT, n.source(), "paragraph");
            else if (block instanceof MarkdownBlock.Quote n) blocks(n.blocks(), prefix + "│ ", depth + 1);
            else if (block instanceof MarkdownBlock.ListBlock n) {
                int number = n.start();
                for (var item : n.items()) {
                    String marker = n.ordered() ? (number++) + ". " : "• ";
                    if (item.task()) marker += item.checked() ? "[x] " : "[ ] ";
                    int start = lines.size();
                    blocks(item.blocks(), prefix + " ".repeat(CellText.width(marker)), depth + 1);
                    if (lines.size() > start) {
                        Line first = lines.get(start);
                        String text = prefix + marker + first.text().substring(Math.min(first.text().length(), prefix.length() + CellText.width(marker)));
                        lines.set(start, new Line(text, first.runs(), first.copyText(), first.source(), first.kind()));
                    }
                }
            } else if (block instanceof MarkdownBlock.Code n) {
                if (!n.language().isBlank()) add(prefix + "[" + n.language() + "]", Role.QUOTE, n.source(), "code-label", "");
                for (String line : n.text().split("\\n", -1)) add(prefix + line, Role.CODE, n.source(), "code", line);
            } else if (block instanceof MarkdownBlock.Math n) {
                for (String line : embedded.math(n.expression()).lines()) add(prefix + line, Role.MATH, n.source(), "math", "");
            } else if (block instanceof MarkdownBlock.Mermaid n) {
                for (String line : embedded.mermaid(n.code(), width)) add(prefix + line, Role.DIAGRAM, n.source(), "mermaid", "");
            } else if (block instanceof MarkdownBlock.Table n) {
                for (Line line : MarkdownTableLayout.layout(n, width, prefix, embedded)) add(line);
            } else if (block instanceof MarkdownBlock.ThematicBreak n) add(prefix + "─".repeat(Math.max(1, width - CellText.width(prefix))), Role.QUOTE, n.source(), "rule", "");
            else if (block instanceof MarkdownBlock.Raw n) {
                for (String line : n.text().split("\\n", -1)) add(prefix + line, Role.TEXT, n.source(), "raw", line);
            }
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).text().isBlank()) add("", Role.TEXT, block.source(), "space", "");
        }
    }
    private record Piece(Box box, Role role, TextRange source, String copy) {
        int width() { return box.lines().stream().mapToInt(CellText::width).max().orElse(0); }
    }
    private void paragraph(List<MarkdownInline> content, String prefix, Role role, TextRange source, String kind) {
        List<Piece> pieces = new ArrayList<>(); inlines(content, role, pieces);
        List<Piece> row = new ArrayList<>(); int used = CellText.width(prefix);
        for (Piece piece : pieces) {
            check();
            if (piece.copy().equals("\n")) { flush(row, prefix, source, kind); row.clear(); used = CellText.width(prefix); continue; }
            if (used + piece.width() > width && !row.isEmpty()) { flush(row, prefix, source, kind); row.clear(); used = CellText.width(prefix); }
            if (row.isEmpty() && piece.copy().isBlank()) continue;
            row.add(piece); used += piece.width();
        }
        if (!row.isEmpty()) flush(row, prefix, source, kind);
    }
    private void inlines(List<MarkdownInline> content, Role inherited, List<Piece> pieces) {
        for (MarkdownInline inline : content) {
            check();
            if (inline instanceof MarkdownInline.Text n) words(n.text(), inherited, n.source(), pieces);
            else if (inline instanceof MarkdownInline.Code n) pieces.add(new Piece(new Box(List.of(n.text()), 0), Role.CODE, n.source(), n.text()));
            else if (inline instanceof MarkdownInline.Math n) pieces.add(new Piece(embedded.math(n.expression()), Role.MATH, n.source(), n.expression()));
            else if (inline instanceof MarkdownInline.Break n) pieces.add(new Piece(new Box(List.of(n.hard() ? "" : " "), 0), inherited, n.source(), n.hard() ? "\n" : " "));
            else if (inline instanceof MarkdownInline.Styled n) inlines(n.content(), n.style() == MarkdownInline.Style.STRONG ? Role.STRONG : Role.EMPHASIS, pieces);
            else if (inline instanceof MarkdownInline.Link n) {
                if (n.image()) words("[image: ", Role.QUOTE, n.source(), pieces);
                inlines(n.content(), Role.LINK, pieces);
                if (n.image()) words("] " + n.destination(), Role.QUOTE, n.source(), pieces);
            }
        }
    }
    private void words(String text, Role role, TextRange source, List<Piece> pieces) {
        var matcher = WORDS.matcher(text);
        while (matcher.find()) {
            String word = matcher.group().isBlank() ? " " : matcher.group();
            if (CellText.width(word) <= width) pieces.add(new Piece(new Box(List.of(word), 0), role, source, word));
            else {
                StringBuilder part = new StringBuilder(); int cells = 0;
                var cursor = TerminalText.graphemeCursor(word);
                while (cursor.advance()) {
                    int next = Math.max(1, cursor.width());
                    if (cells + next > width && !part.isEmpty()) { String value = part.toString(); pieces.add(new Piece(new Box(List.of(value), 0), role, source, value)); part.setLength(0); cells = 0; }
                    part.append(word, cursor.start(), cursor.end()); cells += next;
                }
                if (!part.isEmpty()) { String value = part.toString(); pieces.add(new Piece(new Box(List.of(value), 0), role, source, value)); }
            }
        }
    }
    private void flush(List<Piece> pieces, String prefix, TextRange source, String kind) {
        int baseline = pieces.stream().mapToInt(p -> p.box().baseline()).max().orElse(0);
        int below = pieces.stream().mapToInt(p -> p.box().lines().size() - p.box().baseline() - 1).max().orElse(0);
        for (int y = 0; y <= baseline + below; y++) {
            StringBuilder text = new StringBuilder(prefix), copy = new StringBuilder(); List<Run> runs = new ArrayList<>();
            for (Piece piece : pieces) {
                int line = y - baseline + piece.box().baseline();
                String value = line < 0 || line >= piece.box().lines().size() ? "" : piece.box().lines().get(line);
                int start = text.length(); text.append(value).append(" ".repeat(Math.max(0, piece.width() - CellText.width(value))));
                runs.add(new Run(start, text.length(), piece.role(), piece.source()));
                if (y == baseline) copy.append(piece.copy());
            }
            String value = text.toString().stripTrailing();
            add(new Line(value, runs, copy.toString().stripTrailing(), source, kind));
        }
    }
    private void add(String text, Role role, TextRange source, String kind, String copy) {
        add(new Line(text, List.of(new Run(0, text.length(), role, source)), copy, source, kind));
    }
    private void add(Line line) {
        check();
        if (lines.size() >= MAX_ROWS) throw new IllegalArgumentException("Preview exceeds the layout row budget");
        if (CellText.width(line.text()) > MAX_LINE_CELLS)
            throw new IllegalArgumentException("Preview exceeds the layout line width budget");
        lines.add(line); extent = Math.max(extent, CellText.width(line.text()));
    }
    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Preview cancelled");
    }
}

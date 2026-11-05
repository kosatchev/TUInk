package dev.tuink.render.markdown;

import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.highlight.TokenSpan;
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
    public enum Role { TEXT, HEADING, STRONG, EMPHASIS, CODE, CODE_BLOCK, LINK, QUOTE, QUOTE_MARKER, TABLE, MATH, DIAGRAM, DIAGNOSTIC }
    public record Run(int start, int end, Role role, TextRange source, int projectionStart, int projectionEnd) {
        public Run {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid visual run");
            if (!((projectionStart == -1 && projectionEnd == -1) || (projectionStart >= 0 && projectionEnd >= projectionStart)))
                throw new IllegalArgumentException("Invalid semantic run");
        }
        public Run(int start, int end, Role role, TextRange source) { this(start, end, role, source, -1, -1); }
    }
    public record Line(String text, List<Run> runs, String copyText, TextRange source, String kind,
                       List<TokenSpan> syntax) {
        public Line {
            runs = List.copyOf(runs);
            syntax = List.copyOf(syntax);
            int previousEnd = 0;
            for (TokenSpan span : syntax) {
                if (span.start() < previousEnd || span.end() <= span.start() || span.end() > text.length())
                    throw new IllegalArgumentException("Invalid line syntax spans");
                previousEnd = span.end();
            }
        }
        public Line(String text, List<Run> runs, String copyText, TextRange source, String kind) {
            this(text, runs, copyText, source, kind, List.of());
        }
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
    /**
     * Supplies detached token roles for one code block, without selecting a concrete lexer here.
     * Rows address the supplied text in UTF-16 units and contain sorted, non-overlapping spans.
     * Missing rows are unstyled. Providers must bound their work and output and honor interruption.
     */
    @FunctionalInterface
    public interface CodeHighlighter {
        List<List<TokenSpan>> highlight(String language, List<String> lines);
    }
    private static final Pattern WORDS = Pattern.compile("\\s+|\\S+");
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_LINE_CELLS = 4096;
    private static final int MAX_LINE_SYNTAX_SPANS = 1024;
    private static final int MAX_SYNTAX_SPANS = 32_768;
    private final int width;
    private final EmbeddedRenderer embedded;
    private final CodeHighlighter codeHighlighter;
    private final List<Line> lines = new ArrayList<>();
    /** Shared immutable context for drawing prefixes, never inferred from literal text. */
    private record QuotePrefix(int end) {}
    private static final QuotePrefix NO_QUOTE = new QuotePrefix(0);
    private final List<QuotePrefix> quotePrefixes = new ArrayList<>();
    private QuotePrefix quotePrefix = NO_QUOTE;
    private int extent;
    private int syntaxSpans;

    public MarkdownLayout(int width) { this(width, new EmbeddedRenderer() {}); }
    public MarkdownLayout(int width, EmbeddedRenderer embedded) {
        this(width, embedded, (language, lines) -> List.of());
    }
    public MarkdownLayout(int width, EmbeddedRenderer embedded, CodeHighlighter codeHighlighter) {
        this.width = Math.max(1, Math.min(1024, width));
        this.embedded = Objects.requireNonNull(embedded);
        this.codeHighlighter = Objects.requireNonNull(codeHighlighter);
    }
    public Result layout(MarkdownDocument document) {
        lines.clear(); quotePrefixes.clear(); quotePrefix = NO_QUOTE; extent = 0; syntaxSpans = 0;
        blocks(document.blocks(), "", 0);
        for (var diagnostic : document.diagnostics()) add("[!] " + diagnostic.message(), Role.DIAGNOSTIC, null, "diagnostic", "");
        return new Result(document, decorateQuotes(MarkdownProjectionMap.annotate(document, lines)), width, extent);
    }
    private void blocks(List<MarkdownBlock> blocks, String prefix, int depth) {
        if (depth > 96) throw new IllegalArgumentException("Markdown layout nesting limit");
        for (MarkdownBlock block : blocks) {
            check();
            int firstLine = lines.size();
            if (block instanceof MarkdownBlock.Heading n) paragraph(n.content(), prefix, Role.HEADING, n.source(), "heading");
            else if (block instanceof MarkdownBlock.Paragraph n) paragraph(n.content(), prefix, Role.TEXT, n.source(), "paragraph");
            else if (block instanceof MarkdownBlock.Quote n) quote(n, prefix, depth);
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
                        lines.set(start, new Line(text, first.runs(), first.copyText(), first.source(), first.kind(), first.syntax()));
                    }
                }
            } else if (block instanceof MarkdownBlock.Code n) {
                if (!n.language().isBlank()) add(prefix + "[" + n.language() + "]", Role.QUOTE, n.source(), "code-label", "");
                code(n, prefix);
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
            if (lines.size() > firstLine && !lines.get(lines.size() - 1).text().isBlank())
                add(quotePrefix.end() == 0 ? "" : prefix.stripTrailing(), Role.TEXT, block.source(), "space", "");
        }
    }
    private void quote(MarkdownBlock.Quote block, String prefix, int depth) {
        String nested = prefix + "│ ";
        QuotePrefix previous = quotePrefix;
        quotePrefix = new QuotePrefix(nested.length());
        int firstLine = lines.size();
        try {
            blocks(block.blocks(), nested, depth + 1);
            if (lines.size() == firstLine) add(nested.stripTrailing(), Role.TEXT, block.source(), "quote", "");
            else if (lines.get(lines.size() - 1).kind().equals("space")) {
                // Continue the bar between quoted blocks, then end it with the quote.
                lines.remove(lines.size() - 1);
                quotePrefixes.remove(quotePrefixes.size() - 1);
            }
        } finally { quotePrefix = previous; }
    }

    /** Drawing prefixes do not participate in copy/search, including atomic math and diagram rows. */
    private List<Line> decorateQuotes(List<Line> annotated) {
        List<Line> result = new ArrayList<>(annotated.size());
        for (int row = 0; row < annotated.size(); row++) {
            check();
            Line line = annotated.get(row);
            int prefixEnd = Math.min(line.text().length(), quotePrefixes.get(row).end());
            if (prefixEnd == 0) { result.add(line); continue; }
            List<Run> runs = new ArrayList<>(line.runs().size() + 1);
            runs.add(new Run(0, prefixEnd, Role.QUOTE_MARKER, null));
            for (Run run : line.runs()) {
                if (run.end() <= prefixEnd) continue;
                int start = Math.max(prefixEnd, run.start());
                int projectionStart = run.projectionStart();
                if (projectionStart >= 0 && run.role() != Role.MATH && run.role() != Role.DIAGRAM
                    && run.projectionEnd() - projectionStart == run.end() - run.start())
                    projectionStart += start - run.start();
                runs.add(new Run(start, run.end(), run.role(), run.source(), projectionStart, run.projectionEnd()));
            }
            result.add(new Line(line.text(), runs, line.copyText(), line.source(), line.kind(), line.syntax()));
        }
        return List.copyOf(result);
    }
    private void code(MarkdownBlock.Code block, String prefix) {
        List<String> body = List.of(block.text().split("\\n", -1));
        if (body.size() > MAX_ROWS - lines.size())
            throw new IllegalArgumentException("Preview exceeds the layout row budget");
        List<List<TokenSpan>> highlighted = codeHighlighter.highlight(block.language(), body);
        for (int row = 0; row < body.size(); row++) {
            check();
            String value = body.get(row), text = prefix + value;
            List<TokenSpan> spans = highlighted == null || row >= highlighted.size() ? List.of() : highlighted.get(row);
            add(new Line(text, List.of(new Run(0, text.length(), Role.CODE_BLOCK, block.source())), value,
                block.source(), "code", codeSyntax(spans, value.length(), prefix.length())));
        }
    }
    /** Refused or malformed highlighting does not remove any of the code's text or semantic mapping. */
    private List<TokenSpan> codeSyntax(List<TokenSpan> spans, int length, int prefix) {
        if (spans == null || spans.isEmpty() || spans.size() > MAX_LINE_SYNTAX_SPANS
            || spans.size() > MAX_SYNTAX_SPANS - syntaxSpans) return List.of();
        int previousEnd = 0;
        var result = new ArrayList<TokenSpan>(spans.size());
        for (TokenSpan span : spans) {
            check();
            if (span == null || span.start() < previousEnd || span.end() <= span.start() || span.end() > length)
                return List.of();
            result.add(new TokenSpan(prefix + span.start(), prefix + span.end(), span.styleKey()));
            previousEnd = span.end();
        }
        syntaxSpans += result.size();
        return List.copyOf(result);
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
        lines.add(line); quotePrefixes.add(quotePrefix); extent = Math.max(extent, CellText.width(line.text()));
    }
    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Preview cancelled");
    }
}

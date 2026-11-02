package dev.tuink.markdown.commonmark;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.*;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.parser.SourceLine;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.parser.block.*;

/** CommonMark adapter. No CommonMark type crosses the portable Markdown API. */
public final class CommonmarkMarkdownParser implements MarkdownParser {
    @Override
    public MarkdownDocument parse(DocumentSnapshot source, ParseLimits limits, ParseControl control) {
        Objects.requireNonNull(source); Objects.requireNonNull(limits); Objects.requireNonNull(control);
        Budget budget = new Budget(limits, control);
        long size = source.lineCount() - 1L;
        for (int line = 0; line < source.lineCount(); line++) {
            control.check();
            CharSequence text = source.lineText(line);
            size += text.length();
            if (size > limits.maxCodeUnits()) throw new MarkdownParseException("Markdown preview input exceeds its size budget");
            // Reject pathological container nesting before the external AST is allocated.
            int quotes = 0;
            for (int index = 0; index < text.length(); index++) {
                char c = text.charAt(index);
                if (c == '>') { if (++quotes > limits.maxDepth()) throw new MarkdownParseException("Markdown nesting budget exceeded"); }
                else if (c != ' ' && c != '\t') break;
            }
        }
        Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
            .customInlineContentParserFactory(new MathInlineFactory(budget))
            .customBlockParserFactory(new MathBlockFactory(budget)).build();
        Node tree;
        try (Reader reader = new SnapshotReader(source, control)) { tree = parser.parseReader(reader); }
        catch (IOException e) { throw new MarkdownParseException("Could not read Markdown snapshot", e); }
        control.check();
        Converter converter = new Converter(source, budget);
        List<MarkdownBlock> blocks = converter.blocks(tree, 0);
        return new MarkdownDocument(source.documentId(), source.revision(), blocks, converter.diagnostics);
    }

    private static final class Budget {
        final ParseLimits limits;
        final ParseControl control;
        int nodes, cells;
        long work;
        Budget(ParseLimits limits, ParseControl control) { this.limits = limits; this.control = control; }
        void node(int depth) {
            control.check();
            if (depth > limits.maxDepth() || ++nodes > limits.maxNodes()) throw new MarkdownParseException("Markdown structure budget exceeded");
        }
        void scan() {
            if ((++work & 1023) == 0) control.check();
            if (work > limits.maxCodeUnits() * 16L) throw new MarkdownParseException("Markdown inline scanning budget exceeded");
        }
        void cell() { if (++cells > limits.maxTableCells()) throw new MarkdownParseException("Markdown table budget exceeded"); }
    }

    private static final class Converter {
        final DocumentSnapshot source;
        final Budget budget;
        final List<MarkdownDiagnostic> diagnostics = new ArrayList<>();
        Converter(DocumentSnapshot source, Budget budget) { this.source = source; this.budget = budget; }

        List<MarkdownBlock> blocks(Node parent, int depth) {
            List<MarkdownBlock> result = new ArrayList<>();
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                MarkdownBlock converted = block(child, depth);
                if (converted != null) result.add(converted);
            }
            return List.copyOf(result);
        }

        MarkdownBlock block(Node node, int depth) {
            budget.node(depth);
            TextRange range = range(node);
            if (node instanceof Heading n) return new MarkdownBlock.Heading(n.getLevel(), inlines(n, depth + 1), range);
            if (node instanceof Paragraph n) {
                if (n.getFirstChild() instanceof MathNode m && m.display && m.getNext() == null)
                    return new MarkdownBlock.Math(m.expression, range);
                return new MarkdownBlock.Paragraph(inlines(n, depth + 1), range);
            }
            if (node instanceof BlockQuote n) return new MarkdownBlock.Quote(blocks(n, depth + 1), range);
            if (node instanceof org.commonmark.node.ListBlock n) {
                List<MarkdownBlock.Item> items = new ArrayList<>();
                for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
                    budget.node(depth + 1);
                    Node first = child.getFirstChild();
                    Node marker = first instanceof TaskListItemMarker ? first : first == null ? null : first.getFirstChild();
                    boolean task = marker instanceof TaskListItemMarker;
                    boolean checked = task && ((TaskListItemMarker) marker).isChecked();
                    items.add(new MarkdownBlock.Item(blocks(child, depth + 2), task, checked, range(child)));
                }
                int start = n instanceof OrderedList ordered ? ordered.getStartNumber() : 1;
                return new MarkdownBlock.ListBlock(n instanceof OrderedList, start, items, range);
            }
            if (node instanceof FencedCodeBlock n) {
                String language = n.getInfo() == null ? "" : n.getInfo().strip().split("\\s+", 2)[0];
                if (language.equalsIgnoreCase("math")) return new MarkdownBlock.Math(n.getLiteral(), range);
                if (language.equalsIgnoreCase("mermaid")) return new MarkdownBlock.Mermaid(n.getLiteral(), range);
                return new MarkdownBlock.Code(n.getLiteral(), language, range);
            }
            if (node instanceof IndentedCodeBlock n) return new MarkdownBlock.Code(n.getLiteral(), "", range);
            if (node instanceof DollarMathBlock n) {
                if (n.closed) return new MarkdownBlock.Math(n.expression.toString(), range);
                diagnostics.add(new MarkdownDiagnostic("Unclosed display math delimiter", range));
                return new MarkdownBlock.Raw(MarkdownSource.text(source, range), range);
            }
            if (node instanceof TableBlock n) return table(n, depth, range);
            if (node instanceof ThematicBreak) return new MarkdownBlock.ThematicBreak(range);
            if (node instanceof LinkReferenceDefinition) return null;
            if (node instanceof TaskListItemMarker) return null;
            return new MarkdownBlock.Raw(MarkdownSource.text(source, range), range);
        }

        MarkdownBlock.Table table(TableBlock table, int depth, TextRange range) {
            List<MarkdownBlock.Row> rows = new ArrayList<>();
            List<MarkdownBlock.Alignment> alignments = new ArrayList<>();
            for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
                budget.node(depth + 1);
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    budget.node(depth + 2);
                    List<MarkdownBlock.Cell> cells = new ArrayList<>();
                    boolean header = section instanceof TableHead;
                    for (Node node = row.getFirstChild(); node != null; node = node.getNext()) {
                        budget.node(depth + 3); budget.cell();
                        TableCell cell = (TableCell) node;
                        cells.add(new MarkdownBlock.Cell(inlines(cell, depth + 4), range(cell)));
                        if (header) alignments.add(cell.getAlignment() == TableCell.Alignment.RIGHT ? MarkdownBlock.Alignment.RIGHT
                            : cell.getAlignment() == TableCell.Alignment.CENTER ? MarkdownBlock.Alignment.CENTER : MarkdownBlock.Alignment.LEFT);
                    }
                    rows.add(new MarkdownBlock.Row(header, cells, range(row)));
                }
            }
            return new MarkdownBlock.Table(rows, alignments, range);
        }

        List<MarkdownInline> inlines(Node parent, int depth) {
            List<MarkdownInline> result = new ArrayList<>();
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                budget.node(depth);
                TextRange range = range(node);
                if (node instanceof Text n) result.add(new MarkdownInline.Text(n.getLiteral(), range));
                else if (node instanceof org.commonmark.node.Code n) result.add(new MarkdownInline.Code(n.getLiteral(), range));
                else if (node instanceof MathNode n) result.add(new MarkdownInline.Math(n.expression, range));
                else if (node instanceof Emphasis) result.add(new MarkdownInline.Styled(MarkdownInline.Style.EMPHASIS, inlines(node, depth + 1), range));
                else if (node instanceof StrongEmphasis) result.add(new MarkdownInline.Styled(MarkdownInline.Style.STRONG, inlines(node, depth + 1), range));
                else if (node instanceof Strikethrough) result.add(new MarkdownInline.Styled(MarkdownInline.Style.STRIKETHROUGH, inlines(node, depth + 1), range));
                else if (node instanceof Link n) result.add(new MarkdownInline.Link(inlines(n, depth + 1), n.getDestination(), n.getTitle(), false, range));
                else if (node instanceof Image n) result.add(new MarkdownInline.Link(inlines(n, depth + 1), n.getDestination(), n.getTitle(), true, range));
                else if (node instanceof SoftLineBreak) result.add(new MarkdownInline.Break(false, range));
                else if (node instanceof HardLineBreak) result.add(new MarkdownInline.Break(true, range));
                else if (node instanceof HtmlInline n) result.add(new MarkdownInline.Text(n.getLiteral(), range));
                else if (!(node instanceof TaskListItemMarker)) result.add(new MarkdownInline.Text(MarkdownSource.text(source, range), range));
            }
            return List.copyOf(result);
        }

        TextRange range(Node node) {
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) {
                if (node.getParent() != null) return range(node.getParent());
                return new TextRange(new TextPosition(0, 0), new TextPosition(source.lineCount() - 1, source.lineText(source.lineCount() - 1).length()));
            }
            TextPosition start = null, end = null;
            for (SourceSpan span : spans) {
                int line = Math.min(source.lineCount() - 1, span.getLineIndex());
                int length = source.lineText(line).length();
                TextPosition s = new TextPosition(line, Math.min(length, span.getColumnIndex()));
                TextPosition e = new TextPosition(line, Math.min(length, span.getColumnIndex() + span.getLength()));
                if (start == null || s.compareTo(start) < 0) start = s;
                if (end == null || e.compareTo(end) > 0) end = e;
            }
            return new TextRange(start, end);
        }
    }

    private static final class SnapshotReader extends Reader {
        final DocumentSnapshot source;
        final ParseControl control;
        int line, offset;
        SnapshotReader(DocumentSnapshot source, ParseControl control) { this.source = source; this.control = control; }
        @Override public int read(char[] target, int start, int length) {
            Objects.checkFromIndexSize(start, length, target.length);
            if (length == 0) return 0;
            control.check();
            if (line >= source.lineCount()) return -1;
            int count = 0;
            while (count < length && line < source.lineCount()) {
                CharSequence text = source.lineText(line);
                if (offset < text.length()) target[start + count++] = text.charAt(offset++);
                else if (line < source.lineCount() - 1) { target[start + count++] = '\n'; line++; offset = 0; }
                else { line++; offset = 0; }
            }
            return count == 0 ? -1 : count;
        }
        @Override public void close() {}
    }

    private static final class MathNode extends CustomNode {
        final String expression;
        final boolean display;
        MathNode(String expression, boolean display) { this.expression = expression; this.display = display; }
    }
    private static final class MathInlineFactory implements InlineContentParserFactory {
        final Budget budget;
        MathInlineFactory(Budget budget) { this.budget = budget; }
        @Override public Set<Character> getTriggerCharacters() { return Set.of('$'); }
        @Override public InlineContentParser create() { return state -> parseMath(state, budget); }
    }
    private static ParsedInline parseMath(InlineParserState state, Budget budget) {
        Scanner scanner = state.scanner();
        Position start = scanner.position();
        scanner.next();
        boolean display = scanner.peek() == '$';
        if (display) scanner.next();
        Position body = scanner.position();
        if (!display && (Character.isWhitespace(scanner.peek()) || scanner.peek() == Scanner.END)) return ParsedInline.none();
        int braces = 0;
        while (scanner.peek() != Scanner.END) {
            budget.scan();
            char c = scanner.peek();
            if (!display && c == '\n') return ParsedInline.none();
            // An unmatched currency dollar must not consume dollars inside a later code span.
            if (!display && c == '`') return ParsedInline.none();
            if (c == '\\') { scanner.next(); if (scanner.peek() != Scanner.END) scanner.next(); continue; }
            if (c == '{') braces++;
            else if (c == '}' && braces > 0) braces--;
            if (c == '$' && braces == 0) {
                Position end = scanner.position();
                int previous = scanner.peekPreviousCodePoint();
                scanner.next();
                if (display) {
                    if (scanner.peek() != '$') continue;
                    scanner.next();
                } else if (Character.isWhitespace(previous) || Character.isDigit(scanner.peekCodePoint())) {
                    // A dollar before a number is a currency candidate, not this formula's terminator.
                    return ParsedInline.none();
                }
                String expression = scanner.getSource(body, end).getContent();
                if (expression.isEmpty()) return ParsedInline.none();
                MathNode node = new MathNode(expression, display);
                node.setSourceSpans(scanner.getSource(start, scanner.position()).getSourceSpans());
                return ParsedInline.of(node, scanner.position());
            }
            scanner.next();
        }
        return ParsedInline.none();
    }

    private static final class DollarMathBlock extends CustomBlock {
        final StringBuilder expression = new StringBuilder();
        boolean closed;
    }
    private static final class MathBlockFactory extends AbstractBlockParserFactory {
        final Budget budget;
        MathBlockFactory(Budget budget) { this.budget = budget; }
        @Override public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
            budget.control.check();
            int index = state.getNextNonSpaceIndex();
            CharSequence line = state.getLine().getContent();
            if (state.getIndent() >= 4 || !line.subSequence(index, line.length()).toString().stripTrailing().equals("$$")) return BlockStart.none();
            return BlockStart.of(new MathBlockParser(budget)).atIndex(line.length());
        }
    }
    private static final class MathBlockParser extends AbstractBlockParser {
        final DollarMathBlock block = new DollarMathBlock();
        final Budget budget;
        boolean opening = true;
        int bodyLines;
        MathBlockParser(Budget budget) { this.budget = budget; }
        @Override public Block getBlock() { return block; }
        @Override public BlockContinue tryContinue(ParserState state) {
            budget.control.check();
            CharSequence line = state.getLine().getContent();
            int index = state.getNextNonSpaceIndex();
            if (line.subSequence(index, line.length()).toString().stripTrailing().equals("$$")) {
                block.closed = true;
                if (state.getLine().getSourceSpan() != null) block.addSourceSpan(state.getLine().getSourceSpan());
                return BlockContinue.finished();
            }
            return BlockContinue.atIndex(state.getIndex());
        }
        @Override public void addLine(SourceLine line) {
            if (opening) { opening = false; return; }
            if (bodyLines++ > 0) block.expression.append('\n');
            block.expression.append(line.getContent());
        }
    }
}

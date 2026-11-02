package dev.tuink.markdown;

import dev.tuink.editor.document.TextRange;
import java.util.List;
import java.util.Objects;

/** Immutable Markdown structure before a frontend measures or wraps its content. */
public sealed interface MarkdownBlock {
    TextRange source();

    record Heading(int level, List<MarkdownInline> content, TextRange source) implements MarkdownBlock {
        public Heading { if (level < 1 || level > 6) throw new IllegalArgumentException("Heading level"); content = List.copyOf(content); Objects.requireNonNull(source); }
    }
    record Paragraph(List<MarkdownInline> content, TextRange source) implements MarkdownBlock {
        public Paragraph { content = List.copyOf(content); Objects.requireNonNull(source); }
    }
    record Quote(List<MarkdownBlock> blocks, TextRange source) implements MarkdownBlock {
        public Quote { blocks = List.copyOf(blocks); Objects.requireNonNull(source); }
    }
    record ListBlock(boolean ordered, int start, List<Item> items, TextRange source) implements MarkdownBlock {
        public ListBlock { items = List.copyOf(items); Objects.requireNonNull(source); }
    }
    record Item(List<MarkdownBlock> blocks, boolean task, boolean checked, TextRange source) {
        public Item { blocks = List.copyOf(blocks); Objects.requireNonNull(source); }
    }
    record Code(String text, String language, TextRange source) implements MarkdownBlock {
        public Code { Objects.requireNonNull(text); language = Objects.requireNonNullElse(language, ""); Objects.requireNonNull(source); }
    }
    enum Alignment { LEFT, CENTER, RIGHT }
    record Table(List<Row> rows, List<Alignment> alignments, TextRange source) implements MarkdownBlock {
        public Table { rows = List.copyOf(rows); alignments = List.copyOf(alignments); Objects.requireNonNull(source); }
    }
    record Row(boolean header, List<Cell> cells, TextRange source) {
        public Row { cells = List.copyOf(cells); Objects.requireNonNull(source); }
    }
    record Cell(List<MarkdownInline> content, TextRange source) {
        public Cell { content = List.copyOf(content); Objects.requireNonNull(source); }
    }
    record ThematicBreak(TextRange source) implements MarkdownBlock {
        public ThematicBreak { Objects.requireNonNull(source); }
    }
    record Math(String expression, TextRange source) implements MarkdownBlock {
        public Math { Objects.requireNonNull(expression); Objects.requireNonNull(source); }
    }
    record Mermaid(String code, TextRange source) implements MarkdownBlock {
        public Mermaid { Objects.requireNonNull(code); Objects.requireNonNull(source); }
    }
    record Raw(String text, TextRange source) implements MarkdownBlock {
        public Raw { Objects.requireNonNull(text); Objects.requireNonNull(source); }
    }
}

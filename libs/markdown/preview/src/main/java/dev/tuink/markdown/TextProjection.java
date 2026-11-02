package dev.tuink.markdown;

import dev.tuink.editor.document.TextRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Semantic text for copy/search, with source mappings independent of decorative glyphs. */
public record TextProjection(String text, List<Mapping> mappings) {
    public TextProjection { Objects.requireNonNull(text); mappings = List.copyOf(mappings); }
    public record Mapping(int start, int end, TextRange source, boolean exact) {
        public Mapping {
            if (start < 0 || end < start) throw new IllegalArgumentException("Projection range");
            Objects.requireNonNull(source);
        }
    }
    public TextRange sourceAt(int offset) {
        for (Mapping map : mappings) if (offset >= map.start() && offset < map.end()) return map.source();
        return null;
    }
    public static TextProjection from(List<MarkdownBlock> blocks) {
        Builder b = new Builder(); b.blocks(blocks); return new TextProjection(b.text.toString(), b.maps);
    }
    private static final class Builder {
        final StringBuilder text = new StringBuilder();
        final List<Mapping> maps = new ArrayList<>();
        void add(String value, TextRange range, boolean exact) {
            if (value.isEmpty()) return;
            int start = text.length(); text.append(value); maps.add(new Mapping(start, text.length(), range, exact));
        }
        void separator(char separator) {
            if (!text.isEmpty() && text.charAt(text.length() - 1) != separator) text.append(separator);
        }
        void blocks(List<MarkdownBlock> blocks) {
            for (MarkdownBlock block : blocks) {
                separator('\n');
                if (block instanceof MarkdownBlock.Heading n) inlines(n.content());
                else if (block instanceof MarkdownBlock.Paragraph n) inlines(n.content());
                else if (block instanceof MarkdownBlock.Quote n) blocks(n.blocks());
                else if (block instanceof MarkdownBlock.ListBlock n) {
                    for (MarkdownBlock.Item item : n.items()) { separator('\n'); blocks(item.blocks()); }
                } else if (block instanceof MarkdownBlock.Code n) add(n.text(), n.source(), false);
                else if (block instanceof MarkdownBlock.Raw n) add(n.text(), n.source(), false);
                else if (block instanceof MarkdownBlock.Math n) add(n.expression(), n.source(), false);
                else if (block instanceof MarkdownBlock.Mermaid n) add(n.code(), n.source(), false);
                else if (block instanceof MarkdownBlock.Table n) {
                    for (MarkdownBlock.Row row : n.rows()) {
                        separator('\n');
                        for (int cell = 0; cell < row.cells().size(); cell++) {
                            if (cell > 0) text.append('\t');
                            inlines(row.cells().get(cell).content());
                        }
                    }
                }
            }
        }
        void inlines(List<MarkdownInline> inlines) {
            for (MarkdownInline inline : inlines) {
                if (inline instanceof MarkdownInline.Text n) add(n.text(), n.source(), isExact(n.text(), n.source()));
                else if (inline instanceof MarkdownInline.Code n) add(n.text(), n.source(), false);
                else if (inline instanceof MarkdownInline.Math n) add(n.expression(), n.source(), false);
                else if (inline instanceof MarkdownInline.Styled n) inlines(n.content());
                else if (inline instanceof MarkdownInline.Link n) inlines(n.content());
                else if (inline instanceof MarkdownInline.Break n) add(n.hard() ? "\n" : " ", n.source(), false);
            }
        }
        boolean isExact(String value, TextRange range) {
            return range.start().line() == range.end().line()
                && value.length() == range.end().utf16Offset() - range.start().utf16Offset();
        }
    }
}

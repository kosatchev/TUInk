package dev.tuink.tui;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import dev.tuink.markdown.MarkdownSource;
import dev.tuink.markdown.MarkdownCopy;
import java.util.List;

/** Explicit actions use source/semantic content, never terminal drawing glyphs. */
final class MarkdownPreviewActions {
    record Target(TextRange source, String text, String kind) {}
    private MarkdownPreviewActions() {}

    static Target source(MarkdownDocument document, DocumentSnapshot snapshot, int offset) {
        var range = sourceRange(document, offset);
        if (range == null || !document.documentId().equals(snapshot.documentId()) || document.revision() != snapshot.revision()) return null;
        var block = find(document.blocks(), range);
        if (block == null) return null;
        List<MarkdownInline> inline = block instanceof MarkdownBlock.Paragraph n ? n.content()
            : block instanceof MarkdownBlock.Heading n ? n.content() : List.of();
        Target embedded = inline(inline, range);
        if (embedded != null) return embedded;
        if (block instanceof MarkdownBlock.Table table) {
            for (var row : table.rows()) for (var cell : row.cells()) {
                embedded = inline(cell.content(), range);
                if (embedded != null) return embedded;
            }
        }
        if (block instanceof MarkdownBlock.Math n) return new Target(n.source(), n.expression(), "TeX");
        if (block instanceof MarkdownBlock.Mermaid n) return new Target(n.source(), n.code(), "Mermaid");
        if (block instanceof MarkdownBlock.Code n) return new Target(n.source(), n.text(), "code");
        return new Target(block.source(), MarkdownSource.text(snapshot, block.source()), "Markdown");
    }
    static String table(MarkdownDocument document, int offset) {
        var range = sourceRange(document, offset);
        var block = range == null ? null : find(document.blocks(), range);
        return block instanceof MarkdownBlock.Table table ? MarkdownCopy.tableTsv(table) : null;
    }
    /** Maps an insertion position, retaining the end boundary of exact text mappings. */
    static TextPosition sourcePosition(MarkdownDocument document, int offset) {
        int at = Math.max(0, Math.min(document.projection().text().length(), offset));
        dev.tuink.markdown.TextProjection.Mapping preceding = null;
        for (var mapping : document.projection().mappings()) {
            if (at >= mapping.start() && at < mapping.end()) {
                var start = mapping.source().start();
                return mapping.exact() ? new TextPosition(start.line(), start.utf16Offset() + at - mapping.start()) : start;
            }
            if (mapping.end() <= at && (preceding == null || mapping.end() > preceding.end())) preceding = mapping;
        }
        if (preceding == null) return null;
        return preceding.exact() ? preceding.source().end() : preceding.source().start();
    }
    static TextRange sourceRange(MarkdownDocument document, int offset) {
        if (document.projection().text().isEmpty()) return null;
        int at = Math.max(0, Math.min(document.projection().text().length() - 1, offset));
        for (int i = at; i >= 0; i--) {
            for (var mapping : document.projection().mappings()) {
                if (i < mapping.start() || i >= mapping.end()) continue;
                TextRange range = mapping.source();
                if (mapping.exact()) {
                    var position = new dev.tuink.editor.document.TextPosition(range.start().line(),
                        range.start().utf16Offset() + i - mapping.start());
                    return new TextRange(position, position);
                }
                return range;
            }
        }
        return null;
    }
    private static MarkdownBlock find(List<MarkdownBlock> blocks, TextRange range) {
        for (var block : blocks) {
            if (!contains(block.source(), range)) continue;
            if (block instanceof MarkdownBlock.Quote n) {
                var child = find(n.blocks(), range); if (child != null) return child;
            } else if (block instanceof MarkdownBlock.ListBlock n) {
                for (var item : n.items()) { var child = find(item.blocks(), range); if (child != null) return child; }
            }
            return block;
        }
        return null;
    }
    private static Target inline(List<MarkdownInline> inlines, TextRange range) {
        for (var inline : inlines) {
            if (!contains(inline.source(), range)) continue;
            if (inline instanceof MarkdownInline.Math n) return new Target(n.source(), n.expression(), "TeX");
            if (inline instanceof MarkdownInline.Code n) return new Target(n.source(), n.text(), "code");
            if (inline instanceof MarkdownInline.Styled n) { var child = inline(n.content(), range); if (child != null) return child; }
            if (inline instanceof MarkdownInline.Link n) { var child = inline(n.content(), range); if (child != null) return child; }
        }
        return null;
    }
    private static boolean contains(TextRange outer, TextRange inner) {
        return outer.start().compareTo(inner.start()) <= 0 && outer.end().compareTo(inner.end()) >= 0;
    }
}

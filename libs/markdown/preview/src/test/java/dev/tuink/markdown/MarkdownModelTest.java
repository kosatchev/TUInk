package dev.tuink.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownModelTest {
    private static final TextRange RANGE = new TextRange(new TextPosition(0, 0), new TextPosition(0, 3));
    @Test void immutableModelCopiesItsCollections() {
        List<MarkdownInline> content = new ArrayList<>(); content.add(new MarkdownInline.Text("one", RANGE));
        MarkdownBlock.Paragraph paragraph = new MarkdownBlock.Paragraph(content, RANGE); content.clear();
        List<MarkdownBlock> blocks = new ArrayList<>(List.of(paragraph));
        MarkdownDocument document = new MarkdownDocument(new DocumentId("one"), 1, blocks, List.of()); blocks.clear();
        assertEquals("one", document.projection().text());
        assertThrows(UnsupportedOperationException.class, () -> document.blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> paragraph.content().clear());
    }
    @Test void tableCopyContainsTabsAndNoRendererBorders() {
        MarkdownBlock.Cell a = new MarkdownBlock.Cell(List.of(new MarkdownInline.Text("one", RANGE)), RANGE);
        MarkdownBlock.Cell b = new MarkdownBlock.Cell(List.of(new MarkdownInline.Text("two", RANGE)), RANGE);
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(true, List.of(a, b), RANGE)), List.of(), RANGE);
        assertEquals("one\ttwo", TextProjection.from(List.of(table)).text());
    }
    @Test void sourceExtractionPreservesUtf16AndMultilineContent() {
        DocumentSnapshot source = new DocumentSnapshot(new DocumentId("source"), 1, List.of("😀 one", "two", "three"));
        assertEquals("one\ntwo\nth", MarkdownSource.text(source, new TextRange(new TextPosition(0, 3), new TextPosition(2, 2))));
        assertThrows(IllegalArgumentException.class, () -> MarkdownSource.text(source, new TextRange(new TextPosition(0, 0), new TextPosition(3, 0))));
    }
}

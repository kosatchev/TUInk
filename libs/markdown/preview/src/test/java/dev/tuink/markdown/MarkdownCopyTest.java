package dev.tuink.markdown;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownCopyTest {
    private static final TextRange SOURCE = new TextRange(new TextPosition(0, 0), new TextPosition(0, 10));
    private static MarkdownBlock.Cell code(String text) { return new MarkdownBlock.Cell(List.of(new MarkdownInline.Code(text, SOURCE)), SOURCE); }

    @Test void tsvQuotesEmbeddedTabsNewlinesAndQuotesWithoutChangingSemanticSearchText() {
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(
            new MarkdownBlock.Row(true, List.of(code("Name"), code("Value")), SOURCE),
            new MarkdownBlock.Row(false, List.of(code("字\t👩‍💻"), code("one\ntwo")), SOURCE),
            new MarkdownBlock.Row(false, List.of(code("say \"yes\""), code("\rline")), SOURCE),
            new MarkdownBlock.Row(false, List.of(code(""), code(" plain ")), SOURCE)), List.of(), SOURCE);
        assertEquals("Name\tValue\n\"字\t👩‍💻\"\t\"one\ntwo\"\n\"say \"\"yes\"\"\"\t\"\rline\"\n\t plain ", MarkdownCopy.tableTsv(table));
        assertTrue(TextProjection.from(List.of(table)).text().contains("say \"yes\""));
        assertFalse(TextProjection.from(List.of(table)).text().contains("say \"\"yes\"\""));
    }
}

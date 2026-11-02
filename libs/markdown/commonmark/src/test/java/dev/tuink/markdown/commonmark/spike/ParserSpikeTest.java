package dev.tuink.markdown.commonmark.spike;

import java.io.StringReader;
import java.util.List;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParserSpikeTest {
    @Test void readerTablesAndUtf16SourcePositions() throws Exception {
        Parser parser = Parser.builder().extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
        Node document = parser.parseReader(new StringReader("😀 *bold*\n\n| a | b |\n|---|---|\n| 1 | 2 |\n"));
        Node emphasis = document.getFirstChild().getFirstChild().getNext();
        assertEquals(3, emphasis.getSourceSpans().get(0).getColumnIndex());
        assertEquals(6, emphasis.getSourceSpans().get(0).getLength());
        assertEquals("TableBlock", document.getLastChild().getClass().getSimpleName());
    }
}

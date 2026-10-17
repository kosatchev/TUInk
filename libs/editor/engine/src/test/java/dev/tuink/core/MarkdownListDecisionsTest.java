package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownListDecisionsTest {
    @Test
    void emptyItemReturnsAnUnappliedReplacementKeepingItsIndentation() {
        List<String> lines = List.of("- parent", "  - [ ] \t");
        MarkdownListDecisions decisions = decisions(lines);

        assertEquals(new TextReplacement(new Position(1, 2), new Position(1, 9), ""),
            decisions.termination(new Position(1, 9)));
        assertEquals("  - [ ] \t", lines.get(1));
        assertNull(decisions.termination(new Position(1, 6)));
    }

    @Test
    void continuationUsesTheSuppliedPrefixAndStillChecksTheSurroundingFence() {
        String source = "3. first suffix";
        MarkdownListDecisions ordinary = decisions(List.of(source));
        assertEquals("4. ", ordinary.continuation(0, source.substring(0, 8)));
        assertEquals("", ordinary.continuation(0, "3. "));

        MarkdownListDecisions fenced = decisions(List.of("```text", source));
        assertEquals("", fenced.continuation(1, source.substring(0, 8)));
    }

    @Test
    void deeplyIndentedItemsUseTheProvidedColumnProjection() {
        List<String> lines = List.of("\t- ");
        MarkdownListDecisions narrowTabs = new MarkdownListDecisions(lines::get, (line, column) -> column * 2);
        MarkdownListDecisions wideTabs = new MarkdownListDecisions(lines::get, (line, column) -> column * 4);

        assertEquals(new TextReplacement(new Position(0, 1), new Position(0, 3), ""),
            narrowTabs.termination(new Position(0, 3)));
        assertNull(wideTabs.termination(new Position(0, 3)));
    }

    @Test
    void literalCodeAndNonemptySuffixesProduceNoDestructiveProposal() {
        for (List<String> lines : List.of(
            List.of("```text", "- "),
            List.of("- parent", "      - "),
            List.of("- parent", "    ```text", "    - "),
            List.of("- content"),
            List.of("-     code", "        - ")
        )) {
            int last = lines.size() - 1;
            assertNull(decisions(lines).termination(new Position(last, lines.get(last).length())), lines.toString());
        }
    }

    private static MarkdownListDecisions decisions(List<String> lines) {
        return new MarkdownListDecisions(lines::get, (line, column) -> column);
    }
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownListDecisionsTest {
    @Test
    void ordinaryNewlinesDoNotRequestTheSurroundingBlockContext() {
        MarkdownListDecisions decisions = new MarkdownListDecisions(index -> "", index -> {
            throw new AssertionError("A non-list continuation must not scan preceding lines");
        });
        for (String text : List.of("", "  \t", "ordinary paragraph", "text - item", "- ", "1. ", "- [ ] ",
            "*\t[X]\t", "+  [ ]  ", "1)\t[ ]\t")) {
            assertEquals("", decisions.continuation(800_000, text), text);
        }
    }

    @Test
    void continuationKeepsBulletDelimiterAndHorizontalSpacing() {
        for (String marker : List.of("-", "*", "+", "0.", "9)", "009.")) {
            String next = switch (marker) {
                case "0." -> "1.";
                case "9)" -> "10)";
                case "009." -> "010.";
                default -> marker;
            };
            for (String spacing : List.of(" ", "\t", "  ", " \t")) {
                String source = marker + spacing + "item";

                assertEquals(next + spacing, decisions(List.of(source)).continuation(0, source), source);
            }
        }
    }

    @Test
    void everyBulletContinuesCheckedAndUncheckedTasksWithoutLosingWhitespace() {
        for (String marker : List.of("-", "*", "+", "2)")) {
            String next = marker.equals("2)") ? "3)" : marker;
            for (String checkbox : List.of("[ ]", "[x]", "[X]")) {
                for (String spacing : List.of(" ", "\t", "  ")) {
                    String source = marker + spacing + checkbox + "\t  item";

                    assertEquals(next + spacing + "[ ]\t  ",
                        decisions(List.of(source)).continuation(0, source), source);
                }
            }
        }
    }

    @Test
    void invalidOrdinalsAndTheirOverflowDoNotRequestBlockContext() {
        MarkdownListDecisions decisions = new MarkdownListDecisions(index -> "", index -> {
            throw new AssertionError("An invalid ordinal must not scan preceding lines");
        });
        for (String source : List.of("١. item", "１) item", "1234567890. item", "999999999. item")) {
            assertEquals("", decisions.continuation(0, source), source);
        }
        String lastValid = "999999998. item";
        assertEquals("999999999. ", decisions(List.of(lastValid)).continuation(0, lastValid));
    }

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
    void tabsUseMarkdownGrammarColumnsForCodeAndNestedItems() {
        MarkdownListDecisions code = decisions(List.of("\t- "));
        assertNull(code.termination(new Position(0, 3)));
        assertEquals("", code.continuation(0, "\t- literal code"));

        MarkdownListDecisions nested = decisions(List.of("- parent", "\t- "));
        assertEquals(new TextReplacement(new Position(1, 1), new Position(1, 3), ""),
            nested.termination(new Position(1, 3)));
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
        MarkdownBlockContext context = new MarkdownBlockContext(lines::get);
        context.synchronize(0, 0, 0, lines.size());
        return new MarkdownListDecisions(lines::get, context::allowsListAssist);
    }
}

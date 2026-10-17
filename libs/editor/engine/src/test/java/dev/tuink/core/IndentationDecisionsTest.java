package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndentationDecisionsTest {
    @Test
    void newlineUsesTheCursorPrefixWhileKeepingTheSourceAvailableForReplacement() {
        List<String> lines = List.of("    if ready: suffix");
        IndentationDecisions decisions = decisions("main.py", lines);

        assertEquals("        ", decisions.newLineIndent(new Position(0, 13)));
        assertEquals("  ", decisions.newLineIndent(new Position(0, 2)));
        assertEquals(List.of("    if ready: suffix"), lines);
    }

    @Test
    void keywordDecisionRequiresTheInsertedTriggerAndDoesNotApplyItsRemoval() {
        List<String> lines = List.of("if ready:", "    run()", "    else:");
        IndentationDecisions decisions = decisions("main.py", lines);

        assertEquals(0, decisions.keywordOutdent(new Position(2, 8)));
        assertEquals(4, decisions.keywordOutdent(new Position(2, 9)));
        assertEquals("    else:", lines.get(2));
    }

    @Test
    void ambiguousContextAndLookbackExhaustionProduceNoCorrection() {
        List<String> ambiguous = List.of("value = \"unfinished", "if ready:", "    run()", "    else:");
        assertEquals(0, decisions("main.py", ambiguous).keywordOutdent(new Position(3, 9)));

        List<String> lines = java.util.stream.Stream.concat(
            java.util.stream.Stream.concat(java.util.stream.Stream.of("if ready:"),
                java.util.stream.Stream.generate(() -> "    run()").limit(256)),
            java.util.stream.Stream.of("    else:")
        ).toList();
        assertEquals(0, decisions("main.py", lines).keywordOutdent(new Position(257, 9)));
    }

    @Test
    void indentUnitAndRemovalUseTheSameConfiguredWidth() {
        List<String> lines = List.of("    run()");
        for (TabInsertMode mode : TabInsertMode.values()) {
            IndentationDecisions decisions = new IndentationDecisions(1, lines::get, Path.of("main.py"), 4, mode);
            String prefix = "\t  ";
            assertEquals(prefix, decisions.removeUnit(prefix + decisions.unit(0, prefix), 0), mode.name());
        }
    }

    @Test
    void autoIndentCountsWhitespaceGraphemesWithoutInspectingTheRestOfTheLine() {
        for (String extension : List.of("\u0301", "\u0903", "\u200d")) {
            // A space joined to an extending character is not whitespace,
            // whereas a tab remains a separate whitespace grapheme.
            assertEquals("\t", autoUnit(List.of(" " + extension + "value", "\tvalue")));
            assertEquals("\t", autoUnit(List.of("\t" + extension + "value")));
            assertEquals("  ", autoUnit(List.of("  " + extension + "value", "\tvalue")));
        }
        assertEquals("\t", autoUnit(List.of(" \t\u2003", "\tvalue")));
        assertEquals("    ", autoUnit(List.of(" \r\nvalue", "\tvalue")));
    }

    private static String autoUnit(List<String> lines) {
        return new IndentationDecisions(lines.size(), lines::get, Path.of("notes.txt"), 4, TabInsertMode.AUTO)
            .unit(0, "");
    }

    private static IndentationDecisions decisions(String file, List<String> lines) {
        return new IndentationDecisions(lines.size(), lines::get, Path.of(file), 4, TabInsertMode.ALWAYS_SPACES_4);
    }
}

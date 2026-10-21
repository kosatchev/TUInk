package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
    void nearbyPythonOwnerRemainsAvailablePastTheAbsoluteLookbackBoundary() {
        for (int prefixLength : new int[] {254, 255, 256, 300}) {
            List<String> lines = new ArrayList<>(Collections.nCopies(prefixLength, "value = 1"));
            lines.addAll(List.of("if ready:", "    run()", "    else:"));

            assertEquals(4, decisions("main.py", lines).keywordOutdent(new Position(lines.size() - 1, 9)),
                "The owner is two lines away after " + prefixLength + " unrelated lines");
        }
    }

    @Test
    void characterLookbackCountsTheDistanceToTheOwnerInsteadOfTheWholePrefix() {
        List<String> lines = new ArrayList<>(Collections.nCopies(120, "value = \"" + "x".repeat(200) + "\""));
        lines.addAll(List.of("if ready:", "    run()", "    else:"));

        assertEquals(4, decisions("main.py", lines).keywordOutdent(new Position(lines.size() - 1, 9)));
    }

    @Test
    void nearbyOwnerStillRequiresKnownIncomingLexicalContext() {
        for (String opening : List.of("payload = '''", "value = \"unfinished", "value = `unfinished", "value = \\")) {
            List<String> lines = new ArrayList<>();
            lines.add(opening);
            lines.addAll(Collections.nCopies(300, "value = 1"));
            lines.addAll(List.of("if ready:", "    run()", "    else:"));

            assertEquals(0, decisions("main.py", lines).keywordOutdent(new Position(lines.size() - 1, 9)), opening);
        }
    }

    @Test
    void cachedLexicalContextCanProveANearbyOwnerBeyondTheInitialScanBudget() {
        for (boolean malformed : new boolean[] {false, true}) {
            List<String> lines = new ArrayList<>(Collections.nCopies(3_000, "value = 1"));
            if (malformed) lines.set(0, "value = \"unfinished");
            lines.addAll(List.of("if ready:", "    run()", "    else:"));
            IndentationContext context = new IndentationContext(lines::get);
            context.synchronize(1, 1, 0, lines.size(), Path.of("main.py"));
            int last = lines.size() - 1;
            for (int attempt = 0; attempt < 4; attempt++) context.codeBeforeCursor(last, 9);
            IndentationDecisions decisions = new IndentationDecisions(lines.size(), lines::get, Path.of("main.py"),
                4, TabInsertMode.ALWAYS_SPACES_4, context);

            assertEquals(malformed ? 0 : 4, decisions.keywordOutdent(new Position(last, 9)),
                "Evicting old checkpoints must preserve whether the incoming context was proven");
        }
    }

    @Test
    void completedMultilineLiteralsDoNotMakeLaterKnownCodeUncertain() {
        for (String delimiter : List.of("'''", "\"\"\"")) {
            List<String> lines = new ArrayList<>(List.of("payload = " + delimiter, "    if fake:", delimiter));
            lines.addAll(Collections.nCopies(300, "value = 1"));
            lines.addAll(List.of("if ready:", "    run()", "    else:"));

            assertEquals(4, decisions("main.py", lines).keywordOutdent(new Position(lines.size() - 1, 9)), delimiter);
        }
    }

    @Test
    void aWarmDeepContextIncludesTheOldestPermittedOwnerWithoutExtendingTheLookback() {
        for (int distance : new int[] {256, 257}) {
            List<String> lines = new ArrayList<>(Collections.nCopies(3_000, "value = 1"));
            lines.add("if ready:");
            lines.addAll(Collections.nCopies(distance - 1, "    run()"));
            lines.add("    else:");
            IndentationContext context = new IndentationContext(lines::get);
            context.synchronize(1, 1, 0, lines.size(), Path.of("main.py"));
            int last = lines.size() - 1;
            for (int attempt = 0; attempt < 4; attempt++) context.codeBeforeCursor(last, 9);
            IndentationDecisions decisions = new IndentationDecisions(lines.size(), lines::get, Path.of("main.py"),
                4, TabInsertMode.ALWAYS_SPACES_4, context);

            assertEquals(distance == 256 ? 4 : 0, decisions.keywordOutdent(new Position(last, 9)),
                "Owner distance " + distance);
        }
    }

    @Test
    void genericFileStillNeedsAnAnchoredProofAgainstAnEarlierOpenComment() {
        for (boolean closed : new boolean[] {false, true}) {
            List<String> lines = new ArrayList<>(List.of("/* earlier comment"));
            if (closed) lines.add("*/");
            lines.addAll(Collections.nCopies(300, "work();"));
            lines.addAll(List.of("    work() {", "        run();", "        }"));

            assertEquals(closed ? "    " : "        ", decisions("source.unknown", lines)
                .newLineIndent(new Position(lines.size() - 1, lines.getLast().length())));
        }
    }

    @Test
    void conservativeContinuationProofDoesNotDisableLaterOrdinaryIndentIncreases() {
        List<String> lines = List.of("value = \"continued\\", "closed\"", "if ready:");
        IndentationDecisions decisions = decisions("main.py", lines);

        assertEquals("    ", decisions.newLineIndent(new Position(2, lines.get(2).length())));
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

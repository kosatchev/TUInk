package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TypingAssistCharSequenceTest {
    @Test
    void indentationReadsOnlyTheCursorPrefixOfALongImmutableLine() {
        VirtualText line = new VirtualText("    if ready:", 'x');
        IndentationDecisions decisions = new IndentationDecisions(1, index -> line, Path.of("main.py"),
            4, TabInsertMode.ALWAYS_SPACES_4);
        assertEquals("        ", decisions.newLineIndent(new Position(0, 13)));
        assertTrue(line.reads < 200);
        assertTrue(line.largestSlice <= 13);
    }

    @Test
    void keywordOutdentDoesNotMaterializeTheSuffixAfterTheTypedClause() {
        VirtualText line = new VirtualText("    else:", 'x');
        List<CharSequence> lines = List.of("if ready:", "    work()", line);
        IndentationDecisions decisions = new IndentationDecisions(lines.size(), lines::get, Path.of("main.py"),
            4, TabInsertMode.ALWAYS_SPACES_4);
        assertEquals(4, decisions.keywordOutdent(new Position(2, 9)));
        assertTrue(line.reads < 200);
        assertTrue(line.largestSlice <= 9);
    }

    @Test
    void oversizedLexicalPrefixesAndPreviousLinesAreRejectedBeforeCharacterAccess() {
        VirtualText line = new VirtualText("/*", 'x');
        IndentationContext context = new IndentationContext(index -> index == 0 ? line : "if ready:");
        context.synchronize(1, 1, 0, 2, Path.of("main.py"));
        assertNull(context.codeBeforeCursor(0, line.length()));
        assertNull(context.codeBeforeCursor(1, 9));
        assertEquals(0, line.reads);
        assertEquals(0, line.largestSlice);
    }

    @Test
    void whitespaceAssistsHaveABoundedCharacterBudgetAcrossAllNeighbouringLines() {
        VirtualText line = new VirtualText("", ' ');
        IndentationDecisions decisions = new IndentationDecisions(1000, index -> line, Path.of("main.py"),
            4, TabInsertMode.AUTO);
        assertEquals("    ", decisions.unit(500, ""));
        assertTrue(line.reads < IndentationContext.MAX_SCAN_CODE_UNITS);
        assertEquals(0, line.largestSlice);

        line.reads = 0;
        assertEquals("", decisions.newLineIndent(new Position(500, 100_000)));
        assertEquals(IndentationContext.MAX_SCAN_CODE_UNITS, line.reads);
        assertEquals(0, decisions.keywordOutdent(new Position(500, 100_000)));
        assertEquals(IndentationContext.MAX_SCAN_CODE_UNITS, line.reads);
    }

    @Test
    void skippedMarkdownLinesCannotCertifyTheirSuccessorsUntilThePrefixIsRepaired() {
        VirtualText oversized = new VirtualText("```", 'x');
        AtomicReference<CharSequence> first = new AtomicReference<>(oversized);
        MarkdownBlockContext context = new MarkdownBlockContext(index -> index == 0 ? first.get() : "- item");
        context.synchronize(1, 1, 0, 2);
        assertFalse(context.allowsListAssist(0));
        assertFalse(context.allowsListAssist(1));
        assertEquals(0, oversized.reads);
        assertEquals(0, oversized.largestSlice);

        first.set(new StringBuilder("ordinary paragraph"));
        context.synchronize(1, 2, 0, 2);
        assertTrue(context.allowsListAssist(1));
    }

    @Test
    void markdownListDecisionsRejectOversizedInputBeforeScanningOrRequestingContext() {
        VirtualText line = new VirtualText("- ", ' ');
        MarkdownListDecisions decisions = new MarkdownListDecisions(index -> line, index -> {
            throw new AssertionError("Oversized list assists must not scan block context");
        });
        assertNull(decisions.termination(new Position(0, line.length())));
        assertEquals("", decisions.continuation(0, line));
        assertEquals(0, line.reads);
        assertEquals(0, line.largestSlice);
    }

    @Test
    void ordinaryMarkdownMarkersAndFencesWorkThroughCharSequenceReaders() {
        List<CharSequence> lines = List.of(new StringBuilder("009. [x]\titem"), new StringBuilder("- [ ] "));
        MarkdownBlockContext context = new MarkdownBlockContext(lines::get);
        context.synchronize(1, 1, 0, lines.size());
        MarkdownListDecisions decisions = new MarkdownListDecisions(lines::get, context::allowsListAssist);
        assertEquals("010. [ ]\t", decisions.continuation(0, lines.getFirst()));
        assertEquals(new TextReplacement(new Position(1, 0), new Position(1, 6), ""),
            decisions.termination(new Position(1, 6)));

        List<CharSequence> fenced = List.of(new StringBuilder("```text"), new StringBuilder("- item"));
        MarkdownBlockContext fenceContext = new MarkdownBlockContext(fenced::get);
        fenceContext.synchronize(1, 1, 0, fenced.size());
        assertFalse(fenceContext.allowsListAssist(1));
    }

    /** Ten million virtual units make accidental flattening fail without allocating a giant string. */
    private static final class VirtualText implements CharSequence {
        private static final int LENGTH = 10_000_000;
        private final String prefix;
        private final char filler;
        private int reads;
        private int largestSlice;

        private VirtualText(String prefix, char filler) {
            this.prefix = prefix;
            this.filler = filler;
        }

        @Override public int length() { return LENGTH; }

        @Override public char charAt(int index) {
            if (index < 0 || index >= LENGTH) throw new IndexOutOfBoundsException(index);
            reads++;
            return index < prefix.length() ? prefix.charAt(index) : filler;
        }

        @Override public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < start || end > LENGTH) throw new IndexOutOfBoundsException();
            largestSlice = Math.max(largestSlice, end - start);
            if (end - start > IndentationContext.MAX_SCAN_CODE_UNITS) {
                throw new AssertionError("An assist requested an oversized slice");
            }
            return new Window(this, start, end - start);
        }

        @Override public String toString() {
            throw new AssertionError("An assist flattened the entire virtual line");
        }
    }

    private record Window(VirtualText source, int offset, int length) implements CharSequence {
        @Override public char charAt(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
            return source.charAt(offset + index);
        }

        @Override public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < start || end > length) throw new IndexOutOfBoundsException();
            return source.subSequence(offset + start, offset + end);
        }

        @Override public String toString() {
            char[] text = new char[length];
            for (int index = 0; index < length; index++) text[index] = charAt(index);
            return new String(text);
        }
    }
}

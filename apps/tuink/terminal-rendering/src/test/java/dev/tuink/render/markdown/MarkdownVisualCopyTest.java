package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellText;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDiagnostic;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MarkdownVisualCopyTest {
    @Test void ordinaryWrapsAndSplitWordsRejoinWithoutLosingConsumedSpaces() {
        var document = document(paragraph(text("left right abcdefghij tail", 0)));
        for (int width : List.of(1, 5, 8, 80)) {
            var ready = new MarkdownLayout(width).layout(document);
            assertEquals("left right abcdefghij tail", MarkdownVisualCopy.copyAll(ready));
            assertEquals("right abcdefghij", MarkdownVisualCopy.copy(ready, 5, 21));
        }
    }

    @Test void styledBoundariesNormalizeOnlyWhitespaceAndExplicitBreakRemainsHard() {
        var document = document(paragraph(text("a  ", 0),
            new MarkdownInline.Styled(MarkdownInline.Style.STRONG, List.of(text("b", 3)), range(3, 4)),
            text("\t c", 4), new MarkdownInline.Break(true, range(7, 8)), text("after", 8)));
        for (int width : List.of(2, 80)) {
            var ready = new MarkdownLayout(width).layout(document);
            assertEquals("a b c\nafter", MarkdownVisualCopy.copyAll(ready));
            assertEquals("\n", MarkdownVisualCopy.copy(ready, 7, 8));
            assertEquals("a b c\n", MarkdownVisualCopy.copy(ready, 0, 8));
        }
    }

    @Test void multilineInlineMathUsesPreparedGlyphsAndOneCommonBaselineAfterUnwrapping() {
        var calls = new AtomicInteger();
        var embedded = fraction(calls);
        var document = document(paragraph(text("left ", 0), math("fraction", 5), text(" right", 13)));
        for (int width : List.of(5, 9, 80)) {
            var ready = new MarkdownLayout(width, embedded).layout(document);
            int prepared = calls.get();
            assertEquals("      N\nleft ─── right\n      D", MarkdownVisualCopy.copyAll(ready));
            assertEquals(" N\n───\n D", MarkdownVisualCopy.copy(ready, 7, 8));
            assertEquals(prepared, calls.get(), "Copy must never invoke an embedded renderer");
        }
    }

    @Test void identicalCachedMathBoxesKeepDistinctOccurrenceSpans() {
        var shared = new MarkdownLayout.Box(List.of(" N ", "───", " D "), 1);
        var embedded = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String source) { return shared; }
        };
        var document = document(paragraph(math("same", 0), text(" middle ", 4), math("same", 12)));
        var ready = new MarkdownLayout(6, embedded).layout(document);
        assertEquals(" N\n───\n D", MarkdownVisualCopy.copy(ready, 13, 14));
        assertFalse(MarkdownVisualCopy.copy(ready, 13, 14).contains("middle"));
    }

    @Test void displayGeometryCopiesEveryPreparedRowAndKeepsAlignmentBeyondAnyViewport() {
        var calls = new AtomicInteger();
        var embedded = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String source) {
                calls.incrementAndGet(); return new MarkdownLayout.Box(List.of("  a  ", "─────", "  b  "), 1);
            }
            @Override public List<String> mermaid(String source, int width) {
                calls.incrementAndGet(); return List.of("┌───┐     ┌───┐", "│ A ├────→│ B │", "└───┘     └───┘");
            }
        };
        var document = document(new MarkdownBlock.Math("raw TeX", range(0, 7)),
            new MarkdownBlock.Mermaid("raw Mermaid", range(10, 21)));
        var ready = new MarkdownLayout(5, embedded).layout(document);
        assertEquals("  a  \n─────\n  b  \n\n┌───┐     ┌───┐\n│ A ├────→│ B │\n└───┘     └───┘", MarkdownVisualCopy.copyAll(ready));
        assertEquals("  a  \n─────\n  b  ", MarkdownVisualCopy.copy(ready, 1, 2));
        assertEquals(2, calls.get());
    }

    @Test void codeKeepsIndentationBlankRowsTrailingRowAndUsesDisplayTabStops() {
        var document = document(new MarkdownBlock.Code("\ta  \n\n b\n", "text", range(0, 11)));
        var ready = new MarkdownLayout(4).layout(document);
        assertEquals("[text]\n    a  \n\n b\n", MarkdownVisualCopy.copyAll(ready));
        assertEquals("    a", MarkdownVisualCopy.copy(ready, 0, 2).substring("[text]\n".length()));
        assertEquals("\n", MarkdownVisualCopy.copy(ready, 4, 5));
    }

    @Test void inlineAndQuotedCodeTabsUseTheirActualDisplayColumn() {
        var inline = document(paragraph(text("left ", 0), new MarkdownInline.Code("\tb", range(5, 7))));
        var inlineReady = new MarkdownLayout(80).layout(inline);
        assertEquals("left    b", MarkdownVisualCopy.copyAll(inlineReady));
        assertEquals("   ", MarkdownVisualCopy.copy(inlineReady, 5, 6));
        var standalone = document(paragraph(new MarkdownInline.Code("a\tb", range(0, 3))));
        assertEquals("   ", MarkdownVisualCopy.copy(new MarkdownLayout(80).layout(standalone), 1, 2));
        var quoted = document(new MarkdownBlock.Quote(List.of(new MarkdownBlock.Code("\tx", "", range(0, 2))), range(0, 2)));
        assertEquals("│   x", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(quoted)));
    }

    @Test void codeCopiesDisplayedControlPicturesWhileKeepingTabsAndSelectedOffsets() {
        String code = "abc\u001b[31m\tred\u007f";
        var ready = new MarkdownLayout(80).layout(document(new MarkdownBlock.Code(code, "text", range(0, code.length()))));
        assertEquals("[text]\nabc␛[31m    red␡", MarkdownVisualCopy.copyAll(ready));
        assertEquals("␛", MarkdownVisualCopy.copy(ready, 3, 4));
        assertEquals("    ", MarkdownVisualCopy.copy(ready, 8, 9));
    }

    @Test void flowUsesDisplayedGlyphsAfterComposingStyledGraphemes() {
        var plain = document(paragraph(text("a\u001b b", 0)));
        for (int width : List.of(1, 80)) {
            var ready = new MarkdownLayout(width).layout(plain);
            assertEquals("a␛ b", MarkdownVisualCopy.copyAll(ready));
            assertEquals("␛", MarkdownVisualCopy.copy(ready, 1, 2));
        }
        var joined = document(paragraph(text("a", 0),
            new MarkdownInline.Styled(MarkdownInline.Style.STRONG, List.of(text("\u0301", 1)), range(1, 2))));
        assertEquals("a\u0301", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(joined)));
        var inline = document(paragraph(new MarkdownInline.Code("\u001b\ttext", range(0, 6))));
        assertEquals("␛   text", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(inline)));
    }

    @Test void orphanAndOversizedGraphemesCopyTheirVisibleReplacements() {
        String oversized = "a" + "\u0301".repeat(256);
        String wide = "字" + "\u0301".repeat(256);
        var code = document(new MarkdownBlock.Code("\u0301\n" + oversized + "\n" + wide, "", range(0, 520)));
        assertEquals("◌\u0301\n�\n？", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(code)));
        var flow = document(paragraph(text("\u0301 " + oversized, 0)));
        assertEquals("◌\u0301 �", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(flow)));
    }

    @Test void tableAndEmbeddedGeometryAlsoCopyDisplayedControlPictures() {
        var cell = new MarkdownBlock.Cell(List.of(text("a\u001b", 0)), range(0, 2));
        var row = new MarkdownBlock.Row(false, List.of(cell), range(0, 2));
        var table = new MarkdownLayout(80).layout(document(new MarkdownBlock.Table(List.of(row), List.of(), range(0, 2))));
        assertTrue(MarkdownVisualCopy.copyAll(table).contains("a␛"));
        String selected = MarkdownVisualCopy.copy(table, 1, 2);
        assertTrue(selected.contains("␛"));
        assertFalse(selected.contains("\u001b"));
        var embedded = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String source) { return new MarkdownLayout.Box(List.of("\u001b"), 0); }
        };
        var math = new MarkdownLayout(80, embedded).layout(document(new MarkdownBlock.Math("x", range(0, 1))));
        assertEquals("␛", MarkdownVisualCopy.copyAll(math));
        var diagnostic = new MarkdownDocument(new DocumentId("copy"), 1, List.of(),
            List.of(new MarkdownDiagnostic("bad\u001b", range(0, 1))));
        assertEquals("[!] bad␛", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(diagnostic)));
    }

    @Test void displayExpansionCountsAgainstTheOutputBudget() {
        var ready = new MarkdownLayout(80).layout(document(new MarkdownBlock.Code("\u0301", "", range(0, 1))));
        assertEquals("◌\u0301", MarkdownVisualCopy.copyAll(ready, 2));
        assertThrows(MarkdownVisualCopy.LimitException.class, () -> MarkdownVisualCopy.copyAll(ready, 1));
    }

    @Test void quoteListTaskPrefixesStayVisualWithoutBeingRepeatedForOldSoftWraps() {
        var content = paragraph(text("one two three", 0));
        var quote = new MarkdownBlock.Quote(List.of(content), range(0, 20));
        var item = new MarkdownBlock.Item(List.of(quote), true, false, range(0, 20));
        var document = document(new MarkdownBlock.ListBlock(false, 1, List.of(item), range(0, 20)));
        for (int width : List.of(10, 80)) {
            var ready = new MarkdownLayout(width).layout(document);
            assertEquals("• [ ] │ one two three", MarkdownVisualCopy.copyAll(ready));
        }
    }

    @Test void fullTableCopiesPreparedFramesAndPartialSelectionKeepsColumnsWithoutLeakingOtherCells() {
        String emoji = "👩‍💻", cjk = "字";
        var left = new MarkdownBlock.Cell(List.of(text(emoji, 0)), range(0, emoji.length()));
        var right = new MarkdownBlock.Cell(List.of(text(cjk, 10)), range(10, 11));
        var row = new MarkdownBlock.Row(false, List.of(left, right), range(0, 11));
        var document = document(new MarkdownBlock.Table(List.of(row), List.of(), range(0, 11)));
        var ready = new MarkdownLayout(80).layout(document);
        var tableRows = ready.lines().stream().filter(line -> line.kind().startsWith("table")).map(MarkdownLayout.Line::text).toList();
        assertEquals(String.join("\n", tableRows), MarkdownVisualCopy.copyAll(ready));
        String partial = MarkdownVisualCopy.copy(ready, 1, 2);
        assertTrue(partial.contains(emoji));
        assertFalse(partial.contains(cjk));
        assertTrue(partial.startsWith("┌")); assertTrue(partial.endsWith("┘"));
        assertEquals(1, partial.lines().mapToInt(CellText::width).distinct().count());
    }

    @Test void partialNormalTextSnapsToCompleteGraphemes() {
        String value = "a👩‍💻e\u0301z";
        var ready = new MarkdownLayout(3).layout(document(paragraph(text(value, 0))));
        assertEquals("👩‍💻", MarkdownVisualCopy.copy(ready, 2, 3));
        assertEquals("e\u0301", MarkdownVisualCopy.copy(ready, 7, 8));
    }

    @Test void visibleOnlyCopyAllAndEmptySelectionHaveDistinctContracts() {
        var rule = new MarkdownLayout(7).layout(document(new MarkdownBlock.ThematicBreak(range(0, 3))));
        assertEquals("───────", MarkdownVisualCopy.copyAll(rule));
        var quote = new MarkdownLayout(7).layout(document(new MarkdownBlock.Quote(List.of(), range(0, 1))));
        assertEquals("│", MarkdownVisualCopy.copyAll(quote));
        assertEquals("", MarkdownVisualCopy.copy(rule, 0, 0));
        var code = new MarkdownLayout(7).layout(document(new MarkdownBlock.Code("", "text", range(0, 3))));
        assertEquals("[text]\n", MarkdownVisualCopy.copyAll(code));
        var diagnostic = new MarkdownDocument(new DocumentId("copy"), 1, List.of(),
            List.of(new MarkdownDiagnostic("problem", range(0, 1))));
        assertEquals("[!] problem", MarkdownVisualCopy.copyAll(new MarkdownLayout(80).layout(diagnostic)));
    }

    @Test void exportBudgetIsCheckedBeforeReturningAndCancellationRemainsCooperative() {
        var ready = new MarkdownLayout(3).layout(document(paragraph(text("left right", 0))));
        assertEquals("left right", MarkdownVisualCopy.copyAll(ready, 10));
        assertThrows(MarkdownVisualCopy.LimitException.class, () -> MarkdownVisualCopy.copyAll(ready, 9));
        assertEquals("", MarkdownVisualCopy.copy(ready, 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> MarkdownVisualCopy.copyAll(ready, -1));
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class, () -> MarkdownVisualCopy.copyAll(ready)); }
        finally { Thread.interrupted(); }
    }

    @Test void metadataIsCompactAndSharesOriginalTextRatherThanRetainingEveryWrappedWord() {
        String text = "word ".repeat(1000);
        var ready = new MarkdownLayout(10).layout(document(paragraph(text(text, 0))));
        assertEquals(1, ready.copySections().size());
        assertEquals(1, ready.copySections().get(0).pieces().size());
        assertSame(text, ready.copySections().get(0).pieces().get(0).box().lines().get(0));
        assertTrue(MarkdownVisualCopy.retainedBytes(ready) < 1024);
    }

    private static MarkdownLayout.EmbeddedRenderer fraction(AtomicInteger calls) {
        return new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String source) {
                calls.incrementAndGet(); return new MarkdownLayout.Box(List.of(" N ", "───", " D "), 1);
            }
        };
    }
    private static MarkdownInline.Text text(String value, int start) { return new MarkdownInline.Text(value, range(start, start + value.length())); }
    private static MarkdownInline.Math math(String expression, int start) { return new MarkdownInline.Math(expression, range(start, start + expression.length())); }
    private static MarkdownBlock.Paragraph paragraph(MarkdownInline... inlines) { return new MarkdownBlock.Paragraph(List.of(inlines), range(0, 100)); }
    private static MarkdownDocument document(MarkdownBlock... blocks) { return new MarkdownDocument(new DocumentId("copy"), 1, List.of(blocks), List.of()); }
    private static TextRange range(int start, int end) { return new TextRange(new TextPosition(0, start), new TextPosition(0, end)); }
}

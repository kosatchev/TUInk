package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import dev.tuink.render.markdown.MarkdownLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewSelectionTest {
    static final DocumentId ID = new DocumentId("preview-selection");
    static final TextRange SOURCE = new TextRange(new TextPosition(0, 0), new TextPosition(0, 1));

    @Test void clickingAndDraggingUseSemanticTextWithoutDecorativePrefix() {
        var result = result("alpha", line("│ alpha", 2, 7, 0, 5));
        var selection = new PreviewSelection();
        selection.selectAt(result, 0, 0, false);
        assertEquals(0, selection.caret());
        selection.selectAt(result, 0, 4, false);
        selection.selectAt(result, 0, 7, true);
        assertEquals("pha", selection.selectedText("alpha"));
    }

    @Test void graphemeMovementDoesNotSplitEmojiOrCombiningMarks() {
        String text = "a😀e\u0301z";
        var result = result(text, line(text, 0, text.length(), 0, text.length()));
        var selection = new PreviewSelection();
        selection.move(result, PreviewSelection.Move.RIGHT, false, 1);
        assertEquals(1, selection.caret());
        selection.move(result, PreviewSelection.Move.RIGHT, false, 1);
        assertEquals(3, selection.caret());
        selection.move(result, PreviewSelection.Move.RIGHT, false, 1);
        assertEquals(5, selection.caret());
        selection.move(result, PreviewSelection.Move.LEFT, true, 1);
        assertEquals("e\u0301", selection.selectedText(text));
    }

    @Test void decoratedTableBorderFindsNearestContentAndCopyHasNoFrame() {
        var border = new MarkdownLayout.Line("┌─────┐", List.of(new MarkdownLayout.Run(0, 7,
            MarkdownLayout.Role.TABLE, SOURCE)), "", SOURCE, "table-border");
        var result = result("alpha", border, line("│alpha│", 1, 6, 0, 5));
        var selection = new PreviewSelection();
        selection.selectAt(result, 0, 4, false);
        assertEquals(0, selection.caret());
        selection.selectAll("alpha");
        assertEquals("alpha", selection.selectedText("alpha"));
    }

    @Test void formulasAreAtomicAndCopyRetainsTheExactTex() {
        String source = "\\frac{a}{b}";
        var runs = List.of(new MarkdownLayout.Run(0, 3, MarkdownLayout.Role.MATH, SOURCE, 0, source.length()));
        var result = result(source, new MarkdownLayout.Line(" a ", runs, "", SOURCE, "math"),
            new MarkdownLayout.Line("───", runs, "", SOURCE, "math"),
            new MarkdownLayout.Line(" b ", runs, "", SOURCE, "math"));
        var selection = new PreviewSelection();
        selection.selectAt(result, 1, 0, false);
        selection.move(result, PreviewSelection.Move.RIGHT, true, 1);
        assertEquals(source.length(), selection.caret());
        assertEquals(source, selection.selectedText(source));
    }

    @Test void verticalMovementVisitsEveryVisualRowOfAnAtomicFormula() {
        String source = "x^2";
        var run = List.of(new MarkdownLayout.Run(0, 2, MarkdownLayout.Role.MATH, SOURCE, 0, source.length()));
        var result = result(source + "\nnext", new MarkdownLayout.Line(" 2", run, "", SOURCE, "math"),
            new MarkdownLayout.Line("x ", run, "", SOURCE, "math"), line("next", 0, 4, 4, 8));
        var selection = new PreviewSelection();
        selection.move(result, PreviewSelection.Move.DOWN, false, 1);
        assertEquals(new PreviewSelection.VisualPosition(1, 0), selection.visualPosition(result));
        assertEquals(0, selection.caret());
        selection.move(result, PreviewSelection.Move.DOWN, false, 1);
        assertEquals(new PreviewSelection.VisualPosition(2, 0), selection.visualPosition(result));
        assertEquals(4, selection.caret());
    }

    @Test void horizontalCaretCanVisitTheRightHandSideOfAnAtomicDiagram() {
        String source = "flowchart LR\n A-->B";
        String display = "─".repeat(100) + "→ Target";
        var diagram = new MarkdownLayout.Line(display, List.of(new MarkdownLayout.Run(0, display.length(),
            MarkdownLayout.Role.DIAGRAM, SOURCE, 0, source.length())), "", SOURCE, "mermaid");
        var result = result(source, diagram);
        var selection = new PreviewSelection();
        for (int i = 0; i < 101; i++) selection.move(result, PreviewSelection.Move.RIGHT, false, 1);
        assertEquals(new PreviewSelection.VisualPosition(0, 101), selection.visualPosition(result));
        assertEquals(0, selection.targetOffset(result), "Source actions must still target this Mermaid block");
        selection.move(result, PreviewSelection.Move.RIGHT, true, 1);
        assertEquals(source, selection.selectedText(source), "Copy selection keeps the semantic atom intact");
    }

    @Test void visualClicksAndPanSnapToWideGraphemeBoundaries() {
        String text = "字😀e\u0301";
        var result = result(text, line(text, 0, text.length(), 0, text.length()));
        var selection = new PreviewSelection();
        selection.selectAtColumn(result, 0, 1, false);
        assertEquals(new PreviewSelection.VisualPosition(0, 2), selection.visualPosition(result));
        assertEquals(1, selection.caret());
        selection.pan(result, 0, 1);
        assertEquals(new PreviewSelection.VisualPosition(0, 4), selection.visualPosition(result));
        assertEquals(3, selection.caret());
        selection.move(result, PreviewSelection.Move.LEFT, false, 1);
        assertEquals(new PreviewSelection.VisualPosition(0, 2), selection.visualPosition(result));
        assertEquals(1, selection.caret());
    }

    @Test void selectionSurvivesRewrappingBecauseItUsesProjectionOffsets() {
        var selection = new PreviewSelection();
        selection.selectRange("alpha beta gamma", 6, 10);
        var narrow = result("alpha beta gamma", line("alpha", 0, 5, 0, 5), line("beta", 0, 4, 6, 10),
            line("gamma", 0, 5, 11, 16));
        selection.reconcile(narrow.document().projection().text());
        assertEquals("beta", selection.selectedText(narrow.document().projection().text()));
        assertEquals(new PreviewSelection.Position(1, 0), PreviewSelection.position(narrow, 6));
    }

    @Test void leftAndRightCollapseASelectionBeforeMovingFurther() {
        var result = result("alpha", line("alpha", 0, 5, 0, 5));
        var selection = new PreviewSelection();
        selection.selectRange("alpha", 1, 4);
        selection.move(result, PreviewSelection.Move.LEFT, false, 1);
        assertEquals(new PreviewSelection.Snapshot(1, 1), selection.snapshot());
        selection.selectRange("alpha", 1, 4);
        selection.move(result, PreviewSelection.Move.RIGHT, false, 1);
        assertEquals(new PreviewSelection.Snapshot(4, 4), selection.snapshot());
    }

    static MarkdownLayout.Line line(String display, int start, int end, int projectionStart, int projectionEnd) {
        return new MarkdownLayout.Line(display, List.of(new MarkdownLayout.Run(start, end,
            MarkdownLayout.Role.TEXT, SOURCE, projectionStart, projectionEnd)), display, SOURCE, "paragraph");
    }
    static MarkdownLayout.Result result(String projection, MarkdownLayout.Line... lines) {
        return result(0, 20, projection, lines);
    }
    static MarkdownLayout.Result result(long revision, int columns, String projection, MarkdownLayout.Line... lines) {
        var document = new MarkdownDocument(ID, revision, List.of(), List.of(), new TextProjection(projection, List.of()));
        return new MarkdownLayout.Result(document, List.of(lines), columns,
            List.of(lines).stream().mapToInt(line -> dev.tuiframe.cell.CellText.width(line.text())).max().orElse(0));
    }
}

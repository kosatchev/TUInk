package dev.tuink.render.surface;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.core.*;
import dev.tuink.editor.document.*;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.markdown.*;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.memory.RenderMemoryBudget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextSurfaceAdapterParityTest {
    private static final TextRange SOURCE = new TextRange(new TextPosition(0, 0), new TextPosition(0, 1));

    @Test void bothContentAdaptersFollowTheSameNavigationTargetsAcrossShortUnicodeRows() {
        List<String> text = List.of("A界abcdef", "x", "A界abcdef");
        TextModel model = new TextModel(new SimpleLineBuffer()); model.replaceLines(text);
        var source = new EditorViewCoordinates(new TerminalEditorTextLayout(model), 30, false, false, 4);
        var result = plain(text);
        var budget = RenderMemoryBudget.bounded(1L << 20);
        try (var metrics = new MarkdownGeometry(result, budget)) {
            var preview = new MarkdownTextSurface(result, metrics);
            Position sourcePosition = new Position(0, 6);
            var sourceVisual = source.visualPosition(sourcePosition);
            var previewVisual = preview.visualPosition(6);
            int sourcePreferred = -1, previewPreferred = -1;
            for (var move : List.of(TextSurfaceInteraction.Move.DOWN, TextSurfaceInteraction.Move.DOWN,
                TextSurfaceInteraction.Move.UP, TextSurfaceInteraction.Move.UP)) {
                var sourceMove = TextSurfaceInteraction.move(source, sourceVisual, move, 1, sourcePreferred);
                var previewMove = TextSurfaceInteraction.move(preview, previewVisual, move, 1, previewPreferred);
                sourceVisual = sourceMove.visual(); previewVisual = previewMove.visual();
                sourcePreferred = sourceMove.preferredColumn(); previewPreferred = previewMove.preferredColumn();
                sourcePosition = source.positionAt(sourceVisual);
                int projected = preview.positionAt(previewVisual);
                int expected = sourcePosition.col();
                for (int row = 0; row < sourcePosition.line(); row++) expected += text.get(row).length() + 1;
                assertEquals(expected, projected, "semantic navigation is independent of the content adapter");
            }
            assertEquals(new Position(0, 6), sourcePosition);
            assertEquals(6, preview.positionAt(previewVisual));
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void visualAffinityVisitsAllRowsOfAnAtomWithTheSameSemanticTargetUnderCacheRefusal() {
        String semantic = "\\frac{a}{b}";
        var run = List.of(new MarkdownLayout.Run(0, 3, MarkdownLayout.Role.MATH, SOURCE, 0, semantic.length()));
        var lines = List.of(new MarkdownLayout.Line(" a ", run, "", SOURCE, "math"),
            new MarkdownLayout.Line("───", run, "", SOURCE, "math"),
            new MarkdownLayout.Line(" b ", run, "", SOURCE, "math"));
        var result = new MarkdownLayout.Result(document(semantic), lines, 20, 3);
        var refused = RenderMemoryBudget.bounded(1);
        try (var metrics = new MarkdownGeometry(result, refused)) {
            var preview = new MarkdownTextSurface(result, metrics);
            var first = TextSurfaceInteraction.move(preview, new TextSurfaceInteraction.Visual(0, 1),
                TextSurfaceInteraction.Move.DOWN, 1, -1);
            var second = TextSurfaceInteraction.move(preview, first.visual(), TextSurfaceInteraction.Move.DOWN,
                1, first.preferredColumn());
            assertEquals(1, first.visual().row()); assertEquals(2, second.visual().row());
            assertEquals(0, preview.positionAt(first.visual()));
            assertEquals(0, preview.positionAt(second.visual()));
            assertEquals(semantic.length(), TextSurfaceInteraction.target(preview, preview, second.visual(), true, true));
            assertEquals(0, TextSurfaceInteraction.target(preview, preview, first.visual(), true, false));
        }
        assertEquals(0, refused.usedBytes());
    }

    private static MarkdownLayout.Result plain(List<String> text) {
        var lines = new ArrayList<MarkdownLayout.Line>();
        int start = 0;
        for (String line : text) {
            var run = new MarkdownLayout.Run(0, line.length(), MarkdownLayout.Role.TEXT, SOURCE, start, start + line.length());
            lines.add(new MarkdownLayout.Line(line, List.of(run), line, SOURCE, "paragraph"));
            start += line.length() + 1;
        }
        return new MarkdownLayout.Result(document(String.join("\n", text)), lines, 30,
            text.stream().mapToInt(CellText::width).max().orElse(0));
    }
    private static MarkdownDocument document(String text) {
        return new MarkdownDocument(new DocumentId("shared-surface"), 0, List.of(), List.of(), new TextProjection(text, List.of()));
    }
}

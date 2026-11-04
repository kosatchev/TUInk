package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.PreviewSelectionTest.*;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PreviewSelectionGeometryTest {
    @Test void warmedMovementAndHitTestingReuseBothVisualAndSemanticGeometry() {
        String text = "界e\u0301😀\t".repeat(400);
        var result = result(text, line(text, 0, text.length(), 0, text.length()));
        var budget = RenderMemoryBudget.bounded(2L << 20);
        var selection = new PreviewSelection();
        try (var geometry = new MarkdownGeometry(result, budget)) {
            selection.bindGeometry(geometry);
            for (int index = 0; index < 100; index++) selection.move(result, PreviewSelection.Move.RIGHT, index % 2 == 0, 10);
            for (int index = 0; index < 100; index++) selection.selectAtColumn(result, 0, index, false);
            assertEquals(1, geometry.misses());
            assertEquals(1, geometry.projectionMetricsMisses());
            assertEquals(1, geometry.projectionLineScans());
            assertTrue(geometry.hits() > 100);
            selection.releaseIndex();
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void admittedAndStreamingNavigationHaveTheSameUnicodeAndAtomicSelectionRules() {
        String atom = "\\frac{a}{b}";
        var mathRuns = List.of(new MarkdownLayout.Run(0, 3, MarkdownLayout.Role.MATH, SOURCE, 6, 6 + atom.length()));
        var result = result("a😀e\u0301\n" + atom + "\nlast",
            line("a😀e\u0301", 0, 5, 0, 5),
            new MarkdownLayout.Line(" a ", mathRuns, "", SOURCE, "math"),
            new MarkdownLayout.Line("───", mathRuns, "", SOURCE, "math"),
            new MarkdownLayout.Line(" b ", mathRuns, "", SOURCE, "math"),
            line("last", 0, 4, 7 + atom.length(), 11 + atom.length()));
        var budget = RenderMemoryBudget.bounded(1L << 20);
        var refusal = RenderMemoryBudget.bounded(1);
        try (var indexed = new MarkdownGeometry(result, budget); var streaming = new MarkdownGeometry(result, refusal)) {
            var a = new PreviewSelection(); a.bindGeometry(indexed);
            var b = new PreviewSelection(); b.bindGeometry(streaming);
            var random = new Random(42);
            var moves = PreviewSelection.Move.values();
            for (int index = 0; index < 300; index++) {
                var move = moves[random.nextInt(moves.length)]; boolean extend = random.nextBoolean();
                a.move(result, move, extend, 2); b.move(result, move, extend, 2);
                assertEquals(a.snapshot(), b.snapshot());
                assertEquals(a.visualPosition(result), b.visualPosition(result));
                assertEquals(a.targetOffset(result), b.targetOffset(result));
                assertEquals(a.selectedText(result.document().projection().text()), b.selectedText(result.document().projection().text()));
            }
            a.releaseIndex(); b.releaseIndex();
        }
        assertEquals(0, budget.usedBytes()); assertEquals(0, refusal.usedBytes());
    }

    @Test void repeatedHitsInManyDecorativeRowsReuseTheirNearestContentMapping() {
        var lines = new ArrayList<MarkdownLayout.Line>();
        lines.add(line("alpha", 0, 5, 0, 5));
        var decoration = new MarkdownLayout.Line("─", List.of(), "", SOURCE, "space");
        for (int index = 1; index < 10_000; index++) lines.add(decoration);
        lines.add(line("beta", 0, 4, 6, 10));
        var result = result("alpha\nbeta", lines.toArray(MarkdownLayout.Line[]::new));
        var budget = RenderMemoryBudget.bounded(1L << 20);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            var selection = new PreviewSelection(); selection.bindGeometry(geometry);
            for (int index = 0; index < 100; index++) {
                selection.selectAtColumn(result, 5000, 0, false);
                assertEquals(6, selection.targetOffset(result));
            }
            assertEquals(1, geometry.mappingBuilds());
            selection.releaseIndex();
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void anUnchangedRefreshKeepsThePreferredColumnAfterWideGraphemeSnapping() {
        var result = result("abc\n界x\nabc", line("abc", 0, 3, 0, 3),
            line("界x", 0, 2, 4, 6), line("abc", 0, 3, 7, 10));
        var input = new FrozenDocumentSource(new DocumentSnapshot(ID, 0, List.of("abc", "界x", "abc")),
            DocumentFileMetadata.newDocument(3), ResourceBinding.unbound());
        var bounds = new Rect(0, 1, 21, 6);
        try (var service = new MarkdownPreviewService(() -> {}); var state = new MarkdownPreviewState()) {
            assertTrue(state.initialize(input, 1, service));
            state.active = true; state.result = result; state.requestedId = ID; state.requestedRevision = 0;
            state.requestedWidth = 20; state.bounds = bounds;
            state.geometry = new MarkdownGeometry(result, service.renderMemoryBudget());
            state.selection.bindGeometry(state.geometry);
            state.selectAt(0, 1, false);
            state.moveSelection(PreviewSelection.Move.DOWN, false);
            assertEquals(new PreviewSelection.VisualPosition(1, 2), state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret());
            state.refresh(input, 1, bounds, service);
            state.moveSelection(PreviewSelection.Move.DOWN, false);
            assertEquals(new PreviewSelection.VisualPosition(2, 1), state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret());
        }
    }
}

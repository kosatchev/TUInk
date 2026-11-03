package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.render.PreparedRowCache;
import dev.tuiframe.render.PreparedStyledRow;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreviewRenderOwnershipTest {
    private static final String TEXT = "e\u0301\t界x";
    private static final long METRIC_BYTES = 1024L + 32L * TEXT.length();
    private static final long ROW_BYTES = PreparedStyledRow.estimatedBytes(TEXT.length(), 0);
    private static final int ROW_COUNT = 32;

    @Test void allRenderCachesShareTheLayoutPoolAndOnlyFrameDependenciesSurviveRetirement() {
        var result = result(0);
        var budget = budget();
        var input = new FrozenDocumentSource(new DocumentSnapshot(result.document().documentId(), 0,
            List.of("head", TEXT, "last")), DocumentFileMetadata.newDocument(3), ResourceBinding.unbound());
        var source = budget.source(input);
        var layout = budget.result(result);
        long initialLayout = budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT);
        var geometry = new MarkdownGeometry(result, budget.renderMemoryBudget());
        var visual = geometry.acquire(0);
        var frame = prepare(geometry, visual, 1, CellStyle.DEFAULT);
        assertNotNull(frame);
        assertEquals(5, geometry.projectionFloor(6));
        assertEquals(2, geometry.nearestContentRow(1));
        assertEquals(initialLayout + METRIC_BYTES * 2 + ROW_BYTES + 128L + 4L * ROW_COUNT,
            budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
        assertEquals(1, geometry.misses());
        assertEquals(1, geometry.projectionMetricsMisses());
        assertEquals(1, geometry.mappingBuilds());
        assertEquals(1, geometry.visualMetricsBuilds());
        assertEquals(1, geometry.semanticMetricsBuilds());
        assertEquals(1, geometry.preparedRowsMisses());
        assertEquals(METRIC_BYTES * 2 + ROW_BYTES + 128L + 4L * ROW_COUNT, geometry.retainedBytes());

        visual.close();
        geometry.close();
        layout.close();
        source.close();
        assertEquals(0, budget.usedBytes(MarkdownPreviewBudget.Kind.SOURCE));
        assertEquals(0, budget.usedBytes(MarkdownPreviewBudget.Kind.MODEL));
        assertEquals(METRIC_BYTES + ROW_BYTES, budget.usedBytes());
        assertEquals(METRIC_BYTES + ROW_BYTES, geometry.retainedBytes());
        assertEquals(TEXT, frame.row().metrics().text());
        budget.close();
        var completedFrame = frame.retain();
        frame.close();
        assertEquals(METRIC_BYTES + ROW_BYTES, budget.usedBytes());
        completedFrame.close();
        assertEquals(0, budget.usedBytes());
        assertEquals(0, geometry.retainedBytes());
    }

    @Test void competingLayoutOwnersRefusePreparedRowsUntilTheirActualReservationEnds() {
        var budget = budget();
        var result = result(0);
        try (budget; var layout = budget.result(result);
             var geometry = new MarkdownGeometry(result, budget.renderMemoryBudget());
             var visual = geometry.acquire(0)) {
            assertEquals(5, geometry.projectionFloor(6));
            assertEquals(2, geometry.nearestContentRow(1));
            long admitted = budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT);
            var competing = budget.renderMemoryBudget().tryReserve(64_000 - admitted);
            assertNotNull(competing);
            assertNull(prepare(geometry, visual, 1, CellStyle.DEFAULT));
            assertEquals(0, geometry.rows().size());
            assertEquals(1, geometry.preparedRowsMisses());
            assertEquals(0, geometry.rows().metricsBuilds());
            assertEquals(64_000, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
            assertEquals(7, geometry.visualWidth(0));
            assertEquals(5, geometry.projectionFloor(6));
            assertEquals(2, geometry.nearestContentRow(1));
            assertEquals(64_000, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
            competing.close();
            try (var frame = prepare(geometry, visual, 1, CellStyle.DEFAULT)) {
                assertNotNull(frame);
                assertEquals(admitted + ROW_BYTES, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
                assertEquals(TEXT, frame.row().metrics().text());
                try (var reused = prepare(geometry, visual, 1, CellStyle.DEFAULT)) {
                    assertSame(frame.row(), reused.row());
                    assertEquals(1, geometry.preparedRowsHits());
                    assertEquals(2, geometry.preparedRowsMisses());
                    assertEquals(1, geometry.visualMetricsBuilds());
                }
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void reflowAndStyleChurnRetainOnlyTheThreeFramesStillOwnedByThePresenter() {
        var budget = budget();
        var frames = new ArrayDeque<PreparedRowCache.Handle>();
        try (budget) {
            for (int generation = 0; generation < 80; generation++) {
                var result = result(generation);
                var style = generation % 2 == 0 ? CellStyle.DEFAULT
                    : new CellStyle(CellColor.CYAN, CellColor.DEFAULT, true, false);
                try (var layout = budget.result(result);
                     var geometry = new MarkdownGeometry(result, budget.renderMemoryBudget());
                     var visual = geometry.acquire(0)) {
                    geometry.projectionFloor(6);
                    geometry.nearestContentRow(1);
                    var frame = prepare(geometry, visual, generation, style);
                    assertNotNull(frame);
                    frames.addLast(frame);
                    if (frames.size() > 3) frames.removeFirst().close();
                    assertEquals(style, frame.row().baseStyle(0));
                    assertTrue(budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT) <= 64_000);
                    assertTrue(budget.usedBytes() <= 96_000);
                }
                assertEquals(frames.size() * (METRIC_BYTES + ROW_BYTES), budget.usedBytes());
                for (var frame : frames) assertEquals(TEXT, frame.row().metrics().text());
            }
        } finally {
            for (var frame : frames) frame.close();
        }
        assertEquals(0, budget.usedBytes());
    }

    private static PreparedRowCache.Handle prepare(MarkdownGeometry geometry,
            dev.tuiframe.text.TerminalTextLayoutCache.MetricsHandle visual, long styleRevision, CellStyle style) {
        var key = new PreparedStyledRow.Key(geometry.identity(), geometry.identity(), geometry.revision(), styleRevision,
            0, 0, TEXT.length(), 4, 0);
        return geometry.rows().acquire(key, visual, 0, TEXT.length(), style, List.of());
    }

    private static MarkdownPreviewBudget budget() {
        return new MarkdownPreviewBudget(new MarkdownPreviewBudget.Limits(96_000, 16_000, 16_000, 64_000));
    }

    private static MarkdownLayout.Result result(int generation) {
        var document = new MarkdownDocument(new DocumentId("render-ownership"), generation, List.of(), List.of(),
            new TextProjection("head\n" + TEXT + "\nlast", List.of()));
        var lines = new ArrayList<MarkdownLayout.Line>();
        for (int row = 0; row < ROW_COUNT; row++) lines.add(row % 2 == 0
            ? new MarkdownLayout.Line(TEXT, List.of(new MarkdownLayout.Run(0, TEXT.length(), MarkdownLayout.Role.TEXT,
                null, 5, 5 + TEXT.length())), TEXT, null, "text")
            : new MarkdownLayout.Line("─", List.of(), "", null, "space"));
        return new MarkdownLayout.Result(document, lines, 40 + generation, 7);
    }
}

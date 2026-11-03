package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.text.TerminalText;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownProjectionGeometryTest {
    @Test void localBoundaryQueriesReuseOneAdmittedSemanticLineWithoutRescanningItsPrefix() {
        String text = "start\n" + "e\u0301界x".repeat(2000) + "\nend";
        var budget = RenderMemoryBudget.bounded(1L << 20);
        try (var geometry = new MarkdownGeometry(result(text, List.of()), budget)) {
            for (int index = 0; index < 100; index++) {
                int start = 6 + index * 4;
                assertEquals(start, geometry.projectionFloor(start + 1));
                assertEquals(start + 2, geometry.projectionCeil(start + 1));
            }
            assertEquals(1, geometry.projectionLineScans());
            assertEquals(1, geometry.projectionMetricsMisses());
            assertTrue(geometry.retainedBytes() > 0);
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void refusedSemanticAdmissionUsesConstantSpaceAndRetainsOnlyLineCoordinates() {
        var budget = RenderMemoryBudget.bounded(1);
        try (var geometry = new MarkdownGeometry(result("header\ne\u0301界x\nend", List.of()), budget)) {
            for (int index = 0; index < 20; index++) {
                assertEquals(7, geometry.projectionFloor(8));
                assertEquals(9, geometry.projectionCeil(8));
            }
            assertEquals(1, geometry.projectionLineScans());
            assertEquals(0, geometry.retainedBytes());
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void exactNewlineAndEofBoundariesMatchLineLocalUnicodeRules() {
        String text = "x\r\ne\u0301z\nend";
        for (long limit : List.of(1L, 100_000L)) {
            var budget = RenderMemoryBudget.bounded(limit);
            try (var geometry = new MarkdownGeometry(result(text, List.of()), budget)) {
                for (int offset = 0; offset <= text.length(); offset++) {
                    int start = text.lastIndexOf('\n', Math.max(-1, offset - 1)) + 1;
                    int end = text.indexOf('\n', offset);
                    if (end < 0) end = text.length();
                    var reference = TerminalText.metrics(text.substring(start, end), 4);
                    assertEquals(start + reference.floorBoundary(offset - start), geometry.projectionFloor(offset));
                    assertEquals(start + reference.ceilBoundary(offset - start), geometry.projectionCeil(offset));
                }
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void decorativeRowsUseOneAdmittedMappingAndPreserveForwardTieBreaking() {
        var content = new MarkdownLayout.Line("a", List.of(new MarkdownLayout.Run(0, 1,
            MarkdownLayout.Role.TEXT, null, 0, 1)), "a", null, "text");
        var decoration = new MarkdownLayout.Line("─", List.of(), "", null, "space");
        var result = result("a", List.of(content, decoration, content));
        for (long limit : List.of(1L, 100_000L)) {
            var budget = RenderMemoryBudget.bounded(limit);
            try (var geometry = new MarkdownGeometry(result, budget)) {
                for (int index = 0; index < 100; index++) assertEquals(2, geometry.nearestContentRow(1));
                assertEquals(limit == 1 ? 0 : 1, geometry.mappingBuilds());
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    private static MarkdownLayout.Result result(String projection, List<MarkdownLayout.Line> lines) {
        var document = new MarkdownDocument(new DocumentId("projection-geometry"), 1, List.of(), List.of(),
            new TextProjection(projection, List.of()));
        return new MarkdownLayout.Result(document, lines, 40, 40);
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.tui.style.UiStyles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MarkdownPreviewStableInteractionTest {
    @Test void stableRefreshNavigationSelectionAndPaintingReuseAcceptedLayoutAndCodeStyles() throws Exception {
        var parses = new AtomicInteger();
        var wakeups = new AtomicInteger();
        // Existing worker contract wakes the UI at consumer completion and after releasing its input.
        // Await both so a late initial completion cannot be mistaken for interaction work.
        var initialWorkFinished = new CountDownLatch(2);
        var parser = new CommonmarkMarkdownParser();
        var input = source();
        var bounds = new Rect(2, 1, 50, 12);
        var service = new MarkdownPreviewService((snapshot, limits, cancellation) -> {
            parses.incrementAndGet();
            return parser.parse(snapshot, limits, cancellation);
        }, () -> { wakeups.incrementAndGet(); initialWorkFinished.countDown(); });
        try (service; var state = new MarkdownPreviewState()) {
            assertTrue(state.activate(input, 0, bounds, service));
            assertNotNull(state.pending);
            state.pending.get(3, TimeUnit.SECONDS);
            assertTrue(initialWorkFinished.await(3, TimeUnit.SECONDS));
            assertTrue(state.collect(input.text().documentId(), input.text().revision()));
            assertTrue(state.ready());
            var accepted = state.result;
            var geometry = state.geometry;
            var styledCode = accepted.lines().stream().filter(line -> line.kind().equals("code") && !line.syntax().isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("Initial layout must include real language-highlighted code"));
            var codeStyles = styledCode.syntax();
            int initialWakeups = wakeups.get();
            assertEquals(1, parses.get());
            assertEquals(1, service.cachedModelCount());
            assertTrue(accepted.lines().size() > bounds.height());
            var canvas = new Canvas(new CellBuffer(16, 60));
            var palette = UiStyles.forTheme(UiStyles.Theme.CYAN);
            long firstPassBuilds = -1;
            for (int pass = 0; pass < 2; pass++) {
                state.scroll(0, Integer.MIN_VALUE);
                state.moveSelection(PreviewSelection.Move.START, false);
                for (int turn = 0; turn < 48; turn++) {
                    state.moveSelection(PreviewSelection.Move.DOWN, turn % 2 == 0);
                    state.scroll(3, turn % 2 == 0 ? 1 : -1);
                    var content = state.contentBounds();
                    assertFalse(content.isEmpty());
                    state.pointer(pointer(content.y(), content.x() + 1, PointerEvent.Kind.PRESS));
                    state.pointer(pointer(content.y() + 1, content.x() + 4, PointerEvent.Kind.DRAG));
                    state.pointer(pointer(content.y() + 1, content.x() + 4, PointerEvent.Kind.RELEASE));
                    // This is the same stable-document refresh performed by the window before drawing.
                    state.refresh(input, 0, bounds, service);
                    MarkdownPreviewPainter.paint(canvas, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT), palette, true);
                    assertNull(state.pending, "Interaction must not submit another parse/layout/highlight task");
                    assertSame(accepted, state.result);
                    assertSame(geometry, state.geometry);
                    assertSame(codeStyles, styledCode.syntax());
                    assertEquals(1, parses.get());
                    assertEquals(initialWakeups, wakeups.get(), "No additional worker completion may occur");
                }
                if (pass == 0) firstPassBuilds = geometry.visualMetricsBuilds();
                else assertEquals(firstPassBuilds, geometry.visualMetricsBuilds(),
                    "Repeating the same traversal must reuse bounded per-row geometry");
            }
            long acceptedMetrics = geometry.visualMetricsBuilds();
            for (var theme : List.of(UiStyles.Theme.CYAN, UiStyles.Theme.MONOCHROME, UiStyles.Theme.BLUE, UiStyles.Theme.CYAN)) {
                state.refresh(input, 0, bounds, service);
                MarkdownPreviewPainter.paint(canvas, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT),
                    UiStyles.forTheme(theme), true);
                assertNull(state.pending, "Palette changes must not submit parse/layout/highlight work");
                assertSame(accepted, state.result);
                assertSame(geometry, state.geometry);
                assertSame(codeStyles, styledCode.syntax());
                assertEquals(1, parses.get());
                assertEquals(initialWakeups, wakeups.get());
            }
            assertEquals(acceptedMetrics, geometry.visualMetricsBuilds(), "Theme round trips reuse accepted row geometry");
            assertFalse(state.pointerCaptureActive());
        }
        assertEquals(0, service.retainedBytes(), "Closing the presentation and worker releases their shared ledger");
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of());
    }

    private static FrozenDocumentSource source() {
        var lines = new ArrayList<String>();
        lines.addAll(List.of("# Stable preview", "", "```java"));
        for (int row = 0; row < 80; row++)
            lines.add("public static final String name" + row + " = \"界😀e\u0301\"; // highlighted code");
        lines.addAll(List.of("```", "", "| A | B |", "|---|---|", "| alpha | beta |", "",
            "$$", "\\frac{a}{b} + x^2", "$$", "", "```mermaid", "flowchart TD", " A-->B", "```"));
        var snapshot = new DocumentSnapshot(new DocumentId("stable-preview-interaction"), 0, lines);
        return new FrozenDocumentSource(snapshot, DocumentFileMetadata.newDocument(lines.size()), ResourceBinding.unbound());
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.PreviewSelectionTest.*;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuink.editor.storage.BomKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreviewStatusTest {
    @Test void liveFooterContainsReadingPositionShortcutAndDocumentMetadata() {
        String text = status(placement(false, "", List.of(), -1), false, BomKind.NONE, 120);
        assertTrue(text.contains("Preview (live)"));
        assertTrue(text.contains("Ctrl+P: Source"));
        assertTrue(text.contains("Row 2/3 Col 6"));
        assertFalse(text.contains("READ"), "Preview mode already conveys read-only content");
        assertTrue(text.contains("ENC:UTF-8"));
        assertTrue(text.contains("Tab 2/2"));
        assertTrue(CellText.width(text) <= 120);
    }

    @Test void searchSummaryStaysInTheFooterAfterTheQueryFieldCloses() {
        var matches = List.of(new PreviewSelection.Range(0, 5));
        String text = status(placement(false, "needle中😀", matches, 0), true, BomKind.UTF_8, 160);
        assertTrue(text.contains("Preview (snapshot)"));
        assertTrue(text.contains("Find 1/1"));
        assertTrue(text.contains("needle中😀"));
        assertTrue(text.contains("+BOM"));
    }

    @Test void longQueryCompactsToItsMatchCountBeforeMandatoryFields() {
        var matches = List.of(new PreviewSelection.Range(0, 5));
        for (int width : List.of(80, 120)) {
            String text = status(placement(false, "中😀".repeat(100), matches, 0), false, BomKind.NONE, width);
            assertTrue(text.contains("Find 1/1"), text);
            assertTrue(text.contains("Tab 2/2  Ctrl+P: Source"), text);
            assertTrue(text.contains("Row 2/3 Col 6"), text);
            assertTrue(text.contains("ENC:UTF-8"), text);
            assertEquals(width, CellText.width(text));
        }
    }

    @Test void compactFooterRetainsTheSwitchShortcutAndClipsByTerminalCells() {
        for (int width : List.of(0, 1, 12, 24, 32, 60, 80)) {
            String text = status(placement(false, "中😀".repeat(100), List.of(), -1), true, BomKind.NONE, width);
            assertTrue(CellText.width(text) <= width, text);
            assertFalse(text.endsWith("\ud83d"));
            if (width >= 12) assertTrue(text.contains("Ctrl+P"), text);
        }
    }

    @Test void wideCoordinateGrowthKeepsTabsShortcutAndEncodingInBothModes() {
        for (boolean snapshot : List.of(false, true)) {
            String line = "x".repeat(400);
            var lines = new dev.tuink.render.markdown.MarkdownLayout.Line[123];
            for (int i = 0; i < lines.length; i++) lines[i] = line(line, 0, 400, i * 401, i * 401 + 400);
            var layout = result(0, 80, String.join("\n", java.util.Collections.nCopies(123, line)), lines);
            var initial = new MarkdownPreviewState.Placement(new Rect(0, 2, 80, 20), layout, 0, 0, false, "",
                PreviewSelection.Snapshot.EMPTY, List.of(), -1, "", false, new PreviewSelection.VisualPosition(1, 5), null);
            var moved = new MarkdownPreviewState.Placement(initial.bounds(), initial.result(), 0, 0, false, "",
                initial.selection(), List.of(), -1, "", false, new PreviewSelection.VisualPosition(103, 338), null);
            String before = status(initial, snapshot, BomKind.NONE, 80);
            String after = status(moved, snapshot, BomKind.NONE, 80);
            assertTrue(after.contains("Tab 2/2  Ctrl+P: Source"), after);
            assertTrue(after.contains("Row 104/123 Col 339"), after);
            assertTrue(after.contains("Preview (" + (snapshot ? "snapshot" : "live") + ")"), after);
            assertEquals(before.indexOf("ENC:"), after.indexOf("ENC:"));
            assertTrue(after.endsWith("ENC:UTF-8 "), after);
        }
    }

    @Test void compactFooterKeepsModeTabAndShortcutWithoutPartialMetadata() {
        for (boolean snapshot : List.of(false, true)) {
            for (int width : List.of(35, 45)) {
                String text = status(placement(false, "", List.of(), -1), snapshot, BomKind.UTF_8, width);
                assertTrue(text.contains("MD:" + (snapshot ? "snapshot" : "live")), text);
                assertTrue(text.contains("Tab 2/2  Ctrl+P"), text);
                assertFalse(text.contains("ENC:"), text);
                assertEquals(width, CellText.width(text));
            }
        }
    }

    @Test void loadingIsVisibleBeforeThereIsAnAcceptedPosition() {
        var pending = new MarkdownPreviewState.Placement(new Rect(0, 2, 80, 20), null, 0, 0, true, "");
        String text = status(pending, false, BomKind.NONE, 80);
        assertTrue(text.contains("updating"));
        assertFalse(text.contains("Row "));
        assertTrue(text.contains("Ctrl+P: Source"));
    }

    private static String status(MarkdownPreviewState.Placement p, boolean snapshot, BomKind bom, int columns) {
        return MarkdownPreviewStatus.text(p, snapshot, "UTF-8", bom, 1, 2, columns);
    }

    private static MarkdownPreviewState.Placement placement(boolean searching, String query,
        List<PreviewSelection.Range> matches, int active) {
        var result = result("first\nsecond\nthird", line("first", 0, 5, 0, 5),
            line("second", 0, 6, 6, 12), line("third", 0, 5, 13, 18));
        return new MarkdownPreviewState.Placement(new Rect(0, 2, 160, 15), result, 0, 0, false, "",
            PreviewSelection.Snapshot.EMPTY, matches, active, query, searching,
            new PreviewSelection.VisualPosition(1, 5), null);
    }
}

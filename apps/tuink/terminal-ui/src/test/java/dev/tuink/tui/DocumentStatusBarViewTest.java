package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSummary;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocumentStatusBarViewTest {
    @Test
    void markdownSwitchHintFollowsTheTabCountAndPrecedesSearch() {
        var host = new TerminalEditorApp();
        var tab = host.newEditorTab();
        tab.state().setCurrentResource(JvmDocumentStorage.resource(Path.of("/notes.MD")));
        for (int count : new int[] {1, 2}) {
            var bar = host.buildStatusBar(tab.state(), tab.tabSession().model(), 140, false,
                0, count, "Find 1/3", null, 23);
            assertTrue(bar.text().contains("Tab 1/" + count + "  Ctrl+P: Preview  Find 1/3"), bar.text());
        }
    }

    @Test
    void otherDocumentsDoNotAdvertiseMarkdownSwitching() {
        var host = new TerminalEditorApp();
        var tab = host.newEditorTab();
        for (String name : new String[] {"notes.txt", "notes.md.txt"}) {
            tab.state().setCurrentResource(JvmDocumentStorage.resource(Path.of("/" + name)));
            var bar = host.buildStatusBar(tab.state(), tab.tabSession().model(), 140, false,
                0, 1, "", null, 23);
            assertFalse(bar.text().contains("Ctrl+P"), bar.text());
            assertFalse(bar.text().contains("Tab 1/1"), bar.text());
        }
    }

    @Test
    void homogeneousExistingEndingsAreShownEvenWhenNewlinesUseAnotherDefault() {
        var host = new TerminalEditorApp();
        var tab = host.newEditorTab();
        var summary = new DocumentFormatSummary(DocumentEncoding.UTF8_BOM, "UTF-8", BomKind.UTF_8,
            "\r\n", DocumentFormatSummary.LineEnding.LF, true);
        var bar = host.buildStatusBar(tab.state(), tab.tabSession().model(), 100, false, 0, 1, "", summary, 23);
        assertTrue(bar.text().contains("ENC:UTF-8+BOM"));
        assertTrue(bar.text().contains("EOL:LF"));
        assertFalse(bar.text().contains("EOL:CRLF"));
        assertEquals("ENC:UTF-8+BOM", bar.text().substring(bar.encoding().x(), bar.encoding().rightExclusive()));
        assertEquals("EOL:LF", bar.text().substring(bar.endings().x(), bar.endings().rightExclusive()));
    }

    @Test
    void sourceStatusUsesRightAnchoredMetadataWithCompleteActionBounds() {
        var first = new DocumentStatusBarView.Reading("Ln 1/120, Col 1/300", "Ln 1/120, Col 1",
            "Tab 1/2", "Ctrl+P: Preview", "");
        var moved = new DocumentStatusBarView.Reading("Ln 104/120, Col 99/300", "Ln 104/120, Col 99",
            "Tab 1/2", "Ctrl+P: Preview", "");
        var before = DocumentStatusBarView.place(first, "RW", "UTF-8", "LF", false, 100, 23);
        var after = DocumentStatusBarView.place(moved, "RW", "UTF-8", "LF", false, 100, 23);
        assertEquals(before.encoding(), after.encoding());
        assertEquals(before.endings(), after.endings());
        assertTrue(after.text().contains("Tab 1/2  Ctrl+P: Preview"));
        assertEquals("ENC:UTF-8", after.text().substring(after.encoding().x(), after.encoding().rightExclusive()));
        assertEquals("EOL:LF", after.text().substring(after.endings().x(), after.endings().rightExclusive()));
        assertTrue(after.text().endsWith("RW | ENC:UTF-8 | EOL:LF | INS "));
    }

    @Test
    void narrowMarkdownStatusRetainsItsTabAndSwitchBeforeOptionalFields() {
        var left = new DocumentStatusBarView.Reading("Ln 104/120, Col 99/300", "Ln 104/120, Col 99",
            "Tab 1/2", "Ctrl+P: Preview", "Find 3 in 2 tabs");
        for (int width : new int[] {35, 45, 80}) {
            var placed = DocumentStatusBarView.place(left, "RW", "UTF-8+BOM", "Mixed", false, width, 23);
            assertTrue(placed.text().contains("Tab 1/2  Ctrl+P"), placed.text());
            assertEquals(width, dev.tuiframe.cell.CellText.width(placed.text()));
            if (!placed.encoding().isEmpty())
                assertEquals("ENC:UTF-8+BOM", placed.text().substring(placed.encoding().x(), placed.encoding().rightExclusive()));
            if (!placed.endings().isEmpty())
                assertEquals("EOL:Mixed", placed.text().substring(placed.endings().x(), placed.endings().rightExclusive()));
        }
    }

    @Test
    void sourceSearchSummarySurvivesOptionalMetadataAtNormalWidth() {
        for (String summary : new String[] {"Search is too complex", "Find 262144"}) {
            var left = new DocumentStatusBarView.Reading("Ln 104/120, Col 99/300", "Ln 104/120, Col 99",
                "Tab 1/2", "Ctrl+P: Preview", summary);
            var placed = DocumentStatusBarView.place(left, "RW", "UTF-8+BOM", "Mixed", false, 80, 23);
            assertTrue(placed.text().contains(summary), placed.text());
            assertTrue(placed.text().contains("Tab 1/2  Ctrl+P"), placed.text());
            assertEquals(80, dev.tuiframe.cell.CellText.width(placed.text()));
            assertTrue(placed.text().endsWith("INS "), placed.text());
        }
    }

    @Test
    void partiallyClippedLabelsNeverCreateActionableHitRegions() {
        var bar = DocumentStatusBarView.place(new DocumentStatusBarView.Reading("L", "L", "", "", ""),
            "RW", "UTF-8+BOM", "Mixed", false, 12, 3);
        assertTrue(bar.encoding().isEmpty());
        assertTrue(bar.endings().isEmpty());
        assertEquals(12, dev.tuiframe.cell.CellText.width(bar.text()));
    }
}

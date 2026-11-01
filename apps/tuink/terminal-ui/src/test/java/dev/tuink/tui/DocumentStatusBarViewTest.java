package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSummary;
import dev.tuink.editor.storage.BomKind;
import org.junit.jupiter.api.Test;

class DocumentStatusBarViewTest {
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
    void partiallyClippedLabelsNeverCreateActionableHitRegions() {
        var bar = DocumentStatusBarView.place(" L", "RW", "UTF-8+BOM", "Mixed", false, 12, 3);
        assertTrue(bar.encoding().isEmpty());
        assertTrue(bar.endings().isEmpty());
        assertEquals(12, dev.tuiframe.cell.CellText.width(bar.text()));
    }
}

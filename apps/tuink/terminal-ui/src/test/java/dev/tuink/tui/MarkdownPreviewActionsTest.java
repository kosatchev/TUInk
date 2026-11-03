package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownPreviewActionsTest {
    @Test void copiesInlineFormulaInsideTableAndRevealsExactTextOffset() {
        var snapshot = new DocumentSnapshot(new DocumentId("table-actions"), 0,
            List.of("A longer paragraph.", "", "| Formula |", "|---|", "| $x_i^2$ |"));
        var document = new CommonmarkMarkdownParser().parse(snapshot);
        var formula = MarkdownPreviewActions.source(document, snapshot, document.projection().text().indexOf("x_i"));
        assertEquals("TeX", formula.kind());
        assertEquals("x_i^2", formula.text());
        var position = MarkdownPreviewActions.sourceRange(document, 9).start();
        assertEquals(0, position.line());
        assertEquals(9, position.utf16Offset());
    }

    @Test void copiesExactMathDiagramAndTableContentWithoutRendererDecoration() {
        var snapshot = new DocumentSnapshot(new DocumentId("actions"), 7, List.of(
            "Inline $x_i^{a+b}$.", "", "```mermaid", "flowchart LR", " A-->B", "```", "",
            "| A | B |", "|---|---|", "| left | right |"));
        var document = new CommonmarkMarkdownParser().parse(snapshot);
        String text = document.projection().text();
        var formula = MarkdownPreviewActions.source(document, snapshot, text.indexOf("x_i"));
        assertEquals("TeX", formula.kind()); assertEquals("x_i^{a+b}", formula.text());
        var diagram = MarkdownPreviewActions.source(document, snapshot, text.indexOf("flowchart"));
        assertEquals("Mermaid", diagram.kind()); assertEquals("flowchart LR\n A-->B\n", diagram.text());
        assertEquals("A\tB\nleft\tright", MarkdownPreviewActions.table(document, text.indexOf("left")));
        assertNull(MarkdownPreviewActions.table(document, text.indexOf("x_i")));
        assertNull(MarkdownPreviewActions.source(document,
            new DocumentSnapshot(snapshot.documentId(), 8, snapshot.lines()), 0));
        assertEquals("Inline $x_i^{a+b}$.", snapshot.line(0));
    }
}

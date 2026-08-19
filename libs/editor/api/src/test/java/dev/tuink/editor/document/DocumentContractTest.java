package dev.tuink.editor.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentContractTest {
    private static final DocumentId DOCUMENT = new DocumentId("document-1");

    @Test
    void positionsUseExplicitUtf16Offsets() {
        DocumentSnapshot snapshot = new DocumentSnapshot(DOCUMENT, 7, List.of("A😀B", ""));

        assertTrue(snapshot.contains(new TextPosition(0, 4)));
        assertFalse(snapshot.contains(new TextPosition(0, 5)));
        assertTrue(snapshot.contains(new TextPosition(1, 0)));
    }

    @Test
    void workspaceEditsAreSortedAndOverlapsAreRejected() {
        TextEdit later = edit(0, 4, 0, 5, "z");
        TextEdit earlier = edit(0, 0, 0, 1, "a");

        WorkspaceEdit workspaceEdit = new WorkspaceEdit(DOCUMENT, 3, List.of(later, earlier));

        assertEquals(List.of(earlier, later), workspaceEdit.edits());
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkspaceEdit(
                DOCUMENT,
                3,
                List.of(edit(0, 0, 0, 3, "x"), edit(0, 2, 0, 4, "y"))
            )
        );
    }

    @Test
    void adjacentAndSamePositionInsertionsAreDeterministic() {
        TextEdit first = edit(0, 0, 0, 1, "x");
        TextEdit adjacent = edit(0, 1, 0, 2, "y");
        assertEquals(
            List.of(first, adjacent),
            new WorkspaceEdit(DOCUMENT, 1, List.of(adjacent, first)).edits()
        );

        TextEdit insertionA = edit(0, 1, 0, 1, "a");
        TextEdit insertionB = edit(0, 1, 0, 1, "b");
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkspaceEdit(DOCUMENT, 1, List.of(insertionA, insertionB))
        );
    }

    @Test
    void textContractsRejectMalformedUtf16AndNormalizeLineEndings() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new DocumentSnapshot(DOCUMENT, 0, List.of("bad\uD800"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> edit(0, 0, 0, 0, "bad\uDC00")
        );

        TextEdit normalized = edit(0, 0, 0, 0, "one\r\ntwo\rthree");
        assertEquals("one\ntwo\nthree", normalized.replacement());
    }

    private static TextEdit edit(
        int startLine,
        int startOffset,
        int endLine,
        int endOffset,
        String replacement
    ) {
        return new TextEdit(
            new TextRange(
                new TextPosition(startLine, startOffset),
                new TextPosition(endLine, endOffset)
            ),
            replacement
        );
    }
}

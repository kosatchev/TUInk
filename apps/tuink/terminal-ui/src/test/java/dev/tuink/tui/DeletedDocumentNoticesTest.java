package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeletedDocumentNoticesTest {
    @Test
    void dismissSurvivesDuplicateObservationsAndAcceptedSaveAllowsANewDeletionEvent() {
        var notices = new DeletedDocumentNotices();
        var document = new DocumentId("deleted-document");
        Path original = Path.of("original.txt");
        notices.show(document, original, "File deleted");
        assertTrue(notices.visible(document));
        assertTrue(notices.hasUnsavedRemoval(document));
        Object scope = notices.scopeId(document);
        notices.dismiss(document);
        assertFalse(notices.visible(document));
        assertTrue(notices.hasUnsavedRemoval(document), "Dismiss hides the warning without saving the draft");
        notices.show(document, original, "File deleted");
        assertFalse(notices.visible(document), "The same deletion must not resurrect a dismissed notice");
        assertSame(scope, notices.scopeId(document));

        notices.saved(document);
        assertFalse(notices.hasUnsavedRemoval(document));
        notices.show(document, original, "File deleted");
        assertTrue(notices.visible(document), "Deleting a newly saved resource is a fresh event");
        assertNotSame(scope, notices.scopeId(document));
    }

    @Test
    void saveAndRetirementAreDocumentScopedAndPathLabelsRemainSingleLine() {
        var notices = new DeletedDocumentNotices();
        var first = new DocumentId("first");
        var second = new DocumentId("second");
        notices.show(first, Path.of("first\nfile.txt"), "File deleted");
        notices.show(second, Path.of("second.txt"), "File deleted");
        assertFalse(notices.banner(first).text().contains("\n"));
        assertTrue(notices.banner(first).text().contains("File deleted"));
        notices.saved(first);
        assertFalse(notices.visible(first));
        assertTrue(notices.visible(second));
        notices.retain(Set.of(first));
        assertFalse(notices.visible(second));
        assertFalse(notices.hasUnsavedRemoval(second));
        assertFalse(notices.hasUnsavedRemoval(null));
    }
}

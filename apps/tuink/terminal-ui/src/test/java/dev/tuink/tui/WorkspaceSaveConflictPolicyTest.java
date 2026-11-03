package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSaveConflictPolicyTest {
    @TempDir Path directory;

    @Test void aRecoveredLiveOwnersCurrentBindingTakesPrecedenceOverAnEarlierDuplicate() throws Exception {
        Path target = Files.writeString(directory.resolve("shared.md"), "original");
        DocumentLoad loaded = load(target);
        var first = live("first", loaded);
        var recovered = live("recovered", loaded);
        var documents = List.of(new WorkspaceSaveConflictPolicy.OpenDocument(first, 1),
            new WorkspaceSaveConflictPolicy.OpenDocument(recovered, 3));

        assertNull(WorkspaceSaveConflictPolicy.conflict(documents, recovered, target));
        assertNull(WorkspaceSaveConflictPolicy.conflict(documents, first, target));
    }

    @Test void aFrozenSnapshotsExistingBindingCannotOverrideARegisteredLiveOwner() throws Exception {
        Path target = Files.writeString(directory.resolve("owned.md"), "original");
        DocumentLoad loaded = load(target);
        var source = live("owner", loaded);
        var formerSource = live("closed", loaded);
        try (var frozen = FrozenDocumentSession.create(formerSource.freezeSource())) {
            var documents = List.of(new WorkspaceSaveConflictPolicy.OpenDocument(source, 4));
            String conflict = WorkspaceSaveConflictPolicy.conflict(documents, frozen, target);
            assertNotNull(conflict);
            assertTrue(conflict.contains("tab 4"), conflict);
            assertEquals("original", Files.readString(target));
        }
    }

    private static EditorDocumentSession live(String id, DocumentLoad loaded) {
        var session = EditorDocumentSession.create(new DocumentId(id), List.of(""));
        session.openPrepared(loaded);
        return session;
    }

    private static DocumentLoad load(Path path) throws Exception {
        var resource = JvmDocumentStorage.resource(path);
        var task = new FutureTask<>(() -> resource.storage().load(resource, DocumentOpenMode.DETECT, false));
        Thread worker = new Thread(task, "test-conflict-load");
        worker.start(); worker.join(5_000L);
        assertFalse(worker.isAlive());
        return task.get();
    }
}

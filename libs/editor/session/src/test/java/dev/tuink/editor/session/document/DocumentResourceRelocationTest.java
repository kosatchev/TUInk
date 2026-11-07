package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.TabSession;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentResourceRelocationTest {
    @TempDir Path directory;

    @Test
    void aMovedDirtyDocumentKeepsItsViewsHistoryFormatAndSavepoint() throws Exception {
        Path original = directory.resolve("source.txt");
        Files.write(original, "\uFEFForiginal\r\ntail\r".getBytes(StandardCharsets.UTF_16LE));
        var session = loaded(original);
        var extraView = session.attachView();
        prepend(session, "draft ");
        var text = session.snapshot();
        var metadata = session.metadata();
        var primaryPosition = session.view(session.defaultViewId());
        var extraPosition = session.view(extraView);
        var originalBinding = session.binding();
        var originalMode = session.freezeSource().reopenMode();
        long recoveryRevision = session.status().recoveryRevision();
        Path destination = directory.resolve("moved.txt");

        var replacement = worker(() -> {
            Files.move(original, destination);
            return JvmDocumentStorage.relocatedBinding(originalBinding, destination);
        });
        assertEquals(originalBinding, session.binding(), "the worker cannot change document owner state");
        assertTrue(session.relocateResource(originalBinding, replacement));

        assertSame(text, session.snapshot());
        assertEquals(metadata, session.metadata());
        assertEquals(originalMode, session.freezeSource().reopenMode());
        assertEquals(primaryPosition, session.view(session.defaultViewId()));
        assertEquals(extraPosition, session.view(extraView));
        assertTrue(session.status().dirty());
        assertTrue(session.status().recoveryRevision() > recoveryRevision);
        assertEquals(destination, JvmDocumentStorage.pathOf(session.binding().resource()));
        assertEquals(destination, JvmDocumentStorage.baselineOf(session.binding().version()).target());

        assertTrue(session.undo());
        assertFalse(session.status().dirty(), "relocation must retain the original text savepoint");
        assertEquals(List.of("original", "tail", ""), session.snapshot().lines());
        assertEquals(replacement, session.binding(), "undo changes text, never the confirmed file location");
        assertTrue(session.redo());
        assertTrue(session.status().dirty());
        save(session);
        assertFalse(session.status().dirty());
        assertFalse(Files.exists(original));
        assertArrayEquals("\uFEFFdraft original\r\ntail\r".getBytes(StandardCharsets.UTF_16LE),
            Files.readAllBytes(destination));
    }

    @Test
    void movingACleanDocumentDoesNotInventAnUnsavedEdit() throws Exception {
        Path original = Files.writeString(directory.resolve("clean.txt"), "clean");
        var session = loaded(original);
        ResourceBinding before = session.binding();
        Path destination = directory.resolve("renamed.txt");
        Files.move(original, destination);

        assertTrue(session.relocateResource(before, JvmDocumentStorage.relocatedBinding(before, destination)));

        assertFalse(session.status().dirty());
        assertFalse(session.undo());
        assertEquals(List.of("clean"), session.snapshot().lines());
    }

    @Test
    void relocationKeepsAnExistingExternalChangeConflict() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "loaded");
        var session = loaded(original);
        prepend(session, "draft ");
        ResourceBinding before = session.binding();
        Files.writeString(original, "external replacement");
        Path destination = directory.resolve("moved.txt");
        Files.move(original, destination);
        var replacement = worker(() -> JvmDocumentStorage.relocatedBinding(before, destination));

        assertTrue(session.relocateResource(before, replacement));
        var work = session.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Failed.class, session.io().accept(work, worker(work::perform)));

        assertEquals("external replacement", Files.readString(destination));
        assertEquals(List.of("draft loaded"), session.snapshot().lines());
        assertTrue(session.status().dirty());
    }

    @Test
    void deletionRetainsEvenACleanBufferAsAnUnnamedDraftRequiringSaveAs() throws Exception {
        Path original = Files.writeString(directory.resolve("deleted.txt"), "retain this text");
        var session = loaded(original);
        var before = session.binding();
        var text = session.snapshot();
        Files.delete(original);

        assertTrue(session.relocateResource(before, ResourceBinding.unbound()));

        assertEquals(ResourceBinding.unbound(), session.binding());
        assertSame(text, session.snapshot());
        assertTrue(session.status().dirty());
        assertThrows(IOException.class, () -> session.io().beginSave());
        assertFalse(Files.exists(original));
    }

    @Test
    void ownerGuardsRejectStaleOrSavingBindingsWithoutChangingTheBuffer() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "original");
        var session = loaded(original);
        var before = session.binding();
        var work = session.io().beginSave();
        assertThrows(IllegalStateException.class, () -> session.relocateResource(before, ResourceBinding.unbound()));
        session.io().cancel(work);
        assertThrows(IllegalStateException.class,
            () -> worker(() -> session.relocateResource(before, ResourceBinding.unbound())));
        assertTrue(session.relocateResource(before, ResourceBinding.unbound()));
        assertFalse(session.relocateResource(before, before), "a late move cannot overwrite a newer binding");
        assertEquals(ResourceBinding.unbound(), session.binding());
        assertEquals(List.of("original"), session.snapshot().lines());
    }

    @Test
    void aFrozenSourceRelocatesWithoutRecreatingItsTextOrFormat() throws Exception {
        Path original = Files.writeString(directory.resolve("source.md"), "# title\r\nbody\r\n");
        var live = loaded(original);
        var frozen = FrozenDocumentSession.create(live.freezeSource());
        var text = frozen.source().text();
        var metadata = frozen.metadata();
        var mode = frozen.source().reopenMode();
        var before = frozen.binding();
        Path destination = directory.resolve("moved.md");
        var replacement = worker(() -> {
            Files.move(original, destination);
            return JvmDocumentStorage.relocatedBinding(before, destination);
        });

        assertTrue(frozen.relocateResource(before, replacement));

        assertSame(text, frozen.source().text());
        assertEquals(metadata, frozen.metadata());
        assertEquals(mode, frozen.source().reopenMode());
        assertEquals(1, frozen.generation());
        assertFalse(frozen.relocateResource(before, ResourceBinding.unbound()));
        var work = frozen.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, frozen.io().accept(work, worker(work::perform)));
        assertEquals("# title\r\nbody\r\n", Files.readString(destination));
        assertFalse(Files.exists(original));
    }

    @Test
    void aDestinationThatBecomesInaccessibleStillRetainsTheConfirmedNewLocation() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "loaded");
        var session = loaded(original);
        var before = session.binding();
        Path destination = directory.resolve("unavailable.txt");
        Files.move(original, destination);
        Files.delete(destination);
        var replacement = worker(() -> JvmDocumentStorage.relocatedBinding(before, destination));

        assertEquals(destination, JvmDocumentStorage.pathOf(replacement.resource()));
        var baseline = assertInstanceOf(FileBaseline.class, replacement.version());
        assertEquals(destination, baseline.target());
        assertEquals(JvmDocumentStorage.baselineOf(before.version()).sha256(), baseline.sha256());
        assertEquals(JvmDocumentStorage.baselineOf(before.version()).byteSize(), baseline.byteSize());
    }

    @Test
    void relocationResolvesASymlinkedDestinationParentForSubsequentSave() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path original = Files.writeString(directory.resolve("source.txt"), "original");
        var session = loaded(original);
        prepend(session, "draft ");
        var before = session.binding();
        Path physicalParent = Files.createDirectory(directory.resolve("physical"));
        Path aliasParent = Files.createSymbolicLink(directory.resolve("alias"), physicalParent.getFileName());
        Path destination = aliasParent.resolve("moved.txt");
        var replacement = worker(() -> {
            Files.move(original, destination);
            return JvmDocumentStorage.relocatedBinding(before, destination);
        });

        assertTrue(session.relocateResource(before, replacement));
        assertEquals(destination, JvmDocumentStorage.pathOf(session.binding().resource()));
        assertEquals(physicalParent.resolve("moved.txt"), JvmDocumentStorage.baselineOf(session.binding().version()).target());
        save(session);
        assertEquals("draft original", Files.readString(destination));
        assertFalse(Files.exists(original));
    }

    @Test
    void deletingAFrozenSourceRetainsItsTextForAnExplicitSaveAs() throws Exception {
        Path original = Files.writeString(directory.resolve("source.md"), "# retained snapshot");
        var live = loaded(original);
        var frozen = FrozenDocumentSession.create(live.freezeSource());
        var text = frozen.source().text();
        var before = frozen.binding();
        Files.delete(original);

        assertTrue(frozen.relocateResource(before, ResourceBinding.unbound()));

        assertSame(text, frozen.source().text());
        assertEquals(ResourceBinding.unbound(), frozen.binding());
        assertEquals(1, frozen.generation());
        assertThrows(IOException.class, () -> frozen.io().beginSave());
        assertFalse(Files.exists(original));
    }

    private static EditorDocumentSession loaded(Path path) throws Exception {
        var view = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var session = EditorDocumentSession.connect(CoreDocumentBackend.adopt(new DocumentId(path.toString()), view));
        var resource = JvmDocumentStorage.resource(path);
        session.openPrepared(worker(() -> resource.storage().load(resource, DocumentOpenMode.DETECT, false)));
        return session;
    }

    private static void prepend(EditorDocumentSession session, String text) {
        var start = new TextPosition(0, 0);
        assertInstanceOf(WorkspaceEditResult.Applied.class, session.applyWorkspaceEdit(new WorkspaceEdit(
            session.documentId(), session.snapshot().revision(), List.of(new TextEdit(new TextRange(start, start), text)))));
    }

    private static void save(EditorDocumentSession session) throws Exception {
        var work = session.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, session.io().accept(work, worker(work::perform)));
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            try { return executor.submit(action).get(); }
            catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception cause) throw cause;
                throw (Error) failed.getCause();
            }
        } finally { executor.shutdownNow(); }
    }
}

package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.editor.backend.DocumentBackend;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.PresentationGroupId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.verification.MemoryDocumentStorage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class EditorWorkspacePresentationTest {
    @Test
    void oneOrderAndSelectionExposeEditingOnlyAndAllPresentationProjections() {
        var workspace = new EditorWorkspace<String>();
        var document = document("shared");
        var group = PresentationGroupId.create();
        var source = source(document, "source", group, "editor");
        var preview = projection("preview", group, "live projection");
        var editing = workspace.attachments();
        var all = workspace.presentationAttachments();
        workspace.replacePresentations(List.of(source, preview), preview.id());

        assertSame(all, workspace.presentationAttachments());
        assertSame(editing, workspace.attachments());
        assertEquals(List.of("editor", "live projection"), all);
        assertEquals(List.of("editor"), editing);
        assertEquals(2, workspace.presentations().size());
        assertEquals(1, workspace.entries().size());
        assertSame(preview, workspace.activePresentation());
        assertEquals(1, workspace.activePresentationIndex());
        assertNull(workspace.active());
        assertEquals(-1, workspace.activeIndex());
        assertSame(document, workspace.document(document.documentId()));
        assertSame(source, workspace.findPresentation(document.defaultViewId()));
        workspace.select(document.defaultViewId());
        assertSame(source, workspace.activePresentation());
        assertSame(document, workspace.active().document());
        workspace.selectPresentationIndex(1);
        assertSame(preview, workspace.activePresentation());
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
        assertThrows(UnsupportedOperationException.class, () -> workspace.presentations().clear());
    }

    @Test
    void sourceRetirementAndFrozenAttachmentPublishTogetherWithoutRetainingTheDocument() {
        var workspace = new EditorWorkspace<String>();
        var document = document("closed");
        var group = PresentationGroupId.create();
        var source = source(document, "source", group, "editor");
        var preview = projection("preview", group, "live");
        workspace.replacePresentations(List.of(source, preview), source.id());
        var frozen = preview.withAttachment("frozen source");
        workspace.replacePresentations(List.of(frozen), frozen.id());

        assertEquals(List.of(frozen), workspace.presentations());
        assertEquals(List.of("frozen source"), workspace.presentationAttachments());
        assertTrue(workspace.entries().isEmpty());
        assertTrue(workspace.attachments().isEmpty());
        assertNull(workspace.document(document.documentId()));
        assertNull(document.workspace());
        assertFalse(document.ownsView(document.defaultViewId()));
        assertSame(EditorWorkspace.Projection.INSTANCE, frozen.binding());
        assertSame(frozen, workspace.activePresentation());
        assertNull(workspace.active());
        workspace.removePresentation(preview.id());
        assertNull(workspace.activePresentation());
        assertEquals(-1, workspace.activePresentationIndex());
    }

    @Test
    void diskReopenAdmitsANewSessionBeforeTheSameProjectionAndKeepsItsGroupIdentity() {
        var workspace = new EditorWorkspace<String>();
        var group = PresentationGroupId.create();
        var preview = projection("stable-preview", group, "frozen");
        workspace.insert(0, preview, true);
        var reopened = document("new disk identity");
        var source = source(reopened, "new-source", group, "new editor");
        var live = preview.withAttachment("new live link");
        workspace.replacePresentations(List.of(source, live), source.id());

        assertEquals(List.of(source, live), workspace.presentations());
        assertEquals(preview.id(), live.id());
        assertEquals(group, live.groupId());
        assertSame(workspace, reopened.workspace());
        assertSame(reopened, workspace.document(reopened.documentId()));
        workspace.removePresentation(live.id());
        assertTrue(reopened.ownsView(reopened.defaultViewId()));
        assertSame(reopened, workspace.active().document());
    }

    @Test
    void preparedGroupReorderAndEditingUpdatesKeepOneSelectedPresentationIdentity() {
        var workspace = new EditorWorkspace<String>();
        var first = document("first");
        var second = document("second");
        var group = PresentationGroupId.create();
        var a = source(first, "a", group, "first editor");
        var preview = projection("preview", group, "projection");
        var b = source(second, "b", PresentationGroupId.create(), "second editor");
        workspace.replacePresentations(List.of(a, preview, b), preview.id());
        var previousOrder = workspace.presentations();
        workspace.replacePresentations(List.of(b, a, preview), preview.id());
        workspace.updateAttachment(first.defaultViewId(), "new first editor");

        assertEquals(List.of(a, preview, b), previousOrder, "published snapshots remain immutable");
        assertSame(preview, workspace.activePresentation());
        assertEquals(2, workspace.activePresentationIndex());
        assertEquals(-1, workspace.activeIndex());
        assertEquals(List.of("second editor", "new first editor"), workspace.attachments());
        assertEquals(List.of("second editor", "new first editor", "projection"), workspace.presentationAttachments());
        assertEquals(a.id(), workspace.findPresentation(first.defaultViewId()).id());
        assertEquals(group, workspace.findPresentation(first.defaultViewId()).groupId());
        assertTrue(first.ownsView(first.defaultViewId()));
        assertTrue(second.ownsView(second.defaultViewId()));
    }

    @Test
    void aProjectionDoesNotBypassTheLastEditingViewSaveGuard() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("source", "disk".getBytes(StandardCharsets.UTF_8));
        var document = document("busy");
        document.openPrepared(worker(() -> storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false)));
        document.execute(document.defaultViewId(), new DocumentCommand.Input("draft "));
        var workspace = new EditorWorkspace<String>();
        var group = PresentationGroupId.create();
        var source = source(document, "source", group, "editor");
        var preview = projection("preview", group, "live");
        workspace.replacePresentations(List.of(source, preview), preview.id());
        var before = workspace.presentations();
        var work = document.io().beginSave();

        assertThrows(IllegalStateException.class, () -> workspace.replacePresentations(
            List.of(preview.withAttachment("frozen")), preview.id()));
        assertSame(before, workspace.presentations());
        assertSame(preview, workspace.activePresentation());
        assertEquals("live", workspace.find(preview.id()).attachment());
        assertTrue(document.ownsView(document.defaultViewId()));
        assertSame(workspace, document.workspace());
        document.io().cancel(work);
        workspace.replacePresentations(List.of(preview.withAttachment("frozen")), preview.id());
        assertNull(document.workspace());
    }

    @Test
    void backendRetirementFailureKeepsSelectionAttachmentsAndIncomingDocumentUnclaimed() {
        OutOfMemoryError failure = new OutOfMemoryError("prepared retirement failed");
        var backend = CoreDocumentBackend.create(new DocumentId("failing"), List.of("text"));
        DocumentBackend failing = (DocumentBackend) Proxy.newProxyInstance(DocumentBackend.class.getClassLoader(),
            new Class<?>[] {DocumentBackend.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("detachView")) throw failure;
                try { return method.invoke(backend, arguments); }
                catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
            });
        var oldDocument = EditorDocumentSession.connect(failing);
        var incoming = document("unclaimed");
        var group = PresentationGroupId.create();
        var source = source(oldDocument, "source", group, "editor");
        var preview = projection("preview", group, "live");
        var workspace = new EditorWorkspace<String>();
        workspace.replacePresentations(List.of(source, preview), preview.id());
        var before = workspace.presentations();
        var replacement = source(incoming, "replacement", group, "incoming");

        assertSame(failure, assertThrows(OutOfMemoryError.class, () -> workspace.replacePresentations(
            List.of(replacement, preview.withAttachment("frozen")), replacement.id())));
        assertSame(before, workspace.presentations());
        assertSame(preview, workspace.activePresentation());
        assertEquals("live", workspace.find(preview.id()).attachment());
        assertSame(workspace, oldDocument.workspace());
        assertTrue(oldDocument.ownsView(oldDocument.defaultViewId()));
        assertNull(incoming.workspace());
        assertTrue(incoming.ownsView(incoming.defaultViewId()));
    }

    @Test
    void duplicateAndMultiRetirementRewritesRejectBeforeChangingOwnership() {
        var first = document("first");
        var second = document("second");
        var workspace = new EditorWorkspace<String>();
        var group = PresentationGroupId.create();
        var a = source(first, "a", group, "a");
        var b = source(second, "b", PresentationGroupId.create(), "b");
        workspace.replacePresentations(List.of(a, b), b.id());
        var before = workspace.presentations();
        assertThrows(IllegalArgumentException.class, () -> workspace.replacePresentations(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> workspace.replacePresentations(List.of(a, a), a.id()));
        assertThrows(IllegalArgumentException.class, () -> workspace.replacePresentations(List.of(a, b), new PresentationId("missing")));
        assertSame(before, workspace.presentations());
        assertSame(b, workspace.activePresentation());
        assertSame(workspace, first.workspace());
        assertSame(workspace, second.workspace());
        assertTrue(first.ownsView(first.defaultViewId()));
        assertTrue(second.ownsView(second.defaultViewId()));
    }

    @Test
    void failingRewriteMaterializationAndForeignOwnerArePreflightedBeforeRetirement() {
        var workspace = new EditorWorkspace<String>();
        var old = document("old");
        var incoming = document("incoming");
        workspace.add(old, old.defaultViewId(), "old");
        var before = workspace.presentations();
        var entry = source(incoming, "incoming", PresentationGroupId.create(), "incoming");
        OutOfMemoryError failure = new OutOfMemoryError("membership materialization");
        List<EditorWorkspace.Presentation<String>> broken = new AbstractList<>() {
            @Override public int size() { return 2; }
            @Override public EditorWorkspace.Presentation<String> get(int index) { if (index == 0) return entry; throw failure; }
        };
        assertSame(failure, assertThrows(OutOfMemoryError.class, () -> workspace.replacePresentations(broken, entry.id())));
        var otherWorkspace = new EditorWorkspace<String>();
        otherWorkspace.add(incoming, incoming.defaultViewId(), "other owner");
        assertThrows(IllegalStateException.class, () -> workspace.replacePresentations(List.of(entry), entry.id()));
        assertSame(before, workspace.presentations());
        assertTrue(old.ownsView(old.defaultViewId()));
        assertSame(workspace, old.workspace());
        assertSame(otherWorkspace, incoming.workspace());
    }

    @Test
    void independentPresentationAndGroupIdsAreValidatedAndProjectionAccessStaysOnItsOwner() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PresentationId(" "));
        assertThrows(IllegalArgumentException.class, () -> new PresentationGroupId("x".repeat(513)));
        assertNotEquals(PresentationId.create(), PresentationId.create());
        assertNotEquals(PresentationGroupId.create(), PresentationGroupId.create());
        var workspace = new EditorWorkspace<String>();
        var preview = projection("preview", PresentationGroupId.create(), "frozen");
        workspace.insert(0, preview, true);
        worker(() -> {
            assertThrows(IllegalStateException.class, workspace::activePresentation);
            assertThrows(IllegalStateException.class, workspace::presentations);
            assertThrows(IllegalStateException.class, () -> workspace.presentationAttachments().get(0));
            assertThrows(IllegalStateException.class, () -> workspace.removePresentation(preview.id()));
            return null;
        });
        assertSame(preview, workspace.activePresentation());
    }

    private static EditorDocumentSession document(String id) {
        return EditorDocumentSession.create(new DocumentId(id), List.of("text"));
    }
    private static EditorWorkspace.Presentation<String> source(EditorDocumentSession document, String id,
        PresentationGroupId group, String attachment) {
        return new EditorWorkspace.Presentation<>(new PresentationId(id), group,
            new EditorWorkspace.Editing(document, document.defaultViewId()), attachment);
    }
    private static EditorWorkspace.Presentation<String> projection(String id, PresentationGroupId group, String attachment) {
        return new EditorWorkspace.Presentation<>(new PresentationId(id), group, EditorWorkspace.Projection.INSTANCE, attachment);
    }
    private static <T> T worker(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Thread thread = new Thread(task, "presentation-test-worker");
        thread.start();
        return task.get();
    }
}

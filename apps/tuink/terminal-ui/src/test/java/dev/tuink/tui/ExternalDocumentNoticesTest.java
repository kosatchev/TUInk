package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalDocumentNoticesTest {
    @Test
    void oneVersionKeepsItsPaintedIdentityWhileANewerVersionRejectsOldButtonsAndCompletions() {
        var notices = new ExternalDocumentNotices();
        var subject = subject("document");
        var first = changed(subject, "external one");
        assertTrue(notices.show(first));
        var original = notices.notice(subject.documentId());
        assertFalse(notices.show(first));
        assertSame(original.scopeId(), notices.scopeId(subject.documentId()));
        assertTrue(notices.setBusy(original, true));
        assertTrue(notices.busy(subject.documentId()));
        assertFalse(notices.show(first), "A periodic duplicate cannot reset a pending decision");
        assertTrue(notices.busy(subject.documentId()));

        assertTrue(notices.show(changed(subject, "external two")));
        var newer = notices.notice(subject.documentId());
        assertNotSame(original.scopeId(), newer.scopeId());
        assertFalse(newer.busy());
        assertFalse(notices.setBusy(original, false));
        assertFalse(notices.clearIfCurrent(original));
        assertSame(newer, notices.notice(subject.documentId()));
        assertEquals(List.of("external two"), newer.loaded().lines());
    }

    @Test
    void acceptingOrSavingOneDocumentDoesNotClearAnotherDocumentsPersistentNotice() {
        var notices = new ExternalDocumentNotices();
        var first = subject("first");
        var second = subject("second");
        notices.show(changed(first, "first external"));
        notices.show(changed(second, "second external"));
        assertTrue(notices.clearIfCurrent(notices.notice(first.documentId())));
        assertFalse(notices.visible(first.documentId()));
        assertTrue(notices.visible(second.documentId()));
        notices.show(changed(first, "new first external"));
        notices.saved(first.documentId());
        assertFalse(notices.visible(first.documentId()));
        assertTrue(notices.visible(second.documentId()));
        notices.retain(Set.of(first.documentId()));
        assertFalse(notices.visible(second.documentId()));
        assertNull(notices.notice(null));
        assertNull(notices.scopeId(null));
        assertFalse(notices.busy(null));
    }

    @Test
    void replacingAnOwnerWithTheSameDocumentIdAndBytesStillRetiresItsEarlierActionIdentity() {
        var notices = new ExternalDocumentNotices();
        var previous = subject("same-document");
        notices.show(changed(previous, "external"));
        var original = notices.notice(previous.documentId());
        var replacement = subject("same-document");
        assertTrue(notices.show(changed(replacement, "external")));
        assertNotSame(original.scopeId(), notices.scopeId(previous.documentId()));
        assertFalse(notices.clearIfCurrent(original));
        assertSame(replacement, notices.notice(previous.documentId()).subject());
    }

    @Test
    void knownChangedButUndecodableBytesKeepAPersistentVersionedWarningWithoutManufacturingALoad() {
        var notices = new ExternalDocumentNotices();
        var subject = subject("undecodable");
        Path path = JvmDocumentStorage.pathOf(subject.binding().resource());
        var version = FileBaseline.fromBytes(path, new byte[] {(byte) 0xC3, 0x28});
        var observation = new ExternalFileObserver.Observation(subject, subject.binding(), subject.generation(), path,
            DocumentOpenMode.UTF_8, ExternalFileObserver.Kind.CHANGED, null, version, "Invalid UTF-8");
        assertTrue(notices.show(observation));
        var notice = notices.notice(subject.documentId());
        assertTrue(notices.visible(subject.documentId()));
        assertNull(notice.loaded());
        assertEquals(version, notice.version());
        assertEquals("Invalid UTF-8", notice.failure());
        assertFalse(notices.show(observation));
        assertSame(notice.scopeId(), notices.scopeId(subject.documentId()));
        assertEquals(List.of("original"), subject.source().text().lines());
    }

    @Test
    void presenceAndAccessErrorsCannotBeMisrepresentedAsChangedText() {
        var notices = new ExternalDocumentNotices();
        var subject = subject("missing");
        Path path = JvmDocumentStorage.pathOf(subject.binding().resource());
        for (var kind : List.of(ExternalFileObserver.Kind.MISSING, ExternalFileObserver.Kind.DENIED,
            ExternalFileObserver.Kind.UNKNOWN, ExternalFileObserver.Kind.UNCHANGED)) {
            var observation = new ExternalFileObserver.Observation(subject, subject.binding(), 0, path,
                DocumentOpenMode.UTF_8, kind, null);
            assertThrows(IllegalArgumentException.class, () -> notices.show(observation));
        }
        assertFalse(notices.visible(subject.documentId()));
    }

    private static FrozenDocumentSession subject(String id) {
        Path path = Path.of("/tmp", id + ".txt");
        var resource = JvmDocumentStorage.resource(path);
        var binding = new ResourceBinding(resource, SaveDisposition.REPLACE_EXISTING,
            FileBaseline.fromBytes(path, "original".getBytes(StandardCharsets.UTF_8)));
        return FrozenDocumentSession.create(new FrozenDocumentSource(new DocumentSnapshot(new DocumentId(id), 0,
            List.of("original")), DocumentFileMetadata.newDocument(1), binding, DocumentOpenMode.UTF_8));
    }

    private static ExternalFileObserver.Observation changed(FrozenDocumentSession subject, String text) {
        Path path = JvmDocumentStorage.pathOf(subject.binding().resource());
        var loaded = new DocumentLoad(subject.binding().resource(), List.of(text), DocumentFileMetadata.newDocument(1),
            FileBaseline.fromBytes(path, text.getBytes(StandardCharsets.UTF_8)), SaveDisposition.REPLACE_EXISTING);
        return new ExternalFileObserver.Observation(subject, subject.binding(), subject.generation(), path,
            DocumentOpenMode.UTF_8, ExternalFileObserver.Kind.CHANGED, loaded);
    }
}

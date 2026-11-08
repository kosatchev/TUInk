package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrozenRecoveryAutosaveManagerTest {
    @TempDir Path directory;

    @Test
    void aLiveCheckpointInFlightKeepsItsEntryThroughFrozenHandoffAndCannotSwallowTheNewerFrozenDraft() throws Exception {
        Path file = Files.writeString(directory.resolve("handoff.md"), "original");
        var live = TuiDocumentSession.create(new EditorClipboard());
        live.view().engine().open(JvmDocumentStorage.resource(file));
        live.view().engine().insertInput("old ");
        var persistence = new Persistence();
        var tasks = new ArrayDeque<Runnable>();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(persistence, () -> 0L, tasks::addLast);
        assertNull(manager.autosaveDirtyTabs(List.of(live), 0L));
        assertEquals(1, tasks.size());

        live.view().engine().insertInput("newer ");
        var frozen = FrozenDocumentSession.create(live.document().freezeSource());
        DocumentLoad external = load(file, "external version", DocumentOpenMode.UTF_8);
        assertTrue(frozen.acceptExternal(frozen.binding(), external, false));
        assertTrue(frozen.unsavedExternalChanges());
        manager.syncFrozen(List.of(frozen));
        assertDoesNotThrow(() -> manager.syncTabs(List.of()));
        live.document().io().stop();

        tasks.removeFirst().run();
        String entry = persistence.snapshots.keySet().iterator().next();
        assertEquals(List.of("old original"), persistence.snapshots.get(entry).lines());
        assertNull(manager.autosaveDirtyTabs(List.of(), 3_000_000_000L));
        assertEquals(1, tasks.size(), "The frozen owner must publish after its predecessor's in-flight snapshot");
        tasks.removeFirst().run();
        manager.autosaveDirtyTabs(List.of(), 3_000_000_000L);
        assertEquals(List.of(entry, entry), persistence.saves);
        assertEquals(List.of("old newer original"), persistence.snapshots.get(entry).lines());
        assertEquals(external.baseline(), persistence.snapshots.get(entry).fileBaseline());
        assertTrue(persistence.deletes.isEmpty(), "A source projection retiring is not a document discard");
    }

    @Test
    void keepingAFrozenVersionRecoversItsExactRawFormatEvenWhenThePreviewStoppedBeforeUnexpectedFinish() throws Exception {
        Path file = directory.resolve("format.md");
        String text = "# Заголовок\r\nraw  \n";
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[encoded.length + 2];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xFE;
        System.arraycopy(encoded, 0, bytes, 2, encoded.length);
        Files.write(file, bytes);
        DocumentLoad original = read(file, DocumentOpenMode.UTF_16LE);
        var frozen = frozen("format", original);
        DocumentLoad external = load(file, "external UTF-8\n", DocumentOpenMode.UTF_8);
        assertTrue(frozen.acceptExternal(frozen.binding(), external, false));
        var persistence = new Persistence();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        manager.syncFrozen(List.of(frozen));
        assertNull(manager.autosaveDirtyTabs(List.of(), 0L));
        String entry = persistence.snapshots.keySet().iterator().next();
        frozen.close();
        assertNull(manager.finishSession(List.of(), false));

        RecoverySnapshot snapshot = persistence.snapshots.get(entry);
        assertEquals(original.lines(), snapshot.lines());
        assertEquals(original.metadata(), snapshot.fileMetadata());
        assertEquals(external.baseline(), snapshot.fileBaseline());
        assertEquals(original.resource().id(), snapshot.currentResource().id());
        assertTrue(snapshot.dirty());
        assertEquals(new Position(0, 0), snapshot.cursor());
        assertNull(snapshot.selectionAnchor());
        assertEquals(0, snapshot.viewport().topLine());
        assertTrue(persistence.deletes.isEmpty());
        assertEquals(List.of(entry, entry), persistence.saves, "Unexpected finish forces the latest cached frozen checkpoint");
    }

    @Test
    void undoOfFrozenUpdateIsRecoverableAndRedoToAcceptedDiskCleansItsCheckpoint() throws Exception {
        Path file = directory.resolve("undo.md");
        var frozen = frozen("undo", load(file, "original", DocumentOpenMode.UTF_8));
        var external = load(file, "updated\r\n", DocumentOpenMode.UTF_8);
        assertTrue(frozen.acceptExternal(frozen.binding(), external, true));
        assertTrue(frozen.undoExternal());
        var persistence = new Persistence();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        manager.syncFrozen(List.of(frozen));
        assertNull(manager.autosaveDirtyTabs(List.of(), 0L));
        String entry = persistence.snapshots.keySet().iterator().next();
        assertEquals(List.of("original"), persistence.snapshots.get(entry).lines());
        assertEquals(external.baseline(), persistence.snapshots.get(entry).fileBaseline());
        assertTrue(frozen.redoExternal());
        assertFalse(frozen.unsavedExternalChanges());
        assertNull(manager.autosaveDirtyTabs(List.of(), 3_000_000_000L));
        assertFalse(persistence.snapshots.containsKey(entry));
        assertEquals(List.of(entry), persistence.deletes);
        assertEquals("updated\r\n", Files.readString(file));
    }

    @Test
    void confirmedDiscardRemovesTheFrozenCheckpointAndLiveProjectionWinsDeduplication() throws Exception {
        var live = TuiDocumentSession.create(new EditorClipboard());
        live.view().engine().insertInput("live draft");
        var frozen = FrozenDocumentSession.create(live.document().freezeSource());
        var persistence = new Persistence();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        manager.syncFrozen(List.of(frozen, frozen));
        assertNull(manager.autosaveDirtyTabs(List.of(live), 0L));
        assertEquals(1, persistence.snapshots.size(), "A source and its projections own one recovery document");
        String entry = persistence.snapshots.keySet().iterator().next();
        manager.syncFrozen(List.of(frozen));
        assertDoesNotThrow(() -> manager.syncTabs(List.of()));
        assertNull(manager.finishSession(List.of(), true));
        assertTrue(persistence.snapshots.isEmpty());
        assertEquals(List.of(entry), persistence.deletes);
    }

    private static DocumentLoad load(Path file, String text, DocumentOpenMode mode) throws Exception {
        Files.writeString(file, text);
        return read(file, mode);
    }

    private static DocumentLoad read(Path file, DocumentOpenMode mode) throws Exception {
        var resource = JvmDocumentStorage.resource(file);
        return resource.storage().load(resource, mode, false);
    }

    private static FrozenDocumentSession frozen(String id, DocumentLoad loaded) {
        return FrozenDocumentSession.create(new FrozenDocumentSource(
            new DocumentSnapshot(new DocumentId(id), 0, loaded.lines()), loaded.metadata(),
            new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline()), DocumentOpenMode.forMetadata(loaded.metadata())));
    }

    private static final class Persistence implements TerminalEditorApp.RecoveryPersistence {
        final Map<String, RecoverySnapshot> snapshots = new HashMap<>();
        final List<String> saves = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        @Override public void save(String id, RecoverySnapshot snapshot) { snapshots.put(id, snapshot); saves.add(id); }
        @Override public void delete(String id) { snapshots.remove(id); deletes.add(id); }
        @Override public void release(String id) {}
    }
}

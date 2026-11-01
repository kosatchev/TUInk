package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.RecoveryStoreFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the public format session API through persisted terminal recovery and real saves. */
class DocumentFormatRecoveryIntegrationTest {
    @TempDir Path directory;

    private record Format(DocumentEncoding preset, String charset, byte[] bom, DocumentOpenMode openMode) {}

    private static final List<Format> FORMATS = List.of(
        new Format(DocumentEncoding.UTF8, "UTF-8", new byte[0], DocumentOpenMode.UTF_8),
        new Format(DocumentEncoding.UTF8_BOM, "UTF-8", new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, DocumentOpenMode.UTF_8),
        new Format(DocumentEncoding.UTF16LE_BOM, "UTF-16LE", new byte[] {(byte) 0xff, (byte) 0xfe}, DocumentOpenMode.UTF_16LE),
        new Format(DocumentEncoding.UTF16BE_BOM, "UTF-16BE", new byte[] {(byte) 0xfe, (byte) 0xff}, DocumentOpenMode.UTF_16BE),
        new Format(DocumentEncoding.WINDOWS_1251, "windows-1251", new byte[0], DocumentOpenMode.WINDOWS_1251),
        new Format(DocumentEncoding.CP866, "IBM866", new byte[0], DocumentOpenMode.CP866),
        new Format(DocumentEncoding.KOI8_R, "KOI8-R", new byte[0], DocumentOpenMode.KOI8_R));

    @Test
    void formatOnlyChangesSurviveDiskRecoveryAndSaveWithExactBytes() throws IOException {
        for (Format format : FORMATS) {
            for (boolean finalNewline : new boolean[] {false, true}) {
                String original = "Привет\r\nмир\nконец" + (finalNewline ? "\r" : "");
                String normalized = "Привет\nмир\nконец" + (finalNewline ? "\n" : "");
                Path source = directory.resolve(format.preset() + "-" + finalNewline + ".txt");
                Files.writeString(source, original, StandardCharsets.UTF_8);
                TuiDocumentSession live = open(source, DocumentOpenMode.DETECT);
                long textRevision = live.document().snapshot().revision();
                apply(live, format.preset(), LineEndingPolicy.LF);
                assertEquals(textRevision, live.document().snapshot().revision(), "format must not rewrite text");
                assertTrue(live.document().status().dirty());
                assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(source));
                RecoverySnapshot snapshot = live.view().engine().captureRecoverySnapshot();

                Path recovery = directory.resolve(format.preset() + "-" + finalNewline + "-recovery");
                try (RecoveryStore store = RecoveryStoreFixtures.open(recovery)) {
                    store.save("format", snapshot);
                }
                RecoverySnapshot recovered;
                try (RecoveryStore store = RecoveryStoreFixtures.open(recovery)) {
                    recovered = store.loadAll().entries().get(0).snapshot();
                }
                assertEquals(snapshot.fileMetadata(), recovered.fileMetadata());
                assertEquals(snapshot.lines(), recovered.lines());
                assertTrue(recovered.dirty());

                TuiDocumentSession restored = TuiDocumentSession.create(new EditorClipboard());
                restored.document().restoreRecovery(restored.viewId(), recovered);
                var work = restored.document().io().beginSave();
                assertInstanceOf(DocumentIoCoordinator.Saved.class,
                    restored.document().io().accept(work, work.perform()));
                assertFalse(restored.document().status().dirty());

                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.write(format.bom());
                expected.write(normalized.getBytes(Charset.forName(format.charset())));
                assertArrayEquals(expected.toByteArray(), Files.readAllBytes(source), format.preset().name());
                TuiDocumentSession reopened = open(source, format.openMode());
                assertEquals(snapshot.lines(), reopened.document().snapshot().lines());
                assertEquals(snapshot.fileMetadata(), reopened.document().status().metadata());
            }
        }
    }

    @Test
    void autosaveObservesRepeatedFormatChangesWithoutTextRevisionChanges() throws IOException {
        Path source = directory.resolve("format-autosave.txt");
        Files.writeString(source, "Привет\r\nмир\n", StandardCharsets.UTF_8);
        TuiDocumentSession live = open(source, DocumentOpenMode.DETECT);
        long textRevision = live.document().snapshot().revision();
        try (RecoveryStore store = RecoveryStoreFixtures.open(directory.resolve("autosave-recovery"))) {
            var manager = new TerminalEditorApp.RecoveryAutosaveManager(new TerminalEditorApp.RecoveryPersistence() {
                public void save(String id, RecoverySnapshot snapshot) throws IOException { store.save(id, snapshot); }
                public void delete(String id) throws IOException { store.delete(id); }
                public void release(String id) throws IOException { store.release(id); }
            }, Runnable::run);
            assertNull(manager.autosaveDirtyTabs(List.of(live), 0L));
            assertTrue(store.loadAll().entries().isEmpty());

            apply(live, DocumentEncoding.UTF8_BOM, LineEndingPolicy.KEEP);
            assertNull(manager.autosaveDirtyTabs(List.of(live), 1L));
            RecoverySnapshot first = store.loadAll().entries().get(0).snapshot();
            assertEquals(live.document().status().metadata(), first.fileMetadata());

            apply(live, DocumentEncoding.KOI8_R, LineEndingPolicy.CRLF);
            assertNull(manager.autosaveDirtyTabs(List.of(live), 3_000_000_001L));
            assertEquals(1, store.loadAll().entries().size());
            RecoverySnapshot second = store.loadAll().entries().get(0).snapshot();
            assertEquals(live.document().status().metadata(), second.fileMetadata());
            assertEquals(first.lines(), second.lines());

            live.execute(new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
            assertNull(manager.autosaveDirtyTabs(List.of(live), 6_000_000_001L));
            assertEquals(first.fileMetadata(), store.loadAll().entries().get(0).snapshot().fileMetadata());
            assertEquals(textRevision, live.document().snapshot().revision());
            assertArrayEquals("Привет\r\nмир\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(source));
        }
    }

    private static void apply(TuiDocumentSession document, DocumentEncoding encoding, LineEndingPolicy endings)
        throws IOException {
        var prepared = document.document().prepareFormatChange(document.viewId(), new DocumentFormatSpec(encoding, endings));
        var result = assertInstanceOf(DocumentCommandResult.Applied.class,
            document.document().completeFormatChange(prepared, prepared.perform()));
        assertTrue(result.performed());
    }

    private static TuiDocumentSession open(Path source, DocumentOpenMode mode) throws IOException {
        var resource = JvmDocumentStorage.resource(source);
        return TuiDocumentSession.create(new EditorClipboard()).openPrepared(resource.storage().load(resource, mode, false));
    }
}

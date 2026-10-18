package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.RecoveryStoreFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpectedAbsentOverwriteIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void confirmedOverwriteWorksForNewAndRecoveredDocumentsAndThenRestoresConflictProtection(
        boolean recovered, boolean alias
    ) throws Exception {
        if (alias) {
            assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        }
        Path target = directory.resolve("new-document.txt");
        Path recoveryDirectory = directory.resolve("recovery");
        if (recovered) {
            TabSession source = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
            source.engine().open(target);
            source.engine().insertInput("draft");
            try (RecoveryStore writer = RecoveryStoreFixtures.open(recoveryDirectory)) {
                writer.save("restored", source.engine().captureRecoverySnapshot());
            }
        }
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("window"), false,
                 List.of("other document"), store.loadAll().entries(), persistence(store))) {
            if (recovered) {
                window.event(new TextInput("y"));
            } else {
                window.active().tabSession().engine().open(target);
                window.event(new TextInput("draft"));
            }
            assertEquals(SaveDisposition.CREATE_NEW, window.active().state().getSaveDisposition());
            assertNull(window.active().tabSession().model().fileBaseline());
            Files.writeString(target, "external");
            Path requested = alias
                ? Files.createSymbolicLink(directory.resolve("alias.txt"), target.getFileName()) : target;

            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertEquals("external", Files.readString(target));
            assertTrue(window.active().state().isDirty());
            SaveWorkflowIntegrationTest.submitFilename(window, requested);
            assertTrue(window.screenRow(window.height - 2).contains("Overwrite"));
            window.event(new TextInput("y"));

            assertEquals("draft", Files.readString(target));
            assertEquals(requested, window.active().state().getCurrentFile());
            assertEquals(SaveDisposition.REPLACE_EXISTING, window.active().state().getSaveDisposition());
            assertFalse(window.active().state().isDirty());
            assertTrue(window.notification.message.startsWith("Saved "), window.notification.message);
            assertTrue(store.loadAll().entries().isEmpty(), "a successful save must clean the dirty checkpoint");
            if (alias) {
                assertTrue(Files.isSymbolicLink(requested));
            }

            window.event(new TextInput("!"));
            Files.writeString(target, "changed after the successful save");
            window.command(TuinkCommand.DOCUMENT_SAVE);

            assertTrue(window.notification.message.contains("File changed outside TUInk"), window.notification.message);
            assertEquals("changed after the successful save", Files.readString(target));
            assertTrue(window.active().state().isDirty());
        }
    }

    private static TerminalEditorApp.RecoveryPersistence persistence(RecoveryStore store) {
        return new TerminalEditorApp.RecoveryPersistence() {
            @Override public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
                store.save(entryId, snapshot);
            }
            @Override public void delete(String entryId) throws IOException {
                store.delete(entryId);
            }
            @Override public void release(String entryId) throws IOException {
                store.release(entryId);
            }
        };
    }
}

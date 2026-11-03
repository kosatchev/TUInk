package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.RecoveryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveAsWorkspaceIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherTabsFileCannotBeReassignedOrOverwritten(boolean ownerDirty) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var owner = window.active();
            if (ownerDirty) window.event(new TextInput("owner "));
            var ownerCursor = owner.state().getCursor();
            var ownerLines = owner.tabSession().model().snapshotLines();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("source draft"));
            var source = window.active();

            SaveWorkflowIntegrationTest.submitFilename(window, window.document);

            assertBlocked(window, window.document);
            assertSame(source, window.active());
            assertNull(JvmDocumentStorage.pathOf(source.state().getCurrentResource()));
            assertTrue(source.state().isDirty());
            assertEquals(ownerLines, owner.tabSession().model().snapshotLines());
            assertEquals(ownerCursor, owner.state().getCursor());
            assertEquals(ownerDirty, owner.state().isDirty());
            assertEquals("original", Files.readString(window.document));

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of(""), source.tabSession().model().snapshotLines());
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.notification.message.startsWith("Saved "));
            assertEquals(ownerDirty ? "owner original" : "original", Files.readString(window.document));
            if (ownerDirty) {
                window.command(TuinkCommand.EDIT_UNDO);
                assertEquals(List.of("original"), owner.tabSession().model().snapshotLines());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"symlink", "hardlink"})
    void physicalAliasesOfAnotherOpenFileAreReserved(String kind) throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path alias = directory.resolve("alias.txt");
            if (kind.equals("symlink")) Files.createSymbolicLink(alias, window.document.getFileName());
            else Files.createLink(alias, window.document);
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("source draft"));

            SaveWorkflowIntegrationTest.submitFilename(window, alias);

            assertBlocked(window, alias);
            assertTrue(Files.isSameFile(alias, window.document));
            assertEquals("original", Files.readString(alias));
            assertEquals(kind.equals("symlink"), Files.isSymbolicLink(alias));
        }
    }

    @Test
    void anUnsavedNamedDocumentReservesItsAbsentFilename() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path absent = directory.resolve("reserved.txt");
            var owner = window.active();
            owner.tabSession().engine().open(JvmDocumentStorage.resource(absent));
            window.event(new TextInput("owner draft"));
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("source draft"));

            SaveWorkflowIntegrationTest.submitFilename(window, absent);

            assertBlocked(window, absent);
            assertFalse(Files.exists(absent));
            assertEquals(List.of("owner draft"), owner.tabSession().model().snapshotLines());
            assertTrue(owner.state().isDirty());
        }
    }

    @Test
    void aTargetRetargetedToAnOpenFileDuringConfirmationReturnsToFilenameEditing() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path unrelated = Files.writeString(directory.resolve("unrelated.txt"), "unrelated");
            Path link = Files.createSymbolicLink(directory.resolve("alias.txt"), unrelated.getFileName());
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("source draft"));
            SaveWorkflowIntegrationTest.submitFilename(window, link);
            assertTrue(window.promptText().contains("Overwrite"));
            Files.delete(link);
            Files.createSymbolicLink(link, window.document.getFileName());

            window.event(new TextInput("y"));

            assertFilenameRetry(window, link);
            assertTrue(window.notification.message.contains("Save target changed outside TUInk"),
                window.notification.message);
            assertTrue(window.notification.message.contains("Choose the target again"),
                window.notification.message);
            assertEquals("original", Files.readString(window.document));
            assertEquals("unrelated", Files.readString(unrelated));
            assertEquals(List.of("source draft"), window.active().tabSession().model().snapshotLines());
            assertNull(JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertTrue(window.active().state().isDirty());
            assertEquals(window.document.getFileName(), Files.readSymbolicLink(link));

            Path retry = directory.resolve("retry.txt");
            fillFilename(window, retry);

            assertEquals("source draft", Files.readString(retry));
            assertEquals(retry, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.active().state().isDirty());
            assertEquals("original", Files.readString(window.document));
            assertEquals("unrelated", Files.readString(unrelated));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingNamesKeepQuitOrClosePendingUntilAnotherNameSucceeds(boolean quitting) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("source draft"));
            window.command(quitting ? TuinkCommand.APP_QUIT : TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            fillFilename(window, window.document);

            assertBlocked(window, window.document);
            assertTrue(window.controller.running());
            Path another = directory.resolve("another.txt");
            fillFilename(window, another);

            assertEquals("source draft", Files.readString(another));
            assertEquals("original", Files.readString(window.document));
            assertEquals(!quitting, window.controller.running());
            assertEquals(quitting ? 2 : 1, window.tabs.size());
        }
    }

    @Test
    void saveAsTheActiveDocumentsOwnPathRemainsARegularSave() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            SaveWorkflowIntegrationTest.submitFilename(window, window.document);
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
            assertEquals(1, window.tabs.size());
        }
    }

    @Test
    void retargetingTheOpeningAliasAllowsExplicitSaveAsToTheOtherPhysicalFileAfterConfirmation() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path other = Files.writeString(directory.resolve("other.txt"), "other original");
            Path link = Files.createSymbolicLink(directory.resolve("opening-alias.txt"), window.document.getFileName());
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(link));
            window.event(new TextInput("draft "));
            Files.delete(link);
            Files.createSymbolicLink(link, other.getFileName());

            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.notification.message.contains("File changed outside TUInk"));
            assertTrue(window.active().state().isDirty());
            SaveWorkflowIntegrationTest.submitFilename(window, other);

            assertTrue(window.promptText().contains("Overwrite"));
            assertEquals("other original", Files.readString(other));
            window.event(new TextInput("y"));

            assertEquals("draft original", Files.readString(other));
            assertEquals("original", Files.readString(window.document));
            assertEquals(other, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.active().state().isDirty());
            assertTrue(window.notification.message.startsWith("Saved "));
            assertEquals(other.getFileName(), Files.readSymbolicLink(link));
        }
    }

    @Test
    void saveAsTheOriginalAliasOrChangedLoadedTargetStillRefusesToBypassTheBaseline() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path other = Files.writeString(directory.resolve("unrelated.txt"), "other original");
            Path link = Files.createSymbolicLink(directory.resolve("current-alias.txt"), window.document.getFileName());
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(link));
            window.event(new TextInput("draft "));
            Files.delete(link);
            Files.createSymbolicLink(link, other.getFileName());
            Files.writeString(window.document, "external original change");

            for (Path target : List.of(link, window.document)) {
                SaveWorkflowIntegrationTest.submitFilename(window, target);
                assertTrue(window.notification.message.contains("File changed outside TUInk"));
                assertTrue(window.screenRow(window.height - 2).startsWith("Save As: "));
                assertEquals("other original", Files.readString(other));
                assertEquals("external original change", Files.readString(window.document));
                assertTrue(window.active().state().isDirty());
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            }
        }
    }

    @Test
    void saveAsTheDeletedOwnedNameFailsWhileAnotherNewFilenameCanStillBeCreated() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            Files.delete(window.document);

            SaveWorkflowIntegrationTest.submitFilename(window, window.document);

            assertTrue(window.notification.message.contains("File changed outside TUInk"));
            assertFalse(Files.exists(window.document));
            assertTrue(window.active().state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            Path copy = directory.resolve("another-name.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, copy);

            assertEquals("draft original", Files.readString(copy));
            assertFalse(Files.exists(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void aRecoveredDocumentsExistingIdentityCanStillBeSavedWithSaveAs() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var recovered = window.host.newEditorTab();
            recovered.tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document));
            recovered.tabSession().engine().insertInput("recovered ");
            window.restore(List.of(new RecoveryStore.Entry("draft", recovered.tabSession().engine().captureRecoverySnapshot())));

            SaveWorkflowIntegrationTest.submitFilename(window, window.document);

            assertEquals("recovered original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
            assertEquals(2, window.tabs.size());
        }
    }

    private static void fillFilename(EditorWindowFixture window, Path path) {
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(path.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private static void assertBlocked(EditorWindowFixture window, Path target) {
        assertFilenameRetry(window, target);
        assertTrue(window.notification.message.contains("already open in tab 1"), window.notification.message);
    }

    private static void assertFilenameRetry(EditorWindowFixture window, Path target) {
        assertTrue(window.controller.running());
        assertEquals(2, window.tabs.size());
        assertEquals(target.toString(), window.filename.text());
        assertTrue(window.screenRow(window.height - 2).startsWith("Save As: "));
    }
}

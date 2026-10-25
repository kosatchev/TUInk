package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveAsSuggestionIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"save", "quit", "close"})
    void readOnlySaveFlowsOfferAnImmediatelyUsableCopyInsteadOfAnotherTabsMissingFile(String action)
        throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("edited "));
            Path reserved = directory.resolve("document-copy.txt");
            window.command(TuinkCommand.TAB_NEW);
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(reserved));
            window.frame();
            Files.setPosixFilePermissions(window.document, PosixFilePermissions.fromString("r--------"));
            assumeTrue(!Files.isWritable(window.document));

            if (action.equals("quit")) {
                window.command(TuinkCommand.APP_QUIT);
                window.event(new TextInput("y"));
            } else {
                window.command(TuinkCommand.TAB_PREVIOUS);
                window.command(action.equals("save") ? TuinkCommand.DOCUMENT_SAVE : TuinkCommand.TAB_CLOSE);
                if (action.equals("close")) window.event(new TextInput("y"));
            }
            Path expected = directory.resolve("document-copy-1.txt");
            assertEquals(expected.toString(), window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("edited original", Files.readString(expected));
            assertEquals("original", Files.readString(window.document));
            assertFalse(Files.exists(reserved));
            assertEquals(!action.equals("quit"), window.controller.running());
            assertEquals(action.equals("close") ? 1 : 2, window.tabs.size());
            assertEquals("", window.filename.text());
        }
    }

    @Test
    void submittingAReadOnlyExternalTargetUsesTheSameWorkspaceAwareCopyPolicy() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path target = Files.writeString(directory.resolve("external.txt"), "external");
        Path reserved = directory.resolve("external-copy.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.TAB_NEW);
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(reserved));
            window.frame();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("edited "));
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--------"));
            assumeTrue(!Files.isWritable(target));

            SaveWorkflowIntegrationTest.submitFilename(window, target);

            Path expected = directory.resolve("external-copy-1.txt");
            assertEquals(expected.toString(), window.filename.text());
            assertTrue(window.notification.message.contains("target is read-only"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("edited original", Files.readString(expected));
            assertEquals("external", Files.readString(target));
            assertEquals("original", Files.readString(window.document));
            assertFalse(Files.exists(reserved));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void anEditedFilenameSurvivesCancelAndAConflictingWorkspaceChange() throws Exception {
        Path manual = directory.resolve("chosen.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("edited "));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(manual.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.TAB_NEW);
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(manual));
            window.frame();
            window.command(TuinkCommand.TAB_PREVIOUS);

            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertEquals(manual.toString(), window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertTrue(window.notification.message.contains("already open in tab"));
            assertEquals(manual.toString(), window.filename.text());
            assertFalse(Files.exists(manual));
            assertTrue(window.active().state().isDirty());
            assertEquals("original", Files.readString(window.document));
        }
    }
}

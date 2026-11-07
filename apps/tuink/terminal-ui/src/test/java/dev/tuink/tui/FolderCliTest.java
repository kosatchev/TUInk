package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderCliTest {
    @TempDir Path directory;

    @Test
    void existingDirectoryBecomesExplorerRootWithoutBindingItToTheBlankDocument() throws Exception {
        Path child = Files.createDirectory(directory.resolve("child"));
        try (Bootstrap bootstrap = new Bootstrap()) {
            Path selected = bootstrap.open(child.resolve("..").toString());
            assertEquals(directory.toAbsolutePath().normalize(), selected);
            assertNull(bootstrap.context.state().getCurrentResource());
            assertEquals("", bootstrap.context.model().getLine(0));
            assertFalse(bootstrap.context.state().isDirty());
            assertNull(bootstrap.notification.message);
        }
    }

    @Test
    void currentDirectoryAndEncodedDirectoryArgumentsUseTheSameClassification() {
        try (Bootstrap bootstrap = new Bootstrap()) {
            assertEquals(Path.of("").toAbsolutePath().normalize(), bootstrap.open("."));
        }
        try (Bootstrap bootstrap = new Bootstrap()) {
            assertEquals(directory.toAbsolutePath().normalize(),
                bootstrap.open("--file-uri=" + directory.toUri().toASCIIString()));
        }
    }

    @Test
    void regularFilesStillLoadIntoTheDocument() throws Exception {
        Path file = Files.writeString(directory.resolve("notes.txt"), "hello\nworld");
        try (Bootstrap bootstrap = new Bootstrap()) {
            assertNull(bootstrap.open(file.toString()));
            assertEquals(file, JvmDocumentStorage.pathOf(bootstrap.context.state().getCurrentResource()));
            assertEquals("hello", bootstrap.context.model().getLine(0));
            assertEquals("world", bootstrap.context.model().getLine(1));
            assertFalse(bootstrap.context.state().isDirty());
            assertNull(bootstrap.notification.message);
        }
    }

    @Test
    void missingPathRemainsANewFileAndNoArgumentRemainsAnUnnamedDraft() {
        Path missing = directory.resolve("new.txt");
        try (Bootstrap bootstrap = new Bootstrap()) {
            assertNull(bootstrap.open(missing.toString()));
            assertEquals(missing, JvmDocumentStorage.pathOf(bootstrap.context.state().getCurrentResource()));
            assertEquals("", bootstrap.context.model().getLine(0));
            assertFalse(Files.exists(missing));
            assertNull(bootstrap.notification.message);
        }
        try (Bootstrap bootstrap = new Bootstrap()) {
            assertNull(bootstrap.open());
            assertNull(bootstrap.context.state().getCurrentResource());
            assertNull(bootstrap.notification.message);
        }
    }

    private static final class Bootstrap implements AutoCloseable {
        final TerminalEditorApp host = new TerminalEditorApp();
        final TerminalEditorApp.SessionContext context =
            new TerminalEditorApp.SessionContext(List.of(host.newEditorTab()), 0);
        final TerminalEditorApp.NotificationState notification = host.new NotificationState();

        Path open(String... args) {
            return host.openInitialDocument(TuiCliOptions.parse(args), context, notification);
        }

        @Override public void close() {
            for (WindowTab tab : context.presentations()) tab.uiState().close();
            host.closeFileAccess();
        }
    }
}

package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.OpenFileDialogController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FilePickerFeedbackIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"8,80", "9,80", "10,80", "24,80", "8,24"})
    void permissionFailureHasAStableLocalRowAndSurvivesNavigation(int rows, int columns)
        throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path denied = Files.writeString(directory.resolve("z-unreadable.txt"), "selected");
        Files.setPosixFilePermissions(denied, Set.of());
        try {
            assumeTrue(!Files.isReadable(denied), "Requires an unprivileged reader");
            try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
                window.event(new TextInput("draft "));
                var original = window.active();
                var builder = window.context.renderPlanBuilder();
                window.resize(rows, columns);
                openAndSelectLast(window, denied);
                String listRowBefore = window.screenRow(OpenFileDialogController
                    .layoutForTerminal(rows, columns).listStartRow());

                window.event(KeyStroke.of(KeyStroke.Key.ENTER));

                assertStatus(window, "permission denied");
                assertEquals(listRowBefore, window.screenRow(OpenFileDialogController
                    .layoutForTerminal(rows, columns).listStartRow()), "Feedback must not move the tree");
                assertSame(original, window.active());
                assertSame(builder, window.context.renderPlanBuilder());
                assertEquals(1, window.tabs.size());
                assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_UP));
                window.pointer(PointerEvent.Kind.SCROLL_DOWN, 3, columns / 2);
                window.event(KeyStroke.of(KeyStroke.Key.TAB));
                assertStatus(window, "permission denied");


                Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("rw-------"));
                window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
                window.event(KeyStroke.of(KeyStroke.Key.END));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertEquals(2, window.tabs.size());
                assertEquals(denied, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
                assertTrue(original.state().isDirty());
                assertTrue(original.tabSession().engine().undo());
                assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
            }
        } finally {
            Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void cancelledFailureIsNotReusedWhenThePickerOpensAgain() throws Exception {
        Path missing = Files.writeString(directory.resolve("z-missing.txt"), "selected");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.resize(8, 80);
            openAndSelectLast(window, missing);
            Files.delete(missing);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertStatus(window, "file no longer exists");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            assertFalse(status(window).contains("file no longer exists"));
            assertEquals(1, window.tabs.size());
        }
    }

    @Test
    void asynchronousUnreadableDirectoryReportsTheReasonInTheSameLocalRow() throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path denied = Files.createDirectory(directory.resolve("blocked"));
        Files.setPosixFilePermissions(denied, Set.of());
        try {
            assumeTrue(!Files.isReadable(denied), "Requires an unprivileged reader");
            try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
                window.resize(8, 80);
                window.command(TuinkCommand.DOCUMENT_OPEN);
                window.await(() -> !window.tree.loading());
                window.event(KeyStroke.of(KeyStroke.Key.HOME));
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
                assertEquals(denied, window.tree.state().selectedId().path());
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
                window.await(() -> window.tree.directoryFailure() != null);
                assertStatus(window, "permission denied");
                assertEquals(1, window.tabs.size());
            }
        } finally {
            Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void longPathsKeepTheReasonAndFilenameInTheLocalMessage() {
        Path file = directory.resolve("long-parent/".repeat(12)).resolve("selected.txt");
        String rendered = dev.tuink.tui.ui.NotificationPresentation.fitReasonWithPath("permission denied", file, 60);
        assertTrue(rendered.startsWith("permission denied: "), rendered);
        assertTrue(rendered.endsWith("selected.txt"), rendered);
        assertTrue(rendered.contains("…"), rendered);
        assertTrue(dev.tuiframe.cell.CellText.width(rendered) <= 60);
    }

    private static void openAndSelectLast(EditorWindowFixture window, Path target) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(target, window.tree.state().selectedId().path());
    }

    private static String status(EditorWindowFixture window) {
        int row = OpenFileDialogController.layoutForTerminal(window.height, window.width).bottom() - 1;
        return window.screenRow(row);
    }

    private static void assertStatus(EditorWindowFixture window, String text) {
        assertTrue(status(window).contains(text), status(window));
    }

    private static void assertOnScreen(EditorWindowFixture window, String text) {
        assertTrue(java.util.stream.IntStream.range(0, window.height)
            .anyMatch(row -> window.screenRow(row).contains(text)), text);
    }
}

package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SavePathInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class NotificationIntegrationTest {
    @TempDir Path directory;

    @Test
    void aPersistentWarningSurvivesUserAcknowledgementAndHasNoExpiry() {
        var host = new TerminalEditorApp();
        var warning = host.new NotificationState();
        warning.persistentWarning("Settings changes apply only to this session");
        warning.clearErrorOnUserAction();
        assertEquals("Settings changes apply only to this session", warning.message);
        assertEquals(host.styles.windows().notifyWarning(), warning.style());
        assertTrue(warning.requiresAttention());
        assertFalse(warning.isError());
        assertEquals(-1L, warning.nanosUntilExpire(Long.MAX_VALUE));
        assertFalse(warning.expireIfNeeded());
    }

    @Test
    void savingAFilenameContainingNewlineDisplaysItsExactSafeIdentity() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path file = Files.writeString(directory.resolve("first\nsecond.txt"), "contents");
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(file));
            window.frame();
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertEquals("draft contents", Files.readString(file));
            assertTrue(window.notification.message.contains(SavePathInput.format(file)));
            assertFalse(window.notification.message.contains("\n"));
            assertTrue(window.screenRow(window.height - 2).startsWith("Saved file:"));
            assertEquals(List.of("draft contents"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "info", "warning", "error"})
    void everyLevelDisplaysExternalControlsOnExactlyOneRow(String level) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.await(() -> window.screenRow(window.height - 1).contains("RW | ENC:"));
            List<String> before = java.util.stream.IntStream.range(0, window.height)
                .mapToObj(window::screenRow).toList();
            String raw = "A\nB\rC\tD\u001B\u0085界";
            switch (level) {
                case "success" -> window.notification.success(raw);
                case "info" -> window.notification.info(raw);
                case "warning" -> window.notification.warning(raw);
                case "error" -> window.notification.error(raw);
                default -> throw new AssertionError(level);
            }
            window.frame();
            assertTrue(window.screenRow(window.height - 2).contains("A\\nB\\rC\\tD\\u001B\\u0085界"));
            for (int row = 0; row < window.height; row++) {
                if (row != window.height - 2) assertEquals(before.get(row), window.screenRow(row));
            }
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
}

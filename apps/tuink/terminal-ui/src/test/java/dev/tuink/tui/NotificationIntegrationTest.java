package dev.tuink.tui;

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

    @ParameterizedTest
    @ValueSource(strings = {"success", "info", "warning", "error"})
    void everyLevelDisplaysExternalControlsOnExactlyOneRow(String level) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
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

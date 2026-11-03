package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuiframe.input.KeyStroke;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyboardTabWorkflowIntegrationTest {
    @TempDir Path directory;

    @Test
    void physicalShortcutsSwitchBothWaysAndCloseTheActiveTab() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(""))) {
            var first = window.active();
            window.event(KeyStroke.of(KeyStroke.Key.N, KeyStroke.Modifier.CTRL));
            var second = window.active();
            assertEquals(2, window.tabs.size());
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT));
            assertSame(first, window.active());
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL));
            assertSame(second, window.active());
            window.event(KeyStroke.of(KeyStroke.Key.W, KeyStroke.Modifier.CTRL));
            assertEquals(1, window.tabs.size());
            assertSame(first, window.active());
        }
    }
}

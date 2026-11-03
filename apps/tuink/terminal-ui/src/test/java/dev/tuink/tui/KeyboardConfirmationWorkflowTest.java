package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real input/frame/save pipeline, including application focus interception. */
class KeyboardConfirmationWorkflowTest {
    @TempDir Path directory;

    @Test void tabFromSafeCancelSelectsSaveInsteadOfTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            window.event(ctrl(KeyStroke.Key.Q));
            assertTrue(screen(window).contains("Ctrl+S"));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(window.controller.running());
            assertEquals("draft original", Files.readString(window.document));
        }
    }

    @Test void enterCancelsByDefaultAndCtrlSSavesEvenInANarrowPrompt() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            window.event(ctrl(KeyStroke.Key.Q));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.controller.running());
            assertTrue(window.active().state().isDirty());
            assertEquals("original", Files.readString(window.document));
            window.resize(24, 18);
            window.event(ctrl(KeyStroke.Key.Q));
            assertFalse(screen(window).contains("Enlarge"));
            window.event(ctrl(KeyStroke.Key.S));
            assertFalse(window.controller.running());
            assertEquals("draft original", Files.readString(window.document));
        }
    }

    @Test void overwriteUsesActionNamesAndChoosingAnotherNameKeepsTheDraft() throws Exception {
        Path victim = directory.resolve("existing.md"), other = directory.resolve("other.md");
        Files.writeString(victim, "KEEP");
        try (var window = new EditorWindowFixture(directory.resolve("editor"), false, List.of("original"))) {
            window.resize(24, 120);
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new TextInput(victim.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(screen(window).contains("[Replace]"));
            assertTrue(screen(window).contains("[Choose another name]"));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.active().uiState().modalState.isSaveAs());
            assertEquals("KEEP", Files.readString(victim));
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new TextInput(other.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("draft original", Files.readString(other));
            assertEquals("KEEP", Files.readString(victim));
        }
    }

    @Test void ctrlSCompletesTheNormalCloseTabSaveFlow() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var closing = window.active();
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(ctrl(KeyStroke.Key.S));
            assertTrue(window.controller.running());
            assertFalse(window.tabs.contains(closing));
            assertEquals("draft original", Files.readString(window.document));
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
    private static String screen(EditorWindowFixture window) {
        return java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow)
            .collect(java.util.stream.Collectors.joining("\n"));
    }
}

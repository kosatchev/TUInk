package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real input and quit workflow, including the first prompt frame and on-disk contents. */
class QuitDiscardShortcutIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstCtrlQAsksAndTheNextDiscardsFromSourceOrItsLivePreview(boolean preview) throws Exception {
        try (var window = markdownWindow()) {
            window.event(new TextInput("draft "));
            if (preview) window.event(ctrl(KeyStroke.Key.P));
            window.event(ctrl(KeyStroke.Key.Q));
            assertTrue(window.controller.running());
            assertTrue(window.promptText().contains("Ctrl+Q=Discard"));
            assertEquals("original", Files.readString(window.document));

            window.event(ctrl(KeyStroke.Key.Q));

            assertFalse(window.controller.running());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noDiscardsFromSourceOrItsLivePreview(boolean preview) throws Exception {
        try (var window = markdownWindow()) {
            window.event(new TextInput("draft "));
            if (preview) window.event(ctrl(KeyStroke.Key.P));
            window.event(ctrl(KeyStroke.Key.Q));
            assertTrue(window.controller.running());
            assertTrue(window.promptText().contains("N/Ctrl+Q=Discard  Esc=Cancel"));

            window.event(new TextInput("N"));

            assertFalse(window.controller.running());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quitShortcutDiscardsAllDirtyDocumentsWithoutWritingEitherFile(boolean ctrlQ) throws Exception {
        Path other = directory.resolve("other.md");
        Files.writeString(other, "second original");
        try (var window = markdownWindow()) {
            window.event(new TextInput("first draft "));
            var second = window.host.newEditorTab();
            second.tabSession().engine().open(JvmDocumentStorage.resource(other));
            second.tabSession().engine().insertInput("second draft ");
            window.context.addAll(List.of(second));
            window.resize(24, 120);
            window.event(ctrl(KeyStroke.Key.Q));
            assertTrue(window.controller.running());
            assertTrue(window.promptText().contains("Ctrl+Q=Discard All"));
            assertTrue(window.promptText().contains("Unsaved (2)"));

            window.event(ctrlQ ? ctrl(KeyStroke.Key.Q) : new TextInput("n"));

            assertFalse(window.controller.running());
            assertEquals("original", Files.readString(window.document));
            assertEquals("second original", Files.readString(other));
        }
    }

    @Test void deliveredCtrlQRepeatsDecideOnlyAfterTheFirstPromptFrame() throws Exception {
        try (var window = markdownWindow()) {
            window.event(new TextInput("draft "));
            // Legacy terminal input does not distinguish a repeat from another press.
            for (int event = 0; event < 4 && window.controller.running(); event++) {
                window.event(ctrl(KeyStroke.Key.Q));
                assertEquals(event == 0, window.controller.running());
            }
            assertFalse(window.controller.running());
            assertEquals("original", Files.readString(window.document));
        }
    }

    private EditorWindowFixture markdownWindow() throws Exception {
        return new EditorWindowFixture(directory, "document.md", List.of("original"),
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
    }
    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}

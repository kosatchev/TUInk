package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.input.KeyStroke;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClipboardNoopIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"C,false", "C,true", "X,false", "X,true"})
    void anEmptyFinalLineReportsNoWorkAndKeepsTheCopiedText(KeyStroke.Key operation, boolean newTab) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("payload", ""))) {
            window.event(ctrl(KeyStroke.Key.C));
            assertEquals("payload\n", window.host.clipboard.text());
            if (newTab) {
                window.event(ctrl(KeyStroke.Key.N));
            } else {
                window.event(KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.CTRL));
            }
            var tab = window.active().tabSession();
            List<String> before = tab.model().snapshotLines();
            long revision = tab.model().revision();
            var cursor = tab.state().getCursor();

            window.event(ctrl(operation));

            assertEquals(operation == KeyStroke.Key.C ? "Nothing to copy" : "Nothing to cut", window.notification.message);
            assertEquals("payload\n", window.host.clipboard.text());
            assertEquals(before, tab.model().snapshotLines());
            assertEquals(revision, tab.model().revision());
            assertEquals(cursor, tab.state().getCursor());
            assertFalse(tab.state().isDirty());
            assertFalse(tab.engine().undo());

            window.event(ctrl(KeyStroke.Key.V));

            assertEquals("Pasted", window.notification.message);
            assertEquals(newTab ? List.of("payload", "") : List.of("payload", "payload", ""),
                tab.model().snapshotLines());
            assertEquals("payload\n", Files.readString(window.document));
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

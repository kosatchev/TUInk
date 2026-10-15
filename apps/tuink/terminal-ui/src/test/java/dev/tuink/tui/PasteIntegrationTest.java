package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasteIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void externalPasteIsLiteralAndKeepsTheApplicationClipboard() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("internal"))) {
            window.event(ctrl(KeyStroke.Key.C));
            window.event(KeyStroke.of(KeyStroke.Key.END));

            window.event(new PasteEvent("("));

            assertEquals("internal(", window.active().tabSession().model().getLine(0));
            window.event(ctrl(KeyStroke.Key.V));
            assertEquals("internal(internal", window.active().tabSession().model().getLine(0));
        }
    }

    @Test
    void externalPasteDoesNotOvertypeAnExistingClosingPair() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(")"))) {
            window.event(new PasteEvent(")"));

            assertEquals("))", window.active().tabSession().model().getLine(0));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(")", window.active().tabSession().model().getLine(0));
        }
    }

    @Test
    void undoSeparatesExternalPasteFromTypingBeforeAndAfterIt() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(""))) {
            window.event(new TextInput("a"));
            window.event(new PasteEvent("PASTED"));
            window.event(new TextInput("b"));

            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals("aPASTED", window.active().tabSession().model().getLine(0));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals("a", window.active().tabSession().model().getLine(0));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals("", window.active().tabSession().model().getLine(0));
            assertFalse(window.active().state().isDirty());
            window.event(ctrl(KeyStroke.Key.Y));
            window.event(ctrl(KeyStroke.Key.Y));
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals("aPASTEDb", window.active().tabSession().model().getLine(0));
        }
    }

    @Test
    void externalUnicodePasteReplacesASelectionAsOneStep() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("😀OLDtail"))) {
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            for (int count = 0; count < 3; count++) {
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
            }

            window.event(new PasteEvent("👩‍💻\r\nx"));

            assertEquals(List.of("😀👩‍💻", "xtail"), window.active().tabSession().model().snapshotLines());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("😀OLDtail"), window.active().tabSession().model().snapshotLines());
            assertEquals(new Position(0, 5), window.active().state().getCursor());
            assertEquals(new Position(0, 2), window.active().state().getSelectionAnchor());
        }
    }

    @Test
    void readingModeAndTruncatedPasteKeepTheDocumentUnchanged() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory.resolve("reading"), true, List.of("keep"))) {
            window.active().tabSession().engine().restoreSelection(new Position(0, 4), new Position(0, 0));
            window.frame();

            window.event(new PasteEvent("("));

            assertEquals("keep", window.active().tabSession().model().getLine(0));
            assertEquals(new Position(0, 4), window.active().state().getCursor());
            assertEquals(new Position(0, 0), window.active().state().getSelectionAnchor());
            assertFalse(window.active().state().isDirty());
        }
        try (EditorWindowFixture window = new EditorWindowFixture(directory.resolve("truncated"), false, List.of("keep"))) {
            window.event(new PasteEvent("discard this", true));

            assertEquals("keep", window.active().tabSession().model().getLine(0));
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
        }
    }

    @Test
    void aModalFieldOwnsExternalPaste() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("keep"))) {
            window.event(ctrl(KeyStroke.Key.W));
            window.event(ctrl(KeyStroke.Key.A));

            window.event(new PasteEvent("draft.txt"));

            assertEquals("draft.txt", window.filename.text());
            assertEquals("keep", window.active().tabSession().model().getLine(0));
            assertFalse(window.active().state().isDirty());
            assertTrue(window.rowStartingWith("Save As:") > 0);
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

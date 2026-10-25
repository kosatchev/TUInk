package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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

class ReadOnlySaveSuggestionIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void aFileWhichBecameReadOnlyImmediatelyOffersASaveableCopy() throws Exception {
        try (EditorWindowFixture window = savedWindow()) {
            makeReadOnly(window.document);
            try {
                window.event(new TextInput("B"));
                window.event(ctrl(KeyStroke.Key.S));

                Path copy = directory.resolve("document-copy.txt");
                assertEquals(copy.toString(), window.filename.text());
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertSavedCopy(window, copy);
            } finally {
                makeWritable(window.document);
            }
        }
    }

    @Test
    void aSuitableCancelledSaveAsDraftSurvivesTheReadOnlyTransition() throws Exception {
        try (EditorWindowFixture window = savedWindow()) {
            Path chosen = directory.resolve("my-chosen-name.txt");
            window.event(ctrl(KeyStroke.Key.K));
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new TextInput(chosen.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            makeReadOnly(window.document);
            try {
                window.event(new TextInput("B"));
                window.event(ctrl(KeyStroke.Key.S));

                assertEquals(chosen.toString(), window.filename.text());
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertSavedCopy(window, chosen);
            } finally {
                makeWritable(window.document);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quitAndCloseContinuationsAlsoOfferTheWritableCopyBeforeTheFirstSubmit(boolean quitting) throws Exception {
        try (EditorWindowFixture window = savedWindow()) {
            makeReadOnly(window.document);
            try {
                window.event(new TextInput("B"));
                if (quitting) {
                    window.event(ctrl(KeyStroke.Key.Q));
                } else {
                    window.command(TuinkCommand.TAB_CLOSE);
                }
                window.event(KeyStroke.of(KeyStroke.Key.HOME));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                Path copy = directory.resolve("document-copy.txt");
                assertEquals(copy.toString(), window.filename.text());

                window.event(KeyStroke.of(KeyStroke.Key.ENTER));

                assertEquals("ABalpha", Files.readString(copy));
                assertEquals("Aalpha", Files.readString(window.document));
                if (quitting) {
                    assertFalse(window.controller.running());
                } else {
                    assertTrue(window.controller.running());
                    assertEquals(1, window.tabs.size());
                    assertNull(JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
                    assertEquals(List.of(""), window.active().tabSession().model().snapshotLines());
                }
            } finally {
                makeWritable(window.document);
            }
        }
    }

    private EditorWindowFixture savedWindow() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("alpha"));
        window.event(new TextInput("A"));
        window.event(ctrl(KeyStroke.Key.S));
        assertEquals("Aalpha", Files.readString(window.document));
        return window;
    }

    private static void makeReadOnly(Path path) throws Exception {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--r--r--"));
        assumeFalse(Files.isWritable(path), "test requires a user that respects the file's read-only permissions");
    }

    private static void makeWritable(Path path) throws Exception {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
    }

    private static void assertSavedCopy(EditorWindowFixture window, Path copy) throws Exception {
        assertEquals(copy, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
        assertFalse(window.active().state().isDirty());
        assertEquals("ABalpha", Files.readString(copy));
        assertEquals("Aalpha", Files.readString(window.document));
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

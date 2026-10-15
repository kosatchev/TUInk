package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.DocumentFileMetadata;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Encoding failures stay actionable through the production save and clipboard routes. */
class SaveEncodingErrorIntegrationTest {
    private static final Charset LEGACY_CHARSET = Charset.forName("windows-1251");
    private static final String ORIGINAL = "Привет\r\n";
    private static final String COPIED_TEXT = "😀Привет\n";
    private static final String EXPECTED_ERROR =
        "Save failed: windows-1251 cannot encode U+1F600. Copy to a new UTF-8 tab.";

    @TempDir
    Path directory;

    @Test
    void saveAndSaveAsExplainTheEncodingFailureAndKeepTheDraftRecoverable() throws Exception {
        try (EditorWindowFixture window = legacyWindow()) {
            DocumentFileMetadata metadata = window.active().tabSession().model().fileMetadata();
            window.event(new TextInput("😀"));

            window.event(ctrl(KeyStroke.Key.S));

            assertEncodingError(window);
            assertArrayEquals(ORIGINAL.getBytes(LEGACY_CHARSET), Files.readAllBytes(window.document));
            Path target = directory.resolve("new-name.txt");
            window.event(ctrl(KeyStroke.Key.K));
            replaceFilename(window, target);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEncodingError(window);
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager.focused().orElseThrow());
            assertEquals(target.toString(), window.filename.text());
            assertFalse(Files.exists(target));
            assertArrayEquals(ORIGINAL.getBytes(LEGACY_CHARSET), Files.readAllBytes(window.document));
            assertEquals(metadata, window.active().tabSession().model().fileMetadata());
            assertTrue(window.active().state().isDirty());
            assertNull(window.recoveryManager.finishSession(List.of(window.active().tabSession()), false));
            RecoverySnapshot recovery = window.recoverySnapshots.values().iterator().next();
            assertEquals(List.of("😀Привет", ""), recovery.lines());
            assertEquals(metadata, recovery.fileMetadata());
        }
    }

    @Test
    void aNewUtf8TabCanSaveTheCompleteDraftThroughTheInternalClipboard() throws Exception {
        try (EditorWindowFixture window = legacyWindow()) {
            window.event(new TextInput("😀"));
            window.event(ctrl(KeyStroke.Key.K));
            Path failedTarget = directory.resolve("legacy-copy.txt");
            replaceFilename(window, failedTarget);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEncodingError(window);

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(ctrl(KeyStroke.Key.A));
            window.event(ctrl(KeyStroke.Key.C));
            assertEquals(COPIED_TEXT, window.host.clipboard.text());
            window.event(ctrl(KeyStroke.Key.N));
            window.event(ctrl(KeyStroke.Key.V));
            assertEquals(StandardCharsets.UTF_8, window.active().state().getFileEncoding());
            window.event(ctrl(KeyStroke.Key.S));
            Path utf8Copy = directory.resolve("utf8-copy.txt");
            replaceFilename(window, utf8Copy);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertArrayEquals(COPIED_TEXT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(utf8Copy));
            assertFalse(window.active().state().isDirty());
            assertArrayEquals(ORIGINAL.getBytes(LEGACY_CHARSET), Files.readAllBytes(window.document));
            assertFalse(Files.exists(failedTarget));
            assertTrue(window.tabs.getFirst().state().isDirty());
            assertEquals(LEGACY_CHARSET, window.tabs.getFirst().state().getFileEncoding());
        }
    }

    private EditorWindowFixture legacyWindow() throws Exception {
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("placeholder"));
        try {
            Files.write(window.document, ORIGINAL.getBytes(LEGACY_CHARSET));
            window.active().tabSession().engine().open(window.document);
            window.frame();
            return window;
        } catch (Exception | Error failure) {
            window.close();
            throw failure;
        }
    }

    private static void replaceFilename(EditorWindowFixture window, Path target) {
        window.event(ctrl(KeyStroke.Key.A));
        window.event(new TextInput(target.toString()));
    }

    private static void assertEncodingError(EditorWindowFixture window) {
        assertEquals(EXPECTED_ERROR, window.notification.message);
        assertTrue(IntStream.range(0, window.height)
            .anyMatch(row -> window.screenRow(row).contains(EXPECTED_ERROR)), "encoding error must remain visible");
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

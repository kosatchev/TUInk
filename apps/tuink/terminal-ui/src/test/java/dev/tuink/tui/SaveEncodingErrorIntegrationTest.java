package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
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
        "Save failed: windows-1251 cannot encode U+1F600. Choose Save as UTF-8 to keep this text.";

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
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager.focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER)); // Safe default cancels the conversion.
            Path target = directory.resolve("new-name.txt");
            window.event(ctrl(KeyStroke.Key.K));
            replaceFilename(window, target);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEncodingError(window);
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager.focused().orElseThrow());
            assertEquals(target.toString(), window.filename.text());
            assertFalse(Files.exists(target));
            assertArrayEquals(ORIGINAL.getBytes(LEGACY_CHARSET), Files.readAllBytes(window.document));
            assertEquals(metadata, window.active().tabSession().model().fileMetadata());
            assertTrue(window.active().state().isDirty());
            assertNull(window.recoveryManager.finishSession(List.of(window.active().documentSession()), false));
            RecoverySnapshot recovery = window.recoverySnapshots.values().iterator().next();
            assertEquals(List.of("😀Привет", ""), recovery.lines());
            assertEquals(metadata, recovery.fileMetadata());
        }
    }

    @Test
    void explicitUtf8SavePreservesEolHistoryAndReportsFormatUndo() throws Exception {
        try (EditorWindowFixture window = legacyWindow()) {
            window.event(new TextInput("😀"));
            window.event(ctrl(KeyStroke.Key.S));
            window.event(new TextInput("y"));
            assertTrue(window.screenRow(window.height - 2).startsWith("Save UTF-8: "));
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager.focused().orElseThrow());
            Path target = directory.resolve("utf8-copy.txt");
            replaceFilename(window, target);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertArrayEquals("😀Привет\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
            assertFalse(window.active().state().isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(LEGACY_CHARSET, window.active().state().getFileEncoding());
            assertEquals(List.of("😀Привет", ""), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.notification.message.contains("encoding restored to windows-1251"));
            window.command(TuinkCommand.EDIT_REDO);
            assertEquals(StandardCharsets.UTF_8, window.active().state().getFileEncoding());
            assertFalse(window.active().state().isDirty());
            assertArrayEquals(ORIGINAL.getBytes(LEGACY_CHARSET), Files.readAllBytes(window.document));
        }
    }

    @Test
    void choosingUtf8KeepsQuitAndCloseContinuations() throws Exception {
        for (boolean quitting : new boolean[] {false, true}) {
            Path scenario = directory.resolve(Boolean.toString(quitting));
            try (EditorWindowFixture window = new EditorWindowFixture(scenario, false, List.of("placeholder"))) {
                Files.write(window.document, ORIGINAL.getBytes(LEGACY_CHARSET));
                window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document));
                window.frame();
                window.ui.confirmOnCloseSetting = true;
                if (!quitting) {
                    window.command(TuinkCommand.TAB_NEW);
                    window.command(TuinkCommand.TAB_PREVIOUS);
                }
                window.event(new TextInput("😀"));
                window.command(quitting ? TuinkCommand.APP_QUIT : TuinkCommand.TAB_CLOSE);
                window.event(new TextInput("y"));
                assertEncodingError(window);
                window.event(new TextInput("y"));
                Path occupied = Files.writeString(scenario.resolve("occupied.txt"), "untouched");
                replaceFilename(window, occupied);
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                window.event(KeyStroke.of(KeyStroke.Key.HOME));
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)); // Refuse overwrite, keep UTF-8 flow.
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertTrue(window.screenRow(window.height - 2).startsWith("Save UTF-8: "));
                Path target = scenario.resolve("utf8.txt");
                replaceFilename(window, target);
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertArrayEquals("😀Привет\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
                assertEquals(!quitting, window.controller.running());
                assertEquals(1, window.tabs.size());
                assertEquals("untouched", Files.readString(occupied));
            }
        }
    }

    @Test
    void cancellingUtf8FilenameEntryLeavesTheOriginalFormatAndTextHistory() throws Exception {
        try (EditorWindowFixture window = legacyWindow()) {
            window.event(new TextInput("😀"));
            var model = window.active().tabSession().model();
            var metadata = model.fileMetadata();
            var baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
            window.event(ctrl(KeyStroke.Key.S));
            window.event(new TextInput("y"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(metadata, model.fileMetadata());
            assertEquals(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
            assertEquals(window.document, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertTrue(window.active().state().isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("Привет", ""), model.snapshotLines());
            assertFalse(window.active().state().isDirty());
            assertArrayEquals(ORIGINAL.getBytes(LEGACY_CHARSET), Files.readAllBytes(window.document));
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
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(window.document));
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
            .anyMatch(row -> window.screenRow(row).contains("cannot encode U+1F600")), "encoding reason must remain visible");
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

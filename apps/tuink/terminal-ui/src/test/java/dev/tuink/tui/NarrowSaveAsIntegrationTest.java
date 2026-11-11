package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.cell.CellText;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NarrowSaveAsIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {26, 27, 28})
    void narrowRowsKeepTheFilenameVisibleAndEnterCanSaveWithoutButtons(int columns) throws Exception {
        try (EditorWindowFixture window = window()) {
            Path copy = directory.resolve("copy.txt");
            requestSaveAs(window, copy);
            window.resize(24, columns);

            assertTrue(prompt(window).contains("copy.txt"));
            assertFalse(prompt(window).contains("[Save]"));
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager().focused().orElseThrow());

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("draft original", Files.readString(copy));
            assertEquals("original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void buttonsAppearWithEnoughRoomForAWideFilenameGlyphAndItsCaret() throws Exception {
        try (EditorWindowFixture window = window()) {
            Path copy = directory.resolve("中");
            requestSaveAs(window, copy);
            window.resize(24, 29);

            assertTrue(prompt(window).contains("中"));
            String row = prompt(window);
            int saveOffset = row.indexOf("[Save]");
            assertTrue(saveOffset >= 0);
            int saveColumn = CellText.width(row.substring(0, saveOffset));
            window.pointer(PointerEvent.Kind.PRESS, window.height - 2, saveColumn);

            assertEquals("draft original", Files.readString(copy));
            assertEquals("original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @CsvSource({"24,10", "24,11", "2,80"})
    void anUnreadableOrClippedFilenameCannotSaveUntilTheTerminalGrows(int rows, int columns) throws Exception {
        try (EditorWindowFixture window = window()) {
            Path copy = directory.resolve("copy-中.txt");
            requestSaveAs(window, copy);
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(SaveAsBarView.Target.SAVE, window.host.saveAsFocusManager().focused().orElseThrow());
            window.resize(rows, columns);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.command(TuinkCommand.EDIT_INSERT_NEWLINE);

            assertFalse(Files.exists(copy));
            assertEquals("original", Files.readString(window.document));
            assertEquals(copy.toString(), window.filename.text());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.controller.running());
            if (rows == 24) {
                assertEquals("Enlarge terminal to show the filename before saving", window.notification.message);
            }

            window.resize(24, 80);
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager().focused().orElseThrow());
            assertTrue(prompt(window).contains("copy-"));
            assertTrue(prompt(window).contains("中"));
            assertTrue(prompt(window).contains(".txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("draft original", Files.readString(copy));
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void escapeAndCtrlQCancelEvenWhenOnlyTheFilenameCaretFits(boolean ctrlQ) throws Exception {
        try (EditorWindowFixture window = window()) {
            Path copy = directory.resolve("copy.txt");
            requestSaveAs(window, copy);
            window.resize(24, 10);
            window.event(ctrlQ ? KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)
                : KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertFalse(Files.exists(copy));
            assertTrue(window.filename.isEmpty());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.controller.running());
            window.resize(24, 80);
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void externalConflictKeepsItsDraftAcrossTinyResizeAndCanBeRetriedWithANewVisibleName() throws Exception {
        try (EditorWindowFixture window = window()) {
            requestSaveAs(window, window.document);
            Files.writeString(window.document, "external change");
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.selected().uiState().modalState.isSaveOverwriteConfirm());
            assertTrue(window.selected().uiState().overwriteApproval.request().externalOverwrite());
            window.resize(24, 10);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("external change", Files.readString(window.document));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            Path copy = directory.resolve("retry.txt");
            window.resize(24, 80);
            requestSaveAs(window, copy);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("draft original", Files.readString(copy));
            assertEquals("external change", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    private EditorWindowFixture window() throws Exception {
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"));
        window.event(new TextInput("draft "));
        return window;
    }

    private static void requestSaveAs(EditorWindowFixture window, Path target) {
        window.command(TuinkCommand.DOCUMENT_SAVE_AS);
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(target.toString()));
    }

    private static String prompt(EditorWindowFixture window) {
        return window.screenRow(window.height - 2).stripTrailing();
    }
}

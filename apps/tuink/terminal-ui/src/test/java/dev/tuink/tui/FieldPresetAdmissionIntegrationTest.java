package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FieldPresetAdmissionIntegrationTest {
    @TempDir Path directory;

    @Test
    void refusedSelectedQueryDoesNotOpenFindWithAnOlderQuery() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("selected text"))) {
            window.query.set("previous query");
            window.active().tabSession().engine().restoreSelection(new Position(0, 8), new Position(0, 0));
            denyAllocations(window.query);

            window.command(TuinkCommand.SEARCH_OPEN);

            assertFalse(window.selected().uiState().searchMode);
            assertEquals("previous query", window.query.text());
            assertTrue(window.notification.message.startsWith("Not enough memory to open Find"));
            assertEquals(new Position(0, 8), window.active().state().getCursor());
            assertEquals(new Position(0, 0), window.active().state().getSelectionAnchor());
        }
    }

    @Test
    void refusedFilenamePresetClosesSaveAsWithoutReusingAnotherTarget() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path staleTarget = directory.resolve("previous.txt");
            window.filename.set(staleTarget.toString());
            window.event(new TextInput("draft "));
            denyAllocations(window.filename);

            window.command(TuinkCommand.DOCUMENT_SAVE_AS);

            assertTrue(window.controller.running());
            assertTrue(window.active().state().isDirty());
            assertEquals(staleTarget.toString(), window.filename.text());
            assertTrue(window.notification.message.startsWith("Not enough memory to open Save As"));
            assertFalse(Files.exists(staleTarget));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of("draft ", "original"), window.active().tabSession().model().snapshotLines());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @EnumSource(value = TuinkCommand.class, names = {"APP_QUIT", "TAB_CLOSE"})
    void refusedFilenameCancelsPendingQuitOrCloseWithoutRemovingTheDraft(TuinkCommand command) throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("draft"));
            Path staleTarget = directory.resolve("previous.txt");
            window.filename.set(staleTarget.toString());
            denyAllocations(window.filename);
            window.command(command);

            window.event(new TextInput("y"));

            assertTrue(window.controller.running());
            assertEquals(2, window.tabs.size());
            assertEquals(1, window.context.activeTabIndex());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.notification.message.startsWith("Not enough memory to open Save As"));
            assertFalse(Files.exists(staleTarget));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of("draft", ""), window.active().tabSession().model().snapshotLines());
            assertEquals("original", Files.readString(window.document));
        }
    }

    private static void denyAllocations(TextField field) throws ReflectiveOperationException {
        // Simulate pressure after the previous filename/query was accepted. No
        // public production API is needed solely for this window-level fixture.
        var budget = TextField.class.getDeclaredField("allocationBudget");
        budget.setAccessible(true);
        budget.set(field, (LongSupplier) () -> 0L);
    }
}

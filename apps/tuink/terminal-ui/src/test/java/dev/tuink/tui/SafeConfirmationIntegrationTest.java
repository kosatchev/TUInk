package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.RecoveryStoreFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses the real window and recovery files to verify confirmation data ownership. */
class SafeConfirmationIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void yesSavesANamedDocumentBeforeQuittingOrClosingItsTab(boolean closeTab) throws Exception {
        Path recoveryDirectory = directory.resolve("recovery");
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = window(store)) {
            window.event(new TextInput("draft "));
            var edited = window.active();
            assertEquals(1, store.loadAll().entries().size());

            requestConfirmation(window, closeTab);
            window.event(new TextInput("Y"));

            assertEquals("draft original", Files.readString(window.document));
            assertFalse(edited.state().isDirty());
            assertEquals(closeTab, window.controller.running());
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), !closeTab));
            assertTrue(store.loadAll().entries().isEmpty());
        }
    }

    @ParameterizedTest
    @MethodSource("cancellations")
    void cancellationKeepsTheDirtyDocumentAndItsSnapshotForAnotherLaunch(
        boolean closeTab, int columns, TuiInputEvent input
    ) throws Exception {
        Path recoveryDirectory = directory.resolve("recovery");
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = window(store)) {
            window.event(new TextInput("draft "));
            window.resize(24, columns);
            requestConfirmation(window, closeTab);
            window.event(input);

            assertTrue(window.controller.running());
            assertEquals(1, window.tabs.size());
            assertTrue(window.active().state().isDirty());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertEquals("original", Files.readString(window.document));
            String row = prompt(window);
            assertFalse(row.contains("Unsaved") || row.contains("Close ") || row.contains("Enlarge"), row);
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), false));
        }
        try (RecoveryStore next = RecoveryStoreFixtures.open(recoveryDirectory)) {
            List<RecoveryStore.Entry> entries = next.loadAll().entries();
            assertEquals(1, entries.size());
            assertEquals(List.of("draft original"), entries.getFirst().snapshot().lines());
        }
    }

    @ParameterizedTest
    @MethodSource("explicitDiscards")
    void onlyTheExplicitDiscardButtonQuitsOrClosesWithoutSaving(boolean closeTab, boolean pointer)
        throws Exception {
        Path recoveryDirectory = directory.resolve("recovery");
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = window(store)) {
            window.event(new TextInput("draft "));
            assertEquals(1, store.loadAll().entries().size());
            requestConfirmation(window, closeTab);
            if (pointer) {
                int column = prompt(window).indexOf("[Discard]");
                assertTrue(column >= 0);
                window.pointer(PointerEvent.Kind.PRESS, window.height - 2, column);
            } else {
                window.event(KeyStroke.of(KeyStroke.Key.HOME));
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }

            assertEquals(closeTab, window.controller.running());
            assertEquals("original", Files.readString(window.document));
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), !closeTab));
            assertTrue(store.loadAll().entries().isEmpty());
        }
        try (RecoveryStore next = RecoveryStoreFixtures.open(recoveryDirectory)) {
            assertTrue(next.loadAll().entries().isEmpty());
        }
    }

    private EditorWindowFixture window(RecoveryStore store) throws IOException {
        EditorWindowFixture window = new EditorWindowFixture(directory.resolve("window"), false,
            List.of("original"), List.of(), new TerminalEditorApp.RecoveryPersistence() {
                @Override public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
                    store.save(entryId, snapshot);
                }
                @Override public void delete(String entryId) throws IOException {
                    store.delete(entryId);
                }
                @Override public void release(String entryId) throws IOException {
                    store.release(entryId);
                }
            });
        window.ui.confirmOnCloseSetting = true;
        return window;
    }

    private static void requestConfirmation(EditorWindowFixture window, boolean closeTab) {
        if (closeTab) {
            window.command(TuinkCommand.TAB_CLOSE);
        } else {
            window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));
        }
    }

    private static Stream<Arguments> cancellations() {
        return Stream.of(false, true).flatMap(close -> Stream.of(80, 12).flatMap(columns ->
            Stream.of(new TextInput("n"), KeyStroke.of(KeyStroke.Key.ESCAPE),
                KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL))
                .map(input -> Arguments.of(close, columns, input))));
    }

    private static Stream<Arguments> explicitDiscards() {
        return Stream.of(false, true).flatMap(close -> Stream.of(false, true)
            .map(pointer -> Arguments.of(close, pointer)));
    }

    private static String prompt(EditorWindowFixture window) {
        return window.screenRow(window.height - 2).stripTrailing();
    }
}

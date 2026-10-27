package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TinyMenuIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"enter", "click", "ordinal", "mnemonic"})
    void anUnreadableSettingCannotChangeUntilResizingRevealsItsCompleteName(String route) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("unchanged"))) {
            boolean initialConfirm = window.ui.confirmOnCloseSetting;
            long revision = window.active().tabSession().model().revision();
            window.resize(6, 6);
            window.command(TuinkCommand.MENU_SETTINGS);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(2, window.menu.state().selectedEntryIndex());
            assertFalse(screenContains(window, "Confirm on close"));

            activate(window, route, true);

            assertEquals(initialConfirm, window.ui.confirmOnCloseSetting);
            assertTrue(window.menu.isOpen());
            assertEquals(2, window.menu.state().selectedEntryIndex());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals(List.of("unchanged"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());

            window.resize(24, 80);
            assertTrue(screenContains(window, "Confirm on close"));
            activate(window, route, false);

            assertEquals(!initialConfirm, window.ui.confirmOnCloseSetting);
            assertFalse(window.menu.isOpen());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals("unchanged", Files.readString(window.document));
        }
    }

    @Test
    void keyboardNavigationAndEscapeRemainAvailableWhileTheSettingsNamesAreClipped() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("unchanged"))) {
            boolean initialConfirm = window.ui.confirmOnCloseSetting;
            window.resize(6, 6);
            window.command(TuinkCommand.MENU_SETTINGS);
            for (int step = 0; step < 3; step++) {
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            }
            assertEquals(4, window.menu.state().selectedEntryIndex(), "Navigation still skips separators");

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertFalse(window.menu.isOpen());
            assertEquals(initialConfirm, window.ui.confirmOnCloseSetting);
            assertEquals(List.of("unchanged"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void shrinkingAnAlreadySelectedSettingKeepsQuitConfirmationAndTheRecoverableDraft() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("unchanged"))) {
            window.event(new TextInput("draft😀 "));
            var session = window.active().tabSession();
            List<String> draft = List.of("draft😀 unchanged");
            long revision = session.model().revision();
            assertNull(window.recoveryManager.finishSession(List.of(window.active().documentSession()), false));
            var recovery = Map.copyOf(window.recoverySnapshots);
            assertEquals(1, recovery.size());
            assertEquals(draft, recovery.values().iterator().next().lines());
            assertTrue(window.ui.confirmOnCloseSetting);

            window.command(TuinkCommand.MENU_SETTINGS);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(2, window.menu.state().selectedEntryIndex());
            assertTrue(screenContains(window, "Confirm on close"));
            window.resize(6, 6);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertTrue(window.ui.confirmOnCloseSetting);
            assertTrue(window.menu.isOpen());
            assertEquals(2, window.menu.state().selectedEntryIndex());
            window.resize(24, 80);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.menu.isOpen());

            window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));

            assertTrue(window.controller.running());
            assertTrue(screenContains(window, "Unsaved (1)"));
            assertEquals(draft, session.model().snapshotLines());
            assertEquals(revision, session.model().revision());
            assertTrue(session.state().isDirty());
            assertEquals(recovery, window.recoverySnapshots);
            assertEquals("unchanged", Files.readString(window.document));
        }
    }

    private static void activate(EditorWindowFixture window, String route, boolean tiny) {
        switch (route) {
            case "enter" -> window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            case "ordinal" -> window.event(new TextInput("3"));
            case "mnemonic" -> window.event(new TextInput("c"));
            case "click" -> {
                if (tiny) {
                    window.pointer(PointerEvent.Kind.PRESS, 4, 2);
                } else {
                    for (int row = 0; row < window.height; row++) {
                        int column = window.screenRow(row).indexOf("Confirm on close");
                        if (column >= 0) {
                            window.pointer(PointerEvent.Kind.PRESS, row, column);
                            return;
                        }
                    }
                    throw new AssertionError("The complete setting name must be visible before clicking it");
                }
            }
            default -> throw new AssertionError("Unknown activation route: " + route);
        }
    }

    private static boolean screenContains(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) {
            if (window.screenRow(row).contains(text)) {
                return true;
            }
        }
        return false;
    }
}

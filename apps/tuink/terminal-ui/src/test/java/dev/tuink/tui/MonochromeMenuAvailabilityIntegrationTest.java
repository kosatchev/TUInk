package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.PointerEvent;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class MonochromeMenuAvailabilityIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"VIEW_TOGGLE_SYNTAX,Syntax highlighting,MENU_VIEW",
        "VIEW_TOGGLE_RAINBOW_BRACKETS,Rainbow brackets,MENU_VIEW",
        "SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX,MD code syntax,MENU_SETTINGS"})
    void mouseAndBoundCommandsRemainAvailableAndToggleOnlyTheirExplicitPreference(
        TuinkCommand command, String label, TuinkCommand menu) throws Exception {
        try (var window = monochromeWindow()) {
            var before = window.ui.settingsSnapshot(window.host.theme);
            boolean enabled = setting(window.ui, command);
            assertTrue(window.controller.activeSurface().availability(command).enabled());
            assertEquals("", window.controller.activeSurface().availability(command).reason());
            window.command(menu);
            int row = row(window, label);
            int column = window.screenRow(row).indexOf(label) + 1;
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.RELEASE, row, column);
            assertFalse(window.menu.isOpen(), "an enabled item invokes and closes its menu");
            assertEquals(!enabled, setting(window.ui, command));
            assertNotEquals(before, window.ui.settingsSnapshot(window.host.theme));
            assertFalse(window.notification.message.contains("unavailable"));
            window.command(command);
            assertEquals(enabled, setting(window.ui, command));
            assertEquals(before, window.ui.settingsSnapshot(window.host.theme), "the second explicit action restores the preference");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TuinkCommand.class, names = {"VIEW_TOGGLE_SYNTAX", "VIEW_TOGGLE_RAINBOW_BRACKETS",
        "SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX"})
    void directUiDispatchAlsoHonorsAnAvailableMonochromePreference(TuinkCommand command) throws Exception {
        try (var window = monochromeWindow()) {
            var before = window.ui.settingsSnapshot(window.host.theme);
            boolean enabled = setting(window.ui, command);
            var result = InputActionDispatcher.dispatchUi(command, window.ui, window.controller.activeSurface(),
                null, ignored -> {}, () -> {});
            assertTrue(result.handled());
            assertEquals(!enabled, setting(window.ui, command));
            assertNotNull(result.statusMessage());
            assertFalse(result.statusMessage().contains("unavailable"));
            InputActionDispatcher.dispatchUi(command, window.ui, window.controller.activeSurface(), null, ignored -> {}, () -> {});
            assertEquals(before, window.ui.settingsSnapshot(window.host.theme));
        }
    }

    @Test void meaningfulMonochromeControlsRemainAvailable() throws Exception {
        try (var window = monochromeWindow()) {
            for (var command : List.of(TuinkCommand.VIEW_CYCLE_GUTTER_DIFF, TuinkCommand.VIEW_TOGGLE_WHITESPACE,
                TuinkCommand.VIEW_TOGGLE_ACTIVE_GUTTER, TuinkCommand.VIEW_TOGGLE_SELECTION_HIGHLIGHT,
                TuinkCommand.VIEW_CYCLE_TAB_WIDTH, TuinkCommand.SETTINGS_TOGGLE_AUTO_PAIRS,
                TuinkCommand.SETTINGS_TOGGLE_AUTO_INDENT, TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY))
                assertTrue(window.controller.activeSurface().availability(command).enabled(), command.toString());
        }
    }

    @Test void aRealLayoutRestrictionStillReportsItsReasonWithoutChangingPreferencesInMonochrome() throws Exception {
        try (var window = monochromeWindow()) {
            window.ui.wrapLinesSetting = false; window.frame();
            var before = window.ui.settingsSnapshot(window.host.theme);
            var command = TuinkCommand.VIEW_TOGGLE_WORD_WRAP;
            var availability = window.controller.activeSurface().availability(command);
            assertFalse(availability.enabled());
            assertEquals("Enable line wrapping before changing word wrapping", availability.reason());
            window.command(TuinkCommand.MENU_VIEW);
            int row = row(window, "Wrap by words");
            int column = window.screenRow(row).indexOf("Wrap by words");
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.RELEASE, row, column);
            assertTrue(window.menu.isOpen());
            assertEquals(availability.reason(), window.notification.message);
            assertEquals(before, window.ui.settingsSnapshot(window.host.theme));
        }
    }

    private static boolean setting(UiSessionState ui, TuinkCommand command) {
        return switch (command) {
            case VIEW_TOGGLE_SYNTAX -> ui.syntaxHighlightingSetting;
            case VIEW_TOGGLE_RAINBOW_BRACKETS -> ui.rainbowBracketsSetting;
            case SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX -> ui.markdownCodeHighlightingSetting;
            default -> throw new AssertionError("Not a syntax preference: " + command);
        };
    }
    private EditorWindowFixture monochromeWindow() throws Exception {
        var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Heading", "```java", "int x = (1);", "```"));
        window.command(TuinkCommand.VIEW_CYCLE_THEME); window.command(TuinkCommand.VIEW_CYCLE_THEME);
        assertEquals(UiStyles.Theme.MONOCHROME, window.host.theme);
        return window;
    }
    private static int row(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return row;
        throw new AssertionError("Menu label is missing: " + text);
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellColor;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonochromeSyntaxIntegrationTest {
    @TempDir Path directory;

    @Test void monochromeSyntaxIsAvailableAndBothMenuAndBoundCommandsControlTheSavedPreference() throws Exception {
        var lines = List.of("return value;");
        try (var window = new EditorWindowFixture(directory, "main.java", false, lines)) {
            var before = window.ui.settingsSnapshot(UiStyles.Theme.CYAN);
            monochrome(window);
            assertTrue(window.controller.activeSurface().syntaxDecorationsAvailable());
            assertTrue(PresentationSyntaxPolicy.source(window.ui, window.host.theme).enabled());
            assertKeyword(window, CellColor.GRAY_218, true);

            window.command(TuinkCommand.MENU_VIEW);
            int row = syntaxRow(window);
            String line = window.screenRow(row);
            assertTrue(line.contains("[x] Syntax highlighting"), line);
            int column = line.indexOf("Syntax highlighting");
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.RELEASE, row, column);
            assertFalse(window.menu.isOpen(), "an available menu command closes the menu");
            assertFalse(window.ui.syntaxHighlightingSetting);
            assertFalse(PresentationSyntaxPolicy.source(window.ui, window.host.theme).enabled());
            assertKeyword(window, CellColor.GRAY_188, false);

            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertTrue(window.ui.syntaxHighlightingSetting);
            assertKeyword(window, CellColor.GRAY_218, true);
            assertEquals(before, window.ui.settingsSnapshot(UiStyles.Theme.CYAN));
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertKeyword(window, CellColor.BRIGHT_BLUE, true);
            assertEquals(lines, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void anExplicitlyDisabledPreferenceStaysDisabledAcrossMonochromeWithoutAnotherAction() throws Exception {
        try (var window = new EditorWindowFixture(directory, "main.java", false, List.of("return value;"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertFalse(window.ui.syntaxHighlightingSetting);
            var before = window.ui.settingsSnapshot(UiStyles.Theme.CYAN);
            monochrome(window);
            assertTrue(window.controller.activeSurface().syntaxDecorationsAvailable());
            assertFalse(PresentationSyntaxPolicy.source(window.ui, window.host.theme).enabled());
            assertKeyword(window, CellColor.GRAY_188, false);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertFalse(window.ui.syntaxHighlightingSetting);
            assertKeyword(window, CellColor.DEFAULT, false);
            assertEquals(before, window.ui.settingsSnapshot(UiStyles.Theme.CYAN));
        }
    }

    private static void monochrome(EditorWindowFixture window) {
        window.command(TuinkCommand.VIEW_CYCLE_THEME); window.command(TuinkCommand.VIEW_CYCLE_THEME);
        assertEquals(UiStyles.Theme.MONOCHROME, window.host.theme);
    }
    private static void assertKeyword(EditorWindowFixture window, CellColor foreground, boolean bold) {
        for (int row = 1; row < window.height - 2; row++) {
            int column = window.screenRow(row).indexOf("return value;");
            if (column >= 0) {
                var style = window.screenStyle(row, column);
                assertEquals(foreground, style.fg()); assertEquals(bold, style.bold());
                assertEquals(window.host.theme == UiStyles.Theme.MONOCHROME ? CellColor.GRAY_028 : CellColor.DEFAULT, style.bg());
                return;
            }
        }
        fail("Source keyword is missing");
    }
    private static int syntaxRow(EditorWindowFixture window) {
        for (int row = 0; row < window.height; row++)
            if (window.screenRow(row).contains("Syntax highlighting")) return row;
        throw new AssertionError("Syntax highlighting is missing from the visible View menu");
    }
}

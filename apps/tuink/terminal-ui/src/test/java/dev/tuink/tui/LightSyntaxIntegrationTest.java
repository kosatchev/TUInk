package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LightSyntaxIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void lightThemeDisablesTheMenuActionWithoutChangingThePreference() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("keep"))) {
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.LIGHT, window.host.theme);
            assertFalse(window.ui.syntaxHighlightingAvailable);
            assertTrue(window.ui.syntaxHighlightingSetting);

            window.command(TuinkCommand.MENU_VIEW);
            int row = syntaxRow(window);
            String line = window.screenRow(row);
            assertTrue(line.contains("[x] Syntax highlighting (unavailable in Light)"), line);
            int column = line.indexOf("Syntax highlighting");
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.RELEASE, row, column);
            assertTrue(window.ui.syntaxHighlightingSetting);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertTrue(window.ui.syntaxHighlightingSetting);
            assertTrue(window.screenRow(window.height - 2).contains("unavailable in the current theme"));
            assertFalse(window.host.effectiveSyntaxHighlighting(window.ui.syntaxHighlightingSetting));

            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertTrue(window.ui.syntaxHighlightingAvailable);
            assertTrue(window.host.effectiveSyntaxHighlighting(window.ui.syntaxHighlightingSetting));
            assertEquals("keep", window.active().tabSession().model().getLine(0));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void anExplicitlyDisabledPreferenceStaysDisabledAfterTheLightTheme() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("keep"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertFalse(window.ui.syntaxHighlightingSetting);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);

            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertFalse(window.ui.syntaxHighlightingSetting);
            assertFalse(window.host.effectiveSyntaxHighlighting(window.ui.syntaxHighlightingSetting));
        }
    }

    private static int syntaxRow(EditorWindowFixture window) {
        for (int row = 0; row < window.height; row++) {
            if (window.screenRow(row).contains("Syntax highlighting")) {
                return row;
            }
        }
        throw new AssertionError("Syntax highlighting is missing from the visible View menu");
    }
}

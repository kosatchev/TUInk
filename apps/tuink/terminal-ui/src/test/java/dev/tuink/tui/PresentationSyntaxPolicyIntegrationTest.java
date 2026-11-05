package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The effective policy is consumed by the real source factory, frame preparation and Preview painter. */
class PresentationSyntaxPolicyIntegrationTest {
    @TempDir Path directory;

    @Test
    void togglesFromPreviewKeepItsColorsWhileSourceTraversesAllFourPreferenceCombinations() throws Exception {
        var lines = List.of("# Heading", "", "```python", "return 7", "value = other", "```");
        var settings = new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0]));
        try (var window = new EditorWindowFixture(directory, "policy.md", lines, settings)) {
            var source = window.active();
            long revision = source.documentSession().document().snapshot().revision();
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            assertPairColors(window, CellColor.YELLOW, CellColor.BRIGHT_YELLOW);

            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            assertPairColors(window, CellColor.BRIGHT_BLUE, CellColor.BRIGHT_YELLOW);
            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertPairColors(window, CellColor.DEFAULT, CellColor.DEFAULT);
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            assertPairColors(window, CellColor.DEFAULT, CellColor.DEFAULT);
            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertPairColors(window, CellColor.YELLOW, CellColor.BRIGHT_YELLOW);

            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.MONOCHROME, window.host.theme);
            assertTrue(window.ui.syntaxHighlightingSetting);
            assertPairColors(window, CellColor.GRAY_218, CellColor.GRAY_218);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertPairColors(window, CellColor.YELLOW, CellColor.BRIGHT_YELLOW);

            assertEquals(revision, source.documentSession().document().snapshot().revision());
            assertEquals(lines, source.documentSession().document().snapshot().lines());
            assertFalse(source.state().isDirty());
        }
    }

    private static void assertPairColors(EditorWindowFixture window, CellColor sourceCode, CellColor sourceHeading) throws Exception {
        assertInstanceOf(PreviewTab.class, window.selected());
        boolean simplified = window.host.theme == UiStyles.Theme.MONOCHROME;
        var previewKeyword = simplified ? CellColor.GRAY_218 : CellColor.BRIGHT_BLUE;
        assertEquals(previewKeyword, styleAt(window, "return 7").fg(), "Preview honors the current theme independently of Source preferences");
        assertEquals(CellColor.DARK_GRAY, styleAt(window, "return 7").bg());
        assertEquals(simplified ? CellColor.GRAY_218 : CellColor.WHITE, styleAt(window, "value = other").fg());
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
        assertEquals(sourceCode, styleAt(window, "return 7").fg());
        assertEquals(window.ui.syntaxHighlightingSetting && !window.ui.markdownCodeHighlightingSetting
            ? (simplified ? CellColor.GRAY_218 : CellColor.YELLOW) : CellColor.DEFAULT,
            styleAt(window, "value = other").fg(), "Source keeps its own Markdown syntax preference");
        assertEquals(sourceHeading, styleAt(window, "# Heading").fg());
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.preview().ready());
        assertEquals(previewKeyword, styleAt(window, "return 7").fg());
        assertEquals(simplified ? CellColor.GRAY_218 : CellColor.WHITE, styleAt(window, "value = other").fg());
    }

    private static CellStyle styleAt(EditorWindowFixture window, String text) {
        for (int row = 1; row < window.height - 2; row++) {
            int column = window.screenRow(row).indexOf(text);
            if (column >= 0) return window.screenStyle(row, column);
        }
        fail("Missing displayed text: " + text);
        return null;
    }
}

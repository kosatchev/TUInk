package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownSourceSyntaxIntegrationTest {
    @TempDir Path directory;

    @Test void sourceToggleUpdatesOnlyCodeWithoutEditingTheDocument() throws Exception {
        try (var window = window("document.md", List.of("# Heading", "", "```python", "return 7", "```"))) {
            assertEquals(CellColor.YELLOW, styleAt(window, "return 7").fg());
            assertEquals(CellColor.BRIGHT_YELLOW, styleAt(window, "# Heading").fg());
            long revision = window.active().documentSession().document().snapshot().revision();
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            assertEquals(CellColor.BRIGHT_BLUE, styleAt(window, "return 7").fg());
            assertEquals(CellColor.BRIGHT_YELLOW, styleAt(window, "# Heading").fg());
            assertEquals(revision, window.active().documentSession().document().snapshot().revision());
            assertFalse(window.active().state().isDirty());
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            assertEquals(CellColor.YELLOW, styleAt(window, "return 7").fg());
        }
    }

    @Test void markdownPreferenceLeavesOtherLanguagesAndTheGlobalMasterSwitchIntact() throws Exception {
        try (var window = window("example.java", List.of("return 7;"))) {
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            assertEquals(CellColor.BRIGHT_BLUE, styleAt(window, "return 7;").fg());
            window.command(TuinkCommand.VIEW_TOGGLE_SYNTAX);
            assertEquals(window.host.styles.renderBody().fg(), styleAt(window, "return 7;").fg());
        }
    }

    @Test void changingTheSourcePreferenceFromPreviewKeepsPreviewColors() throws Exception {
        try (var window = window("document.md", List.of("```python", "return 7", "```"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            assertEquals(CellColor.BRIGHT_BLUE, styleAt(window, "return 7").fg());
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            assertEquals(CellColor.BRIGHT_BLUE, styleAt(window, "return 7").fg());
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            assertEquals(CellColor.BRIGHT_BLUE, styleAt(window, "return 7").fg());
        }
    }

    private EditorWindowFixture window(String name, List<String> lines) throws Exception {
        return new EditorWindowFixture(directory, name, lines,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
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

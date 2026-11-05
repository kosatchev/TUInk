package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Theme palettes recolor prepared content without replacing documents or saved preferences. */
class MonochromeDecorationIntegrationTest {
    @TempDir Path directory;
    private static final EnumSet<CellColor> MONOCHROME = EnumSet.of(CellColor.DEFAULT, CellColor.BLACK,
        CellColor.WHITE, CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE, CellColor.DARK_GRAY,
        CellColor.GRAY_028, CellColor.GRAY_128, CellColor.GRAY_158, CellColor.GRAY_188, CellColor.GRAY_218, CellColor.GRAY_238);

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void sourceSyntaxMatchingQuotesAndRainbowUseTheMonochromePaletteAndPreservePreferences(boolean syntax, boolean rainbow) throws Exception {
        var lines = List.of("(alpha)", "([{(alpha)}])", "\"literal\"", "return 7; // note", "unmatched(", "tail");
        try (var window = new EditorWindowFixture(directory.resolve(syntax + "-" + rainbow), "main.java", false, lines)) {
            window.ui.syntaxHighlightingSetting = syntax;
            window.ui.rainbowBracketsSetting = rainbow;
            window.ui.markdownCodeHighlightingSetting = true;
            window.command(TuinkCommand.CURSOR_DOCUMENT_END);
            var source = window.active();
            long revision = source.tabSession().model().revision();
            var preferences = window.ui.settingsSnapshot(UiStyles.Theme.CYAN);
            var ordinaryBracket = styleAt(window, "(alpha)", 0);
            assertEquals(rainbow ? EditorStyleAdapter.toCell(window.host.styles.syntax().bracketDepths().getFirst())
                : CellStyle.DEFAULT, ordinaryBracket);
            var unmatched = EditorStyleAdapter.toCell(window.host.styles.syntax().unmatchedBracket());
            assertEquals(unmatched, styleAt(window, "unmatched(", "unmatched".length()));
            assertEquals(syntax ? CellColor.BRIGHT_BLUE : CellColor.DEFAULT, styleAt(window, "return 7;", 0).fg());
            source.tabSession().engine().moveCursorTo(new Position(0, 0)); window.frame();
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.syntax().bracketMatch()), styleAt(window, "(alpha)", 0));
            source.tabSession().engine().moveCursorTo(new Position(2, 0)); window.frame();
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.syntax().quoteMatch()), styleAt(window, "\"literal\"", 0));

            monochrome(window);
            assertTrue(window.controller.activeSurface().syntaxDecorationsAvailable());
            assertEquals(rainbow ? new CellStyle(CellColor.GRAY_158, CellColor.GRAY_028, false, false) : monochromeBody(), styleAt(window, "(alpha)", 0));
            assertEquals(new CellStyle(CellColor.GRAY_028, CellColor.GRAY_218, true, false),
                styleAt(window, "unmatched(", "unmatched".length()));
            assertEquals(new CellStyle(CellColor.GRAY_238, CellColor.GRAY_028, true, true), styleAt(window, "\"literal\"", 0));
            assertEquals(syntax ? new CellStyle(CellColor.GRAY_218, CellColor.GRAY_028, true, false) : monochromeBody(),
                styleAt(window, "return 7;", 0));
            assertEquals(syntax ? new CellStyle(CellColor.GRAY_158, CellColor.GRAY_028, false, false) : monochromeBody(), styleAt(window, "// note", 0));
            var depths = List.of(CellColor.GRAY_158, CellColor.GRAY_188, CellColor.GRAY_218, CellColor.GRAY_238);
            for (int depth = 0; depth < depths.size(); depth++)
                assertEquals(rainbow ? new CellStyle(depths.get(depth), CellColor.GRAY_028, false, false) : monochromeBody(),
                    styleAt(window, "([{(alpha)}])", depth), "bracket depth " + (depth + 1));
            source.tabSession().engine().moveCursorTo(new Position(0, 0)); window.frame();
            assertEquals(new CellStyle(CellColor.GRAY_238, CellColor.GRAY_028, true, true), styleAt(window, "(alpha)", 0));
            assertMonochrome(window);
            assertEquals(preferences, window.ui.settingsSnapshot(UiStyles.Theme.CYAN));
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var selected = styleAt(window, "(alpha)", 0);
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.windows().renderSelection()), selected);
            assertNotEquals(CellStyle.DEFAULT, selected, "selection remains visible in the monochrome theme");
            window.command(TuinkCommand.CURSOR_DOCUMENT_END);

            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertTrue(window.controller.activeSurface().syntaxDecorationsAvailable());
            assertEquals(ordinaryBracket, styleAt(window, "(alpha)", 0));
            assertEquals(unmatched, styleAt(window, "unmatched(", "unmatched".length()));
            assertEquals(preferences, window.ui.settingsSnapshot(UiStyles.Theme.CYAN));
            source.tabSession().engine().moveCursorTo(new Position(0, 0)); window.frame();
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.syntax().bracketMatch()), styleAt(window, "(alpha)", 0));
            source.tabSession().engine().moveCursorTo(new Position(2, 0)); window.frame();
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.syntax().quoteMatch()), styleAt(window, "\"literal\"", 0));
            assertEquals(revision, source.tabSession().model().revision());
            assertEquals(lines, source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
            assertFalse(source.tabSession().engine().undo());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void previewReusesItsAcceptedLayoutAndGeometryAcrossCyanMonochromeCyanWhileRestoringCachedColors(boolean sourceSyntax) throws Exception {
        var lines = List.of("# Color heading", "", "> Quoted words", "", "```python",
            "return result # note", "```", "", "$x_i^2$", "", "ordinary **bold** and `inline`");
        try (var window = new EditorWindowFixture(directory.resolve("preview-" + sourceSyntax), "document.md", false, lines)) {
            window.ui.syntaxHighlightingSetting = sourceSyntax;
            window.ui.rainbowBracketsSetting = true;
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX);
            var preferences = window.ui.settingsSnapshot(UiStyles.Theme.CYAN);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            var preview = window.preview();
            var result = preview.result;
            var geometry = preview.placement(MarkdownPreviewState.Placement.Focus.CONTENT).geometry();
            assertNotNull(geometry);
            var heading = styleAt(window, "Color heading", 1);
            var quote = styleAt(window, "│ Quoted words", 0);
            var keyword = styleAt(window, "return result", 0);
            var comment = styleAt(window, "# note", 0);
            var inline = styleAt(window, "inline", 0);
            assertEquals(CellColor.CYAN, quote.fg());
            assertEquals(CellColor.BRIGHT_BLUE, keyword.fg());
            assertEquals(CellColor.DARK_GRAY, keyword.bg());
            assertEquals(CellColor.BRIGHT_BLACK, comment.fg());
            assertEquals(CellColor.YELLOW, inline.fg());
            long preparedBefore = geometry.rows().misses();
            long geometryBuilds = geometry.visualMetricsBuilds();

            monochrome(window);
            assertSame(result, preview.result, "theme changes do not reparse or relayout Markdown");
            assertSame(geometry, preview.placement(MarkdownPreviewState.Placement.Focus.CONTENT).geometry());
            assertEquals(new CellStyle(CellColor.GRAY_238, CellColor.GRAY_028, true, false), styleAt(window, "Color heading", 1));
            assertEquals(monochromeBody(), styleAt(window, "│ Quoted words", 0));
            assertEquals(new CellStyle(CellColor.GRAY_218, CellColor.GRAY_028, false, false), styleAt(window, "inline", 0));
            assertEquals(new CellStyle(CellColor.GRAY_218, CellColor.DARK_GRAY, true, false), styleAt(window, "return result", 0));
            assertEquals(new CellStyle(CellColor.GRAY_158, CellColor.DARK_GRAY, false, false), styleAt(window, "# note", 0));
            assertEquals(geometryBuilds, geometry.visualMetricsBuilds(), "only styles change, not text geometry");
            assertTrue(geometry.rows().misses() > preparedBefore, "prepared rows distinguish independent palette identities");
            assertMonochrome(window);
            assertEquals(preferences, window.ui.settingsSnapshot(UiStyles.Theme.CYAN));

            preview.setSearchQuery("return"); window.frame();
            var search = styleAt(window, "return result", 0);
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.windows().renderSearchMatch()), search);
            assertNotEquals(CellStyle.DEFAULT, search, "search stays distinct from grayscale syntax");
            String projection = preview.result.document().projection().text();
            int start = projection.indexOf("result");
            preview.selection.selectRange(projection, start, start + "result".length()); window.frame();
            var selected = styleAt(window, "result # note", 0);
            assertEquals(EditorStyleAdapter.toCell(window.host.styles.windows().renderSelection()), selected);
            assertNotEquals(CellStyle.DEFAULT, selected);
            assertMonochrome(window);
            preview.selection.clear(); preview.setSearchQuery(""); window.frame();

            window.command(TuinkCommand.VIEW_CYCLE_THEME);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            assertSame(result, preview.result);
            assertSame(geometry, preview.placement(MarkdownPreviewState.Placement.Focus.CONTENT).geometry());
            assertEquals(heading, styleAt(window, "Color heading", 1));
            assertEquals(quote, styleAt(window, "│ Quoted words", 0));
            assertEquals(keyword, styleAt(window, "return result", 0));
            assertEquals(comment, styleAt(window, "# note", 0));
            assertEquals(inline, styleAt(window, "inline", 0));
            assertEquals(preferences, window.ui.settingsSnapshot(UiStyles.Theme.CYAN));
        }
    }

    private static CellStyle monochromeBody() { return new CellStyle(CellColor.GRAY_188, CellColor.GRAY_028, false, false); }
    private static void monochrome(EditorWindowFixture window) {
        window.command(TuinkCommand.VIEW_CYCLE_THEME); window.command(TuinkCommand.VIEW_CYCLE_THEME);
        assertEquals(UiStyles.Theme.MONOCHROME, window.host.theme);
    }
    private static CellStyle styleAt(EditorWindowFixture window, String text, int offset) {
        for (int row = 1; row < window.height - 2; row++) {
            int column = window.screenRow(row).indexOf(text);
            if (column >= 0) return window.screenStyle(row, column + offset);
        }
        throw new AssertionError("Missing displayed text: " + text);
    }
    private static void assertMonochrome(EditorWindowFixture window) {
        for (int row = 0; row < window.height; row++) for (int column = 0; column < window.width; column++) {
            var style = window.screenStyle(row, column);
            assertTrue(MONOCHROME.contains(style.fg()), "Colored foreground at " + row + ":" + column + " / " + style);
            assertTrue(MONOCHROME.contains(style.bg()), "Colored background at " + row + ":" + column + " / " + style);
        }
    }
}

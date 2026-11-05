package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonochromePaletteTest {
    private static final Set<CellColor> NEUTRAL = Set.of(CellColor.DEFAULT, CellColor.BLACK,
        CellColor.WHITE, CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE, CellColor.DARK_GRAY,
        CellColor.GRAY_028, CellColor.GRAY_128, CellColor.GRAY_158, CellColor.GRAY_188, CellColor.GRAY_218, CellColor.GRAY_238);

    @Test void allThreeMonochromeStyleGroupsAreNeutralAndBoldAlwaysHasAnExplicitForeground() throws Exception {
        var palette = UiStyles.forTheme(UiStyles.Theme.MONOCHROME);
        for (var component : UiStyles.InterfaceStyles.class.getRecordComponents()) {
            Object value = component.getAccessor().invoke(palette.windows());
            if (value instanceof CellStyle style) neutral(style, "windows." + component.getName());
            else if (value instanceof Style style) neutral(style, "windows." + component.getName());
            else fail("Unverified interface component: " + component.getName());
        }
        neutral(palette.windows().renderBody(), "windows.body");
        neutral(palette.preview().body(), "preview.body");
        neutral(palette.preview().codeBody(), "preview.codeBody");
        for (var role : MarkdownLayout.Role.values()) neutral(palette.preview().role(role), "preview." + role);
        var syntax = palette.syntax();
        neutral(syntax.plain(), "syntax.plain");
        neutral(syntax.bracketMatch(), "syntax.bracketMatch");
        neutral(syntax.quoteMatch(), "syntax.quoteMatch");
        neutral(syntax.unmatchedBracket(), "syntax.unmatchedBracket");
        for (var role : syntax.roles().entrySet()) neutral(role.getValue(), role.getKey().value());
        for (var style : syntax.bracketDepths()) neutral(style, "syntax.bracketDepth");
        neutral(syntax.resolve(new SemanticStyleKey("syntax.future")), "syntax.fallback");
    }

    @Test void monochromeSemanticRolesUseDistinctDocumentedGrayLevelsAndReadableSurfaces() {
        var palette = UiStyles.forTheme(UiStyles.Theme.MONOCHROME);
        assertEquals(new CellStyle(CellColor.GRAY_188, CellColor.GRAY_028, false, false), palette.windows().renderBody());
        assertEquals(new Style(Color.GRAY_188, Color.GRAY_028, false, false), palette.preview().body());
        assertEquals(new Style(Color.GRAY_218, Color.DARK_GRAY, false, false), palette.preview().codeBody());
        assertEquals(new Style(Color.GRAY_238, Color.DEFAULT, true, false), palette.preview().role(MarkdownLayout.Role.HEADING));
        assertEquals(Style.fg(Color.GRAY_218), palette.preview().role(MarkdownLayout.Role.CODE));
        assertEquals(new Style(Color.GRAY_218, Color.DEFAULT, true, false), palette.syntax().resolve(SemanticStyleKey.KEYWORD));
        assertEquals(Style.fg(Color.GRAY_158), palette.syntax().resolve(SemanticStyleKey.COMMENT));
        var pair = new Style(Color.GRAY_238, Color.DEFAULT, true, true);
        assertEquals(pair, palette.syntax().bracketMatch()); assertEquals(pair, palette.syntax().quoteMatch());
        assertEquals(new Style(Color.GRAY_028, Color.GRAY_218, true, false), palette.syntax().unmatchedBracket());
        assertEquals(List.of(Style.fg(Color.GRAY_158), Style.fg(Color.GRAY_188), Style.fg(Color.GRAY_218),
            Style.fg(Color.GRAY_238)), palette.syntax().bracketDepths());
    }

    @Test void interactiveStatesRemainDistinctFromBodyAndEachOther() {
        var palette = UiStyles.forTheme(UiStyles.Theme.MONOCHROME);
        assertNotEquals(palette.windows().renderBody(), EditorStyleAdapter.toCell(palette.windows().renderSelection()));
        assertNotEquals(palette.windows().renderBody(), EditorStyleAdapter.toCell(palette.windows().renderSearchMatch()));
        assertNotEquals(palette.windows().renderSearchMatch(), palette.windows().renderCurrentSearchMatch());
        assertNotEquals(palette.windows().renderGutter(), palette.windows().renderGutterActive());
    }

    @Test void replacingOnePaletteGroupKeepsTheOtherTwoIndependent() {
        var cyan = UiStyles.forTheme(UiStyles.Theme.CYAN);
        var monochrome = UiStyles.forTheme(UiStyles.Theme.MONOCHROME);
        var onlyPreview = new UiStyles.Palette(cyan.windows(), monochrome.preview(), cyan.syntax());
        assertSame(cyan.windows(), onlyPreview.windows()); assertSame(cyan.syntax(), onlyPreview.syntax());
        assertSame(monochrome.preview(), onlyPreview.preview());
        assertEquals(Color.GRAY_238, onlyPreview.preview().role(MarkdownLayout.Role.HEADING).fg());
        assertEquals(Color.BRIGHT_BLUE, onlyPreview.syntax().resolve(SemanticStyleKey.KEYWORD).fg());
        var onlySyntax = new UiStyles.Palette(cyan.windows(), cyan.preview(), monochrome.syntax());
        assertSame(cyan.windows(), onlySyntax.windows()); assertSame(cyan.preview(), onlySyntax.preview());
        assertEquals(Color.GRAY_218, onlySyntax.syntax().resolve(SemanticStyleKey.KEYWORD).fg());
        assertEquals(Color.CYAN, onlySyntax.preview().role(MarkdownLayout.Role.HEADING).fg());
        assertNotEquals(cyan.identity(), onlyPreview.identity()); assertNotEquals(onlyPreview.identity(), onlySyntax.identity());
    }

    private static void neutral(Style style, String role) { neutral(EditorStyleAdapter.toCell(style), role); }
    private static void neutral(CellStyle style, String role) {
        assertTrue(NEUTRAL.contains(style.fg()), role + " foreground " + style.fg());
        assertTrue(NEUTRAL.contains(style.bg()), role + " background " + style.bg());
        if (style.bold()) assertNotEquals(CellColor.DEFAULT, style.fg(), role + " cannot rely on terminal-dependent bold default text");
    }
}

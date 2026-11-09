package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextFieldPlaceholderStyleTest {
    @Test void placeholderOnlyChangesInkAndNeverTheFieldSurfaceOrValue() {
        var body = new CellStyle(CellColor.GRAY_218, CellColor.BLACK, false, false);
        var accent = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false);
        Theme theme = role -> WidgetThemeKeys.MODAL_BODY.equals(role) ? body
            : WidgetThemeKeys.ACCENT.equals(role) ? accent : null;
        var field = new TextField().placeholder("e.g. *.java");
        for (var state : List.of(WidgetState.NORMAL, WidgetState.FOCUSED, WidgetState.DISABLED)) {
            var cells = new CellBuffer(1, 20);
            field.layout(new Rect(0, 0, 20, 1)).paint(new Canvas(cells), theme, state);
            assertEquals("e", cells.get(0, 0).grapheme()); assertEquals("", field.text()); assertEquals(0, field.cursor());
            var text = cells.get(0, 0).style(); var surface = cells.get(0, 18).style();
            assertEquals(background(surface), background(text)); assertEquals(surface.inverse(), text.inverse());
            if (state.enabled()) assertNotEquals(foreground(surface), foreground(text));
            assertEquals(ControlStyles.placeholder(theme, state), text);
        }
        field.set("*.md");
        var cells = new CellBuffer(1, 20); field.layout(new Rect(0, 0, 20, 1)).paint(new Canvas(cells), theme, WidgetState.FOCUSED);
        assertEquals("*", cells.get(0, 0).grapheme());
        assertEquals(ControlStyles.input(theme, WidgetState.FOCUSED), cells.get(0, 0).style());
    }

    @Test void anExplicitPlaceholderRoleCannotReplaceTheContainingBackground() {
        Theme theme = role -> WidgetThemeKeys.TEXT_FIELD.equals(role)
            ? new CellStyle(CellColor.BLACK, CellColor.GRAY_188, false, false)
            : WidgetThemeKeys.TEXT_FIELD_PLACEHOLDER.equals(role) ? new CellStyle(CellColor.GRAY_128, CellColor.RED, false, false) : null;
        assertEquals(CellColor.GRAY_188, background(ControlStyles.placeholder(theme, WidgetState.NORMAL)));
        assertEquals(CellColor.GRAY_128, foreground(ControlStyles.placeholder(theme, WidgetState.NORMAL)));
    }
    private static CellColor foreground(CellStyle style) { return style.inverse() ? style.bg() : style.fg(); }
    private static CellColor background(CellStyle style) { return style.inverse() ? style.fg() : style.bg(); }
}

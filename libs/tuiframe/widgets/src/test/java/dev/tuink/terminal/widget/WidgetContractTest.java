package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import org.junit.jupiter.api.Test;

class WidgetContractTest {
    @Test
    void widgetStyleRolesAreOpenValidatedValuesAndFixedThemeIsReusable() {
        WidgetStyleRole custom = new WidgetStyleRole("example.status");
        Theme fixed = Theme.fixed(CellStyle.DEFAULT);

        assertEquals(new WidgetStyleRole("example.status"), custom);
        assertSame(CellStyle.DEFAULT, fixed.resolveStyle(custom));
        assertSame(CellStyle.DEFAULT, ((Theme) key -> null).resolveStyle(custom));
        CellStyle fallback = CellStyle.fg(CellColor.BRIGHT_BLUE);
        assertSame(fallback, ((Theme) key -> null).resolveStyle(custom, fallback));
        assertThrows(NullPointerException.class, () -> new WidgetStyleRole(null));
        assertThrows(IllegalArgumentException.class, () -> new WidgetStyleRole(" \t"));
        assertThrows(NullPointerException.class, () -> fixed.resolveStyle(null));
        assertThrows(NullPointerException.class, () -> fixed.resolveStyle(custom, null));
    }

    @Test
    void visualStateRejectsAnImpossibleFocusedDisabledCombination() {
        assertEquals(new WidgetState(false, true), WidgetState.NORMAL);
        assertEquals(new WidgetState(true, true), WidgetState.FOCUSED);
        assertEquals(new WidgetState(false, false), WidgetState.DISABLED);
        assertThrows(IllegalArgumentException.class, () -> new WidgetState(true, false));
    }
}

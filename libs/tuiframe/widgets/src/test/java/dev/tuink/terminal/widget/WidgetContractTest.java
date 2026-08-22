package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void interactionResultsMergeTypedEffectsWithoutAmbiguity() {
        InteractionResult<String> handled = InteractionResult.handled();
        InteractionResult<String> action = InteractionResult.output("close");
        InteractionResult<String> merged = handled.merge(action);

        assertTrue(merged.consumed());
        assertEquals("close", merged.output().orElseThrow());
        assertEquals(FocusRequest.NONE, merged.focusRequest());
        assertThrows(
            IllegalArgumentException.class,
            () -> InteractionResult.output("first")
                .merge(InteractionResult.output("second"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> InteractionResult.output("same")
                .merge(InteractionResult.output("same"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new InteractionResult<>(
                false,
                FocusRequest.NEXT,
                java.util.Optional.empty()
            )
        );
    }
}

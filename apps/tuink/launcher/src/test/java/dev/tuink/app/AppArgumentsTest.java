package dev.tuink.app;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AppArgumentsTest {
    @Test
    void defaultsToTuiWithoutForwardedArguments() {
        AppArguments options = AppArguments.parse(new String[0]);

        assertAll(
            () -> assertEquals(AppArguments.UiTarget.TUI, options.ui()),
            () -> assertEquals(List.of(), options.forwarded())
        );
    }

    @Test
    void acceptsEqualsAndSeparatedUiForms() {
        AppArguments equalsForm = AppArguments.parse(new String[] {"--ui=tui"});
        AppArguments separatedForm = AppArguments.parse(new String[] {"--ui", "tui"});

        assertAll(
            () -> assertEquals(AppArguments.UiTarget.TUI, equalsForm.ui()),
            () -> assertEquals(AppArguments.UiTarget.TUI, separatedForm.ui())
        );
    }

    @Test
    void forwardsNonUiArgumentsInOriginalOrder() {
        AppArguments options = AppArguments.parse(
            new String[] {"first.txt", "--ui=tui", "--line", "42", "second.txt"}
        );

        assertEquals(List.of("first.txt", "--line", "42", "second.txt"), options.forwarded());
    }

    @Test
    void rejectsUnknownUiTarget() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> AppArguments.parse(new String[] {"--ui=web"})
        );

        assertEquals("Unknown UI target: web. Expected: tui", error.getMessage());
    }

    @Test
    void rejectsUnimplementedGuiTargetInBothForms() {
        IllegalArgumentException equalsError = assertThrows(
            IllegalArgumentException.class,
            () -> AppArguments.parse(new String[] {"--ui=gui"})
        );
        IllegalArgumentException separatedError = assertThrows(
            IllegalArgumentException.class,
            () -> AppArguments.parse(new String[] {"--ui", "gui"})
        );

        assertAll(
            () -> assertEquals("Unknown UI target: gui. Expected: tui", equalsError.getMessage()),
            () -> assertEquals("Unknown UI target: gui. Expected: tui", separatedError.getMessage())
        );
    }

    @Test
    void rejectsMissingSeparatedUiValue() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> AppArguments.parse(new String[] {"document.txt", "--ui"})
        );

        assertEquals("Missing value after --ui", error.getMessage());
    }

    @Test
    void acceptsRepeatedTuiOptionsCaseInsensitively() {
        AppArguments options = AppArguments.parse(
            new String[] {"--ui=TUI", "document.txt", "--ui", "tui"}
        );

        assertAll(
            () -> assertEquals(AppArguments.UiTarget.TUI, options.ui()),
            () -> assertEquals(List.of("document.txt"), options.forwarded())
        );
    }
}

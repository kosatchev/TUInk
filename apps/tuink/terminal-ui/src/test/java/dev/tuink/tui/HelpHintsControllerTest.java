package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelpHintsControllerTest {
    private static final CommandRouter ROUTER = new CommandRouter();
    private static final UiRecipient RECIPIENT = UiRecipient.window(new TabUiState(), null);
    @Test
    void closeCommandsDismissHintsWithoutLeakingIntoTheEditor() {
        for (TuinkCommand command : List.of(
            TuinkCommand.UI_ESCAPE,
            TuinkCommand.EDIT_INSERT_NEWLINE,
            TuinkCommand.HELP_KEY_HINTS
        )) {
            HelpHintsController.Result result = HelpHintsController.handle(WindowInput.command(ROUTER, command, RECIPIENT), true);

            assertTrue(result.consumed());
            assertFalse(result.helpHintsMode());
        }
    }

    @Test
    void textAndUnboundPhysicalKeysRemainInsideTheOpenHelpOverlay() {
        for (WindowInput input : List.of(
            new WindowInput.Text("中😀", false, false, RECIPIENT),
            WindowInput.physical(KeyStroke.of(KeyStroke.Key.F1), RECIPIENT)
        )) {
            HelpHintsController.Result result = HelpHintsController.handle(input, true);

            assertTrue(result.consumed());
            assertTrue(result.helpHintsMode());
        }
    }

    @Test
    void closedHelpOverlayLeavesTheCommandToTheEditor() {
        HelpHintsController.Result result = HelpHintsController.handle(
            WindowInput.command(ROUTER, TuinkCommand.DOCUMENT_SAVE, RECIPIENT),
            false
        );

        assertFalse(result.consumed());
        assertFalse(result.helpHintsMode());
    }
}

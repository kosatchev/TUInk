package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.input.EditorInput;
import dev.tuiframe.input.KeyStroke;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelpHintsControllerTest {
    @Test
    void closeCommandsDismissHintsWithoutLeakingIntoTheEditor() {
        for (TuinkCommand command : List.of(
            TuinkCommand.UI_ESCAPE,
            TuinkCommand.EDIT_INSERT_NEWLINE,
            TuinkCommand.HELP_KEY_HINTS
        )) {
            HelpHintsController.Result result = HelpHintsController.handle(EditorInput.command(command), true);

            assertTrue(result.consumed());
            assertFalse(result.helpHintsMode());
        }
    }

    @Test
    void textAndUnboundPhysicalKeysRemainInsideTheOpenHelpOverlay() {
        for (EditorInput input : List.of(
            EditorInput.text("中😀"),
            new EditorInput(KeyStroke.of(KeyStroke.Key.F1), null)
        )) {
            HelpHintsController.Result result = HelpHintsController.handle(input, true);

            assertTrue(result.consumed());
            assertTrue(result.helpHintsMode());
        }
    }

    @Test
    void closedHelpOverlayLeavesTheCommandToTheEditor() {
        HelpHintsController.Result result = HelpHintsController.handle(
            EditorInput.command(TuinkCommand.DOCUMENT_SAVE),
            false
        );

        assertFalse(result.consumed());
        assertFalse(result.helpHintsMode());
    }
}

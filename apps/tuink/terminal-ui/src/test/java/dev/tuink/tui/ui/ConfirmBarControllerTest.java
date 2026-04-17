package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.input.KeyEvent;
import org.junit.jupiter.api.Test;

class ConfirmBarControllerTest {
    @Test
    void threeButtonNavigationFollowsRenderedOrderWhenYesMeansDiscard() {
        assertNavigation(KeyEvent.Action.ARROW_RIGHT, ConfirmChoice.CANCEL, ConfirmChoice.SAVE);
        assertNavigation(KeyEvent.Action.ARROW_LEFT, ConfirmChoice.CANCEL, ConfirmChoice.DISCARD);
        assertNavigation(KeyEvent.Action.ARROW_RIGHT, ConfirmChoice.SAVE, ConfirmChoice.DISCARD);
        assertNavigation(KeyEvent.Action.ARROW_LEFT, ConfirmChoice.SAVE, ConfirmChoice.CANCEL);
    }

    @Test
    void selectedSaveRemainsSaveWhenYesMeansDiscard() {
        ConfirmBarController.InputResult result = handle(
            KeyEvent.Action.ENTER,
            ConfirmChoice.SAVE,
            ConfirmChoice.DISCARD
        );

        assertEquals(ConfirmChoice.SAVE, result.confirmChoice());
        assertEquals(ConfirmChoice.SAVE, result.decision());
    }

    @Test
    void mouseSelectedSaveRemainsSaveWhenEnterIsRemapped() {
        ConfirmBarView bar = ConfirmBarView.build("Quit now?", ConfirmChoice.CANCEL, 80);
        int saveCol = bar.row().indexOf("[Save]");
        assertTrue(saveCol >= 0);
        ConfirmBarController.InputResult mouseResult =
            ConfirmBarController.handleMousePress(bar, 4, saveCol, 4);

        ConfirmBarController.InputResult enterResult = handle(
            KeyEvent.Action.ENTER,
            mouseResult.confirmChoice(),
            ConfirmChoice.DISCARD
        );

        assertEquals(ConfirmChoice.SAVE, mouseResult.decision());
        assertEquals(ConfirmChoice.SAVE, enterResult.decision());
    }

    @Test
    void customButtonLabelsKeepTheirSemanticChoices() {
        ConfirmBarView bar = ConfirmBarView.build(
            "Unsaved (2): a.txt, b.txt",
            ConfirmChoice.CANCEL,
            80,
            "Save All",
            "Discard All",
            true,
            "Cancel"
        );

        int saveAllCol = bar.row().indexOf("[Save All]");
        int discardAllCol = bar.row().indexOf("[Discard All]");

        assertTrue(saveAllCol >= 0);
        assertTrue(discardAllCol >= 0);
        assertEquals(
            ConfirmChoice.SAVE,
            ConfirmBarController.handleMousePress(bar, 4, saveAllCol, 4).decision()
        );
        assertEquals(
            ConfirmChoice.DISCARD,
            ConfirmBarController.handleMousePress(bar, 4, discardAllCol, 4).decision()
        );
    }

    @Test
    void yesAndNoKeepQuitPromptSemantics() {
        ConfirmBarController.InputResult yesResult = ConfirmBarController.handleKey(
            new KeyEvent.CharInput('y'),
            ConfirmChoice.CANCEL,
            ConfirmChoice.DISCARD
        );
        ConfirmBarController.InputResult noResult = ConfirmBarController.handleKey(
            new KeyEvent.CharInput('n'),
            ConfirmChoice.CANCEL,
            ConfirmChoice.DISCARD
        );

        assertEquals(ConfirmChoice.DISCARD, yesResult.decision());
        assertEquals(ConfirmChoice.CANCEL, noResult.decision());
    }

    @Test
    void recoveryDialogNavigatesOnlyRestoreAndDiscard() {
        ConfirmBarController.InputResult initialRight = ConfirmBarController.handleKey(
            action(KeyEvent.Action.ARROW_RIGHT),
            ConfirmChoice.CANCEL,
            ConfirmChoice.SAVE,
            ConfirmChoice.DISCARD,
            false
        );
        ConfirmBarController.InputResult discardRight = ConfirmBarController.handleKey(
            action(KeyEvent.Action.ARROW_RIGHT),
            ConfirmChoice.DISCARD,
            ConfirmChoice.SAVE,
            ConfirmChoice.DISCARD,
            false
        );

        assertEquals(ConfirmChoice.DISCARD, initialRight.confirmChoice());
        assertEquals(ConfirmChoice.SAVE, discardRight.confirmChoice());
    }

    private static void assertNavigation(
        KeyEvent.Action action,
        ConfirmChoice selected,
        ConfirmChoice expected
    ) {
        ConfirmBarController.InputResult result = handle(action, selected, ConfirmChoice.DISCARD);

        assertEquals(expected, result.confirmChoice());
        assertNull(result.decision());
    }

    private static ConfirmBarController.InputResult handle(
        KeyEvent.Action action,
        ConfirmChoice selected,
        ConfirmChoice yesDecision
    ) {
        return ConfirmBarController.handleKey(action(action), selected, yesDecision);
    }

    private static KeyEvent.ActionEvent action(KeyEvent.Action action) {
        return new KeyEvent.ActionEvent(action);
    }
}

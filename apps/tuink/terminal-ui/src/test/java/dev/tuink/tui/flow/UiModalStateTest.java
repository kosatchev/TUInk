package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UiModalStateTest {
    @Test
    void exactlyOneModeCanBeActive() {
        UiModalState state = UiModalState.closeTabConfirm(3);

        assertEquals(UiModalMode.CLOSE_TAB_CONFIRM, state.mode());
        assertEquals(3, state.pendingCloseTabIndex());
        assertThrows(
            IllegalArgumentException.class,
            () -> new UiModalState(
                UiModalMode.CLOSE_TAB_CONFIRM,
                Path.of("other.txt"),
                3,
                ConfirmChoice.CANCEL
            )
        );
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.input.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InputActionDispatcherTest {
    @Test
    void unavailableMouseModePreservesPreferenceAndReportsTheFallback() {
        UiSessionState ui = new UiSessionState();
        ui.mouseModeSetting = true;
        ui.mouseModeAvailable = false;
        AtomicBoolean controllerCalled = new AtomicBoolean();

        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(
            KeyEvent.Action.TOGGLE_MOUSE_MODE,
            ui,
            context(ignored -> controllerCalled.set(true))
        );

        assertTrue(result.handled());
        assertEquals("Mouse mode is unavailable in this terminal", result.statusMessage());
        assertEquals(InputActionDispatcher.StatusLevel.WARNING, result.statusLevel());
        assertTrue(ui.mouseModeSetting);
        assertFalse(controllerCalled.get());
    }

    @Test
    void availableMouseModeStillTogglesThePreferenceAndTerminalController() {
        UiSessionState ui = new UiSessionState();
        ui.mouseModeSetting = true;
        AtomicBoolean enabled = new AtomicBoolean(true);

        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(
            KeyEvent.Action.TOGGLE_MOUSE_MODE,
            ui,
            context(enabled::set)
        );

        assertTrue(result.handled());
        assertEquals(InputActionDispatcher.StatusLevel.ERROR, result.statusLevel());
        assertFalse(ui.mouseModeSetting);
        assertFalse(enabled.get());
    }

    private static InputActionDispatcher.DispatchContext context(
        java.util.function.Consumer<Boolean> mouseTrackingController
    ) {
        return new InputActionDispatcher.DispatchContext(
            null,
            null,
            null,
            mouseTrackingController,
            0
        );
    }
}

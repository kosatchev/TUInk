package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.widget.Button;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.FocusResetPolicy;
import dev.tuink.terminal.widget.FocusScope;
import dev.tuink.terminal.widget.FocusSpace;
import dev.tuink.terminal.widget.Modal;
import dev.tuink.terminal.widget.PlacedInteractiveWidget;
import dev.tuink.terminal.widget.PlacedModal;
import dev.tuink.terminal.widget.RoutedTarget;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HelpOverlayInputRouterTest {
    @Test
    void focusedCloseProducesOneTypedCloseIntentForKeyboardAndPointer() {
        Fixture fixture = fixture();

        assertTrue(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)).closeRequested());
        assertTrue(fixture.route(new TextInput(" ")).closeRequested());
        assertTrue(
            fixture.route(pointer(6, 4, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT))
                .closeRequested()
        );
        assertTrue(
            fixture.route(pointer(2, 9, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT))
                .closeRequested()
        );
    }

    @Test
    void exactTabStaysInsideModalWhileCommandsAndMissesBubbleToOverlayPolicy() {
        Fixture fixture = fixture();

        HelpOverlayInputRouter.Result tab = fixture.route(KeyStroke.of(KeyStroke.Key.TAB));
        assertTrue(tab.consumed());
        assertFalse(tab.closeRequested());

        HelpOverlayInputRouter.Result shiftTab = fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        );
        assertTrue(shiftTab.consumed());
        assertFalse(shiftTab.closeRequested());

        assertFalse(
            fixture.route(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)).consumed()
        );
        assertFalse(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).consumed());
        assertFalse(
            fixture.route(pointer(0, 0, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)).consumed()
        );
    }

    private static Fixture fixture() {
        Rect viewport = new Rect(0, 0, 20, 10);
        PlacedModal frame = new Modal(10, 5, "Help").layout(new Rect(2, 2, 10, 5));
        PlacedInteractiveWidget<Button.Action> close = Button.plain("Close").layout(
            new Rect(4, 6, 5, 1)
        );
        FocusSpace space = FocusSpace.root(viewport);
        FocusScope<HelpOverlayInputRouter.Target, Button.Action> scope = FocusScope.single(
            HelpOverlayInputRouter.Scope.HELP,
            FocusResetPolicy.FIRST_FOCUSABLE,
            new RoutedTarget<>(HelpOverlayInputRouter.Target.CLOSE, close, space)
        );
        return new Fixture(new FocusManager<>(), scope, frame);
    }

    private static PointerEvent pointer(int row, int col, PointerEvent.Kind kind, PointerEvent.Button button) {
        return new PointerEvent(row, col, kind, button, Set.of());
    }

    private record Fixture(
        FocusManager<HelpOverlayInputRouter.Target> focus,
        FocusScope<HelpOverlayInputRouter.Target, Button.Action> scope,
        PlacedModal frame
    ) {
        private HelpOverlayInputRouter.Result route(dev.tuink.terminal.input.TuiInputEvent event) {
            return HelpOverlayInputRouter.route(focus, scope, frame, event);
        }
    }
}

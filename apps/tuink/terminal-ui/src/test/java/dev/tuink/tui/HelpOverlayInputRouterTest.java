package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.RoutedTarget;
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
        private HelpOverlayInputRouter.Result route(dev.tuiframe.input.TuiInputEvent event) {
            return HelpOverlayInputRouter.route(focus, scope, frame, event);
        }
    }
}

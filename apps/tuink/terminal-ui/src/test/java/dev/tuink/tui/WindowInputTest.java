package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WindowInputTest {
    private final CommandRouter router = new CommandRouter();
    private final UiRecipient recipient = UiRecipient.window(new TabUiState(), null);

    @Test void commandHasOnlyInvocationRouteAndRecipientEvenWhenInvokedByAPointer() {
        var invocation = CommandInvocation.of(TuinkCommand.EDIT_SELECT_ALL.id());
        var route = router.dispatch(invocation).orElseThrow();
        var click = new PointerEvent(3, 5, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
        var input = assertInstanceOf(WindowInput.Command.class, TerminalEditorApp.resolveEditorInput(
            router, click, invocation, route, recipient));
        assertEquals(invocation, input.invocation());
        assertSame(route, input.route());
        assertSame(recipient, input.recipient());
        assertEquals(List.of("invocation", "route", "recipient"), Arrays.stream(
            WindowInput.Command.class.getRecordComponents()).map(component -> component.getName()).toList());
    }

    @Test void parameterizedTabActivationKeepsItsValidatedRouteAndArguments() {
        var invocation = router.tabActivationInvocation(7);
        var input = assertInstanceOf(WindowInput.Command.class, TerminalEditorApp.resolveEditorInput(
            router, KeyStroke.of(KeyStroke.Key.F12), invocation, null, recipient));
        assertEquals(invocation, input.invocation());
        assertEquals("7", input.invocation().argument("index").orElseThrow());
        assertEquals(new CommandRouter.ActivateTab(7), input.route());
    }

    @Test void invalidInvocationConsumesItsOriginRatherThanReplayingTextOrClick() {
        for (var event : List.of(new TextInput("must not be inserted"),
            new PointerEvent(3, 5, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()))) {
            assertSame(WindowInput.Consumed.INSTANCE, TerminalEditorApp.resolveEditorInput(router, event,
                CommandInvocation.of("tuink.missing"), null, recipient));
            assertSame(WindowInput.Consumed.INSTANCE, TerminalEditorApp.resolveEditorInput(router, event,
                CommandInvocation.of(TuinkCommand.TAB_ACTIVATE.id()), null, recipient));
        }
    }

    @Test void unicodeAndPasteRemainDifferentTextIntentsWithoutACommandPayload() {
        var text = assertInstanceOf(WindowInput.Text.class,
            WindowInput.physical(new TextInput("😀e\u0301"), recipient));
        assertEquals("😀e\u0301", text.value());
        assertFalse(text.pasted());
        var paste = assertInstanceOf(WindowInput.Text.class,
            WindowInput.physical(new PasteEvent("line1\nline2", true), recipient));
        assertTrue(paste.pasted());
        assertTrue(paste.truncated());
        assertEquals("line1\nline2", paste.value());
        assertThrows(IllegalArgumentException.class, () -> new WindowInput.Text("\uD83D", false, false, recipient));
        assertThrows(IllegalArgumentException.class, () -> new WindowInput.Text("", false, false, recipient));
        assertThrows(IllegalArgumentException.class, () -> new WindowInput.Text("x", false, true, recipient));
    }

    @Test void gestureRetainsPointerModifiersAndModelsKeyboardPanWithoutAKeyFallback() {
        var pointer = new PointerEvent(3, 5, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT));
        var gesture = assertInstanceOf(WindowInput.Gesture.class, WindowInput.physical(pointer, recipient));
        assertSame(pointer, ((WindowInput.Pointer) gesture.motion()).event());
        var pan = assertInstanceOf(WindowInput.Gesture.class, WindowInput.physical(
            KeyStroke.of(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.ALT), recipient));
        assertEquals(new WindowInput.Pan(-3, 0), pan.motion());
        assertSame(WindowInput.Consumed.INSTANCE,
            WindowInput.physical(KeyStroke.of(KeyStroke.Key.F1), recipient));
    }

    @Test void everyExecutableIntentRequiresARecipient() {
        assertThrows(NullPointerException.class, () -> new WindowInput.Text("x", false, false, null));
        assertThrows(NullPointerException.class, () -> new WindowInput.Gesture(new WindowInput.Pan(1, 0), null));
        assertThrows(NullPointerException.class, () -> new WindowInput.Command(
            CommandInvocation.of(TuinkCommand.DOCUMENT_SAVE.id()), new CommandRouter.RoutedAction(TuinkCommand.DOCUMENT_SAVE), null));
    }
}

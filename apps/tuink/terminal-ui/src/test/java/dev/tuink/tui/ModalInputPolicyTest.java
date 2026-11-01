package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuink.tui.ModalInputPolicy.Kind;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.input.EditorInput;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModalInputPolicyTest {
    private static final CommandRouter COMMANDS = new CommandRouter();
    private static final List<Kind> BLOCKERS = List.of(Kind.SAVE_AS, Kind.CONFIRM, Kind.HELP, Kind.OPEN_FILE, Kind.FORMAT);

    @Test
    void boundEditingAndGlobalShortcutsRequestFeedbackBehindEachBlocker() {
        for (Kind kind : BLOCKERS) {
            for (KeyStroke event : List.of(
                ctrl(KeyStroke.Key.V), ctrl(KeyStroke.Key.X), ctrl(KeyStroke.Key.Z),
                ctrl(KeyStroke.Key.S), ctrl(KeyStroke.Key.N),
                KeyStroke.of(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.SHIFT)
            )) {
                assertNotNull(command(event));
                boolean localField = kind == Kind.SAVE_AS && (event.key() == KeyStroke.Key.V
                    || event.key() == KeyStroke.Key.X || event.key() == KeyStroke.Key.ARROW_UP);
                assertEquals(!localField, blocked(kind, event), kind + " " + event);
            }
        }
    }

    @Test
    void cancelHiddenFileAndTreeHorizontalScrollExceptionsRemainLocal() {
        for (Kind kind : BLOCKERS) {
            assertEquals(kind != Kind.SAVE_AS && kind != Kind.CONFIRM, blocked(kind, ctrl(KeyStroke.Key.Q)));
            assertEquals(kind != Kind.OPEN_FILE, blocked(kind, ctrl(KeyStroke.Key.H)));
            for (KeyStroke.Key key : List.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Key.ARROW_RIGHT)) {
                assertEquals(kind != Kind.OPEN_FILE && kind != Kind.SAVE_AS,
                    blocked(kind, KeyStroke.of(key, KeyStroke.Modifier.SHIFT)));
            }
        }
    }

    @Test
    void textAndPasteDistinguishFieldEditingDecisionsAndBlockedContent() {
        for (Kind kind : Kind.values()) {
            assertEquals(kind != Kind.NONE && kind != Kind.SAVE_AS, blocked(kind, new TextInput("😀text")));
            assertEquals(kind != Kind.NONE && kind != Kind.SAVE_AS, blocked(kind, new TextInput("Yes")));
            assertEquals(kind != Kind.NONE && kind != Kind.SAVE_AS, blocked(kind, new PasteEvent("text")));
            assertFalse(blocked(kind, new PasteEvent("partial", true)));
            for (String mnemonic : List.of("y", "Y", "n", "N")) {
                assertEquals(kind == Kind.HELP || kind == Kind.OPEN_FILE || kind == Kind.FORMAT, blocked(kind, new TextInput(mnemonic)));
            }
            assertFalse(blocked(kind, new TextInput(" ")));
        }
        assertTrue(blocked(Kind.CONFIRM, new TextInput("Y\u0301")), "a mnemonic must be one code point");
    }

    @Test
    void pointerResizeUnknownAndUnboundKeysNeverRequestKeyboardFeedback() {
        for (Kind kind : Kind.values()) {
            for (TuiInputEvent event : List.of(
                new ResizeEvent(24, 80),
                new PointerEvent(0, 0, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()),
                new PointerEvent(0, 0, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of()),
                KeyStroke.of(KeyStroke.Key.UNKNOWN)
            )) {
                assertFalse(ModalInputPolicy.blocked(kind, event, TuinkCommand.EDIT_PASTE));
            }
            KeyStroke unbound = KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.ALT);
            assertNull(command(unbound));
            assertFalse(blocked(kind, unbound));
            assertFalse(blocked(kind, KeyStroke.of(KeyStroke.Key.F12)));
        }
        assertFalse(blocked(Kind.NONE, ctrl(KeyStroke.Key.S)));
    }

    @Test
    void saveAsFieldRouterAcceptsItsEditingNavigationAndPasteWithoutFeedback() {
        List<TuiInputEvent> events = List.of(
            new TextInput("path😀"), new PasteEvent("first\nsecond"), new PasteEvent("partial", true),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT), KeyStroke.of(KeyStroke.Key.ARROW_RIGHT),
            KeyStroke.of(KeyStroke.Key.HOME), KeyStroke.of(KeyStroke.Key.END),
            KeyStroke.of(KeyStroke.Key.BACKSPACE), KeyStroke.of(KeyStroke.Key.DELETE),
            ctrl(KeyStroke.Key.A),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL),
            KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.ENTER), KeyStroke.of(KeyStroke.Key.TAB),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        );
        for (TuiInputEvent event : events) {
            TextField field = new TextField();
            field.set("path");
            SaveAsBarView.Placement placement = SaveAsBarView.build(field, 80)
                .place("save-as", 4, new Rect(0, 0, 80, 8));
            assertTrue(SaveAsInputRouter.route(new FocusManager<>(), placement, event).consumed());
            assertFalse(blocked(Kind.SAVE_AS, event), event.toString());
        }
        for (KeyStroke cancel : List.of(KeyStroke.of(KeyStroke.Key.ESCAPE), ctrl(KeyStroke.Key.Q))) {
            SaveAsBarView.Placement placement = SaveAsBarView.build(new TextField(), 80)
                .place("save-as", 4, new Rect(0, 0, 80, 8));
            assertFalse(SaveAsInputRouter.route(new FocusManager<>(), placement, cancel).consumed());
            assertFalse(blocked(Kind.SAVE_AS, cancel), "application-owned cancel is also allowed");
        }
    }

    @Test
    void saveAsSpacePassesThePolicyAndEditsOnlyTheFieldOrActivatesTheFocusedButton() {
        for (SaveAsBarView.Target target : SaveAsBarView.Target.values()) {
            for (TuiInputEvent event : List.of(new TextInput(" "), KeyStroke.of(KeyStroke.Key.SPACE))) {
                TextField filename = new TextField();
                filename.set("my");
                SaveAsBarView.Placement placed = SaveAsBarView.build(filename, 80)
                    .place("save-as", 4, new Rect(0, 0, 80, 8));
                FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
                placed.activate(focus);
                focus.focus(placed.focusScope(), target);

                assertFalse(blocked(Kind.SAVE_AS, event));
                var result = SaveAsInputRouter.route(focus, placed, event);
                assertTrue(result.consumed());
                assertEquals(switch (target) {
                    case FIELD -> SaveAsBarView.Action.VALUE_CHANGED;
                    case SAVE -> SaveAsBarView.Action.SUBMITTED;
                    case CANCEL -> SaveAsBarView.Action.CANCELLED;
                }, result.action());
                assertEquals(target == SaveAsBarView.Target.FIELD ? "my " : "my", filename.text());
            }
        }
    }

    @Test
    void confirmationRouterNavigationAndDecisionsDoNotProduceFeedback() {
        List<TuiInputEvent> events = List.of(
            new TextInput("Y"), new TextInput("n"), new TextInput(" "),
            KeyStroke.of(KeyStroke.Key.TAB), KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT), KeyStroke.of(KeyStroke.Key.ARROW_RIGHT),
            KeyStroke.of(KeyStroke.Key.HOME), KeyStroke.of(KeyStroke.Key.END),
            KeyStroke.of(KeyStroke.Key.ENTER), KeyStroke.of(KeyStroke.Key.SPACE),
            KeyStroke.of(KeyStroke.Key.ESCAPE), ctrl(KeyStroke.Key.Q)
        );
        for (TuiInputEvent event : events) {
            ConfirmBarView.Placement placement = ConfirmBarView.build("Quit?", 80)
                .place("confirm", 4, new Rect(0, 0, 80, 8));
            assertTrue(ConfirmBarInputRouter.route(
                new FocusManager<ConfirmChoice>(), placement, ConfirmBarInputRouter.Policy.quit(), event
            ).consumed());
            assertFalse(blocked(Kind.CONFIRM, event), event.toString());
        }
        assertTrue(blocked(Kind.CONFIRM, KeyStroke.of(KeyStroke.Key.ARROW_UP)));
    }

    @Test
    void helpScrollingIncludingBoundaryNoOpsAndClosingDoNotProduceFeedback() {
        TerminalEditorApp app = new TerminalEditorApp();
        HelpPage page = new HelpPage("Help", List.of("one", "two", "three", "four"));
        for (KeyStroke.Key key : List.of(
            KeyStroke.Key.HOME, KeyStroke.Key.ARROW_UP, KeyStroke.Key.PAGE_UP,
            KeyStroke.Key.END, KeyStroke.Key.ARROW_DOWN, KeyStroke.Key.PAGE_DOWN
        )) {
            KeyStroke event = KeyStroke.of(key);
            assertTrue(app.routeHelpOverlayInput(app.helpOverlayPlacement(8, 24, page), event, null, null).consumed());
            assertFalse(blocked(Kind.HELP, event));
        }
        Rect clip = new Rect(0, 0, 30, 12);
        var frame = new Modal(20, 8, "Help").layout(new Rect(2, 2, 20, 8));
        var close = Button.plain("Close").layout(new Rect(4, 8, 5, 1));
        var scope = FocusScope.single(
            HelpOverlayInputRouter.Scope.HELP, FocusResetPolicy.FIRST_FOCUSABLE,
            new RoutedTarget<>(HelpOverlayInputRouter.Target.CLOSE, close, FocusSpace.root(clip))
        );
        for (TuiInputEvent event : List.of(KeyStroke.of(KeyStroke.Key.ENTER), new TextInput(" "))) {
            assertTrue(HelpOverlayInputRouter.route(new FocusManager<>(), scope, frame, event).closeRequested());
            assertFalse(blocked(Kind.HELP, event));
        }
        assertFalse(HelpHintsController.handle(EditorInput.command(TuinkCommand.UI_ESCAPE), true).helpHintsMode());
        assertFalse(blocked(Kind.HELP, KeyStroke.of(KeyStroke.Key.ESCAPE)));
        for (TuinkCommand command : List.of(
            TuinkCommand.HELP_KEY_HINTS, TuinkCommand.HELP_LICENSES, TuinkCommand.HELP_AUTHOR
        )) {
            assertFalse(ModalInputPolicy.blocked(Kind.HELP, KeyStroke.of(KeyStroke.Key.F6), command));
        }
        assertTrue(blocked(Kind.HELP, KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
    }

    @Test
    void openFileTreeNavigationAndHiddenToggleRemainLocalButSpaceDoesNotActivate() {
        Rect bounds = new Rect(0, 0, 30, 10);
        FileTreeView.Placement placement = FileTreeView.fromSnapshot(
            new FileTreeModel.Snapshot(Path.of("/"), 0, List.of()), false, false
        ).place(bounds, FileTreeView.State.initial(), bounds, true);
        for (KeyStroke event : List.of(
            KeyStroke.of(KeyStroke.Key.ARROW_UP), KeyStroke.of(KeyStroke.Key.ARROW_DOWN),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT), KeyStroke.of(KeyStroke.Key.ARROW_RIGHT),
            KeyStroke.of(KeyStroke.Key.PAGE_UP), KeyStroke.of(KeyStroke.Key.PAGE_DOWN),
            KeyStroke.of(KeyStroke.Key.HOME), KeyStroke.of(KeyStroke.Key.END),
            KeyStroke.of(KeyStroke.Key.BACKSPACE), KeyStroke.of(KeyStroke.Key.ENTER),
            KeyStroke.of(KeyStroke.Key.ESCAPE), KeyStroke.of(KeyStroke.Key.TAB),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT), ctrl(KeyStroke.Key.H)
        )) {
            assertTrue(OpenFileDialogTestSupport.route(
                placement, OpenFileInputRouter.Focus.LIST, event, 0, 450_000_000
            ).consumed());
            assertFalse(blocked(Kind.OPEN_FILE, event), event.toString());
        }
        TextInput space = new TextInput(" ");
        assertNull(OpenFileDialogTestSupport.route(
            placement, OpenFileInputRouter.Focus.LIST, space, 0, 450_000_000
        ).intent());
        assertFalse(blocked(Kind.OPEN_FILE, space));
    }

    @Test
    void openFileSpacePassesFeedbackPolicyBeforeActivatingPlacedButtons() {
        Rect bounds = new Rect(0, 0, 30, 10);
        FileTreeView.Placement tree = FileTreeView.fromSnapshot(
            new FileTreeModel.Snapshot(Path.of("/"), 0, List.of()), false, false
        ).place(bounds, FileTreeView.State.initial(), bounds, true);
        for (TuiInputEvent event : List.of(new TextInput(" "), KeyStroke.of(KeyStroke.Key.SPACE))) {
            for (OpenFileInputRouter.Focus focused : OpenFileInputRouter.Focus.values()) {
                assertFalse(blocked(Kind.OPEN_FILE, event), event.toString());
                // Model the application's policy gate before its raw-event router.
                var result = blocked(Kind.OPEN_FILE, event) ? null
                    : OpenFileDialogTestSupport.route(tree, focused, event, 0, 0);
                assertNotNull(result);
                assertTrue(result.consumed());
                switch (focused) {
                    case LIST -> assertNull(result.intent(), "space must not activate a tree item");
                    case OK -> assertEquals(OpenFileInputRouter.Rejection.NO_SELECTION,
                        assertInstanceOf(OpenFileInputRouter.SelectionRejected.class, result.intent()).reason());
                    case CANCEL -> assertInstanceOf(OpenFileInputRouter.CloseDialog.class, result.intent());
                }
            }
        }
    }

    private static boolean blocked(Kind kind, TuiInputEvent event) {
        return ModalInputPolicy.blocked(kind, event, command(event));
    }

    private static TuinkCommand command(TuiInputEvent event) {
        return event instanceof KeyStroke stroke
            ? COMMANDS.invocationFor(stroke).flatMap(invocation -> TuinkCommand.fromId(invocation.id())).orElse(null)
            : null;
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

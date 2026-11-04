package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SearchBarView;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerminalEditorInputRoutingTest {
    private static final UiRecipient RECIPIENT = UiRecipient.window(new TabUiState(), null);

    private static WindowInput resolve(CommandRouter router, dev.tuiframe.input.TuiInputEvent event,
        CommandInvocation pendingCommand, CommandRouter.RoutedCommand selected,
        boolean searchOwnsInput, SearchFocus focus) {
        return TerminalEditorApp.resolveEditorInput(router, event, pendingCommand, selected, RECIPIENT);
    }

    @Test
    void onlyPhysicalShiftLeftPressExtendsEditorSelection() {
        assertTrue(TerminalEditorApp.shouldExtendEditorSelection(new PointerEvent(
            2,
            4,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        )));
        assertFalse(TerminalEditorApp.shouldExtendEditorSelection(new PointerEvent(
            2,
            4,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of()
        )));
        assertFalse(TerminalEditorApp.shouldExtendEditorSelection(new PointerEvent(
            2,
            4,
            PointerEvent.Kind.RELEASE,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        )));
        assertFalse(TerminalEditorApp.shouldExtendEditorSelection(new PointerEvent(
            2,
            4,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.RIGHT,
            Set.of(KeyStroke.Modifier.SHIFT)
        )));
    }

    @Test
    void typedSearchOutputsMapToApplicationOptionsAndCommands() {
        assertEquals(SearchFocus.CASE, TerminalEditorApp.toggleTarget(SearchBarView.Action.TOGGLE_CASE));
        assertEquals(SearchFocus.WORD, TerminalEditorApp.toggleTarget(SearchBarView.Action.TOGGLE_WORD));
        assertEquals(TuinkCommand.SEARCH_NEXT, TerminalEditorApp.searchCommand(SearchBarView.Action.FIND_NEXT));
        assertEquals(TuinkCommand.SEARCH_PREVIOUS, TerminalEditorApp.searchCommand(SearchBarView.Action.FIND_PREVIOUS));
        assertEquals(TuinkCommand.SEARCH_REPLACE, TerminalEditorApp.searchCommand(SearchBarView.Action.REPLACE));
        assertEquals(TuinkCommand.SEARCH_REPLACE_ALL, TerminalEditorApp.searchCommand(SearchBarView.Action.REPLACE_ALL));
        assertNull(TerminalEditorApp.toggleTarget(SearchBarView.Action.FIND_NEXT));
        assertNull(TerminalEditorApp.searchCommand(SearchBarView.Action.QUERY_CHANGED));
    }

    @Test
    void modalPromptExclusivelyOwnsTheInteractiveRow() {
        assertTrue(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.none()));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.saveAs()));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.closeTabConfirm(0)));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.helpHints()));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(null));
    }

    @Test
    void rawSearchRoutingYieldsToEveryHigherPriorityOwner() {
        assertTrue(TerminalEditorApp.searchOwnsRawInput(true, UiModalState.none(), false, false));
        assertFalse(TerminalEditorApp.searchOwnsRawInput(false, UiModalState.none(), false, false));
        assertFalse(TerminalEditorApp.searchOwnsRawInput(true, UiModalState.helpHints(), false, false));
        assertFalse(TerminalEditorApp.searchOwnsRawInput(true, UiModalState.saveAs(), false, false));
        assertFalse(TerminalEditorApp.searchOwnsRawInput(true, UiModalState.none(), true, false));
        assertFalse(TerminalEditorApp.searchOwnsRawInput(true, UiModalState.none(), false, true));
        assertFalse(TerminalEditorApp.searchOwnsRawInput(
            true,
            UiModalState.none(),
            false,
            false,
            true
        ));
    }

    @Test
    void alreadyRoutedMenuCommandWinsOverUnicodePhysicalTextAndSearchRemapping() {
        CommandRouter router = new CommandRouter();
        CommandRouter.RoutedCommand selected = router.dispatch(
            CommandInvocation.of(EDIT_SELECT_ALL.id())
        ).orElseThrow();
        TextInput decomposedMnemonic = new TextInput("e\u0301");

        WindowInput input = resolve(
            router,
            decomposedMnemonic,
            CommandInvocation.of(EDIT_SELECT_ALL.id()),
            selected,
            true,
            SearchFocus.FIND
        );

        var command = assertInstanceOf(WindowInput.Command.class, input);
        assertEquals(EDIT_SELECT_ALL, ((CommandRouter.RoutedAction) command.route()).action());
        assertEquals(CommandInvocation.of(EDIT_SELECT_ALL.id()), command.invocation());
        assertSame(RECIPIENT, command.recipient());
    }

    @Test
    void searchFocusKeepsSelectAllSeparateFromReplaceAll() {
        CommandRouter router = new CommandRouter();
        KeyStroke selectAll = KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL);
        CommandInvocation invocation = router.invocationFor(selectAll).orElseThrow();

        for (boolean searchOwnsInput : new boolean[] {true, false}) {
            var command = assertInstanceOf(WindowInput.Command.class, resolve(
                router, selectAll, invocation, null, searchOwnsInput, SearchFocus.FIND));
            assertEquals(new CommandRouter.RoutedAction(EDIT_SELECT_ALL), command.route());
        }
    }

    @Test
    void semanticEditCommandsKeepSearchFocusWhileGlobalMenuCommandsKeepTheirApplicationRoute() {
        for (TuinkCommand command : new TuinkCommand[] {
            TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_CUT, TuinkCommand.EDIT_PASTE,
            TuinkCommand.EDIT_SELECT_ALL, TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO,
            TuinkCommand.EDIT_TOGGLE_LINE_COMMENT
        }) {
            assertEquals(CommandRecipient.SEARCH_CONTROL, CommandRecipient.resolve(command, true, false));
            assertEquals(CommandRecipient.EDITOR, CommandRecipient.resolve(command, true, true));
            assertEquals(CommandRecipient.EDITOR, CommandRecipient.resolve(command, false, false));
        }
        for (TuinkCommand command : new TuinkCommand[] {
            TuinkCommand.SEARCH_OPEN, TuinkCommand.DOCUMENT_SAVE, TuinkCommand.DOCUMENT_SAVE_AS,
            TuinkCommand.TAB_NEW, TuinkCommand.TAB_NEXT, TuinkCommand.APP_QUIT,
            TuinkCommand.VIEW_TOGGLE_LINE_WRAP, TuinkCommand.HELP_KEY_HINTS
        }) {
            assertEquals(CommandRecipient.EDITOR, CommandRecipient.resolve(command, true, false));
        }
    }

    @Test
    void unboundModifiedKeyAndUnregisteredCommandHaveNoPhysicalFallback() {
        CommandRouter router = new CommandRouter();
        KeyStroke extraModifier = KeyStroke.of(
            KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.ALT
        );
        assertSame(WindowInput.Consumed.INSTANCE, resolve(
            router, extraModifier, router.invocationFor(extraModifier).orElse(null),
            null, false, SearchFocus.FIND
        ));
        assertSame(WindowInput.Consumed.INSTANCE, resolve(
            router, KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL),
            CommandInvocation.of("tuink.missing-command"), null, false, SearchFocus.FIND
        ));
        KeyStroke shiftedEnter = KeyStroke.of(KeyStroke.Key.ENTER, KeyStroke.Modifier.SHIFT);
        assertSame(WindowInput.Consumed.INSTANCE, resolve(
            router, shiftedEnter, router.invocationFor(shiftedEnter).orElse(null),
            null, true, SearchFocus.FIND
        ));
    }

    @Test
    void rawUnicodeTextAndPointerModifiersArePreservedThroughRouting() {
        CommandRouter router = new CommandRouter();
        for (String text : new String[] {"😀", "e\u0301", "аб"}) {
            TextInput event = new TextInput(text);
            WindowInput input = resolve(
                router, event, null, null, false, SearchFocus.FIND
            );
            var routedText = assertInstanceOf(WindowInput.Text.class, input);
            assertEquals(text, routedText.value());
            assertFalse(routedText.pasted());
            assertSame(RECIPIENT, routedText.recipient());
        }
        PointerEvent event = new PointerEvent(
            2, 4, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.CTRL)
        );
        WindowInput input = resolve(
            router, event, null, null, false, SearchFocus.FIND
        );
        var gesture = assertInstanceOf(WindowInput.Gesture.class, input);
        assertSame(event, ((WindowInput.Pointer) gesture.motion()).event());
        assertSame(RECIPIENT, gesture.recipient());
    }

    @Test
    void tabChromePolicyDistinguishesWindowOwnershipFromLocalPresentationControls() {
        assertEquals(TabBarInputRouter.Policy.NORMAL,
            TerminalEditorApp.tabBarInputPolicy(true, true, false, false));
        assertEquals(TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM,
            TerminalEditorApp.tabBarInputPolicy(true, true, true, false));
        assertNull(TerminalEditorApp.tabBarInputPolicy(false, true, false, false));
        assertNull(TerminalEditorApp.tabBarInputPolicy(true, false, false, false));
        assertNull(TerminalEditorApp.tabBarInputPolicy(true, true, false, true));
    }

    @Test
    void helpRequestedWhileSearchOwnsInputIsPromotedAndConsumed() {
        UiSessionState ui = new UiSessionState();
        var result = InputActionDispatcher.dispatchUi(TuinkCommand.HELP_KEY_HINTS, ui,
            TestSurfaces.source(ui), new TopMenuController(), ignored -> {}, () -> {});
        assertEquals(InputActionDispatcher.Intent.HELP_HINTS, result.intent());
    }
}

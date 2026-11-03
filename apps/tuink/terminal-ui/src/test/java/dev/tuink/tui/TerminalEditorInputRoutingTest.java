package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.input.EditorInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SearchBarView;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerminalEditorInputRoutingTest {
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
    void alreadyRoutedMenuCommandWinsOverUnicodeTextAndPendingKeyboardCommand() {
        CommandRouter router = new CommandRouter();
        CommandRouter.RoutedCommand selected = router.dispatch(
            CommandInvocation.of(EDIT_SELECT_ALL.id())
        ).orElseThrow();
        TextInput decomposedMnemonic = new TextInput("e\u0301");

        EditorInput input = TerminalEditorApp.resolveEditorInput(
            router,
            decomposedMnemonic,
            CommandInvocation.of(DOCUMENT_SAVE.id()),
            selected,
            true,
            SearchFocus.FIND
        );

        assertEquals(EDIT_SELECT_ALL, input.command());
        assertSame(decomposedMnemonic, input.event());
    }

    @Test
    void searchFocusKeepsSelectAllSeparateFromReplaceAll() {
        CommandRouter router = new CommandRouter();
        KeyStroke selectAll = KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL);
        CommandInvocation invocation = router.invocationFor(selectAll).orElseThrow();

        assertEquals(EDIT_SELECT_ALL, TerminalEditorApp.resolveEditorInput(
            router, selectAll, invocation, null, true, SearchFocus.FIND
        ).command());
        assertEquals(EDIT_SELECT_ALL, TerminalEditorApp.resolveEditorInput(
            router, selectAll, invocation, null, false, SearchFocus.FIND
        ).command());
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
        assertNull(TerminalEditorApp.resolveEditorInput(
            router, extraModifier, router.invocationFor(extraModifier).orElse(null),
            null, false, SearchFocus.FIND
        ));
        assertNull(TerminalEditorApp.resolveEditorInput(
            router, KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL),
            CommandInvocation.of("tuink.missing-command"), null, false, SearchFocus.FIND
        ));
        KeyStroke shiftedEnter = KeyStroke.of(KeyStroke.Key.ENTER, KeyStroke.Modifier.SHIFT);
        assertNull(TerminalEditorApp.resolveEditorInput(
            router, shiftedEnter, router.invocationFor(shiftedEnter).orElse(null),
            null, true, SearchFocus.FIND
        ));
    }

    @Test
    void rawUnicodeTextAndPointerModifiersArePreservedThroughRouting() {
        CommandRouter router = new CommandRouter();
        for (String text : new String[] {"😀", "e\u0301", "аб"}) {
            TextInput event = new TextInput(text);
            EditorInput input = TerminalEditorApp.resolveEditorInput(
                router, event, null, null, false, SearchFocus.FIND
            );
            assertSame(event, input.event());
            assertNull(input.command());
        }
        PointerEvent event = new PointerEvent(
            2, 4, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.CTRL)
        );
        EditorInput input = TerminalEditorApp.resolveEditorInput(
            router, event, null, null, false, SearchFocus.FIND
        );
        assertSame(event, input.event());
        assertNull(input.command());
    }

    @Test
    void rawTabRoutingYieldsToVisualOwnersAndKeepsCloseConfirmationSafe() {
        assertEquals(
            TabBarInputRouter.Policy.NORMAL,
            TerminalEditorApp.tabBarInputPolicy(true, true, UiModalState.none(), false, false, false)
        );
        assertEquals(
            TabBarInputRouter.Policy.SEARCH,
            TerminalEditorApp.tabBarInputPolicy(true, true, UiModalState.none(), false, false, true)
        );
        assertEquals(
            TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM,
            TerminalEditorApp.tabBarInputPolicy(
                true,
                true,
                UiModalState.closeTabConfirm(0),
                false,
                false,
                false
            )
        );
        assertNull(TerminalEditorApp.tabBarInputPolicy(
            true,
            true,
            UiModalState.none(),
            false,
            true,
            false
        ));
        assertNull(TerminalEditorApp.tabBarInputPolicy(
            true,
            true,
            UiModalState.none(),
            true,
            false,
            false
        ));
        assertNull(TerminalEditorApp.tabBarInputPolicy(
            true,
            true,
            UiModalState.saveAs(),
            false,
            false,
            false
        ));
        assertNull(TerminalEditorApp.tabBarInputPolicy(
            false,
            true,
            UiModalState.none(),
            false,
            false,
            false
        ));
        assertNull(TerminalEditorApp.tabBarInputPolicy(
            true,
            false,
            UiModalState.none(),
            false,
            false,
            false
        ));
    }

    @Test
    void helpRequestedWhileSearchOwnsInputIsPromotedAndConsumed() {
        UiSessionState ui = new UiSessionState();
        var result = InputActionDispatcher.dispatchUi(TuinkCommand.HELP_KEY_HINTS, ui,
            TestSurfaces.source(ui), new TopMenuController(), ignored -> {}, () -> {});
        assertEquals(InputActionDispatcher.Intent.HELP_HINTS, result.intent());
    }
}

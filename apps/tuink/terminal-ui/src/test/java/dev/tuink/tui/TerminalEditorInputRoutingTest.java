package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_SELECT_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.input.KeyEvent;
import dev.tuink.tui.ui.SearchBarView;
import org.junit.jupiter.api.Test;

class TerminalEditorInputRoutingTest {
    @Test
    void typedSearchOutputsMapToApplicationOptionsAndCommands() {
        assertEquals(SearchFocus.CASE, TerminalEditorApp.toggleTarget(SearchBarView.Action.TOGGLE_CASE));
        assertEquals(SearchFocus.WORD, TerminalEditorApp.toggleTarget(SearchBarView.Action.TOGGLE_WORD));
        assertEquals(KeyEvent.Action.CTRL_F, TerminalEditorApp.keyAction(SearchBarView.Action.FIND_NEXT));
        assertEquals(KeyEvent.Action.CTRL_G, TerminalEditorApp.keyAction(SearchBarView.Action.FIND_PREVIOUS));
        assertEquals(KeyEvent.Action.CTRL_R, TerminalEditorApp.keyAction(SearchBarView.Action.REPLACE));
        assertEquals(KeyEvent.Action.CTRL_A, TerminalEditorApp.keyAction(SearchBarView.Action.REPLACE_ALL));
        assertNull(TerminalEditorApp.toggleTarget(SearchBarView.Action.FIND_NEXT));
        assertNull(TerminalEditorApp.keyAction(SearchBarView.Action.QUERY_CHANGED));
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
    void typedMenuCommandWinsOverUnicodeTextFallbackAndRegistryResolutionRunsOnce() {
        CommandInvocation selected = CommandInvocation.of(EDIT_SELECT_ALL);
        TextInput decomposedMnemonic = new TextInput("e\u0301");

        assertTrue(TerminalEditorApp.shouldHandleMultiScalarTextFallback(
            decomposedMnemonic,
            null
        ));
        assertFalse(TerminalEditorApp.shouldHandleMultiScalarTextFallback(
            decomposedMnemonic,
            selected
        ));

        LegacyCommandBridge bridge = new LegacyCommandBridge();
        LegacyCommandBridge.RoutedCommand routedSelection = bridge.dispatch(selected).orElseThrow();
        LegacyCommandBridge.RoutedAction selectedAction =
            (LegacyCommandBridge.RoutedAction) routedSelection;
        KeyEvent.Action resolvedSelection = selectedAction.action();
        assertEquals(KeyEvent.Action.CTRL_A, resolvedSelection);
        assertEquals(
            resolvedSelection,
            TerminalEditorApp.resolvePendingCommand(
                bridge,
                CommandInvocation.of(DOCUMENT_SAVE),
                resolvedSelection,
                true
            ),
            "an already routed menu command must not invoke the pending registry entry again"
        );
        assertEquals(
            KeyEvent.Action.CTRL_S,
            TerminalEditorApp.resolvePendingCommand(
                bridge,
                CommandInvocation.of(DOCUMENT_SAVE),
                KeyEvent.Action.CTRL_A,
                false
            )
        );
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
        ui.helpHintsMode = true;

        UiModalState modal = TerminalEditorApp.consumeRequestedHelpModal(ui, UiModalState.none());

        assertTrue(modal.isHelpHints());
        assertFalse(ui.helpHintsMode);
    }
}

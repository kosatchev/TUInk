package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE_AS;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_TOGGLE_LINE_COMMENT;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_NEXT;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_REPLACE_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.tui.command.CommandInvocation;
import dev.tuink.tui.input.KeyEvent;
import dev.tuink.tui.input.KeyStroke;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.keymap.Keymap;
import dev.tuink.tui.style.CellStyle;
import dev.tuink.tui.ui.MenuBar;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class LegacyCommandBridgeTest {
    private final LegacyCommandBridge bridge = new LegacyCommandBridge();

    @Test
    void keyboardAndMenuActionsProduceTheSameSemanticInvocation() {
        CommandInvocation keyboard = bridge.invocationFor(
            KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL)
        ).orElseThrow();
        CommandInvocation menu = bridge.invocationFor(
            KeyEvent.Action.TOGGLE_LINE_COMMENT
        ).orElseThrow();

        assertEquals(EDIT_TOGGLE_LINE_COMMENT, keyboard.id());
        assertEquals(keyboard, menu);
        assertEquals(KeyEvent.Action.TOGGLE_LINE_COMMENT, bridge.invoke(keyboard).orElseThrow());
        assertEquals(KeyEvent.Action.TOGGLE_LINE_COMMENT, bridge.invoke(menu).orElseThrow());
    }

    @Test
    void actualMenuSelectionJoinsTheSameCommandPath() {
        MenuBar menu = new MenuBar(
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT
        );
        menu.openEdit();
        for (int i = 0; i < 5; i++) {
            TopMenuInputController.handleWhenOpen(
                menu,
                new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_DOWN)
            );
        }

        TopMenuInputController.HandleResult selected = TopMenuInputController.handleWhenOpen(
            menu,
            new KeyEvent.ActionEvent(KeyEvent.Action.ENTER)
        );
        CommandInvocation menuInvocation = bridge.invocationFor(selected.selectedAction()).orElseThrow();
        CommandInvocation keyboardInvocation = bridge.invocationFor(
            KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL)
        ).orElseThrow();

        assertEquals(KeyEvent.Action.TOGGLE_LINE_COMMENT, selected.selectedAction());
        assertEquals(keyboardInvocation, menuInvocation);
        assertEquals(KeyEvent.Action.TOGGLE_LINE_COMMENT, bridge.invoke(menuInvocation).orElseThrow());
    }

    @Test
    void multipleShortcutsAndMenuAliasUseOneSaveAsCommand() {
        CommandInvocation ctrlK = bridge.invocationFor(
            KeyStroke.of(KeyStroke.Key.K, KeyStroke.Modifier.CTRL)
        ).orElseThrow();
        CommandInvocation ctrlShiftS = bridge.invocationFor(
            KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        ).orElseThrow();
        CommandInvocation menu = bridge.invocationFor(KeyEvent.Action.CTRL_K).orElseThrow();

        assertEquals(DOCUMENT_SAVE_AS, ctrlK.id());
        assertEquals(ctrlK, ctrlShiftS);
        assertEquals(ctrlK, menu);
        assertEquals(KeyEvent.Action.CTRL_K, bridge.invoke(ctrlShiftS).orElseThrow());
    }

    @Test
    void unboundPhysicalAndUnknownLegacyInputsStayOutsideTheRegistry() {
        assertFalse(bridge.invocationFor(KeyStroke.of(KeyStroke.Key.F12)).isPresent());
        assertFalse(bridge.invocationFor(KeyEvent.Action.UNKNOWN).isPresent());
    }

    @Test
    void everySupportedLegacyActionHasASemanticCommandId() {
        List<KeyEvent.Action> missing = Arrays.stream(KeyEvent.Action.values())
            .filter(action -> action != KeyEvent.Action.UNKNOWN)
            .filter(action -> bridge.invocationFor(action).isEmpty())
            .toList();

        assertEquals(List.of(), missing);
    }

    @Test
    void semanticSaveIdDoesNotEncodeItsKeyboardShortcut() {
        assertEquals(
            DOCUMENT_SAVE,
            bridge.invocationFor(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL))
                .orElseThrow()
                .id()
        );
    }

    @Test
    void searchFocusReplacesGlobalEditorSemanticsBeforeRegistryDispatch() {
        CommandInvocation globalSelectAll = bridge.invocationFor(
            KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL)
        ).orElseThrow();
        CommandInvocation searchReplaceAll = bridge.invocationForSearch(
            KeyEvent.Action.CTRL_A,
            SearchFocus.FIND,
            globalSelectAll
        );
        CommandInvocation searchEnter = bridge.invocationForSearch(
            KeyEvent.Action.ENTER,
            SearchFocus.PREV_BUTTON,
            bridge.invocationFor(KeyStroke.of(KeyStroke.Key.ENTER)).orElseThrow()
        );
        CommandInvocation fieldEnter = bridge.invocationForSearch(
            KeyEvent.Action.ENTER,
            SearchFocus.FIND,
            bridge.invocationFor(KeyStroke.of(KeyStroke.Key.ENTER)).orElseThrow()
        );

        assertEquals(EDIT_SELECT_ALL, globalSelectAll.id());
        assertEquals(SEARCH_REPLACE_ALL, searchReplaceAll.id());
        assertEquals(SEARCH_PREVIOUS, searchEnter.id());
        assertEquals(SEARCH_NEXT, fieldEnter.id());
        assertEquals(KeyEvent.Action.CTRL_A, bridge.invoke(searchReplaceAll).orElseThrow());
        assertEquals(KeyEvent.Action.CTRL_G, bridge.invoke(searchEnter).orElseThrow());
    }

    @Test
    void unknownSemanticBindingNeverFallsBackToDangerousLegacyAction() {
        Keymap<KeyStroke> custom = new Keymap<>();
        KeyStroke ctrlQ = KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL);
        CommandInvocation pluginCommand = CommandInvocation.of("example.plugin.run");
        custom.bind(ctrlQ, pluginCommand);
        LegacyCommandBridge customBridge = new LegacyCommandBridge(custom);

        assertEquals(
            KeyEvent.Action.UNKNOWN,
            customBridge.resolve(customBridge.invocationFor(ctrlQ).orElseThrow(), KeyEvent.Action.CTRL_Q)
        );
    }

    @Test
    void dynamicTabActivationCarriesItsIndexThroughTheRegistry() {
        CommandInvocation invocation = bridge.tabActivationInvocation(3);

        assertEquals("tuink.tab.activate", invocation.id().value());
        assertEquals("3", invocation.argument("index").orElseThrow());
        assertEquals(3, bridge.invokeTabActivation(invocation).orElseThrow());
        assertFalse(bridge.invoke(invocation).isPresent());
        assertThrows(IllegalArgumentException.class, () -> bridge.tabActivationInvocation(-1));
    }
}

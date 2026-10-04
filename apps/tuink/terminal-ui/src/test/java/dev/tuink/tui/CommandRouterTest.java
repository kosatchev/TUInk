package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.APP_QUIT;
import static dev.tuink.tui.command.TuinkCommand.CURSOR_HOME;
import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE_AS;
import static dev.tuink.tui.command.TuinkCommand.EDIT_INSERT_NEWLINE;
import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommand.EDIT_TOGGLE_LINE_COMMENT;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_NEXT;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_REPLACE_ALL;
import static dev.tuink.tui.command.TuinkCommand.TAB_ACTIVATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.keymap.Keymap;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.widget.MenuBar;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandRouterTest {
    private final CommandRouter router = new CommandRouter();

    @Test
    void keyboardAndMenuActionsProduceTheSameSemanticInvocation() {
        CommandInvocation keyboard = router.invocationFor(
            KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL)
        ).orElseThrow();
        CommandInvocation menu = CommandInvocation.of(EDIT_TOGGLE_LINE_COMMENT.id());

        assertEquals(EDIT_TOGGLE_LINE_COMMENT.id(), keyboard.id());
        assertEquals(keyboard, menu);
        assertEquals(EDIT_TOGGLE_LINE_COMMENT, router.invoke(keyboard).orElseThrow());
        assertEquals(EDIT_TOGGLE_LINE_COMMENT, router.invoke(menu).orElseThrow());
    }

    @Test
    void actualMenuSelectionJoinsTheSameCommandPath() {
        TopMenuController controller = new TopMenuController();
        controller.adopt(MenuBar.State.open(TopMenuController.EDIT_INDEX));
        for (int i = 0; i < 5; i++) {
            TopMenuInputRouter.route(
                controller,
                menuPlacement(controller),
                router,
                KeyStroke.of(KeyStroke.Key.ARROW_DOWN)
            );
        }

        TopMenuInputRouter.Result selected = TopMenuInputRouter.route(
            controller,
            menuPlacement(controller),
            router,
            KeyStroke.of(KeyStroke.Key.ENTER)
        );
        CommandInvocation keyboard = router.invocationFor(
            KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL)
        ).orElseThrow();

        assertTrue(selected.consumed());
        assertEquals(keyboard, selected.command());
        assertEquals(EDIT_TOGGLE_LINE_COMMENT, router.invoke(selected.command()).orElseThrow());
        assertEquals(MenuBar.State.closed(), controller.state());
    }

    @Test
    void multipleShortcutsAndMenuUseOneSaveAsCommand() {
        CommandInvocation ctrlK = router.invocationFor(
            KeyStroke.of(KeyStroke.Key.K, KeyStroke.Modifier.CTRL)
        ).orElseThrow();
        CommandInvocation ctrlShiftS = router.invocationFor(
            KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        ).orElseThrow();
        CommandInvocation menu = CommandInvocation.of(DOCUMENT_SAVE_AS.id());

        assertEquals(DOCUMENT_SAVE_AS.id(), ctrlK.id());
        assertEquals(ctrlK, ctrlShiftS);
        assertEquals(ctrlK, menu);
        assertEquals(DOCUMENT_SAVE_AS, router.invoke(ctrlShiftS).orElseThrow());
    }

    @Test
    void unassignedPhysicalInputStaysOutsideTheRegistry() {
        assertFalse(router.invocationFor(KeyStroke.of(KeyStroke.Key.F12)).isPresent());
    }

    @Test
    void builtinsHaveUniqueIdsAndDispatchTheirCanonicalSemanticCommands() {
        assertEquals(
            TuinkCommand.values().length,
            Arrays.stream(TuinkCommand.values()).map(TuinkCommand::id).distinct().count()
        );
        for (TuinkCommand command : TuinkCommand.values()) {
            assertFalse(command.title().isBlank(), command.name());
            assertEquals(command, TuinkCommand.fromId(command.id()).orElseThrow());
            if (command == TAB_ACTIVATE) {
                assertEquals(new CommandRouter.ActivateTab(0), router.dispatch(
                    router.tabActivationInvocation(0)
                ).orElseThrow());
            } else {
                assertEquals(command, router.invoke(CommandInvocation.of(command.id())).orElseThrow());
            }
        }
        assertTrue(TuinkCommand.fromId(CommandId.of("example.plugin.run")).isEmpty());
    }

    @Test
    void semanticSaveIdDoesNotEncodeItsKeyboardShortcut() {
        assertEquals(
            DOCUMENT_SAVE.id(),
            router.invocationFor(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL))
                .orElseThrow()
                .id()
        );
    }

    @Test
    void searchFocusReplacesGlobalEditorSemanticsBeforeRegistryDispatch() {
        CommandInvocation globalSelectAll = router.invocationFor(
            KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL)
        ).orElseThrow();
        CommandInvocation searchReplaceAll = router.invocationForSearch(
            EDIT_SELECT_ALL,
            SearchFocus.FIND,
            globalSelectAll
        );
        CommandInvocation enter = router.invocationFor(KeyStroke.of(KeyStroke.Key.ENTER)).orElseThrow();
        CommandInvocation searchEnter = router.invocationForSearch(
            EDIT_INSERT_NEWLINE,
            SearchFocus.PREV_BUTTON,
            enter
        );
        CommandInvocation fieldEnter = router.invocationForSearch(
            EDIT_INSERT_NEWLINE,
            SearchFocus.FIND,
            enter
        );

        assertEquals(EDIT_SELECT_ALL.id(), globalSelectAll.id());
        assertEquals(SEARCH_REPLACE_ALL.id(), searchReplaceAll.id());
        assertEquals(SEARCH_PREVIOUS.id(), searchEnter.id());
        assertEquals(SEARCH_NEXT.id(), fieldEnter.id());
        assertEquals(SEARCH_REPLACE_ALL, router.invoke(searchReplaceAll).orElseThrow());
        assertEquals(SEARCH_PREVIOUS, router.invoke(searchEnter).orElseThrow());
    }

    @Test
    void searchFieldNavigationUsesFocusedCommandsAndPreservesUnrelatedGlobalSemantics() {
        assertEquals(
            TuinkCommand.SEARCH_NAVIGATE_LEFT.id(),
            router.invocationForSearch(TuinkCommand.CURSOR_LEFT, SearchFocus.FIND, null).id()
        );
        assertEquals(
            TuinkCommand.SEARCH_FIELD_BACKSPACE.id(),
            router.invocationForSearch(TuinkCommand.EDIT_BACKSPACE, SearchFocus.REPLACE, null).id()
        );
        assertEquals(
            TuinkCommand.SEARCH_FIELD_HOME.id(),
            router.invocationForSearch(CURSOR_HOME, SearchFocus.FIND, null).id()
        );
        assertEquals(
            CURSOR_HOME.id(),
            router.invocationForSearch(CURSOR_HOME, SearchFocus.CASE, null).id()
        );
        assertEquals(
            SEARCH_NEXT.id(),
            router.invocationForSearch(TuinkCommand.SEARCH_OPEN, SearchFocus.FIND, null).id()
        );
        CommandInvocation quit = CommandInvocation.of(APP_QUIT.id());
        assertEquals(quit, router.invocationForSearch(APP_QUIT, SearchFocus.FIND, quit));
        assertNull(router.invocationForSearch(null, SearchFocus.FIND, null));
    }

    @Test
    void unknownSemanticBindingNeverFallsBackToAnotherAction() {
        Keymap<KeyStroke> custom = new Keymap<>();
        KeyStroke ctrlQ = KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL);
        CommandInvocation pluginCommand = CommandInvocation.of("example.plugin.run");
        custom.bind(ctrlQ, pluginCommand);
        CommandRouter customRouter = new CommandRouter(custom);
        CommandInvocation resolved = customRouter.invocationFor(ctrlQ).orElseThrow();

        assertEquals(pluginCommand, resolved);
        assertTrue(customRouter.invoke(resolved).isEmpty());
        assertTrue(customRouter.dispatch(resolved).isEmpty());
        assertEquals(pluginCommand, customRouter.invocationForSearch(null, SearchFocus.FIND, resolved));
    }

    @Test
    void customPhysicalBindingDispatchesItsDeclaredSemanticCommand() {
        Keymap<KeyStroke> custom = new Keymap<>();
        KeyStroke ctrlQ = KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL);
        custom.bind(ctrlQ, CommandInvocation.of(DOCUMENT_SAVE.id()));
        CommandRouter customRouter = new CommandRouter(custom);

        assertEquals(DOCUMENT_SAVE, customRouter.invoke(
            customRouter.invocationFor(ctrlQ).orElseThrow()
        ).orElseThrow());
    }

    @Test
    void dynamicTabActivationCarriesItsIndexThroughTheRegistry() {
        CommandInvocation invocation = router.tabActivationInvocation(3);

        assertEquals("tuink.tab.activate", invocation.id().value());
        assertEquals("3", invocation.argument("index").orElseThrow());
        assertEquals(3, router.invokeTabActivation(invocation).orElseThrow());
        assertEquals(3, assertInstanceOf(
            CommandRouter.ActivateTab.class,
            router.dispatch(invocation).orElseThrow()
        ).tabIndex());
        assertFalse(router.invoke(invocation).isPresent());
        assertThrows(IllegalArgumentException.class, () -> router.tabActivationInvocation(-1));
    }

    @Test
    void malformedTabArgumentsAreRejectedBeforeDispatch() {
        List<Map<String, String>> invalidArguments = List.of(
            Map.of(),
            Map.of("index", "-1"),
            Map.of("index", "not-an-index"),
            Map.of("index", "2147483648"),
            Map.of("index", "3", "unexpected", "value")
        );
        for (Map<String, String> arguments : invalidArguments) {
            CommandInvocation invalid = new CommandInvocation(TAB_ACTIVATE.id(), arguments);

            assertTrue(router.dispatch(invalid).isEmpty(), arguments.toString());
            assertTrue(router.invokeTabActivation(invalid).isEmpty(), arguments.toString());
        }
    }

    private TopMenuView.Placement menuPlacement(TopMenuController controller) {
        return TopMenuView.build(
            new UiSessionState(),
            List.of(),
            -1,
            "Cyan",
            router,
            controller.state()
        ).place(new Rect(0, 0, 80, 20));
    }
}

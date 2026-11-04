package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.DialogInputPolicy;
import dev.tuiframe.widget.DialogInputPolicy.Action;
import dev.tuiframe.widget.DialogInputPolicy.Decision;
import dev.tuiframe.widget.DialogInputPolicy.Target;
import dev.tuiframe.widget.DialogMode;
import dev.tuiframe.widget.TextField;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DialogCommandPolicyTest {
    private static final List<TuinkCommand> READ_ONLY = List.of(
        TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_SELECT_ALL,
        TuinkCommand.CURSOR_UP, TuinkCommand.CURSOR_DOWN, TuinkCommand.CURSOR_LEFT, TuinkCommand.CURSOR_RIGHT,
        TuinkCommand.CURSOR_SELECT_UP, TuinkCommand.CURSOR_SELECT_DOWN,
        TuinkCommand.CURSOR_SELECT_LEFT, TuinkCommand.CURSOR_SELECT_RIGHT,
        TuinkCommand.CURSOR_WORD_LEFT, TuinkCommand.CURSOR_WORD_RIGHT,
        TuinkCommand.CURSOR_PAGE_UP, TuinkCommand.CURSOR_PAGE_DOWN,
        TuinkCommand.CURSOR_HOME, TuinkCommand.CURSOR_END,
        TuinkCommand.CURSOR_SELECT_HOME, TuinkCommand.CURSOR_SELECT_END,
        TuinkCommand.CURSOR_DOCUMENT_START, TuinkCommand.CURSOR_DOCUMENT_END,
        TuinkCommand.CURSOR_SELECT_DOCUMENT_START, TuinkCommand.CURSOR_SELECT_DOCUMENT_END
    );
    private static final KeyStroke PHYSICAL_KEY = KeyStroke.of(KeyStroke.Key.UNKNOWN);

    @Test
    void exclusivePanelsExcludeTheDocumentWhileInlinePromptsAndSearchPermitContentFocus() {
        for (ModalInputPolicy.Kind kind : ModalInputPolicy.Kind.values()) {
            DialogMode expected = switch (kind) {
                case HELP, OPEN_FILE, FORMAT -> DialogMode.EXCLUSIVE;
                case SAVE_AS, CONFIRM -> DialogMode.BLOCKING;
                case NONE -> DialogMode.NON_BLOCKING;
            };
            assertEquals(expected, DialogCommandPolicy.mode(kind), kind.toString());
            assertEquals(expected != DialogMode.EXCLUSIVE,
                DialogCommandPolicy.mode(kind).permitsContentFocus(), kind.toString());
        }
    }

    @Test
    void helpAndOpenFileRejectDocumentReadingAndCopyEvenIfContentOwnershipIsStale() {
        for (ModalInputPolicy.Kind kind : List.of(ModalInputPolicy.Kind.HELP, ModalInputPolicy.Kind.OPEN_FILE, ModalInputPolicy.Kind.FORMAT)) {
            for (TuinkCommand command : READ_ONLY) {
                Fixture fixture = fixture();
                List<String> original = fixture.tab.model().snapshotLines();
                long revision = fixture.tab.model().revision();
                Position cursor = fixture.tab.state().getCursor();

                assertEquals(Decision.BLOCK, routeContent(fixture, kind, PHYSICAL_KEY, command),
                    kind + " " + command);

                assertEquals(original, fixture.tab.model().snapshotLines());
                assertEquals(revision, fixture.tab.model().revision());
                assertEquals(cursor, fixture.tab.state().getCursor());
                assertEquals("clipboard seed", fixture.clipboard.text());
                assertTrue(fixture.tab.engine().undo(), "rejected reading must preserve document history");
                assertEquals(List.of(""), fixture.tab.model().snapshotLines());
                assertTrue(fixture.tab.engine().redo());
                assertEquals(original, fixture.tab.model().snapshotLines());
            }
            for (PointerEvent.Kind pointerKind : List.of(PointerEvent.Kind.PRESS, PointerEvent.Kind.DRAG,
                PointerEvent.Kind.RELEASE, PointerEvent.Kind.SCROLL_UP, PointerEvent.Kind.SCROLL_DOWN,
                PointerEvent.Kind.SCROLL_LEFT, PointerEvent.Kind.SCROLL_RIGHT)) {
                PointerEvent event = new PointerEvent(3, 8, pointerKind, PointerEvent.Button.LEFT, Set.of());
                assertEquals(Decision.BLOCK, routeContent(fixture(), kind, event, null),
                    kind + " " + pointerKind);
            }
            assertEquals(Decision.BLOCK, routeContent(fixture(), kind, PHYSICAL_KEY, null),
                "exclusive dialogs must also capture unclassified document input");
        }
    }

    @Test
    void everyAllowedDocumentCommandReachesTheEngineWithoutChangingTextRevisionOrHistory() {
        for (TuinkCommand command : READ_ONLY) {
            Fixture fixture = fixture();
            List<String> original = fixture.tab.model().snapshotLines();
            long revision = fixture.tab.model().revision();

            Decision decision = routeContent(fixture, PHYSICAL_KEY, command);

            assertEquals(Decision.ALLOW, decision, command.toString());
            assertEquals(original, fixture.tab.model().snapshotLines(), command.toString());
            assertEquals(revision, fixture.tab.model().revision(), command.toString());
            assertTrue(fixture.tab.state().isDirty());
            assertFalse(fixture.ui.readingModeSetting, "the dialog policy must work independently of reading mode");
            assertTrue(fixture.tab.engine().undo(), command.toString());
            assertEquals(List.of(""), fixture.tab.model().snapshotLines(), "navigation must not add undo entries");
            assertTrue(fixture.tab.engine().redo());
            assertEquals(original, fixture.tab.model().snapshotLines());
        }
    }

    @Test
    void allowedCopyUsesTheDocumentSelectionAndChangesOnlyTheSharedClipboard() {
        Fixture fixture = fixture();
        fixture.tab.engine().beginSelectionAt(new Position(0, "alpha ".length()));
        fixture.tab.engine().extendSelectionTo(new Position(0, "alpha 👩‍💻".length()));
        long revision = fixture.tab.model().revision();
        List<String> original = fixture.tab.model().snapshotLines();

        assertEquals(Decision.ALLOW, routeContent(fixture, PHYSICAL_KEY, TuinkCommand.EDIT_COPY));

        assertEquals("👩‍💻", fixture.clipboard.text());
        assertEquals(original, fixture.tab.model().snapshotLines());
        assertEquals(revision, fixture.tab.model().revision());
        assertTrue(fixture.tab.engine().hasSelection());
    }

    @Test
    void allOtherCommandsIncludingEditsFileTabAndMenuActionsStayBehindTheBlockingDialog() {
        for (TuinkCommand command : TuinkCommand.values()) {
            if (READ_ONLY.contains(command)) {
                continue;
            }
            Fixture fixture = fixture();
            List<String> original = fixture.tab.model().snapshotLines();
            long revision = fixture.tab.model().revision();
            Position cursor = fixture.tab.state().getCursor();

            Decision decision = routeContent(fixture, PHYSICAL_KEY, command);

            assertEquals(Decision.BLOCK, decision, command.toString());
            assertTrue(decision.consumed());
            assertEquals(original, fixture.tab.model().snapshotLines(), command.toString());
            assertEquals(revision, fixture.tab.model().revision(), command.toString());
            assertEquals(cursor, fixture.tab.state().getCursor(), command.toString());
            assertEquals("clipboard seed", fixture.clipboard.text(), command.toString());
            assertFalse(fixture.topMenu.isOpen(), command.toString());
        }
    }

    @Test
    void typedMnemonicsAndCompleteOrTruncatedPasteAreMutationsBeforeAnyCommandFallback() {
        for (TuiInputEvent event : List.of(new TextInput("y"), new TextInput("n"), new TextInput(" "),
            new TextInput("👩‍💻e\u0301"), new PasteEvent("first\nsecond"), new PasteEvent("partial", true))) {
            Fixture fixture = fixture();
            List<String> original = fixture.tab.model().snapshotLines();
            long revision = fixture.tab.model().revision();

            assertEquals(Decision.BLOCK, routeContent(fixture, event, null), event.toString());
            assertEquals(event instanceof PasteEvent ? Action.PASTE : Action.MODIFY,
                DialogCommandPolicy.action(event, TuinkCommand.EDIT_COPY));
            assertEquals(original, fixture.tab.model().snapshotLines());
            assertEquals(revision, fixture.tab.model().revision());
        }
    }

    @Test
    void documentPointerSelectionAndScrollingAreReadOperations() {
        for (PointerEvent.Kind kind : List.of(PointerEvent.Kind.PRESS, PointerEvent.Kind.DRAG,
            PointerEvent.Kind.RELEASE, PointerEvent.Kind.SCROLL_UP, PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Kind.SCROLL_LEFT, PointerEvent.Kind.SCROLL_RIGHT)) {
            PointerEvent event = new PointerEvent(3, 8, kind, PointerEvent.Button.LEFT, Set.of());
            assertEquals(Action.READ, DialogCommandPolicy.action(event, null));
            assertEquals(Decision.ALLOW, DialogInputPolicy.decide(DialogMode.BLOCKING, Target.CONTENT,
                DialogCommandPolicy.action(event, null)), kind.toString());
        }
    }

    @Test
    void contentOwnershipOverridesDialogControlRolesAndButtonClipboardStaysQuiet() {
        for (ModalInputPolicy.Kind kind : ModalInputPolicy.Kind.values()) {
            for (OpenFileInputRouter.Focus focus : OpenFileInputRouter.Focus.values()) {
                assertEquals(Target.CONTENT, DialogCommandPolicy.target(kind, focus, null, true));
            }
        }
        assertEquals(Target.TEXT_INPUT, DialogCommandPolicy.target(ModalInputPolicy.Kind.SAVE_AS, null, SaveAsBarView.Target.FIELD, false));
        assertEquals(Target.OTHER_CONTROL,
            DialogCommandPolicy.target(ModalInputPolicy.Kind.OPEN_FILE, OpenFileInputRouter.Focus.LIST, null, false));
        for (Target target : List.of(DialogCommandPolicy.target(ModalInputPolicy.Kind.CONFIRM, null, null, false),
            DialogCommandPolicy.target(ModalInputPolicy.Kind.HELP, null, null, false),
            DialogCommandPolicy.target(ModalInputPolicy.Kind.FORMAT, null, null, false),
            DialogCommandPolicy.target(ModalInputPolicy.Kind.SAVE_AS, null, SaveAsBarView.Target.SAVE, false),
            DialogCommandPolicy.target(ModalInputPolicy.Kind.SAVE_AS, null, SaveAsBarView.Target.CANCEL, false),
            DialogCommandPolicy.target(ModalInputPolicy.Kind.OPEN_FILE, OpenFileInputRouter.Focus.OK, null, false),
            DialogCommandPolicy.target(ModalInputPolicy.Kind.OPEN_FILE, OpenFileInputRouter.Focus.CANCEL, null, false),
            Target.BUTTON /* Search option/checkbox controls use the same button role. */)) {
            assertEquals(Target.BUTTON, target);
            for (DialogMode mode : DialogMode.values()) {
                for (TuinkCommand command : List.of(TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_CUT, TuinkCommand.EDIT_PASTE)) {
                    Decision decision = DialogInputPolicy.decide(mode, target, DialogCommandPolicy.action(PHYSICAL_KEY, command));
                    assertEquals(Decision.IGNORE, decision);
                    assertTrue(decision.consumed());
                }
            }
        }
    }

    @Test
    void currentTuinkControlsConsumeUnsupportedHistoryWhileToolkitAllowsLocalHistory() {
        for (DialogMode mode : DialogMode.values()) {
            for (Target target : List.of(Target.TEXT_INPUT, Target.BUTTON, Target.OTHER_CONTROL)) {
                for (TuinkCommand command : List.of(TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO)) {
                    Action action = DialogCommandPolicy.action(PHYSICAL_KEY, command);
                    assertEquals(Decision.ALLOW, DialogInputPolicy.decide(mode, target, action));
                    assertEquals(Decision.IGNORE, DialogCommandPolicy.decide(mode, target, action),
                        mode + " " + target + " " + command);
                }
            }
            for (Action action : Action.values()) {
                assertEquals(DialogInputPolicy.decide(mode, Target.CONTENT, action),
                    DialogCommandPolicy.decide(mode, Target.CONTENT, action), mode + " " + action);
            }
        }
    }

    @Test
    void unsupportedFieldHistoryPreservesTheFieldDocumentSelectionClipboardAndHistory() {
        for (DialogMode mode : DialogMode.values()) {
            Fixture fixture = fixture();
            fixture.tab.engine().beginSelectionAt(new Position(0, 0));
            fixture.tab.engine().extendSelectionTo(new Position(0, "alpha".length()));
            var modelBefore = fixture.tab.model().snapshotLines();
            long revisionBefore = fixture.tab.model().revision();
            Position cursorBefore = fixture.tab.state().getCursor();
            Position anchorBefore = fixture.tab.state().getSelectionAnchor();
            TextField field = new TextField();
            field.set("local 👩‍💻");
            field.selectAll();
            for (TuinkCommand command : List.of(TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO)) {
                Decision decision = DialogCommandPolicy.decide(mode, Target.TEXT_INPUT,
                    DialogCommandPolicy.action(PHYSICAL_KEY, command));
                if (!decision.consumed()) {
                    InputActionDispatcher.dispatchDocument(command, fixture.ui,
                        TuiSessionTestSupport.context(fixture.tab.engine(), fixture.tab.state(), 3, 12, fixture.navigation));
                }
                assertTrue(decision.consumed(), mode + " " + command);
                assertEquals(modelBefore, fixture.tab.model().snapshotLines());
                assertEquals(revisionBefore, fixture.tab.model().revision());
                assertEquals(cursorBefore, fixture.tab.state().getCursor());
                assertEquals(anchorBefore, fixture.tab.state().getSelectionAnchor());
                assertEquals("local 👩‍💻", field.text());
                assertEquals("local 👩‍💻", field.selectedText());
                assertEquals("clipboard seed", fixture.clipboard.text());
            }
            assertTrue(fixture.tab.engine().undo());
            assertEquals(List.of(""), fixture.tab.model().snapshotLines());
            assertTrue(fixture.tab.engine().redo());
            assertEquals(modelBefore, fixture.tab.model().snapshotLines());
        }
    }

    @Test
    void escapeCancelsEveryActiveDialogButCtrlQOnlyCancelsSaveAndConfirmationFlows() {
        for (ModalInputPolicy.Kind kind : List.of(ModalInputPolicy.Kind.SAVE_AS, ModalInputPolicy.Kind.CONFIRM,
            ModalInputPolicy.Kind.HELP, ModalInputPolicy.Kind.OPEN_FILE)) {
            assertTrue(DialogCommandPolicy.cancels(kind, TuinkCommand.UI_ESCAPE), kind.toString());
            assertEquals(kind == ModalInputPolicy.Kind.SAVE_AS || kind == ModalInputPolicy.Kind.CONFIRM,
                DialogCommandPolicy.cancels(kind, TuinkCommand.APP_QUIT), kind.toString());
            for (TuinkCommand command : List.of(TuinkCommand.EDIT_INSERT_NEWLINE, TuinkCommand.EDIT_COPY,
                TuinkCommand.EDIT_PASTE, TuinkCommand.DOCUMENT_SAVE)) {
                assertFalse(DialogCommandPolicy.cancels(kind, command), kind + " " + command);
            }
        }
        assertFalse(DialogCommandPolicy.cancels(ModalInputPolicy.Kind.NONE, TuinkCommand.APP_QUIT));
    }

    private static Fixture fixture() {
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("clipboard seed");
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, clipboard);
        tab.engine().insertInput("alpha 👩‍💻 e\u0301\nsecond 中🦀\nthird line");
        tab.state().setCursor(new Position(1, 3));
        return new Fixture(tab, clipboard, new UiSessionState(), new TopMenuController(), dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine()));
    }

    private static Decision routeContent(Fixture fixture, TuiInputEvent event, TuinkCommand command) {
        return routeContent(fixture, ModalInputPolicy.Kind.CONFIRM, event, command);
    }

    private static Decision routeContent(Fixture fixture, ModalInputPolicy.Kind kind,
        TuiInputEvent event, TuinkCommand command) {
        Decision decision = DialogCommandPolicy.decide(DialogCommandPolicy.mode(kind),
            DialogCommandPolicy.target(kind, null, null, true),
            DialogCommandPolicy.action(event, command));
        if (decision.consumed()) {
            return decision;
        }
        if (event instanceof TextInput text) {
            fixture.tab.engine().insertInput(text.text());
        } else if (event instanceof PasteEvent paste) {
            fixture.tab.engine().insertInput(paste.text());
        } else if (command != null) {
            assertTrue(InputActionDispatcher.dispatchDocument(command, fixture.ui,
                TuiSessionTestSupport.context(fixture.tab.engine(), fixture.tab.state(), 3, 12, fixture.navigation)).handled(), command.toString());
        }
        return decision;
    }

    private record Fixture(TabSession tab, EditorClipboard clipboard, UiSessionState ui, TopMenuController topMenu,
        EditorViewNavigation navigation) {
    }
}

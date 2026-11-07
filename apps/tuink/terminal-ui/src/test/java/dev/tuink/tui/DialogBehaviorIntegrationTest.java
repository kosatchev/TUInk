package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.DialogAttention;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.DialogInputPolicy;
import dev.tuiframe.widget.DialogInputPolicy.Decision;
import dev.tuiframe.widget.DialogMode;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Composes application adapters and the editor dispatcher with the reusable dialog contracts. */
class DialogBehaviorIntegrationTest {
    private static final Rect VIEWPORT = new Rect(0, 0, 120, 24);
    private static final KeyStroke TAB = KeyStroke.of(KeyStroke.Key.TAB);
    private static final KeyStroke BACK_TAB = KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT);

    @Test
    void blockedConfirmationInputNeverReplaysTheDecisionOrExposesAClippedQuestion() {
        for (boolean clipPrimary : List.of(false, true)) {
            for (TuiInputEvent event : forbiddenContentInput()) {
                ConfirmBarView view = ConfirmBarView.build("Unsaved document", 120);
                ConfirmBarView.Placement full = view.place(new Object(), 20, VIEWPORT);
                Rect discard = full.focusScope().target(ConfirmChoice.DISCARD).orElseThrow().placement().bounds();
                Rect clip = clipPrimary
                    ? new Rect(discard.x(), 0, VIEWPORT.width() - discard.x(), VIEWPORT.height()) : VIEWPORT;
                ConfirmBarView.Placement placed = view.place(new Object(), 20, clip);
                FocusManager<ConfirmChoice> focus = new FocusManager<>();
                placed.activate(focus);
                Gate<ConfirmChoice, Button.Action> gate = new Gate<>(DialogMode.BLOCKING,
                    ModalInputPolicy.Kind.CONFIRM, focus, placed.focusScope(),
                    input -> ConfirmBarInputRouter.route(focus, placed, ConfirmBarInputRouter.Policy.quit(), input));
                gate.focusContent();

                assertEquals(Decision.BLOCK, gate.route(event), event.toString());

                ConfirmChoice first = ConfirmChoice.SAVE;
                if (clipPrimary) {
                    assertTrue(focus.focused().isEmpty(), "a clipped question must not offer a decision");
                } else {
                    assertEquals(first, focus.focused().orElseThrow());
                }
                assertFalse(gate.contentFocused);
                assertTrue(gate.attention.highlighted());
                assertTrue(gate.controlOutputs.isEmpty(), "the rejected event cannot decide the newly focused action");
                gate.document.assertUnchanged();
                assertEquals("clipboard seed", gate.document.clipboard.text());

                // Only a new, deliberate Enter may activate the redirected choice.
                assertEquals(Decision.ALLOW, gate.route(KeyStroke.of(KeyStroke.Key.ENTER)));
                var result = assertInstanceOf(ConfirmBarInputRouter.Result.class, gate.controlOutputs.getFirst());
                assertEquals(clipPrimary ? null : first, result.decision());
                gate.document.assertUnchanged();
                gate.document.assertHistoryIntact();
            }
        }
    }

    @Test
    void blockedSaveAsInputCannotBecomeFilenameEditingOrSubmissionAfterFocusRedirect() {
        for (TuiInputEvent event : List.of(new TextInput("y"), new PasteEvent("unexpected\npath"),
            KeyStroke.of(KeyStroke.Key.ENTER))) {
            TextField field = new TextField();
            field.set("notes-😀.txt");
            field.moveLeft();
            String original = field.text();
            int caret = field.cursor();
            SaveAsBarView.Placement placed = SaveAsBarView.build(field, 120).place(new Object(), 20, VIEWPORT);
            FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
            placed.activate(focus);
            Gate<SaveAsBarView.Target, SaveAsBarView.Action> gate = new Gate<>(DialogMode.BLOCKING,
                ModalInputPolicy.Kind.SAVE_AS, focus, placed.focusScope(),
                input -> SaveAsInputRouter.route(focus, placed, input));
            gate.focusContent();

            assertEquals(Decision.BLOCK, gate.route(event));

            assertEquals(SaveAsBarView.Target.FIELD, focus.focused().orElseThrow());
            assertEquals(original, field.text());
            assertEquals(caret, field.cursor());
            assertTrue(gate.controlOutputs.isEmpty(), "the rejected event cannot be inserted or submitted");
            gate.document.assertUnchanged();
            assertEquals("clipboard seed", gate.document.clipboard.text());

            assertEquals(Decision.ALLOW, gate.route(new TextInput("z")));
            var result = assertInstanceOf(SaveAsInputRouter.Result.class, gate.controlOutputs.getFirst());
            assertEquals(SaveAsBarView.Action.VALUE_CHANGED, result.action());
            assertEquals(original.substring(0, caret) + "z" + original.substring(caret), field.text());
            gate.document.assertUnchanged();
            gate.document.assertHistoryIntact();
        }
    }

    @Test
    void exclusiveHelpRejectsStaleContentEnterWithoutReplayingTheCloseAction() {
        FocusManager<HelpOverlayInputRouter.Target> focus = new FocusManager<>();
        var frame = new Modal(40, 12, "Help").layout(new Rect(4, 3, 40, 12));
        var scope = helpScope(VIEWPORT);
        DialogFocusTraversal.focusFirst(focus, scope);
        Gate<HelpOverlayInputRouter.Target, Button.Action> gate = new Gate<>(DialogCommandPolicy.mode(ModalInputPolicy.Kind.HELP),
            ModalInputPolicy.Kind.HELP, focus, scope,
            event -> HelpOverlayInputRouter.route(focus, scope, frame, event));
        // Content cannot be reached by traversal; a stale owner must still be rejected defensively.
        gate.contentFocused = true;
        focus.clear(scope);

        assertEquals(Decision.BLOCK, gate.route(KeyStroke.of(KeyStroke.Key.ENTER)));

        assertEquals(HelpOverlayInputRouter.Target.CLOSE, focus.focused().orElseThrow());
        assertTrue(gate.controlOutputs.isEmpty());
        assertTrue(gate.attention.highlighted());
        gate.document.assertUnchanged();
        assertEquals(Decision.ALLOW, gate.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertTrue(assertInstanceOf(HelpOverlayInputRouter.Result.class, gate.controlOutputs.getFirst()).closeRequested());
        gate.document.assertHistoryIntact();
    }

    @Test
    void exclusiveHelpTraversalKeepsTheCloseControlAndCannotFallBackToContentWhenClipped() {
        FocusManager<HelpOverlayInputRouter.Target> focus = new FocusManager<>();
        var scope = helpScope(VIEWPORT);
        DialogFocusTraversal.focusFirst(focus, scope);
        boolean contentAvailable = DialogCommandPolicy.mode(ModalInputPolicy.Kind.HELP).permitsContentFocus();
        assertFalse(contentAvailable);

        for (KeyStroke stroke : List.of(TAB, TAB, BACK_TAB, BACK_TAB)) {
            assertTrue(DialogFocusTraversal.isTraversal(stroke));
            assertFalse(DialogFocusTraversal.advance(focus, scope, false, contentAvailable, stroke));
            assertEquals(HelpOverlayInputRouter.Target.CLOSE, focus.focused().orElseThrow());
            focus.context(scope, HelpOverlayInputRouter.Target.CLOSE);
            assertEquals(HelpOverlayInputRouter.Target.CLOSE, focus.focused().orElseThrow());
        }
        for (KeyStroke stroke : List.of(ctrl(KeyStroke.Key.TAB),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.ALT))) {
            assertFalse(DialogFocusTraversal.isTraversal(stroke));
            assertFalse(DialogFocusTraversal.advance(focus, scope, false, contentAvailable, stroke));
            assertEquals(HelpOverlayInputRouter.Target.CLOSE, focus.focused().orElseThrow());
        }

        var clipped = helpScope(new Rect(0, 0, 1, 1));
        for (KeyStroke stroke : List.of(TAB, BACK_TAB)) {
            assertFalse(DialogFocusTraversal.advance(focus, clipped, false, contentAvailable, stroke));
            assertTrue(focus.focused().isEmpty(), "hidden controls must not expose the document as a fallback");
        }
    }

    @Test
    void exclusiveOpenFileTraversalMatchesTheRealRouterAndIncludesLocationAndHidden() {
        var view = FileTreeView.fromSnapshot(new FileTreeModel.Snapshot(Path.of("dialog-fixture"), 0, List.of()),
            false, false);
        var placement = view.place(new Rect(4, 3, 50, 12), FileTreeView.State.initial(), VIEWPORT, true);
        List<OpenFileInputRouter.Focus> order = List.of(OpenFileInputRouter.Focus.LIST,
            OpenFileInputRouter.Focus.LOCATION, OpenFileInputRouter.Focus.HIDDEN,
            OpenFileInputRouter.Focus.OK, OpenFileInputRouter.Focus.CANCEL);
        boolean contentAvailable = DialogCommandPolicy.mode(ModalInputPolicy.Kind.OPEN_FILE).permitsContentFocus();
        assertFalse(contentAvailable);
        assertTrue(placement.enabled());

        for (KeyStroke stroke : List.of(TAB, BACK_TAB)) {
            OpenFileInputRouter.Focus current = OpenFileInputRouter.Focus.LIST;
            for (int step = 0; step < order.size() * 2; step++) {
                var next = DialogFocusTraversal.navigate(order, current, false, contentAvailable,
                    stroke.has(KeyStroke.Modifier.SHIFT));
                var routed = OpenFileDialogTestSupport.route(placement, current, stroke, 0, 0);
                assertFalse(next.contentFocused());
                assertEquals(routed.focus(), next.control());
                assertTrue(routed.consumed());
                assertNull(routed.intent(), "traversal must not open a file or dismiss the dialog");
                current = next.control();
            }
            assertEquals(OpenFileInputRouter.Focus.LIST, current);
        }

        var hidden = view.place(new Rect(4, 3, 50, 12), placement.state(), VIEWPORT, true, false);
        assertFalse(hidden.enabled());
        for (boolean backwards : List.of(false, true)) {
            var next = DialogFocusTraversal.navigate(List.<OpenFileInputRouter.Focus>of(),
                OpenFileInputRouter.Focus.LIST, false, contentAvailable, backwards);
            assertFalse(next.contentFocused(), "a hidden file tree must not expose document focus");
            assertNull(next.control());
        }
    }

    @ParameterizedTest
    @EnumSource(DialogMode.class)
    void buttonClipboardAndHistoryDoNotReachTheControlOrDocumentAndKeepTheirFocus(DialogMode mode) {
        ConfirmBarView.Placement placed = ConfirmBarView.build("Unsaved document", 120)
            .place(new Object(), 20, VIEWPORT);
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placed.activate(focus);
        Gate<ConfirmChoice, Button.Action> gate = new Gate<>(mode, ModalInputPolicy.Kind.CONFIRM,
            focus, placed.focusScope(),
            event -> ConfirmBarInputRouter.route(focus, placed, ConfirmBarInputRouter.Policy.quit(), event));

        for (KeyStroke.Key key : List.of(KeyStroke.Key.C, KeyStroke.Key.X, KeyStroke.Key.V,
            KeyStroke.Key.Z, KeyStroke.Key.Y)) {
            assertEquals(Decision.IGNORE, gate.route(ctrl(key)));
            assertEquals(ConfirmChoice.CANCEL, focus.focused().orElseThrow());
            assertTrue(gate.controlOutputs.isEmpty());
            assertFalse(gate.attention.highlighted());
            assertEquals("clipboard seed", gate.document.clipboard.text());
            gate.document.assertUnchanged();
        }
        gate.document.assertHistoryIntact();
    }

    @Test
    void blockingDialogContentStillUsesTheRealEditorSelectionNavigationAndClipboard() {
        ConfirmBarView.Placement placed = ConfirmBarView.build("Unsaved document", 120)
            .place(new Object(), 20, VIEWPORT);
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placed.activate(focus);
        Gate<ConfirmChoice, Button.Action> gate = new Gate<>(DialogMode.BLOCKING, ModalInputPolicy.Kind.CONFIRM,
            focus, placed.focusScope(),
            event -> ConfirmBarInputRouter.route(focus, placed, ConfirmBarInputRouter.Policy.quit(), event));
        gate.focusContent();
        assertFalse(gate.document.ui.readingModeSetting, "the dialog, rather than reading mode, protects content");

        assertEquals(Decision.ALLOW, gate.route(ctrl(KeyStroke.Key.C)));
        assertEquals("👩‍💻", gate.document.clipboard.text());
        gate.document.assertUnchanged();
        assertEquals(Decision.ALLOW, gate.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertEquals(Decision.ALLOW, gate.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(Decision.ALLOW, gate.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT)));
        assertEquals(new Position(0, 2), gate.document.tab.state().getCursor());
        assertEquals(new Position(0, 1), gate.document.tab.state().getSelectionAnchor());
        gate.document.assertContentUnchanged();
        assertTrue(gate.contentFocused);
        assertTrue(focus.focused().isEmpty());
        assertFalse(gate.attention.highlighted());
        assertTrue(gate.controlOutputs.isEmpty());
        gate.document.assertHistoryIntact();
    }

    @Test
    void nonBlockingSearchContentCanUndoAndRedoThroughTheRealEditorDispatcher() {
        TextField query = new TextField();
        TextField replacement = new TextField();
        SearchBarView.Placement placed = SearchBarView.build(query, replacement, 120, false,
            SearchOptions.MatchMode.SUBSTRING, false).place(new Object(), 20, VIEWPORT);
        FocusManager<SearchFocus> focus = new FocusManager<>();
        placed.activate(focus);
        Gate<SearchFocus, SearchBarView.Action> gate = new Gate<>(DialogMode.NON_BLOCKING, ModalInputPolicy.Kind.NONE,
            focus, placed.focusScope(), event -> SearchInputRouter.route(focus, placed, event));
        gate.focusContent();

        assertEquals(Decision.ALLOW, gate.route(ctrl(KeyStroke.Key.Z)));
        assertEquals(gate.document.beforeEdit, gate.document.tab.model().snapshotLines());
        assertEquals(Decision.ALLOW, gate.route(ctrl(KeyStroke.Key.Y)));
        assertEquals(gate.document.original, gate.document.tab.model().snapshotLines());
        assertTrue(gate.contentFocused);
        assertTrue(focus.focused().isEmpty());
        assertFalse(gate.attention.highlighted());
        assertTrue(gate.controlOutputs.isEmpty());
        gate.document.assertHistoryIntact();
    }

    @Test
    void saveAsButtonsIgnoreClipboardAndHistoryWithoutChangingTheFilenameOrDocument() {
        for (SaveAsBarView.Target target : List.of(SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL)) {
            TextField filename = new TextField();
            filename.set("draft-😀.txt");
            filename.moveLeft();
            int caret = filename.cursor();
            SaveAsBarView.Placement placed = SaveAsBarView.build(filename, 120).place(new Object(), 20, VIEWPORT);
            FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
            placed.activate(focus);
            focus.focus(placed.focusScope(), target);
            Gate<SaveAsBarView.Target, SaveAsBarView.Action> gate = new Gate<>(DialogMode.BLOCKING,
                ModalInputPolicy.Kind.SAVE_AS, focus, placed.focusScope(),
                event -> SaveAsInputRouter.route(focus, placed, event));

            for (TuiInputEvent event : List.of(ctrl(KeyStroke.Key.C), ctrl(KeyStroke.Key.X), ctrl(KeyStroke.Key.V),
                ctrl(KeyStroke.Key.Z), ctrl(KeyStroke.Key.Y), new PasteEvent("unexpected\nfilename"))) {
                assertEquals(Decision.IGNORE, gate.route(event), target + " " + event);
                assertEquals(target, focus.focused().orElseThrow());
                assertEquals("draft-😀.txt", filename.text());
                assertEquals(caret, filename.cursor());
                assertEquals("clipboard seed", gate.document.clipboard.text());
                assertFalse(gate.attention.highlighted());
                assertTrue(gate.controlOutputs.isEmpty());
                gate.document.assertUnchanged();
            }
            gate.document.assertHistoryIntact();
        }
    }

    @Test
    void realBlockingAndNonBlockingControlScopesIncludeOneDocumentStopInBothDirections() {
        TextField filename = new TextField();
        filename.set("draft-😀.txt");
        filename.moveLeft();
        int filenameCaret = filename.cursor();
        SaveAsBarView.Placement save = SaveAsBarView.build(filename, 120).place(new Object(), 20, VIEWPORT);
        FocusManager<SaveAsBarView.Target> saveFocus = new FocusManager<>();
        save.activate(saveFocus);
        assertEquals(List.of(SaveAsBarView.Target.FIELD, SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL),
            save.focusScope().tabOrder());
        assertRing(saveFocus, save.focusScope(), () -> save.activate(saveFocus));
        assertEquals("draft-😀.txt", filename.text());
        assertEquals(filenameCaret, filename.cursor());

        TextField query = new TextField();
        query.set("find 中");
        TextField replacement = new TextField();
        replacement.set("replace e\u0301");
        SearchBarView.Placement search = SearchBarView.build(query, replacement, 120, false,
            SearchOptions.MatchMode.SUBSTRING, false).place(new Object(), 20, VIEWPORT);
        FocusManager<SearchFocus> searchFocus = new FocusManager<>();
        search.activate(searchFocus);
        assertEquals(10, search.focusScope().tabOrder().size());
        assertRing(searchFocus, search.focusScope(), () -> search.activate(searchFocus));
        assertEquals("find 中", query.text());
        assertEquals("replace e\u0301", replacement.text());
    }

    private static <K, A> void assertRing(FocusManager<K> focus, FocusScope<K, A> scope, Runnable reconcile) {
        DialogFocusTraversal.focusFirst(focus, scope);
        List<K> order = scope.tabOrder();
        assertEquals(order.getFirst(), focus.focused().orElseThrow());
        for (int index = 1; index < order.size(); index++) {
            assertFalse(DialogFocusTraversal.advance(focus, scope, false, true, TAB));
            assertEquals(order.get(index), focus.focused().orElseThrow());
        }
        assertTrue(DialogFocusTraversal.advance(focus, scope, false, true, TAB));
        reconcile.run();
        assertTrue(focus.focused().isEmpty(), "frame reconciliation must keep document ownership");
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, true, BACK_TAB));
        for (int index = order.size() - 1; index >= 0; index--) {
            assertEquals(order.get(index), focus.focused().orElseThrow());
            assertEquals(index == 0, DialogFocusTraversal.advance(focus, scope, false, true, BACK_TAB));
        }
        assertTrue(focus.focused().isEmpty());
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, true, TAB));
        assertEquals(order.getFirst(), focus.focused().orElseThrow());
    }

    private static List<TuiInputEvent> forbiddenContentInput() {
        return List.of(KeyStroke.of(KeyStroke.Key.ENTER), new TextInput("Y"), new TextInput("n"),
            new TextInput("😀e\u0301"), new PasteEvent("unsafe\ninput"), new PasteEvent("partial", true),
            ctrl(KeyStroke.Key.X), ctrl(KeyStroke.Key.V), ctrl(KeyStroke.Key.Z), ctrl(KeyStroke.Key.Y));
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }

    private static FocusScope<HelpOverlayInputRouter.Target, Button.Action> helpScope(Rect clip) {
        return FocusScope.single(HelpOverlayInputRouter.Scope.HELP, FocusResetPolicy.FIRST_FOCUSABLE,
            new RoutedTarget<>(HelpOverlayInputRouter.Target.CLOSE,
                Button.plain("Close").layout(new Rect(18, 13, 6, 1)), FocusSpace.root(clip)));
    }

    /** Dispatches through domain adapters after applying reusable focus and input contracts. */
    private static final class Gate<K, A> {
        private final DialogMode mode;
        private final ModalInputPolicy.Kind kind;
        private final FocusManager<K> focus;
        private final FocusScope<K, A> scope;
        private final Function<TuiInputEvent, Object> controlRouter;
        private final CommandRouter commands = new CommandRouter();
        private final DialogAttention attention = new DialogAttention();
        private final Document document = new Document();
        private final List<Object> controlOutputs = new ArrayList<>();
        private boolean contentFocused;

        private Gate(DialogMode mode, ModalInputPolicy.Kind kind, FocusManager<K> focus,
            FocusScope<K, A> scope, Function<TuiInputEvent, Object> controlRouter) {
            this.mode = mode;
            this.kind = kind;
            this.focus = focus;
            this.scope = scope;
            this.controlRouter = controlRouter;
        }

        private void focusContent() {
            focus.focus(scope, scope.tabOrder().getLast());
            contentFocused = DialogFocusTraversal.advance(focus, scope, false, true, TAB);
            assertTrue(contentFocused);
            assertTrue(focus.focused().isEmpty());
        }

        private Decision route(TuiInputEvent event) {
            TuinkCommand command = event instanceof KeyStroke stroke
                ? commands.invocationFor(stroke).flatMap(invocation -> TuinkCommand.fromId(invocation.id())).orElse(null)
                : null;
            Decision decision = DialogCommandPolicy.decide(mode,
                DialogCommandPolicy.target(kind, null,
                    focus.focused().filter(SaveAsBarView.Target.class::isInstance)
                        .map(SaveAsBarView.Target.class::cast).orElse(null), contentFocused), DialogCommandPolicy.action(event, command));
            DialogInputPolicy.apply(decision, () -> {
                contentFocused = false;
                DialogFocusTraversal.focusFirst(focus, scope);
            }, () -> attention.request(scope.id(), 0));
            if (!decision.consumed()) {
                if (!contentFocused) {
                    controlOutputs.add(controlRouter.apply(event));
                } else if (event instanceof TextInput text) {
                    document.tab.engine().insertInput(text.text());
                } else if (event instanceof PasteEvent paste) {
                    document.tab.engine().insertInput(paste.text());
                } else if (command != null) {
                    assertTrue(InputActionDispatcher.dispatchDocument(command, document.ui,
                        TuiSessionTestSupport.context(document.tab.engine(), document.tab.state(), 6, 30, document.navigation)).handled());
                }
            }
            return decision;
        }
    }

    private static final class Document {
        private final EditorClipboard clipboard = new EditorClipboard();
        private final TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, clipboard);
        private final EditorViewNavigation navigation = dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine());
        private final UiSessionState ui = new UiSessionState();
        private final List<String> beforeEdit;
        private final List<String> original;
        private final long revision;
        private final Position selectionStart = new Position(0, "alpha ".length());
        private final Position selectionEnd = new Position(0, "alpha 👩‍💻".length());

        private Document() {
            tab.engine().insertInput("alpha 👩‍💻\nsecond 中🦀");
            beforeEdit = tab.model().snapshotLines();
            tab.engine().restoreSelection(new Position(1, tab.model().getLine(1).length()), null);
            tab.engine().insertInput("!");
            tab.engine().insertNewline();
            assertTrue(tab.engine().undo());
            tab.engine().restoreSelection(selectionEnd, selectionStart);
            clipboard.setText("clipboard seed");
            original = tab.model().snapshotLines();
            revision = tab.model().revision();
        }

        private void assertUnchanged() {
            assertContentUnchanged();
            assertEquals(selectionEnd, tab.state().getCursor());
            assertEquals(selectionStart, tab.state().getSelectionAnchor());
        }

        private void assertContentUnchanged() {
            assertEquals(original, tab.model().snapshotLines());
            assertEquals(revision, tab.model().revision());
            assertTrue(tab.state().isDirty());
        }

        private void assertHistoryIntact() {
            assertTrue(tab.engine().undo());
            assertEquals(beforeEdit, tab.model().snapshotLines());
            assertTrue(tab.engine().redo());
            assertEquals(original, tab.model().snapshotLines());
            assertTrue(tab.engine().redo(), "the redo entry must not be discarded by rejected input");
            List<String> withNewline = new ArrayList<>(original);
            withNewline.add("");
            assertEquals(withNewline, tab.model().snapshotLines());
            assertFalse(tab.engine().redo());
        }
    }
}

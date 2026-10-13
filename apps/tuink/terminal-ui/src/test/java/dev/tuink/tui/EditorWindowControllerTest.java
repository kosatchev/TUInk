package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.ViewportState;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.ui.EditorView;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses the production event entry point and production frame composition. */
class EditorWindowControllerTest {
    @TempDir
    Path directory;

    @Test
    void rejectedReadingCommandsPreserveTheManualViewportThroughTheRealEventRoute() throws Exception {
        try (Fixture fixture = new Fixture(directory, true)) {
            fixture.wheel(PointerEvent.Kind.SCROLL_DOWN, 4);
            fixture.wheel(PointerEvent.Kind.SCROLL_RIGHT, 2);
            ViewportState before = fixture.active().state().viewport();
            Position caret = fixture.active().state().getCursor();
            List<String> text = fixture.active().tabSession().model().snapshotLines();
            assertTrue(before.topLine() > 0);
            assertTrue(before.leftVisualColumn() > 0);

            for (TuiInputEvent event : List.of(ctrl(KeyStroke.Key.X), ctrl(KeyStroke.Key.Z), new TextInput("q"))) {
                fixture.event(event);
                assertEquals(before, fixture.active().state().viewport());
                assertEquals(caret, fixture.active().state().getCursor());
                assertEquals(text, fixture.active().tabSession().model().snapshotLines());
                assertFalse(fixture.active().state().isDirty());
                assertEquals("Reading mode: editing is disabled", fixture.notification.message);
            }

            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertEquals(0, fixture.active().state().getTopLine());
            assertEquals(new Position(0, 1), fixture.active().state().getCursor());
        }
    }

    @Test
    void openingSearchEndsDocumentCaptureBeforeLaterDragDelivery() throws Exception {
        try (Fixture fixture = new Fixture(directory, false)) {
            fixture.pointer(PointerEvent.Kind.PRESS, 3, 15);
            assertTrue(fixture.editorView.isMouseSelecting());

            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.event(new TextInput("needle"));
            assertTrue(fixture.ui.searchMode);
            assertFalse(fixture.editorView.isMouseSelecting());
            Position caret = fixture.active().state().getCursor();
            Position anchor = fixture.active().state().getSelectionAnchor();

            fixture.pointer(PointerEvent.Kind.DRAG, 10, 40);
            fixture.pointer(PointerEvent.Kind.RELEASE, 10, 40);
            fixture.event(new TextInput("X"));

            assertEquals("needleX", fixture.query.text());
            assertEquals(caret, fixture.active().state().getCursor());
            assertEquals(anchor, fixture.active().state().getSelectionAnchor());
            assertFalse(fixture.active().state().isDirty());

            fixture.pointer(PointerEvent.Kind.PRESS, 4, 15);
            fixture.pointer(PointerEvent.Kind.DRAG, 6, 30);
            fixture.pointer(PointerEvent.Kind.RELEASE, 6, 30);
            assertTrue(fixture.active().state().hasSelection());
            assertTrue(fixture.ui.searchEditorFocused);
            assertEquals("needleX", fixture.query.text());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void editMenuCutUsesTheFocusedSearchFieldInEditAndReadingModes(boolean reading) throws Exception {
        try (Fixture fixture = new Fixture(directory, reading)) {
            List<String> document = fixture.active().tabSession().model().snapshotLines();
            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.event(new TextInput("field value😀"));
            fixture.event(ctrl(KeyStroke.Key.A));
            assertEquals("field value😀", fixture.query.selectedText());

            fixture.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.ALT));
            assertTrue(fixture.menu.isOpen());
            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertFalse(fixture.menu.isOpen());
            assertEquals("", fixture.query.text());
            assertEquals("field value😀", fixture.host.clipboard.text());
            assertEquals(document, fixture.active().tabSession().model().snapshotLines());
            assertFalse(fixture.active().state().isDirty());
            assertEquals(SearchFocus.FIND, fixture.host.searchFocusManager.focused().orElseThrow());
        }
    }

    @Test
    void keyboardTabSwitchingRestoresEachIndependentViewportAndEndsCapture() throws Exception {
        try (Fixture fixture = new Fixture(directory, false)) {
            fixture.event(ctrl(KeyStroke.Key.N));
            assertEquals(2, fixture.tabs.size());
            fixture.active().tabSession().engine().open(fixture.document);
            fixture.frame();
            fixture.wheel(PointerEvent.Kind.SCROLL_DOWN, 7);
            fixture.wheel(PointerEvent.Kind.SCROLL_RIGHT, 2);
            TerminalEditorApp.EditorTab second = fixture.active();
            ViewportState secondViewport = second.state().viewport();

            fixture.event(ctrl(KeyStroke.Key.TAB));
            TerminalEditorApp.EditorTab first = fixture.active();
            assertEquals(0, first.state().getTopLine());
            fixture.wheel(PointerEvent.Kind.SCROLL_DOWN, 3);
            ViewportState firstViewport = first.state().viewport();
            assertTrue(firstViewport.topLine() > 0);
            assertTrue(secondViewport.topLine() > firstViewport.topLine());

            fixture.event(ctrl(KeyStroke.Key.TAB));
            assertEquals(second, fixture.active());
            assertEquals(secondViewport, second.state().viewport());
            fixture.event(ctrl(KeyStroke.Key.TAB));
            assertEquals(first, fixture.active());
            assertEquals(firstViewport, first.state().viewport());

            fixture.pointer(PointerEvent.Kind.PRESS, 4, 15);
            assertTrue(fixture.editorView.isMouseSelecting());
            fixture.event(ctrl(KeyStroke.Key.TAB));
            assertFalse(fixture.editorView.isMouseSelecting());
        }
    }

    @Test
    void resizeAndHelpTerminateFieldCaptureWithoutReplayingTheOldDrag() throws Exception {
        try (Fixture fixture = new Fixture(directory, false)) {
            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.event(new TextInput("0123456789".repeat(15)));
            fixture.event(KeyStroke.of(KeyStroke.Key.HOME));
            fixture.pointer(PointerEvent.Kind.PRESS, fixture.height - 3, 8);
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height - 3, 35);
            assertTrue(fixture.query.mouseSelecting());

            fixture.width = 100;
            fixture.event(new ResizeEvent(fixture.height, fixture.width));
            assertFalse(fixture.query.mouseSelecting());
            int caret = fixture.query.cursor();
            fixture.pointer(PointerEvent.Kind.DRAG, 0, 79);
            fixture.pointer(PointerEvent.Kind.RELEASE, 0, 79);
            assertEquals(caret, fixture.query.cursor());

            fixture.pointer(PointerEvent.Kind.PRESS, fixture.height - 3, 8);
            assertTrue(fixture.query.mouseSelecting());
            fixture.event(KeyStroke.of(KeyStroke.Key.F6));
            fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(fixture.query.mouseSelecting());
            fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(fixture.ui.searchMode);
            assertEquals(SearchFocus.FIND, fixture.host.searchFocusManager.focused().orElseThrow());
            fixture.event(ctrl(KeyStroke.Key.A));
            fixture.event(new TextInput("resumed"));
            assertEquals("resumed", fixture.query.text());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }

    private static final class Fixture implements AutoCloseable {
        final TerminalEditorApp host = new TerminalEditorApp();
        final List<TerminalEditorApp.EditorTab> tabs = new ArrayList<>();
        final TerminalEditorApp.SessionContext context;
        final TerminalEditorApp.NotificationState notification = host.new NotificationState();
        final TextField query = new TextField();
        final TextField replacement = new TextField();
        final TextField filename = new TextField();
        final EditorView editorView = new EditorView();
        final TopMenuController menu = new TopMenuController();
        final TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.idle());
        final OpenFileTreeController tree;
        final UiSessionState ui;
        final EditorWindowController controller;
        final Path document;
        int width = 80;
        int height = 24;

        Fixture(Path directory, boolean reading) throws IOException {
            document = directory.resolve("document.txt");
            Files.writeString(document, IntStream.range(0, 120)
                .mapToObj(index -> "ROW%04d|".formatted(index) + "abcdefgh ".repeat(30))
                .collect(java.util.stream.Collectors.joining("\n")));
            tabs.add(host.newEditorTab());
            tabs.getFirst().tabSession().engine().open(document);
            context = new TerminalEditorApp.SessionContext(tabs, 0);
            tree = new OpenFileTreeController(directory, true, false);
            AppSettings settings = AppSettings.defaults();
            ui = UiSessionState.from(false, settings.showTabBar(), settings.wrapLines(), settings.wrapWords(),
                reading, settings.confirmOnClose(), settings.mouseMode(), settings.highlightActiveGutter(),
                settings.smartHomeEnd(), settings.autoIndent(), settings.autoPairs(), settings.autoList(),
                settings.visibleWhitespace(), settings.syntaxHighlighting(), settings.rainbowBrackets(),
                settings.autoSelectionHighlight(), settings.gutterDiffMode(), settings.tabWidth(),
                settings.tabInsertMode(), false, false, false);
            RenderRasterizer renderer = new RenderRasterizer(1, host.theme);
            ScreenPresenter presenter = new ScreenPresenter(new PrintWriter(new StringWriter()), TerminalCapabilities.DEFAULT);
            Map<String, RecoverySnapshot> recovery = new HashMap<>();
            var recoveryManager = new TerminalEditorApp.RecoveryAutosaveManager(new TerminalEditorApp.RecoveryPersistence() {
                @Override
                public void save(String entryId, RecoverySnapshot snapshot) {
                    recovery.put(entryId, snapshot);
                }

                @Override
                public void delete(String entryId) {
                    recovery.remove(entryId);
                }
            });
            controller = new EditorWindowController(host, new EditorWindowController.Resources(
                new CommandRouter(), editorView, loop, UiModalState.none(), SearchController.SearchUiOptions.DEFAULT,
                notification, tree, recoveryManager, host.new RenderFrameController(renderer, editorView, true, presenter),
                renderer, filename, new SaveAsSuggestionStore(), presenter, query, replacement, context,
                new ArrayList<>(), tabs, new EditorWindowController.TerminalAccess(() -> width, () -> height, enabled -> {}),
                menu, ui));
            frame();
        }

        TerminalEditorApp.EditorTab active() {
            return tabs.get(context.activeTabIndex());
        }

        void event(TuiInputEvent event) {
            controller.handlePoll(new TuiEventLoop.Poll<>(event, event instanceof ResizeEvent, false));
            frame();
        }

        void pointer(PointerEvent.Kind kind, int row, int column) {
            event(new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of()));
        }

        void wheel(PointerEvent.Kind kind, int count) {
            for (int index = 0; index < count; index++) {
                event(new PointerEvent(10, 15, kind, PointerEvent.Button.NONE, Set.of()));
            }
        }

        void frame() {
            controller.prepareTurn();
            loop.invalidate();
            TuiEventLoop.FrameTicket ticket = loop.beginFrame();
            assertNotNull(ticket);
            boolean rendered = controller.renderFrame(ticket);
            loop.endFrame(ticket, rendered);
            controller.frameEnded(rendered);
        }

        @Override
        public void close() {
            tree.close();
        }
    }
}

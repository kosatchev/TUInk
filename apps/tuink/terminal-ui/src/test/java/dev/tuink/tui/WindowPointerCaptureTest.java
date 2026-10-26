package dev.tuink.tui;

import static dev.tuink.tui.file.FileTreeTestSupport.awaitLoaded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowPointerCaptureTest {
    private static final Rect CLIP = new Rect(0, 0, 80, 24);
    private static final String SURFACE = "editor-window";

    @TempDir
    Path root;

    @Test
    void fileTreeAcquiresThroughNormalPressAndCapturedDeliveryDoesNotBorrowAnEntryClock() throws Exception {
        for (int index = 0; index < 30; index++) {
            Files.createFile(root.resolve("note-" + index + ".txt"));
        }
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeView view = FileTreeView.fromSnapshot(awaitLoaded(model), false, false);
            Rect bounds = new Rect(2, 3, 30, 6);
            FileTreeView.Placement initial = view.place(bounds, FileTreeView.State.initial(), CLIP, true);
            OpenFileDialogView.Placement dialog = OpenFileDialogTestSupport.placement(initial);
            OpenFileDialogView.FocusState focus = new OpenFileDialogView.FocusState();
            WindowPointerCapture capture = new WindowPointerCapture();
            Rect track = initial.verticalScrollbarBounds().orElseThrow();
            OpenFileInputRouter.Result pressed = OpenFileInputRouter.route(dialog, focus,
                pointer(track.y(), track.x(), PointerEvent.Kind.PRESS), 10L, 1_000L, capture, SURFACE);
            assertTrue(capture.active());
            assertEquals(PointerCaptureRequest.ACQUIRE, pressed.pointerCaptureRequest());

            AtomicReference<FileTreeView.State> state = new AtomicReference<>(pressed.state());
            FileTreeView.Placement current = view.place(bounds, state.get(), CLIP, true);
            OpenFileDialogView.Placement capturedDialog = OpenFileDialogTestSupport.placement(current);
            WindowPointerCapture.Frame frame = WindowPointerCapture.frame(SURFACE)
                .addScope(capturedDialog.captureScope(), action -> state.set(
                    assertInstanceOf(FileTreeView.StateChanged.class, action).state()));
            assertTrue(capture.dispatch(frame, pointer(23, 79, PointerEvent.Kind.DRAG)));
            assertTrue(state.get().widgetState().firstVisibleIndex() > 0);
            assertEquals(pressed.state().selectedId(), state.get().selectedId());
            assertNull(state.get().lastPressedId());

            FileTreeView.Placement moved = view.place(bounds, state.get(), CLIP, true);
            OpenFileDialogView.Placement movedDialog = OpenFileDialogTestSupport.placement(moved);
            assertTrue(capture.beforeInput(WindowPointerCapture.frame(SURFACE)
                .addScope(movedDialog.captureScope(), action -> state.set(
                    assertInstanceOf(FileTreeView.StateChanged.class, action).state())),
                KeyStroke.of(KeyStroke.Key.ARROW_DOWN)));
            assertNull(state.get().widgetState().drag());
            assertFalse(capture.active());

            FileTreeView.Placement entry = view.place(bounds, state.get(), CLIP, true);
            PointerEvent entryPress = pointer(entry.leadingBounds().y(), entry.leadingBounds().x() + 3,
                PointerEvent.Kind.PRESS);
            OpenFileInputRouter.Result first = OpenFileInputRouter.route(OpenFileDialogTestSupport.placement(entry),
                focus, entryPress, 10_000L, 1_000L, capture, SURFACE);
            assertNull(first.intent());
            assertEquals(10_000L, first.state().lastPressedAtNanos());
            FileTreeView.Placement next = view.place(bounds, first.state(), CLIP, true);
            OpenFileInputRouter.Result second = OpenFileInputRouter.route(OpenFileDialogTestSupport.placement(next),
                focus, entryPress, 10_001L, 1_000L, capture, SURFACE);
            assertInstanceOf(OpenFileInputRouter.OpenFile.class, second.intent());
        }
    }

    @Test
    void heterogeneousScopesUseOneOwnerAndNewPressEndsThePreviousGesture() {
        TextField query = field("query text");
        TextField filename = field("draft.txt");
        SearchBarView.Placement search = SearchBarView.build(query, new TextField(), 80,
            false, SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, CLIP);
        SaveAsBarView.Placement save = SaveAsBarView.build(filename, 80).place("save", 22, CLIP);
        FocusManager<SearchFocus> searchFocus = new FocusManager<>();
        FocusManager<SaveAsBarView.Target> saveFocus = new FocusManager<>();
        search.activate(searchFocus);
        save.activate(saveFocus);
        WindowPointerCapture capture = new WindowPointerCapture();
        WindowPointerCapture.Frame frame = WindowPointerCapture.frame(SURFACE)
            .addScope(search.focusScope(), searchFocus, action -> { })
            .addScope(save.focusScope(), saveFocus, action -> { });

        Rect queryBounds = search.targetBounds(SearchFocus.FIND);
        PointerEvent queryPress = pointer(queryBounds.y(), queryBounds.x() + 1, PointerEvent.Kind.PRESS);
        capture.beforeInput(frame, queryPress);
        assertTrue(SearchInputRouter.route(searchFocus, search, queryPress, capture, SURFACE).consumed());
        assertTrue(query.mouseSelecting());
        assertTrue(capture.active());

        Rect filenameBounds = save.fieldBounds();
        PointerEvent filenamePress = pointer(filenameBounds.y(), filenameBounds.x() + 1, PointerEvent.Kind.PRESS);
        capture.beforeInput(frame, filenamePress);
        assertFalse(query.mouseSelecting());
        assertFalse(capture.active());
        assertTrue(SaveAsInputRouter.route(saveFocus, save, filenamePress, capture, SURFACE).consumed());
        assertTrue(filename.mouseSelecting());

        assertTrue(capture.dispatch(frame, pointer(0, 79, PointerEvent.Kind.DRAG)));
        assertEquals("raft.txt", filename.selectedText());
        assertEquals("", query.selectedText());
        assertTrue(capture.dispatch(frame, pointer(0, 79, PointerEvent.Kind.RELEASE)));
        assertFalse(filename.mouseSelecting());
        assertFalse(capture.active());
    }

    @Test
    void immutableOutputsUseTheCurrentFramesTypedAdopterAndCancelBeforeRemoval() {
        WindowPointerCapture capture = new WindowPointerCapture();
        AtomicReference<ListView.State> state = new AtomicReference<>(new ListView.State(0, 0));
        Rect bounds = new Rect(0, 0, 10, 4);
        ListView.Placement initial = new ListView(100, index -> "row" + index, state.get()).layout(bounds);
        FocusScope<String, ListView.Action> firstScope = listScope(initial);
        Rect bar = initial.scrollbarBounds().orElseThrow();
        InteractionResult<ListView.Action> press = initial.handle(pointer(bar.y(), bar.x(), PointerEvent.Kind.PRESS),
            new FocusContext(false, true, FocusSpace.root(CLIP)));
        state.set(assertInstanceOf(ListView.StateChanged.class, press.output().orElseThrow()).state());
        capture.observe(SURFACE, firstScope, "viewport", press);

        AtomicInteger staleCalls = new AtomicInteger();
        ListView.Placement oldFramePlacement = new ListView(100, index -> "row" + index, state.get()).layout(bounds);
        WindowPointerCapture.Frame oldFrame = WindowPointerCapture.frame(SURFACE)
            .addScope(listScope(oldFramePlacement), action -> staleCalls.incrementAndGet());
        capture.beforeInput(oldFrame, pointer(bar.y(), bar.x(), PointerEvent.Kind.MOVE));
        ListView.Placement current = new ListView(100, index -> "row" + index, state.get()).layout(bounds);
        AtomicInteger currentCalls = new AtomicInteger();
        WindowPointerCapture.Frame currentFrame = WindowPointerCapture.frame(SURFACE).addScope(listScope(current),
            action -> {
                state.set(assertInstanceOf(ListView.StateChanged.class, action).state());
                currentCalls.incrementAndGet();
            });
        assertTrue(capture.dispatch(currentFrame, pointer(20, 70, PointerEvent.Kind.DRAG)));
        assertTrue(state.get().firstVisibleIndex() > 0);
        assertEquals(0, staleCalls.get());
        assertEquals(1, currentCalls.get());

        ListView.Placement moved = new ListView(100, index -> "row" + index, state.get()).layout(bounds);
        WindowPointerCapture.Frame movedFrame = WindowPointerCapture.frame(SURFACE).addScope(listScope(moved),
            action -> state.set(assertInstanceOf(ListView.StateChanged.class, action).state()));
        capture.cancel(movedFrame, PointerCaptureCancellation.HIDDEN);
        assertNull(state.get().drag());
        assertFalse(capture.active());
        assertFalse(capture.dispatch(WindowPointerCapture.frame(SURFACE), pointer(20, 70, PointerEvent.Kind.DRAG)));
    }

    @Test
    void fieldAdaptersPreserveCaptureEffectsWithoutProducingSpuriousApplicationActions() {
        TextField query = field("abc");
        SearchBarView.Placement search = SearchBarView.build(query, new TextField(), 80,
            false, SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, CLIP);
        RoutedTarget<SearchFocus, SearchBarView.Action> target = search.focusScope().target(SearchFocus.FIND)
            .orElseThrow();
        Rect bounds = target.placement().bounds();
        InteractionResult<SearchBarView.Action> press = target.placement().handle(
            pointer(bounds.y(), bounds.x(), PointerEvent.Kind.PRESS), new FocusContext(true, true, target.space()));
        assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());
        assertTrue(press.output().isEmpty());

        FocusManager<SearchFocus> focus = new FocusManager<>();
        search.activate(focus);
        WindowPointerCapture capture = new WindowPointerCapture();
        capture.observe(SURFACE, search.focusScope(), SearchFocus.FIND, press);
        AtomicInteger actions = new AtomicInteger();
        capture.beforeInput(WindowPointerCapture.frame(SURFACE).addScope(search.focusScope(), focus,
            action -> actions.incrementAndGet()), KeyStroke.of(KeyStroke.Key.TAB));
        assertFalse(query.mouseSelecting());
        assertFalse(capture.active());
        assertEquals(0, actions.get());
    }

    private static TextField field(String text) {
        TextField field = new TextField();
        field.set(text);
        field.moveHome();
        return field;
    }

    private static FocusScope<String, ListView.Action> listScope(ListView.Placement placement) {
        return FocusScope.single("help", FocusResetPolicy.CLEARED,
            new RoutedTarget<>("viewport", placement, FocusSpace.root(CLIP), false, true));
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of());
    }
}

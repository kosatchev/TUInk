package dev.tuink.tui;

import static dev.tuink.tui.file.FileTreeTestSupport.awaitLoaded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenFileInputRouterTest {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final Rect BOUNDS = new Rect(2, 1, 24, 5);
    private static final Rect CLIP = new Rect(0, 0, 32, 8);

    @TempDir
    Path root;

    @Test
    void exactTreeKeysProduceFileNavigationIntents() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement directory = placement(view, snapshot, entry(snapshot, folder), true);

            OpenFileInputRouter.Result enterDirectory = route(
                directory,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.ENTER)
            );
            assertEquals(folder.toAbsolutePath().normalize(), assertInstanceOf(
                OpenFileInputRouter.ChangeRoot.class,
                enterDirectory.intent()
            ).root());

            OpenFileInputRouter.Result backspace = route(
                directory,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.BACKSPACE)
            );
            assertInstanceOf(OpenFileInputRouter.MoveToParentRoot.class, backspace.intent());

            FileTreeView.Placement filePlacement = placement(view, snapshot, entry(snapshot, file), true);
            OpenFileInputRouter.Result enterFile = route(
                filePlacement,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.ENTER)
            );
            assertEquals(file.toAbsolutePath().normalize(), assertInstanceOf(
                OpenFileInputRouter.OpenFile.class,
                enterFile.intent()
            ).file());

            OpenFileInputRouter.Result down = route(
                directory,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.ARROW_DOWN)
            );
            assertTrue(down.consumed());
            assertNull(down.intent());
            assertEquals(entry(snapshot, file).id(), down.state().selectedId());

            FileTreeView.Placement parentPlacement = placement(
                view,
                snapshot,
                entry(snapshot, root.getParent()),
                true
            );
            OpenFileInputRouter.ChangeRoot parent = assertInstanceOf(
                OpenFileInputRouter.ChangeRoot.class,
                route(
                    parentPlacement,
                    OpenFileInputRouter.Focus.LIST,
                    KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)
                ).intent()
            );
            assertEquals(root.getParent().toAbsolutePath().normalize(), parent.root());
        }
    }

    @Test
    void rawTextPasteModifiersAndFocusTraversalAreCaptured() throws IOException {
        Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement placement = view.place(
                BOUNDS,
                FileTreeView.State.initial(),
                CLIP,
                true
            );

            assertCapturedWithoutIntent(route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                new TextInput("x")
            ));
            assertCapturedWithoutIntent(route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                new PasteEvent("payload")
            ));

            OpenFileInputRouter.Result tab = route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.TAB)
            );
            assertEquals(OpenFileInputRouter.Focus.OK, tab.focus());
            OpenFileInputRouter.Result reverse = route(
                placement,
                OpenFileInputRouter.Focus.OK,
                KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
            );
            assertEquals(OpenFileInputRouter.Focus.LIST, reverse.focus());

            PointerEvent modified = new PointerEvent(
                placement.bounds().y(),
                placement.bounds().x(),
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT,
                Set.of(KeyStroke.Modifier.ALT)
            );
            assertCapturedWithoutIntent(route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                modified
            ));

            for (OpenFileInputRouter.Focus focus : OpenFileInputRouter.Focus.values()) {
                OpenFileInputRouter.Result hidden = route(
                    placement,
                    focus,
                    KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL)
                );
                assertInstanceOf(OpenFileInputRouter.ToggleHidden.class, hidden.intent());
                assertEquals(OpenFileInputRouter.Focus.LIST, hidden.focus());
            }
        }
    }

    @Test
    void okAndCancelApplyButtonPolicyWithoutActivatingDirectoryOrError() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement directory = placement(view, snapshot, entry(snapshot, folder), true);
            OpenFileInputRouter.SelectionRejected rejected = assertInstanceOf(
                OpenFileInputRouter.SelectionRejected.class,
                route(
                    directory,
                    OpenFileInputRouter.Focus.OK,
                    KeyStroke.of(KeyStroke.Key.ENTER)
                ).intent()
            );
            assertEquals(OpenFileInputRouter.Rejection.DIRECTORY, rejected.reason());

            FileTreeView.Placement filePlacement = placement(view, snapshot, entry(snapshot, file), true);
            assertInstanceOf(
                OpenFileInputRouter.OpenFile.class,
                route(
                    filePlacement,
                    OpenFileInputRouter.Focus.OK,
                    KeyStroke.of(KeyStroke.Key.ENTER)
                ).intent()
            );
            assertInstanceOf(
                OpenFileInputRouter.CloseDialog.class,
                route(
                    filePlacement,
                    OpenFileInputRouter.Focus.CANCEL,
                    KeyStroke.of(KeyStroke.Key.ENTER)
                ).intent()
            );
        }

        Path plainFile = Files.createFile(root.resolve("plain-file"));
        try (FileTreeModel errorModel = new FileTreeModel(plainFile, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(errorModel);
            FileTreeModel.Entry error = snapshot.entries().stream()
                .filter(entry -> entry.kind() == FileTreeModel.Kind.ERROR)
                .findFirst()
                .orElseThrow();
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement placement = placement(view, snapshot, error, true);
            OpenFileInputRouter.SelectionRejected rejected = assertInstanceOf(
                OpenFileInputRouter.SelectionRejected.class,
                route(
                    placement,
                    OpenFileInputRouter.Focus.OK,
                    KeyStroke.of(KeyStroke.Key.ENTER)
                ).intent()
            );
            assertEquals(OpenFileInputRouter.Rejection.ERROR, rejected.reason());
        }
    }

    @Test
    void placedButtonsShareEnterAndBothDecodedSpaceForms() throws IOException {
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement tree = placement(view, snapshot, entry(snapshot, file), true);
            OpenFileDialogView.Placement dialog = OpenFileDialogTestSupport.placement(tree);
            for (var event : java.util.List.of(
                KeyStroke.of(KeyStroke.Key.ENTER), KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" ")
            )) {
                OpenFileDialogView.FocusState focus = new OpenFileDialogView.FocusState();
                focus.focus(OpenFileInputRouter.Focus.OK);
                var confirmed = OpenFileInputRouter.route(dialog, focus, event, 0L, DOUBLE_CLICK_NANOS);
                assertEquals(file, assertInstanceOf(OpenFileInputRouter.OpenFile.class, confirmed.intent()).file());
                assertEquals(OpenFileInputRouter.Focus.OK, focus.focus());

                focus.focus(OpenFileInputRouter.Focus.CANCEL);
                var cancelled = OpenFileInputRouter.route(dialog, focus, event, 0L, DOUBLE_CLICK_NANOS);
                assertInstanceOf(OpenFileInputRouter.CloseDialog.class, cancelled.intent());
                assertEquals(OpenFileInputRouter.Focus.CANCEL, focus.focus());
            }
        }
    }

    @Test
    void placedButtonPointersUseCurrentFrameBoundsAndSingleFocusOwner() throws IOException {
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement tree = placement(view, snapshot, entry(snapshot, file), true);
            OpenFileDialogView.Placement dialog = OpenFileDialogTestSupport.placement(tree);
            OpenFileDialogView.FocusState focus = new OpenFileDialogView.FocusState();
            dialog.activate(focus);

            var ok = OpenFileInputRouter.route(dialog, focus,
                buttonPress(dialog, OpenFileDialogView.Control.OK), 0L, DOUBLE_CLICK_NANOS);
            assertInstanceOf(OpenFileInputRouter.OpenFile.class, ok.intent());
            assertEquals(OpenFileInputRouter.Focus.OK, focus.focus());
            assertEquals(focus.focus(), ok.focus());

            var cancelled = OpenFileInputRouter.route(dialog, focus,
                buttonPress(dialog, OpenFileDialogView.Control.CANCEL), 0L, DOUBLE_CLICK_NANOS);
            assertInstanceOf(OpenFileInputRouter.CloseDialog.class, cancelled.intent());
            assertEquals(OpenFileInputRouter.Focus.CANCEL, focus.focus());

            var hidden = OpenFileInputRouter.route(dialog, focus,
                buttonPress(dialog, OpenFileDialogView.Control.HIDDEN), 0L, DOUBLE_CLICK_NANOS);
            assertInstanceOf(OpenFileInputRouter.ToggleHidden.class, hidden.intent());
            assertEquals(OpenFileInputRouter.Focus.LIST, focus.focus());

            focus.focus(OpenFileInputRouter.Focus.OK);
            var closed = OpenFileInputRouter.route(dialog, focus,
                buttonPress(dialog, OpenFileDialogView.Control.CLOSE), 0L, DOUBLE_CLICK_NANOS);
            assertInstanceOf(OpenFileInputRouter.CloseDialog.class, closed.intent());
            assertEquals(OpenFileInputRouter.Focus.OK, focus.focus());
            assertEquals(java.util.List.of(OpenFileInputRouter.Focus.LIST,
                OpenFileInputRouter.Focus.OK, OpenFileInputRouter.Focus.CANCEL), dialog.focusOrder());
            var tab = route(view, closed.state(), focus, KeyStroke.of(KeyStroke.Key.TAB), 1L);
            assertCapturedWithoutIntent(tab);
            assertEquals(OpenFileInputRouter.Focus.CANCEL, focus.focus());
            var reverse = route(view, tab.state(), focus,
                KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT), 2L);
            assertCapturedWithoutIntent(reverse);
            assertEquals(OpenFileInputRouter.Focus.OK, focus.focus());
            dialog.focusFirst(focus);
            assertEquals(OpenFileInputRouter.Focus.LIST, focus.focus());
        }
    }

    private static PointerEvent buttonPress(OpenFileDialogView.Placement dialog, OpenFileDialogView.Control id) {
        Rect bounds = dialog.focusScope().target(id).orElseThrow().placement().bounds();
        return new PointerEvent(bounds.y(), bounds.x(), PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT, Set.of());
    }

    @Test
    void directoryDoubleClickRoutesToToggleByStableId() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeModel.Entry entry = entry(snapshot, folder);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement firstPlacement = placement(view, snapshot, entry, true);
            int row = firstPlacement.contentBounds().y()
                + firstPlacement.state().widgetState().selectedIndex();
            PointerEvent press = new PointerEvent(
                row,
                firstPlacement.leadingBounds().x() + 3,
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT,
                Set.of()
            );
            OpenFileInputRouter.Result first = OpenFileDialogTestSupport.route(
                firstPlacement,
                OpenFileInputRouter.Focus.LIST,
                press,
                -10L,
                DOUBLE_CLICK_NANOS
            );
            assertNull(first.intent());

            FileTreeView.Placement secondPlacement = view.place(
                BOUNDS,
                first.state(),
                CLIP,
                true
            );
            PointerEvent release = new PointerEvent(
                press.row(),
                press.column(),
                PointerEvent.Kind.RELEASE,
                PointerEvent.Button.LEFT,
                Set.of()
            );
            OpenFileInputRouter.Result released = OpenFileDialogTestSupport.route(
                secondPlacement,
                OpenFileInputRouter.Focus.LIST,
                release,
                -9L,
                DOUBLE_CLICK_NANOS
            );
            assertEquals(entry.id(), released.state().lastPressedId());

            secondPlacement = view.place(BOUNDS, released.state(), CLIP, true);
            OpenFileInputRouter.Result second = OpenFileDialogTestSupport.route(
                secondPlacement,
                OpenFileInputRouter.Focus.LIST,
                press,
                -8L,
                DOUBLE_CLICK_NANOS
            );
            OpenFileInputRouter.ToggleDirectory toggle = assertInstanceOf(
                OpenFileInputRouter.ToggleDirectory.class,
                second.intent()
            );
            assertEquals(entry.id(), toggle.id());
            assertTrue(toggle.expand());
            assertNull(second.state().lastPressedId());

            FileTreeModel.Entry fileEntry = entry(snapshot, file);
            FileTreeView.Placement filePlacement = placement(view, snapshot, fileEntry, true);
            int fileRow = filePlacement.contentBounds().y()
                + filePlacement.state().widgetState().selectedIndex();
            PointerEvent filePress = new PointerEvent(
                fileRow,
                filePlacement.leadingBounds().x() + 3,
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT,
                Set.of()
            );
            OpenFileInputRouter.Result fileFirst = OpenFileDialogTestSupport.route(
                filePlacement,
                OpenFileInputRouter.Focus.LIST,
                filePress,
                10L,
                DOUBLE_CLICK_NANOS
            );
            filePlacement = view.place(BOUNDS, fileFirst.state(), CLIP, true);
            OpenFileInputRouter.Result fileRelease = OpenFileDialogTestSupport.route(
                filePlacement,
                OpenFileInputRouter.Focus.LIST,
                new PointerEvent(
                    filePress.row(),
                    filePress.column(),
                    PointerEvent.Kind.RELEASE,
                    PointerEvent.Button.LEFT,
                    Set.of()
                ),
                11L,
                DOUBLE_CLICK_NANOS
            );
            filePlacement = view.place(BOUNDS, fileRelease.state(), CLIP, true);
            OpenFileInputRouter.OpenFile opened = assertInstanceOf(
                OpenFileInputRouter.OpenFile.class,
                OpenFileDialogTestSupport.route(
                    filePlacement,
                    OpenFileInputRouter.Focus.LIST,
                    filePress,
                    12L,
                    DOUBLE_CLICK_NANOS
                ).intent()
            );
            assertEquals(file.toAbsolutePath().normalize(), opened.file());
        }
    }

    @Test
    void unrelatedPointerPressCancelsPendingDoubleClick() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeModel.Entry entry = entry(snapshot, folder);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement placement = placement(view, snapshot, entry, true);
            int row = placement.contentBounds().y()
                + placement.state().widgetState().selectedIndex();
            PointerEvent press = new PointerEvent(
                row,
                placement.leadingBounds().x() + 3,
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT,
                Set.of()
            );
            OpenFileInputRouter.Result first = OpenFileDialogTestSupport.route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                press,
                100L,
                DOUBLE_CLICK_NANOS
            );
            placement = view.place(BOUNDS, first.state(), CLIP, true);
            OpenFileInputRouter.Result outside = OpenFileDialogTestSupport.route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                new PointerEvent(
                    0,
                    0,
                    PointerEvent.Kind.PRESS,
                    PointerEvent.Button.LEFT,
                    Set.of()
                ),
                101L,
                DOUBLE_CLICK_NANOS
            );
            assertFalse(outside.consumed());
            assertNull(outside.state().lastPressedId());

            placement = view.place(BOUNDS, outside.state(), CLIP, true);
            OpenFileInputRouter.Result next = OpenFileDialogTestSupport.route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                press,
                102L,
                DOUBLE_CLICK_NANOS
            );
            assertNull(next.intent());
        }
    }

    @Test
    void hiddenTinyPlacementCapturesEverythingExceptSafeClose() throws IOException {
        Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement hidden = view.place(
                BOUNDS,
                FileTreeView.State.initial(),
                CLIP,
                true,
                false
            );

            OpenFileInputRouter.Result tab = route(
                hidden,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.TAB)
            );
            assertCapturedWithoutIntent(tab);
            assertEquals(OpenFileInputRouter.Focus.LIST, tab.focus());
            assertCapturedWithoutIntent(route(
                hidden,
                OpenFileInputRouter.Focus.LIST,
                KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL)
            ));
            assertCapturedWithoutIntent(route(
                hidden,
                OpenFileInputRouter.Focus.LIST,
                new PointerEvent(1, 1, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of())
            ));
            assertInstanceOf(
                OpenFileInputRouter.CloseDialog.class,
                route(
                    hidden,
                    OpenFileInputRouter.Focus.LIST,
                    KeyStroke.of(KeyStroke.Key.ESCAPE)
                ).intent()
            );
        }
    }

    @Test
    void genericThumbCaptureSurvivesPlacementAndEndsOutsideTheDialog() throws IOException {
        for (int index = 0; index < 12; index++) {
            Files.createFile(root.resolve("note-" + index + ".txt"));
        }
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.Placement initial = view.place(
                BOUNDS, FileTreeView.State.initial(), CLIP, true
            );
            Rect track = initial.verticalScrollbarBounds().orElseThrow();
            int grabOffset = ScrollInteraction.thumbSize(track.height(), initial.itemCount()) - 1;
            PointerEvent press = new PointerEvent(
                track.y() + grabOffset, track.x(), PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT, Set.of()
            );
            OpenFileInputRouter.Result pressed = route(initial, OpenFileInputRouter.Focus.OK, press);
            assertEquals(OpenFileInputRouter.Focus.LIST, pressed.focus());
            assertEquals(Scrollbar.Axis.VERTICAL, pressed.state().widgetState().drag().axis());
            assertEquals(grabOffset, pressed.state().widgetState().drag().grabOffset());

            FileTreeView.Placement captured = view.place(BOUNDS, pressed.state(), CLIP, true);
            OpenFileInputRouter.Result stationary = route(
                captured,
                OpenFileInputRouter.Focus.LIST,
                new PointerEvent(
                    press.row(), press.column(), PointerEvent.Kind.DRAG,
                    PointerEvent.Button.LEFT, Set.of()
                )
            );
            assertEquals(
                pressed.state().widgetState().firstVisibleIndex(),
                stationary.state().widgetState().firstVisibleIndex()
            );

            OpenFileInputRouter.Result dragged = route(
                captured,
                OpenFileInputRouter.Focus.LIST,
                new PointerEvent(
                    CLIP.bottomExclusive() + 20, CLIP.rightExclusive() + 20,
                    PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT, Set.of()
                )
            );
            assertCapturedWithoutIntent(dragged);
            assertEquals(
                initial.itemCount() - track.height(),
                dragged.state().widgetState().firstVisibleIndex()
            );
            assertEquals(pressed.state().selectedId(), dragged.state().selectedId());

            FileTreeView.Placement moved = view.place(BOUNDS, dragged.state(), CLIP, true);
            OpenFileInputRouter.Result released = route(
                moved,
                OpenFileInputRouter.Focus.LIST,
                new PointerEvent(
                    CLIP.bottomExclusive() + 20, CLIP.rightExclusive() + 20,
                    PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT, Set.of()
                )
            );
            assertCapturedWithoutIntent(released);
            assertNull(released.state().widgetState().drag());

            OpenFileInputRouter.Result tab = route(
                captured, OpenFileInputRouter.Focus.LIST, KeyStroke.of(KeyStroke.Key.TAB)
            );
            assertEquals(OpenFileInputRouter.Focus.OK, tab.focus());
            assertNull(tab.state().widgetState().drag());

            FileTreeView.Placement hidden = view.place(BOUNDS, pressed.state(), CLIP, true, false);
            assertNull(hidden.state().widgetState().drag());
            assertEquals(
                pressed.state().widgetState().firstVisibleIndex(),
                hidden.state().widgetState().firstVisibleIndex()
            );
        }
    }

    @Test
    void forwardAndReverseTabCancelDragAndReleaseCannotStealButtonFocus() throws IOException {
        Path selected = null;
        for (int index = 0; index < 12; index++) {
            Path file = Files.createFile(root.resolve("note-" + index + ".txt"));
            if (index == 0) {
                selected = file;
            }
        }
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            for (boolean backwards : List.of(false, true)) {
                for (boolean releaseInside : List.of(false, true)) {
                    var initial = placement(view, snapshot, entry(snapshot, selected), true);
                    var focus = new OpenFileDialogView.FocusState();
                    Rect track = initial.verticalScrollbarBounds().orElseThrow();
                    int grabOffset = ScrollInteraction.thumbSize(track.height(), initial.itemCount()) - 1;
                    PointerEvent press = new PointerEvent(track.y() + grabOffset, track.x(),
                        PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
                    var pressed = route(view, initial.state(), focus, press, 10L);
                    assertCapturedWithoutIntent(pressed);
                    assertEquals(Scrollbar.Axis.VERTICAL, pressed.state().widgetState().drag().axis());

                    KeyStroke traversal = backwards ? KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
                        : KeyStroke.of(KeyStroke.Key.TAB);
                    var traversed = route(view, pressed.state(), focus, traversal, 20L);
                    OpenFileInputRouter.Focus expected = backwards ? OpenFileInputRouter.Focus.CANCEL
                        : OpenFileInputRouter.Focus.OK;
                    assertCapturedWithoutIntent(traversed);
                    assertEquals(expected, focus.focus());
                    assertNull(traversed.state().widgetState().drag());
                    assertNull(traversed.state().lastPressedId());
                    assertEquals(pressed.state().selectedId(), traversed.state().selectedId());
                    assertEquals(pressed.state().widgetState().firstVisibleIndex(),
                        traversed.state().widgetState().firstVisibleIndex());

                    PointerEvent release = new PointerEvent(
                        releaseInside ? press.row() : CLIP.bottomExclusive() + 20,
                        releaseInside ? press.column() : CLIP.rightExclusive() + 20,
                        PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT, Set.of());
                    PointerEvent drag = new PointerEvent(release.row(), release.column(),
                        PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT, Set.of());
                    var dragged = route(view, traversed.state(), focus, drag, 25L);
                    assertNull(dragged.intent());
                    assertNull(dragged.state().widgetState().drag());
                    assertEquals(expected, dragged.focus(), "a cancelled drag must not reclaim tree focus");
                    assertEquals(expected, focus.focus());
                    var released = route(view, dragged.state(), focus, release, 30L);
                    assertNull(released.intent());
                    assertNull(released.state().widgetState().drag());
                    assertEquals(expected, released.focus(), "release after Tab must not reclaim tree focus");
                    assertEquals(expected, focus.focus());

                    FileTreeView.State state = released.state();
                    if (!backwards) {
                        var cancelTab = route(view, state, focus, KeyStroke.of(KeyStroke.Key.TAB), 40L);
                        assertCapturedWithoutIntent(cancelTab);
                        assertEquals(OpenFileInputRouter.Focus.CANCEL, focus.focus());
                        state = cancelTab.state();
                    }
                    var cancelled = route(view, state, focus, KeyStroke.of(KeyStroke.Key.ENTER), 50L);
                    assertInstanceOf(OpenFileInputRouter.CloseDialog.class, cancelled.intent(),
                        "the deliberately focused Cancel must still cancel rather than open the selected file");
                }
            }
        }
    }

    @Test
    void wheelScrollsBothViewportsWithoutChangingTheKeyboardAction() throws IOException {
        Path selected = null;
        for (int index = 0; index < 12; index++) {
            Path file = Files.createFile(root.resolve("note-with-a-long-name-" + index + ".txt"));
            if (index == 0) {
                selected = file;
            }
        }
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            var initial = placement(view, snapshot, entry(snapshot, selected), true);
            for (var owner : OpenFileInputRouter.Focus.values()) {
                var focus = new OpenFileDialogView.FocusState();
                focus.focus(owner);
                FileTreeView.State state = initial.state();
                for (var kind : List.of(PointerEvent.Kind.SCROLL_UP, PointerEvent.Kind.SCROLL_LEFT,
                    PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Kind.SCROLL_RIGHT,
                    PointerEvent.Kind.SCROLL_UP, PointerEvent.Kind.SCROLL_LEFT)) {
                    PointerEvent wheel = new PointerEvent(initial.contentBounds().y(),
                        initial.leadingBounds().x() + 3, kind, PointerEvent.Button.NONE, Set.of());
                    var scrolled = route(view, state, focus, wheel, 10L);
                    assertCapturedWithoutIntent(scrolled);
                    assertEquals(owner, scrolled.focus(), "wheel must retain the keyboard action: " + kind);
                    assertEquals(owner, focus.focus());
                    assertEquals(initial.state().selectedId(), scrolled.state().selectedId());
                    if (kind == PointerEvent.Kind.SCROLL_DOWN) {
                        assertTrue(scrolled.state().widgetState().firstVisibleIndex()
                            > state.widgetState().firstVisibleIndex());
                    } else if (kind == PointerEvent.Kind.SCROLL_RIGHT) {
                        assertTrue(scrolled.state().widgetState().horizontalOffset()
                            > state.widgetState().horizontalOffset());
                    } else if (kind == PointerEvent.Kind.SCROLL_UP) {
                        assertEquals(0, scrolled.state().widgetState().firstVisibleIndex());
                    } else {
                        assertEquals(0, scrolled.state().widgetState().horizontalOffset());
                    }
                    state = scrolled.state();
                }
                var activated = route(view, state, focus, KeyStroke.of(KeyStroke.Key.ENTER), 20L);
                if (owner == OpenFileInputRouter.Focus.CANCEL) {
                    assertInstanceOf(OpenFileInputRouter.CloseDialog.class, activated.intent());
                } else {
                    assertEquals(selected, assertInstanceOf(OpenFileInputRouter.OpenFile.class,
                        activated.intent()).file());
                }
            }
        }
    }

    @Test
    void tabBetweenClicksBreaksTheDoubleClickButTheNextFreshPairStillOpensTheFile() throws IOException {
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            var initial = placement(view, snapshot, entry(snapshot, file), true);
            var focus = new OpenFileDialogView.FocusState();
            int row = initial.contentBounds().y() + initial.state().widgetState().selectedIndex();
            PointerEvent press = new PointerEvent(row, initial.leadingBounds().x() + 3,
                PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
            PointerEvent release = new PointerEvent(press.row(), press.column(),
                PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT, Set.of());
            long firstClick = 1_000_000_000L;
            var first = route(view, initial.state(), focus, press, firstClick);
            assertCapturedWithoutIntent(first);
            assertEquals(entry(snapshot, file).id(), first.state().lastPressedId());
            var released = route(view, first.state(), focus, release, firstClick + 10_000_000L);

            var tab = route(view, released.state(), focus, KeyStroke.of(KeyStroke.Key.TAB),
                firstClick + 20_000_000L);
            assertEquals(OpenFileInputRouter.Focus.OK, focus.focus());
            assertNull(tab.state().lastPressedId());
            assertEquals(0L, tab.state().lastPressedAtNanos());
            var back = route(view, tab.state(), focus, KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT),
                firstClick + 30_000_000L);
            assertEquals(OpenFileInputRouter.Focus.LIST, focus.focus());

            var afterNavigation = route(view, back.state(), focus, press, firstClick + 40_000_000L);
            assertCapturedWithoutIntent(afterNavigation);
            assertEquals(entry(snapshot, file).id(), afterNavigation.state().lastPressedId());
            var nextRelease = route(view, afterNavigation.state(), focus, release, firstClick + 50_000_000L);
            var freshDoubleClick = route(view, nextRelease.state(), focus, press, firstClick + 60_000_000L);
            assertEquals(file, assertInstanceOf(OpenFileInputRouter.OpenFile.class, freshDoubleClick.intent()).file());
            assertNull(freshDoubleClick.state().lastPressedId());
        }
    }

    private static FileTreeView.Placement placement(
        FileTreeView view,
        FileTreeModel.Snapshot snapshot,
        FileTreeModel.Entry selected,
        boolean focused
    ) {
        int index = snapshot.entries().indexOf(selected);
        FileTreeView.State state = new FileTreeView.State(
            selected.id(),
            new dev.tuiframe.widget.TreeView.State(index, 0, 0),
            snapshot.revision(),
            null,
            0L
        );
        return view.place(BOUNDS, state, CLIP, focused);
    }

    private static FileTreeModel.Entry entry(FileTreeModel.Snapshot snapshot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return snapshot.entries().stream()
            .filter(entry -> entry.path().equals(normalized))
            .findFirst()
            .orElseThrow();
    }

    private static OpenFileInputRouter.Result route(
        FileTreeView.Placement placement,
        OpenFileInputRouter.Focus focus,
        dev.tuiframe.input.TuiInputEvent event
    ) {
        return OpenFileDialogTestSupport.route(
            placement,
            focus,
            event,
            1_000L,
            DOUBLE_CLICK_NANOS
        );
    }

    /** Retains the dialog focus owner while reconciling a fresh placement after every event. */
    private static OpenFileInputRouter.Result route(FileTreeView view, FileTreeView.State state,
        OpenFileDialogView.FocusState focus, TuiInputEvent event, long nowNanos) {
        FileTreeView.Placement tree = view.place(BOUNDS, state, CLIP,
            focus.focus() == OpenFileInputRouter.Focus.LIST);
        return OpenFileInputRouter.route(OpenFileDialogTestSupport.placement(tree), focus, event,
            nowNanos, DOUBLE_CLICK_NANOS);
    }

    private static void assertCapturedWithoutIntent(OpenFileInputRouter.Result result) {
        assertTrue(result.consumed());
        assertNull(result.intent());
    }
}

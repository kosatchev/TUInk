package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PasteEvent;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            FileTreeModel.Snapshot snapshot = model.snapshot();
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
            FileTreeModel.Snapshot snapshot = model.snapshot();
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
            FileTreeModel.Snapshot snapshot = model.snapshot();
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
            FileTreeModel.Snapshot snapshot = errorModel.snapshot();
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
    void directoryDoubleClickRoutesToToggleByStableId() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path file = Files.createFile(root.resolve("note.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
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
            OpenFileInputRouter.Result first = OpenFileInputRouter.route(
                firstPlacement,
                OpenFileInputRouter.Focus.LIST,
                null,
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
            OpenFileInputRouter.Result released = OpenFileInputRouter.route(
                secondPlacement,
                OpenFileInputRouter.Focus.LIST,
                null,
                release,
                -9L,
                DOUBLE_CLICK_NANOS
            );
            assertEquals(entry.id(), released.state().lastPressedId());

            secondPlacement = view.place(BOUNDS, released.state(), CLIP, true);
            OpenFileInputRouter.Result second = OpenFileInputRouter.route(
                secondPlacement,
                OpenFileInputRouter.Focus.LIST,
                null,
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
            OpenFileInputRouter.Result fileFirst = OpenFileInputRouter.route(
                filePlacement,
                OpenFileInputRouter.Focus.LIST,
                null,
                filePress,
                10L,
                DOUBLE_CLICK_NANOS
            );
            filePlacement = view.place(BOUNDS, fileFirst.state(), CLIP, true);
            OpenFileInputRouter.Result fileRelease = OpenFileInputRouter.route(
                filePlacement,
                OpenFileInputRouter.Focus.LIST,
                null,
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
                OpenFileInputRouter.route(
                    filePlacement,
                    OpenFileInputRouter.Focus.LIST,
                    null,
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
            FileTreeModel.Snapshot snapshot = model.snapshot();
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
            OpenFileInputRouter.Result first = OpenFileInputRouter.route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                null,
                press,
                100L,
                DOUBLE_CLICK_NANOS
            );
            placement = view.place(BOUNDS, first.state(), CLIP, true);
            OpenFileInputRouter.Result outside = OpenFileInputRouter.route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                null,
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
            OpenFileInputRouter.Result next = OpenFileInputRouter.route(
                placement,
                OpenFileInputRouter.Focus.LIST,
                null,
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
            FileTreeModel.Snapshot snapshot = model.snapshot();
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

    private static FileTreeView.Placement placement(
        FileTreeView view,
        FileTreeModel.Snapshot snapshot,
        FileTreeModel.Entry selected,
        boolean focused
    ) {
        int index = snapshot.entries().indexOf(selected);
        FileTreeView.State state = new FileTreeView.State(
            selected.id(),
            new dev.tuink.terminal.widget.TreeView.State(index, 0, 0),
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
        dev.tuink.terminal.input.TuiInputEvent event
    ) {
        return OpenFileInputRouter.route(
            placement,
            focus,
            null,
            event,
            1_000L,
            DOUBLE_CLICK_NANOS
        );
    }

    private static void assertCapturedWithoutIntent(OpenFileInputRouter.Result result) {
        assertTrue(result.consumed());
        assertNull(result.intent());
    }
}

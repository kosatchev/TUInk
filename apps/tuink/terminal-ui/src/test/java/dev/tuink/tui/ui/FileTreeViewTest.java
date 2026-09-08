package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.scroll.ScrollInteraction;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.terminal.widget.InteractionResult;
import dev.tuink.terminal.widget.Theme;
import dev.tuink.tui.file.FileTreeModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeViewTest {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;

    @TempDir
    Path root;

    @Test
    void metadataColumnsStayPinnedAndAdaptWithoutEnteringTheGenericModel() throws IOException {
        Files.createDirectory(root.resolve("folder"));
        Path unicode = Files.createFile(root.resolve("very-long-中e\u0301-name-0123456789.txt"));

        try (FileTreeModel model = new FileTreeModel(root, true, true)) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
            FileTreeModel.Entry selected = entry(snapshot, unicode);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, true, true);
            FileTreeView.State state = state(snapshot, selected, 0, 0);
            Rect bounds = new Rect(2, 1, 50, 4);
            Rect clip = new Rect(0, 0, 60, 8);
            FileTreeView.Placement initial = view.place(bounds, state, clip, true);

            assertEquals(11, initial.sizeColumnWidth());
            assertEquals(14, initial.dateColumnWidth());
            assertTrue(initial.trailingBounds().isPresent());
            assertTrue(initial.horizontalScrollbarBounds().isPresent());

            CellBuffer first = new CellBuffer(8, 60);
            initial.paint(new Canvas(first), Theme.fixed(CellStyle.DEFAULT));
            String initialDetail = cells(
                first,
                selectedRow(initial),
                initial.trailingBounds().orElseThrow()
            );

            var widgetState = initial.state().widgetState();
            FileTreeView.State scrolledState = new FileTreeView.State(
                selected.id(),
                new dev.tuink.terminal.widget.TreeView.State(
                    widgetState.selectedIndex(),
                    widgetState.firstVisibleIndex(),
                    12
                ),
                snapshot.revision(),
                null,
                0L
            );
            FileTreeView.Placement scrolled = view.place(bounds, scrolledState, clip, true);
            CellBuffer second = new CellBuffer(8, 60);
            scrolled.paint(new Canvas(second), Theme.fixed(CellStyle.DEFAULT));

            assertNotEquals(0, scrolled.state().widgetState().horizontalOffset());
            assertEquals(
                initialDetail,
                cells(second, selectedRow(scrolled), scrolled.trailingBounds().orElseThrow())
            );

            FileTreeView.Placement medium = view.place(
                new Rect(2, 1, 30, 6),
                state,
                clip,
                true
            );
            assertEquals(7, medium.sizeColumnWidth());
            assertEquals(0, medium.dateColumnWidth());

            FileTreeView.Placement narrow = view.place(
                new Rect(2, 1, 19, 6),
                state,
                clip,
                true
            );
            assertEquals(0, narrow.sizeColumnWidth());
            assertEquals(0, narrow.dateColumnWidth());
        }
    }

    @Test
    void metadataProfileIsResolvedAgainstItsOwnScrollbarGeometry() throws IOException {
        for (int index = 0; index < 4; index++) {
            Files.createFile(root.resolve("123456" + index + ".txt"));
        }
        try (FileTreeModel model = new FileTreeModel(root, true, true)) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
            assertEquals(5, snapshot.entries().size());
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, true, true);
            FileTreeView.Placement placement = view.place(
                new Rect(1, 1, 36, 5),
                FileTreeView.State.initial(),
                new Rect(0, 0, 40, 8),
                true
            );

            // Rich metadata would create H, then V, reducing the leading area
            // below its minimum. The resolved sparse profile itself needs no bars.
            assertEquals(7, placement.sizeColumnWidth());
            assertEquals(0, placement.dateColumnWidth());
            assertTrue(placement.horizontalScrollbarBounds().isEmpty());
            assertTrue(placement.verticalScrollbarBounds().isEmpty());

            for (int width = 0; width <= 50; width++) {
                for (int height = 0; height <= 7; height++) {
                    FileTreeView.Placement candidate = view.place(
                        new Rect(0, 0, width, height),
                        FileTreeView.State.initial(),
                        new Rect(0, 0, 60, 10),
                        true
                    );
                    if (candidate.sizeColumnWidth() > 0 || candidate.dateColumnWidth() > 0) {
                        assertTrue(
                            candidate.leadingBounds().width() >= 12,
                            "metadata squeezed labels at " + width + "x" + height
                        );
                    }
                }
            }
        }
    }

    @Test
    void stableIdRestoresSelectionAcrossHiddenExpansionAndMetadataRevision() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Files.createFile(folder.resolve("child.txt"));
        Files.createFile(root.resolve(".hidden.txt"));
        Path target = Files.createFile(root.resolve("zz-target.txt"));

        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot first = model.snapshot();
            FileTreeModel.Entry targetEntry = entry(first, target);
            FileTreeView firstView = FileTreeView.fromSnapshot(first, false, false);
            FileTreeView.Placement firstPlacement = firstView.place(
                new Rect(1, 1, 24, 5),
                state(first, targetEntry, 0, 0),
                new Rect(0, 0, 40, 10),
                true
            );
            int originalIndex = firstPlacement.state().widgetState().selectedIndex();

            model.setShowHiddenFiles(true);
            FileTreeModel.Snapshot hidden = model.snapshot();
            FileTreeView.Placement hiddenPlacement = FileTreeView
                .fromSnapshot(hidden, false, false)
                .place(
                    new Rect(1, 1, 24, 5),
                    firstPlacement.state(),
                    new Rect(0, 0, 40, 10),
                    true
                );
            assertEquals(targetEntry.id(), hiddenPlacement.state().selectedId());
            assertNotEquals(originalIndex, hiddenPlacement.state().widgetState().selectedIndex());

            FileTreeModel.Entry folderEntry = entry(hidden, folder);
            assertTrue(model.toggleDirectory(folderEntry.id()));
            FileTreeModel.Snapshot expanded = model.snapshot();
            FileTreeView.Placement expandedPlacement = FileTreeView
                .fromSnapshot(expanded, false, false)
                .place(
                    new Rect(1, 1, 24, 5),
                    hiddenPlacement.state(),
                    new Rect(0, 0, 40, 10),
                    true
                );
            assertEquals(targetEntry.id(), expandedPlacement.state().selectedId());

            FileTreeView.State clicked = new FileTreeView.State(
                targetEntry.id(),
                expandedPlacement.state().widgetState(),
                expanded.revision(),
                targetEntry.id(),
                123L
            );
            FileTreeModel.Snapshot metadataOnly = new FileTreeModel.Snapshot(
                expanded.root(),
                expanded.revision() + 1L,
                expanded.entries()
            );
            FileTreeView.Placement metadataPlacement = FileTreeView
                .fromSnapshot(metadataOnly, false, false)
                .place(
                    new Rect(1, 1, 24, 5),
                    clicked,
                    new Rect(0, 0, 40, 10),
                    true
                );
            assertEquals(targetEntry.id(), metadataPlacement.state().selectedId());
            assertEquals(targetEntry.id(), metadataPlacement.state().lastPressedId());
            assertEquals(123L, metadataPlacement.state().lastPressedAtNanos());
        }
    }

    @Test
    void doubleClickUsesStableIdAndKeepsDirectoryAndKeyboardPoliciesDistinct() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path file = Files.createFile(root.resolve("note.txt"));

        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
            FileTreeModel.Entry directory = entry(snapshot, folder);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            Rect bounds = new Rect(2, 1, 24, 5);
            Rect clip = new Rect(0, 0, 32, 8);
            FileTreeView.Placement placement = view.place(
                bounds,
                state(snapshot, directory, 0, 0),
                clip,
                true
            );

            PointerEvent press = pointer(
                placement.contentBounds().y()
                    + placement.state().widgetState().selectedIndex(),
                placement.leadingBounds().x() + 3,
                PointerEvent.Kind.PRESS
            );
            FileTreeView.State afterFirst = assertInstanceOf(
                FileTreeView.StateChanged.class,
                output(placement.handle(press, 1_000L, DOUBLE_CLICK_NANOS))
            ).state();
            FileTreeView.Placement secondPlacement = view.place(
                bounds,
                afterFirst,
                clip,
                true
            );
            FileTreeView.ToggleRequested doubleClick = assertInstanceOf(
                FileTreeView.ToggleRequested.class,
                output(secondPlacement.handle(press, 2_000L, DOUBLE_CLICK_NANOS))
            );
            assertEquals(directory.id(), doubleClick.id());
            assertTrue(doubleClick.expand());
            assertEquals(null, doubleClick.state().lastPressedId());

            FileTreeView.RootRequested enterDirectory = assertInstanceOf(
                FileTreeView.RootRequested.class,
                output(placement.handle(KeyStroke.of(KeyStroke.Key.ENTER), 3_000L, DOUBLE_CLICK_NANOS))
            );
            assertEquals(folder.toAbsolutePath().normalize(), enterDirectory.root());

            FileTreeModel.Entry parent = snapshot.entries().stream()
                .filter(entry -> entry.kind() == FileTreeModel.Kind.PARENT)
                .findFirst()
                .orElseThrow();
            FileTreeView.Placement parentPlacement = view.place(
                bounds,
                state(snapshot, parent, 0, 0),
                clip,
                true
            );
            assertEquals(parent.path(), assertInstanceOf(
                FileTreeView.RootRequested.class,
                output(parentPlacement.handle(
                    KeyStroke.of(KeyStroke.Key.ENTER),
                    3_500L,
                    DOUBLE_CLICK_NANOS
                ))
            ).root());

            FileTreeModel.Entry fileEntry = entry(snapshot, file);
            FileTreeView.Placement filePlacement = view.place(
                bounds,
                state(snapshot, fileEntry, 0, 0),
                clip,
                true
            );
            assertInstanceOf(
                FileTreeView.FileRequested.class,
                output(filePlacement.handle(KeyStroke.of(KeyStroke.Key.ENTER), 4_000L, DOUBLE_CLICK_NANOS))
            );
        }
    }

    @Test
    void errorRowsAndInvisibleTinyPlacementsNeverActivate() throws IOException {
        Path notDirectory = Files.createFile(root.resolve("plain-file"));
        try (FileTreeModel model = new FileTreeModel(notDirectory, false, false)) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
            FileTreeModel.Entry error = snapshot.entries().stream()
                .filter(entry -> entry.kind() == FileTreeModel.Kind.ERROR)
                .findFirst()
                .orElseThrow();
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, true, true);
            FileTreeView.State state = state(snapshot, error, 0, 0);
            Rect clip = new Rect(0, 0, 30, 8);
            FileTreeView.Placement visible = view.place(
                new Rect(1, 1, 20, 4),
                state,
                clip,
                true
            );
            assertEquals(0, visible.sizeColumnWidth());
            assertEquals(0, visible.dateColumnWidth());
            CellBuffer buffer = new CellBuffer(8, 30);
            visible.paint(new Canvas(buffer), Theme.fixed(CellStyle.DEFAULT));
            assertEquals("<", buffer.get(selectedRow(visible), visible.leadingBounds().x()).grapheme());
            assertInstanceOf(
                FileTreeView.StateChanged.class,
                output(visible.handle(KeyStroke.of(KeyStroke.Key.ENTER), 1L, DOUBLE_CLICK_NANOS))
            );

            FileTreeView.Placement hidden = view.place(
                new Rect(1, 1, 20, 4),
                visible.state(),
                clip,
                true,
                false
            );
            assertFalse(hidden.enabled());
            assertTrue(hidden.bounds().isEmpty());
            assertTrue(hidden.cursorPosition().isEmpty());
            assertEquals(visible.state().widgetState(), hidden.state().widgetState());
            assertFalse(hidden.handle(
                KeyStroke.of(KeyStroke.Key.ENTER),
                2L,
                DOUBLE_CLICK_NANOS
            ).consumed());
        }
    }

    @Test
    void thumbCapturePreservesGrabOffsetAndClampsDragOutsideTrack() throws IOException {
        for (int index = 0; index < 6; index++) {
            Files.createFile(root.resolve("long-file-name-" + index + "-0123456789.txt"));
        }

        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
            FileTreeModel.Entry selected = snapshot.entries().get(2);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            Rect clip = new Rect(0, 0, 40, 20);
            FileTreeView.Placement placement = view.place(
                new Rect(5, 3, 12, 5),
                state(snapshot, selected, 1, 3),
                clip,
                true
            );
            Rect track = placement.verticalScrollbarBounds().orElseThrow();
            int first = placement.state().widgetState().firstVisibleIndex();
            int thumbOffset = ScrollInteraction.thumbOffset(
                track.height(),
                placement.itemCount(),
                first
            );
            int thumbSize = ScrollInteraction.thumbSize(track.height(), placement.itemCount());
            int grab = Math.min(1, thumbSize - 1);
            PointerEvent pressEvent = pointer(
                track.y() + thumbOffset + grab,
                track.x(),
                PointerEvent.Kind.PRESS
            );
            FileTreeView.ScrollbarPress press = placement.scrollbarPress(pressEvent).orElseThrow();

            assertEquals(FileTreeView.ScrollAxis.VERTICAL, press.axis());
            assertEquals(grab, press.grabOffset());
            assertEquals(
                first,
                placement.drag(
                    pointer(pressEvent.row(), pressEvent.column(), PointerEvent.Kind.DRAG),
                    press
                ).widgetState().firstVisibleIndex()
            );
            assertEquals(
                placement.itemCount() - track.height(),
                placement.drag(
                    pointer(clip.bottomExclusive() + 20, track.x(), PointerEvent.Kind.DRAG),
                    press
                ).widgetState().firstVisibleIndex()
            );
        }
    }

    private static FileTreeView.State state(
        FileTreeModel.Snapshot snapshot,
        FileTreeModel.Entry selected,
        int firstVisible,
        int horizontalOffset
    ) {
        int index = snapshot.entries().indexOf(selected);
        return new FileTreeView.State(
            selected.id(),
            new dev.tuink.terminal.widget.TreeView.State(index, firstVisible, horizontalOffset),
            snapshot.revision(),
            null,
            0L
        );
    }

    private static FileTreeModel.Entry entry(FileTreeModel.Snapshot snapshot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return snapshot.entries().stream()
            .filter(entry -> entry.path().equals(normalized))
            .findFirst()
            .orElseThrow();
    }

    private static FileTreeView.Action output(InteractionResult<FileTreeView.Action> result) {
        assertTrue(result.consumed());
        return result.output().orElseThrow();
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of());
    }

    private static int selectedRow(FileTreeView.Placement placement) {
        int selectedIndex = placement.state().widgetState().selectedIndex();
        return placement.contentBounds().y()
            + selectedIndex
            - placement.state().widgetState().firstVisibleIndex();
    }

    private static String cells(CellBuffer buffer, int row, Rect bounds) {
        StringBuilder text = new StringBuilder();
        for (int column = bounds.x(); column < bounds.rightExclusive(); column++) {
            if (!buffer.get(row, column).continuation()) {
                text.append(buffer.get(row, column).grapheme());
            }
        }
        return text.toString();
    }
}

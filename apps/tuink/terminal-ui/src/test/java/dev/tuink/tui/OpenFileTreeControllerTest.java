package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenFileTreeControllerTest {
    private static final Rect BOUNDS = new Rect(2, 1, 24, 5);
    private static final Rect NARROW_BOUNDS = new Rect(2, 1, 14, 4);
    private static final Rect CLIP = new Rect(0, 0, 40, 10);
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;

    @TempDir
    Path root;

    @Test
    void metadataRevisionKeepsStableSelectionAndBothViewports() throws IOException {
        for (int index = 0; index < 512; index++) {
            Path directory = Files.createDirectory(
                root.resolve("entry-%03d-long-directory-name".formatted(index))
            );
            Files.createFile(directory.resolve("child.txt"));
        }

        try (OpenFileTreeController controller = new OpenFileTreeController(root, true, true)) {
            FileTreeView.Placement placement = controller.place(NARROW_BOUNDS, CLIP, true, true);
            OpenFileInputRouter.Result end = route(
                placement,
                KeyStroke.of(KeyStroke.Key.END)
            );
            controller.adopt(end.state());
            placement = controller.place(NARROW_BOUNDS, CLIP, true, true);
            OpenFileInputRouter.Result right = route(
                placement,
                KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT)
            );
            controller.adopt(right.state());

            // Rebuild the structural snapshot to restart metadata enrichment,
            // while keeping the selected stable ID and numeric viewports.
            controller.toggleHiddenFiles();
            FileTreeView.Placement pending = controller.place(NARROW_BOUNDS, CLIP, true, true);
            FileTreeModel.EntryId selectedId = pending.state().selectedId();
            int firstVisible = pending.state().widgetState().firstVisibleIndex();
            int horizontalOffset = pending.state().widgetState().horizontalOffset();
            long pendingRevision = pending.state().snapshotRevision();

            assertTrue(firstVisible > 0);
            assertTrue(horizontalOffset > 0);
            FileTreeView.Placement enriched = awaitMetadataRevision(controller, pendingRevision);

            assertEquals(selectedId, enriched.state().selectedId());
            assertEquals(firstVisible, enriched.state().widgetState().firstVisibleIndex());
            assertEquals(horizontalOffset, enriched.state().widgetState().horizontalOffset());
            assertTrue(enriched.state().snapshotRevision() > pendingRevision);
            assertNull(enriched.state().lastPressedId());
        }
    }

    @Test
    void routerIntentsDriveExpansionHiddenAndRootLifecycleAndResetClicks() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Files.createFile(folder.resolve("child.txt"));
        Files.createFile(root.resolve("note.txt"));

        try (OpenFileTreeController controller = new OpenFileTreeController(root, false, false)) {
            FileTreeView.Placement placement = controller.place(BOUNDS, CLIP, true, true);
            OpenFileInputRouter.Result selectFolder = route(
                placement,
                KeyStroke.of(KeyStroke.Key.ARROW_DOWN)
            );
            controller.adopt(selectFolder.state());
            placement = controller.place(BOUNDS, CLIP, true, true);
            FileTreeModel.Entry directory = placement.selectedEntry().orElseThrow();
            assertEquals(folder.toAbsolutePath().normalize(), directory.path());

            OpenFileInputRouter.Result pressed = route(
                placement,
                rowPress(placement)
            );
            controller.adopt(pressed.state());
            assertEquals(directory.id(), controller.state().lastPressedId());

            placement = controller.place(BOUNDS, CLIP, true, true);
            OpenFileInputRouter.Result expand = route(
                placement,
                KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)
            );
            OpenFileInputRouter.ToggleDirectory toggle = assertInstanceOf(
                OpenFileInputRouter.ToggleDirectory.class,
                expand.intent()
            );
            controller.adopt(withClick(expand.state(), directory.id()));
            controller.requestExpansion(toggle.id(), toggle.expand());
            assertNull(controller.state().lastPressedId());

            placement = controller.place(BOUNDS, CLIP, true, true);
            assertTrue(placement.selectedEntry().orElseThrow().expanded());
            assertEquals(directory.id(), placement.state().selectedId());

            controller.adopt(withClick(placement.state(), directory.id()));
            OpenFileInputRouter.Result hidden = route(
                placement,
                KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL)
            );
            assertInstanceOf(OpenFileInputRouter.ToggleHidden.class, hidden.intent());
            controller.adopt(hidden.state());
            controller.toggleHiddenFiles();
            assertTrue(controller.showHiddenFiles());
            assertNull(controller.state().lastPressedId());
            placement = controller.place(BOUNDS, CLIP, true, true);
            assertEquals(directory.id(), placement.state().selectedId());

            controller.adopt(withClick(placement.state(), directory.id()));
            OpenFileInputRouter.Result enter = route(
                placement,
                KeyStroke.of(KeyStroke.Key.ENTER)
            );
            OpenFileInputRouter.ChangeRoot changeRoot = assertInstanceOf(
                OpenFileInputRouter.ChangeRoot.class,
                enter.intent()
            );
            controller.adopt(enter.state());
            controller.setRoot(changeRoot.root());

            FileTreeView.Placement nested = controller.place(BOUNDS, CLIP, true, true);
            assertEquals(folder.toAbsolutePath().normalize(), controller.root());
            assertEquals(0, nested.state().widgetState().selectedIndex());
            assertEquals(0, nested.state().widgetState().firstVisibleIndex());
            assertEquals(0, nested.state().widgetState().horizontalOffset());
            assertNull(nested.state().lastPressedId());
        }
    }

    @Test
    void hiddenPlacementPreservesControllerStateAndCloseStopsMutation() throws IOException {
        for (int index = 0; index < 12; index++) {
            Files.createFile(root.resolve("long-file-name-" + index + "-0123456789.txt"));
        }
        OpenFileTreeController controller = new OpenFileTreeController(root, false, false);
        FileTreeView.Placement placement = controller.place(NARROW_BOUNDS, CLIP, true, true);
        OpenFileInputRouter.Result end = route(placement, KeyStroke.of(KeyStroke.Key.END));
        controller.adopt(end.state());
        placement = controller.place(NARROW_BOUNDS, CLIP, true, true);
        OpenFileInputRouter.Result right = route(
            placement,
            KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT)
        );
        controller.adopt(right.state());
        FileTreeView.Placement visible = controller.place(NARROW_BOUNDS, CLIP, true, true);
        FileTreeView.Placement hidden = controller.place(NARROW_BOUNDS, CLIP, true, false);

        assertFalse(hidden.enabled());
        assertTrue(hidden.bounds().isEmpty());
        assertEquals(visible.state(), hidden.state());
        assertEquals(visible.state(), controller.state());

        controller.close();
        assertThrows(IllegalStateException.class, () -> controller.setRoot(root));
        controller.close();
    }

    private static FileTreeView.Placement awaitMetadataRevision(
        OpenFileTreeController controller,
        long baselineRevision
    ) {
        long startedAt = System.nanoTime();
        long timeout = Duration.ofSeconds(5).toNanos();
        FileTreeView.Placement current = null;
        while (System.nanoTime() - startedAt < timeout) {
            if (controller.consumeMetadataChanged()) {
                current = controller.place(NARROW_BOUNDS, CLIP, true, true);
                if (current.state().snapshotRevision() > baselineRevision) {
                    return current;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
            "metadata revision was not published; last placement=" + current
        );
    }

    private static OpenFileInputRouter.Result route(
        FileTreeView.Placement placement,
        dev.tuink.terminal.input.TuiInputEvent event
    ) {
        return OpenFileInputRouter.route(
            placement,
            OpenFileInputRouter.Focus.LIST,
            null,
            event,
            System.nanoTime(),
            DOUBLE_CLICK_NANOS
        );
    }

    private static PointerEvent rowPress(FileTreeView.Placement placement) {
        int row = placement.contentBounds().y()
            + placement.state().widgetState().selectedIndex()
            - placement.state().widgetState().firstVisibleIndex();
        return new PointerEvent(
            row,
            placement.leadingBounds().x() + 3,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of()
        );
    }

    private static FileTreeView.State withClick(
        FileTreeView.State state,
        FileTreeModel.EntryId id
    ) {
        return new FileTreeView.State(
            state.selectedId(),
            state.widgetState(),
            state.snapshotRevision(),
            id,
            System.nanoTime()
        );
    }
}

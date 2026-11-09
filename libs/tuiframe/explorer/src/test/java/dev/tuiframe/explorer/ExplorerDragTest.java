package dev.tuiframe.explorer;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.widget.Theme;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerDragTest {
    private static final Rect BOUNDS = new Rect(0, 0, 30, 8);
    private static final Rect CLIP = new Rect(0, 0, 80, 24);
    private static final long DOUBLE_CLICK = 450_000_000L;
    @TempDir Path root;

    @Test
    void holdAndMovementProduceOneDropIntentWithoutTouchingTheFilesystem() throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer); place(explorer);
            int sourceRow = row(explorer, file), destinationRow = row(explorer, destination);
            var press = explorer.handle(pointer(sourceRow, PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());
            place(explorer);
            assertFalse(explorer.dragging());
            assertEquals(400_000_000L, explorer.nanosUntilWakeup(100_000_000L));
            assertFalse(explorer.tick(499_999_999L));
            assertFalse(explorer.dragging());
            assertTrue(explorer.tick(500_000_000L));
            assertFalse(explorer.dragging(), "Holding only prepares the gesture; motion starts the move");
            explorer.handle(pointer(destinationRow, PointerEvent.Kind.DRAG), 500_000_000L, DOUBLE_CLICK);
            assertTrue(explorer.dragging());
            assertTrue(Files.exists(file));
            assertFalse(Files.exists(destination.resolve(file.getFileName())));

            var released = explorer.handle(pointer(destinationRow, PointerEvent.Kind.RELEASE), 510_000_000L, DOUBLE_CLICK);
            var drop = assertInstanceOf(FileTreeView.MoveRequested.class, released.output().orElseThrow());
            assertEquals(file, drop.source());
            assertEquals(destination, drop.destination());
            assertEquals(file, drop.expected().path());
            assertEquals(new ExplorerFileService().inspect(file).key(), drop.expected().key());
            assertEquals(PointerCaptureRequest.RELEASE, released.pointerCaptureRequest());
            assertFalse(explorer.pointerCaptureActive());
            assertTrue(Files.exists(file), "A gesture requests one move; the operation queue owns publication");
        }
    }

    @Test void earlyMotionPermanentlyCancelsFileAndFolderMovesAndAFreshStationaryPressCanRetry() throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        Path folder = Files.createDirectory(root.resolve("source-folder"));
        for (Path source : new Path[] {file, folder}) try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer); place(explorer);
            int sourceRow = row(explorer, source), targetRow = row(explorer, destination);
            explorer.handle(pointer(sourceRow, PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            place(explorer);
            explorer.handle(new PointerEvent(sourceRow, 9, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT, Set.of()), 499_999_999L, DOUBLE_CLICK);
            assertEquals(-1L, explorer.nanosUntilWakeup(1_000_000_000L));
            assertFalse(explorer.tick(1_000_000_000L));
            explorer.handle(pointer(sourceRow, PointerEvent.Kind.DRAG), 1_100_000_000L, DOUBLE_CLICK);
            explorer.handle(pointer(targetRow, PointerEvent.Kind.DRAG), 1_200_000_000L, DOUBLE_CLICK);
            assertFalse(explorer.dragging());
            var cancelled = explorer.handle(pointer(targetRow, PointerEvent.Kind.RELEASE), 1_300_000_000L, DOUBLE_CLICK);
            assertFalse(cancelled.output().orElse(null) instanceof FileTreeView.MoveRequested);
            assertFalse(explorer.pointerCaptureActive());
            place(explorer);
            explorer.handle(pointer(sourceRow, PointerEvent.Kind.PRESS), 2_000_000_000L, DOUBLE_CLICK);
            explorer.handle(pointer(targetRow, PointerEvent.Kind.DRAG), 2_500_000_000L, DOUBLE_CLICK);
            assertTrue(explorer.dragging());
            assertInstanceOf(FileTreeView.MoveRequested.class, explorer.handle(pointer(targetRow, PointerEvent.Kind.RELEASE),
                2_510_000_000L, DOUBLE_CLICK).output().orElseThrow());
            assertTrue(Files.exists(source)); assertFalse(Files.exists(destination.resolve(source.getFileName())));
        }
    }

    @Test
    void hoveringExpandsMultipleNestedFoldersWithoutLosingTheCapturedSource() throws Exception {
        Path outer = Files.createDirectory(root.resolve("outer"));
        Path inner = Files.createDirectory(outer.resolve("inner"));
        Files.createDirectory(inner.resolve("leaf"));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer); place(explorer);
            explorer.handle(pointer(row(explorer, file), PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            place(explorer);
            explorer.handle(pointer(row(explorer, outer), PointerEvent.Kind.DRAG), 1_100_000_000L, DOUBLE_CLICK);
            explorer.tick(1_800_000_000L);
            loaded(explorer); place(explorer);
            assertTrue(explorer.pointerCaptureActive());
            assertTrue(explorer.snapshot().entries().stream().anyMatch(entry -> entry.path().equals(inner)));
            explorer.handle(pointer(row(explorer, inner), PointerEvent.Kind.DRAG), 1_900_000_000L, DOUBLE_CLICK);
            explorer.tick(2_600_000_000L);
            loaded(explorer); place(explorer);
            assertTrue(explorer.pointerCaptureActive());
            assertTrue(explorer.snapshot().entries().stream().anyMatch(entry -> entry.path().equals(inner.resolve("leaf"))));
            var drop = assertInstanceOf(FileTreeView.MoveRequested.class,
                explorer.handle(pointer(row(explorer, inner), PointerEvent.Kind.RELEASE), 2_700_000_000L, DOUBLE_CLICK)
                    .output().orElseThrow());
            assertEquals(file, drop.source());
            assertEquals(inner, drop.destination());
            assertTrue(Files.exists(file));
        }
    }

    @Test
    void onlyCompletedShortDoubleClicksOpenAndAHeldSecondPressCanDrag() throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer); place(explorer);
            int row = row(explorer, file);
            explorer.handle(pointer(row, PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            explorer.handle(pointer(row, PointerEvent.Kind.RELEASE), 10_000_000L, DOUBLE_CLICK);
            place(explorer);
            assertInstanceOf(FileTreeView.StateChanged.class,
                explorer.handle(pointer(row, PointerEvent.Kind.PRESS), 100_000_000L, DOUBLE_CLICK).output().orElseThrow());
            var opened = explorer.handle(pointer(row, PointerEvent.Kind.RELEASE), 110_000_000L, DOUBLE_CLICK);
            assertInstanceOf(FileTreeView.FileRequested.class, opened.output().orElseThrow());
            place(explorer);
            explorer.handle(pointer(row, PointerEvent.Kind.PRESS), 200_000_000L, DOUBLE_CLICK);
            explorer.handle(pointer(row, PointerEvent.Kind.RELEASE), 210_000_000L, DOUBLE_CLICK);
            place(explorer);
            var held = explorer.handle(pointer(row, PointerEvent.Kind.PRESS), 300_000_000L, DOUBLE_CLICK);
            assertFalse(held.output().orElseThrow() instanceof FileTreeView.FileRequested);
            explorer.handle(pointer(row(explorer, destination), PointerEvent.Kind.DRAG), 1_400_000_000L, DOUBLE_CLICK);
            var drop = explorer.handle(pointer(row(explorer, destination), PointerEvent.Kind.RELEASE), 1_410_000_000L, DOUBLE_CLICK);
            assertInstanceOf(FileTreeView.MoveRequested.class, drop.output().orElseThrow());
        }
    }

    @Test
    void readOnlyAndRootRowsNeverBecomeFileDrags() throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var readonly = new Explorer(root, false, false)) {
            loaded(readonly); place(readonly);
            readonly.handle(pointer(row(readonly, file), PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            readonly.handle(pointer(row(readonly, destination), PointerEvent.Kind.DRAG), 1_400_000_000L, DOUBLE_CLICK);
            readonly.tick(2_000_000_000L);
            assertFalse(readonly.dragging());
            var release = readonly.handle(pointer(row(readonly, destination), PointerEvent.Kind.RELEASE), 2_600_000_000L, DOUBLE_CLICK);
            assertFalse(release.output().orElse(null) instanceof FileTreeView.MoveRequested);
        }
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer); place(explorer);
            explorer.handle(pointer(row(explorer, root), PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            explorer.handle(pointer(row(explorer, destination), PointerEvent.Kind.DRAG), 1_400_000_000L, DOUBLE_CLICK);
            assertFalse(explorer.dragging());
            var release = explorer.handle(pointer(row(explorer, destination), PointerEvent.Kind.RELEASE), 1_410_000_000L, DOUBLE_CLICK);
            assertFalse(release.output().orElse(null) instanceof FileTreeView.MoveRequested);
        }
        assertEquals("contents", Files.readString(file));
    }

    @Test
    void escapeResizeAndHiddenSurfacesCancelWithoutPublishingAnything() throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer);
            for (int cancellation = 0; cancellation < 3; cancellation++) {
                place(explorer);
                explorer.handle(pointer(row(explorer, file), PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
                explorer.handle(pointer(row(explorer, destination), PointerEvent.Kind.DRAG), 1_400_000_000L, DOUBLE_CLICK);
                assertTrue(explorer.dragging());
                switch (cancellation) {
                    case 0 -> explorer.handle(KeyStroke.of(KeyStroke.Key.ESCAPE), 1_410_000_000L, DOUBLE_CLICK);
                    case 1 -> explorer.place(new Rect(0, 0, 29, 8), CLIP, true, true);
                    default -> explorer.place(BOUNDS, CLIP, false, false);
                }
                assertFalse(explorer.pointerCaptureActive());
                assertEquals(-1L, explorer.nanosUntilWakeup(2_000_000_000L));
            }
        }
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(destination.resolve(file.getFileName())));
    }

    @Test
    void draggingAtAnEdgeScrollsOnTheTimerAndKeepsTheGesture() throws Exception {
        for (int index = 0; index < 30; index++) Files.createDirectory(root.resolve("folder-%02d".formatted(index)));
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer);
            assertTrue(explorer.select(file));
            place(explorer);
            int before = explorer.state().widgetState().firstVisibleIndex();
            assertTrue(before > 0);
            explorer.handle(pointer(row(explorer, file), PointerEvent.Kind.PRESS), 0L, DOUBLE_CLICK);
            explorer.handle(pointer(0, PointerEvent.Kind.DRAG), 1_100_000_000L, DOUBLE_CLICK);
            explorer.tick(1_180_000_000L);
            place(explorer);
            assertEquals(before - 1, explorer.state().widgetState().firstVisibleIndex());
            assertTrue(explorer.pointerCaptureActive());
            explorer.cancelPointerInteraction(PointerCaptureCancellation.KEYBOARD_INPUT);
            assertFalse(explorer.pointerCaptureActive());
        }
    }

    @Test
    void operationNoticeOwnsOnlyItsFooterAndExpiresWithoutAKey() throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "contents");
        try (var explorer = Explorer.sidebar(root)) {
            loaded(explorer);
            var result = new ExplorerFileService.Result(ExplorerFileService.Action.RENAME, file, file, true, false, null);
            explorer.acceptOperation(result, "Undo rename", true, 0L);
            var placement = place(explorer);
            assertEquals(BOUNDS, placement.bounds());
            assertEquals(7, placement.contentBounds().height());
            var cells = new CellBuffer(24, 80);
            var warning = new CellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW, false, false);
            Theme theme = role -> ExplorerStyleKeys.WARNING.equals(role) ? warning : CellStyle.DEFAULT;
            placement.paint(new Canvas(cells), theme);
            assertEquals(warning, cells.get(7, 0).style());
            assertEquals(CellStyle.DEFAULT, cells.get(7, 31).style());
            assertTrue(explorer.tick(3_000_000_000L));
            assertNull(explorer.noticeText());
            assertEquals(8, place(explorer).contentBounds().height());
            explorer.acceptOperation(result, "Redo rename", false, 4_000_000_000L);
            assertNull(explorer.noticeText());
        }
    }

    private static PointerEvent pointer(int row, PointerEvent.Kind kind) {
        return new PointerEvent(row, 8, kind, PointerEvent.Button.LEFT, Set.of());
    }

    private static FileTreeView.Placement place(Explorer explorer) { return explorer.place(BOUNDS, CLIP, true, true); }
    private static int row(Explorer explorer, Path path) {
        var entries = explorer.snapshot().entries();
        for (int index = 0; index < entries.size(); index++) if (entries.get(index).path().equals(path))
            return index - explorer.state().widgetState().firstVisibleIndex();
        throw new AssertionError("No row for " + path);
    }
    private static void loaded(Explorer explorer) {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (explorer.loading()) {
            if (System.nanoTime() > end) fail("Tree loading timed out");
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
    }
}

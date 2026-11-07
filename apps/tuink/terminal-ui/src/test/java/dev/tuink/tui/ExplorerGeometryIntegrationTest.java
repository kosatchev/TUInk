package dev.tuink.tui;

import dev.tuiframe.explorer.Explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerGeometryIntegrationTest {
    @TempDir Path directory;

    @Test
    void dividerDragTracksSeveralFramesAndReleaseOutsideStopsResizingWithoutMovingSourceCursor() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("source text"))) {
            window.ui.explorer.visible = true;
            window.frame();
            var explorer = window.ui.explorer;
            var divider = explorer.placement.divider().bounds();
            var cursor = window.active().state().getCursor();
            int row = divider.y() + 2;
            window.pointer(PointerEvent.Kind.PRESS, row, divider.x());
            assertTrue(explorer.splitterState.dragging());
            assertFalse(explorer.focused, "resizing must retain the document's keyboard focus");

            window.pointer(PointerEvent.Kind.DRAG, row, divider.x() + 5);
            assertEquals(divider.x() + 5, explorer.preferredWidth);
            assertEquals(divider.x() + 5, explorer.placement.divider().bounds().x());
            assertTrue(explorer.splitterState.dragging());
            window.pointer(PointerEvent.Kind.DRAG, row + 1, divider.x() + 9);
            assertEquals(divider.x() + 9, explorer.preferredWidth);
            assertEquals(explorer.preferredWidth + 1, explorer.placement.documentBounds().x());

            window.pointer(PointerEvent.Kind.RELEASE, window.height - 1, 0);
            assertFalse(explorer.splitterState.dragging());
            window.pointer(PointerEvent.Kind.DRAG, row, divider.x() + 1);
            assertEquals(divider.x() + 9, explorer.preferredWidth, "a released drag cannot resize again");
            assertEquals(cursor, window.active().state().getCursor());
            assertFalse(window.editorView.isMouseSelecting());
        }
    }

    @Test
    void hidingOrNarrowingDuringDividerDragCancelsCaptureBeforeRemovingThePane() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("source text"))) {
            window.ui.explorer.visible = true;
            window.frame();
            var explorer = window.ui.explorer;
            var divider = explorer.placement.divider().bounds();
            window.pointer(PointerEvent.Kind.PRESS, divider.y() + 2, divider.x());
            assertTrue(explorer.splitterState.dragging());
            int preferred = explorer.preferredWidth;

            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertFalse(explorer.visible);
            assertNull(explorer.placement);
            assertFalse(explorer.splitterState.dragging());
            window.pointer(PointerEvent.Kind.RELEASE, 4, 2);
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertNotNull(explorer.placement);
            assertEquals(preferred, explorer.placement.bounds().width());

            divider = explorer.placement.divider().bounds();
            window.pointer(PointerEvent.Kind.PRESS, divider.y() + 2, divider.x());
            assertTrue(explorer.splitterState.dragging());
            window.resize(16, 40);
            assertTrue(explorer.visible, "temporary lack of space must retain the visibility preference");
            assertNull(explorer.placement);
            assertFalse(explorer.splitterState.dragging());
            window.pointer(PointerEvent.Kind.RELEASE, 4, 2);
            window.resize(16, 80);
            assertNotNull(explorer.placement);
            assertEquals(preferred, explorer.preferredWidth);
            assertEquals(preferred, explorer.placement.bounds().width());
            window.pointer(PointerEvent.Kind.DRAG, 4, preferred + 7);
            assertEquals(preferred, explorer.preferredWidth, "resizing the terminal must end the old gesture");
        }
    }

    @Test
    void asynchronousTabNotificationKeepsTheCapturedDividerAndItsGesture() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("source text"))) {
            window.resize(3, 80);
            window.notification.clear();
            var explorer = window.ui.explorer;
            explorer.visible = true;
            explorer.focused = true;
            window.frame();
            assertNotNull(explorer.placement);
            assertEquals(2, explorer.placement.bounds().height());
            assertTrue(explorer.focused);
            var divider = explorer.placement.divider().bounds();
            int preferred = explorer.preferredWidth;
            window.pointer(PointerEvent.Kind.PRESS, divider.y(), divider.x());
            assertTrue(explorer.splitterState.dragging());

            // Model an owner-thread completion publishing a notice without a key or resize event.
            window.notification.info("Background operation completed");
            window.frame();
            assertNotNull(explorer.placement);
            assertEquals(divider, explorer.placement.divider().bounds());
            assertTrue(explorer.splitterState.dragging(), "A sibling footer cannot revoke a divider capture");
            window.pointer(PointerEvent.Kind.DRAG, divider.y(), divider.x() + 5);
            assertEquals(preferred + 5, explorer.preferredWidth);
            assertDoesNotThrow(() -> window.pointer(PointerEvent.Kind.RELEASE, divider.y(), divider.x()));
            assertFalse(explorer.splitterState.dragging());

            window.notification.clear();
            window.frame();
            assertNotNull(explorer.placement);
            assertTrue(explorer.focused);
            window.pointer(PointerEvent.Kind.DRAG, divider.y(), divider.x() + 5);
            assertEquals(preferred + 5, explorer.preferredWidth, "The released gesture cannot resume resizing");
        }
    }

    @Test
    void narrowTerminalRetainsDesiredWidthAndRestoresPaneWithoutStealingDocumentFocus() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("source text"))) {
            var explorer = window.ui.explorer;
            explorer.preferredWidth = 38;
            window.command(TuinkCommand.EXPLORER_FOCUS);
            assertTrue(explorer.focused);
            assertEquals(38, explorer.placement.bounds().width());

            window.resize(16, 50);
            assertNotNull(explorer.placement);
            assertEquals(25, explorer.placement.bounds().width());
            assertEquals(38, explorer.preferredWidth, "a constrained layout must not overwrite the desired width");
            window.resize(16, 40);
            assertNull(explorer.placement);
            assertFalse(explorer.focused);
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            window.event(new TextInput("!"));
            assertEquals(1, window.active().state().getCursor().col());

            window.resize(16, 90);
            assertNotNull(explorer.placement);
            assertEquals(38, explorer.preferredWidth);
            assertEquals(38, explorer.placement.bounds().width());
            assertFalse(explorer.focused, "restoring space must not steal focus from ongoing document editing");
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            window.event(new TextInput("?"));
            assertEquals(2, window.active().state().getCursor().col());
        }
    }

    @Test
    void previewHitAndCursorUseItsPaneOffsetBeforeAndAfterDividerResize() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("alpha beta gamma"))) {
            var sourceCursor = window.active().state().getCursor();
            var source = window.active();
            window.ui.explorer.visible = true;
            window.frame();
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            var explorer = window.ui.explorer;
            var preview = window.preview();
            assertEquals(explorer.placement.documentBounds().x(), preview.bounds.x());
            assertTrue(preview.bounds.x() > 0);

            window.command(TuinkCommand.EXPLORER_FOCUS);
            var before = preview.selection.snapshot();
            window.event(new PointerEvent(preview.bounds.y() + 1, 2,
                PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of()));
            assertEquals(before, preview.selection.snapshot());
            assertEquals(0, preview.top);

            assertPreviewHit(window, 7);
            assertFalse(explorer.focused);
            assertEquals(sourceCursor, source.state().getCursor());
            var divider = explorer.placement.divider().bounds();
            window.pointer(PointerEvent.Kind.PRESS, divider.y() + 2, divider.x());
            window.pointer(PointerEvent.Kind.DRAG, divider.y() + 2, divider.x() + 8);
            window.pointer(PointerEvent.Kind.RELEASE, divider.y() + 2, divider.x() + 8);
            window.await(() -> window.preview().ready());
            assertEquals(divider.x() + 9, window.preview().bounds.x());
            assertPreviewHit(window, 3);
            assertEquals(sourceCursor, source.state().getCursor());
        }
    }

    private static void assertPreviewHit(EditorWindowFixture window, int offset) {
        int row = -1;
        for (int candidate = 0; candidate < window.height; candidate++) {
            if (window.screenRow(candidate).contains("alpha beta gamma")) { row = candidate; break; }
        }
        assertTrue(row >= 0, "the preview paragraph must be visible");
        int start = window.screenRow(row).indexOf("alpha beta gamma");
        assertTrue(start >= window.preview().bounds.x());
        window.pointer(PointerEvent.Kind.PRESS, row, start + offset);
        window.pointer(PointerEvent.Kind.RELEASE, row, start + offset);
        assertEquals(offset, window.preview().cursorOffset());
        assertTrue(window.controller.previewFrame().cursor().visible());
        assertEquals(new Point(start + offset, row), window.controller.previewFrame().cursor().position());
    }

    @Test
    void clickingSidebarBehindSaveAsDoesNotFocusOrSelectTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("source text"))) {
            window.ui.explorer.visible = true;
            window.frame();
            var before = window.active().state().getCursor();
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertFalse(window.selected().uiState().blockingContentFocused);

            window.pointer(PointerEvent.Kind.PRESS, 5, 3);

            assertFalse(window.selected().uiState().blockingContentFocused);
            assertEquals(before, window.active().state().getCursor());
            assertFalse(window.editorView.isMouseSelecting());
            assertTrue(window.selected().uiState().modalState.isSaveAs());
        }
    }

    @Test
    void showingSidebarRevealsActiveTabAfterTheTabStripWasScrolledAway() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of(""))) {
            window.ui.showTabBarSetting = true;
            for (int index = 0; index < 8; index++) window.event(ctrl(KeyStroke.Key.N));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.event(new TextInput(directory.resolve("active-last.txt").toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && window.selected().uiState().modalState.isNone());
            assertTrue(window.screenRow(1).contains("active-last.txt"));

            for (int index = 0; index < 12; index++) window.event(new PointerEvent(1, 5,
                PointerEvent.Kind.SCROLL_UP, PointerEvent.Button.NONE, Set.of()));
            assertFalse(window.screenRow(1).contains("active-last.txt"));
            window.ui.explorer.visible = true;
            window.frame();

            assertTrue(window.screenRow(1).contains("active-last.txt"));
            assertTrue(window.screenRow(1).startsWith(" Explorer"));
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}

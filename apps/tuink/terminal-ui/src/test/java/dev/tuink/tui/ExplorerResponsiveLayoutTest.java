package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerResponsiveLayoutTest {
    @TempDir Path directory;

    @Test
    void automaticallyHiddenExplorerReturnsTheReadyPreviewCaretToContentInTheSameFrame() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
                List.of("alpha beta gamma"))) {
            // Removing the 30-column sidebar and divider at width 40 preserves
            // the ready 40-column layout, so focus is tested without an async reflow.
            window.resize(24, 71);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            assertTrue(window.controller.previewFrame().cursor().visible());
            window.command(TuinkCommand.EXPLORER_FOCUS);
            window.await(() -> window.preview().ready());
            assertEquals(40, window.preview().bounds.width());
            assertTrue(window.ui.sidebar.focused);

            window.resize(24, 40);

            assertNull(window.ui.sidebar.placement);
            assertFalse(window.ui.sidebar.focused);
            assertTrue(window.preview().ready(), "The existing 40-column layout remains usable");
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            assertTrue(window.controller.previewFrame().cursor().visible(),
                "Auto-hiding the focused explorer must immediately render the ready content caret");
        }
    }

    @Test
    void automaticallyHiddenExplorerRestoresTheRetainedPreviewQueryCaretInTheSameFrame() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
                List.of("alpha beta gamma"))) {
            window.resize(24, 71);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.await(() -> window.preview().ready());
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.command(TuinkCommand.EXPLORER_FOCUS);
            window.await(() -> window.preview().ready());
            assertEquals(40, window.preview().bounds.width());
            assertTrue(window.ui.sidebar.focused);
            assertFalse(window.controller.previewFrame().cursor().visible());

            window.resize(24, 40);

            assertNull(window.ui.sidebar.placement);
            assertFalse(window.ui.sidebar.focused);
            assertTrue(window.preview().ready());
            assertTrue(window.preview().searchVisible);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            assertTrue(window.controller.previewFrame().cursor().visible(),
                "A retained Find field must regain its caret as soon as the explorer hides");
        }
    }

    @Test
    void tabLocalNotificationRetainsTheSidebarAndItsTabStripOffsetInAShortWindow() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("source"))) {
            window.ui.showTabBarSetting = true;
            window.command(TuinkCommand.TAB_NEW);
            window.ui.sidebar.visible = true;
            window.resize(3, 80);
            window.notification.info("Background notification");
            window.frame();

            assertNotNull(window.ui.sidebar.placement,
                "A document notification must not remove space from the sibling sidebar");
            assertEquals(2, window.ui.sidebar.placement.bounds().height());
            int firstTabColumn = window.screenRow(1).indexOf("document.txt");
            assertTrue(firstTabColumn >= 0, window.screenRow(1));
            assertTrue(firstTabColumn >= window.ui.sidebar.placement.documentBounds().x(),
                "Tabs remain in the document container: " + window.screenRow(1));
        }
    }
}

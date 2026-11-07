package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentFooterIntegrationTest {
    @TempDir Path directory;

    @Test
    void searchAndDocumentNoticeStayUnderTheEditorWhileTheTreeKeepsItsHeight() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("needle original"))) {
            openTree(window);
            int sidebarHeight = window.ui.explorer.placement.bounds().height();
            int left = documentLeft(window);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));

            int find = rowContaining(window, "Find: ");
            int replace = rowContaining(window, "Replace: ");
            assertEquals(left, window.screenRow(find).indexOf("Find: "));
            assertEquals(left, window.screenRow(replace).indexOf("Replace: "));
            assertEquals(sidebarHeight, window.ui.explorer.placement.bounds().height());
            assertEditorBackgroundOnLeft(window, find);
            assertEditorBackgroundOnLeft(window, replace);

            window.pointer(PointerEvent.Kind.PRESS, find, left + "Find: ".length());
            window.pointer(PointerEvent.Kind.RELEASE, find, left + "Find: ".length());
            window.event(new TextInput("X"));
            assertEquals("Xneedle", window.query.text(), "The painted field and its input allocation share the pane offset");
            assertEquals(List.of("needle original"), window.active().tabSession().model().snapshotLines());

            window.notification.info("TAB_ONLY_NOTICE");
            window.frame();
            int notice = rowContaining(window, "TAB_ONLY_NOTICE");
            assertEquals(left, window.screenRow(notice).indexOf("TAB_ONLY_NOTICE"));
            assertEquals(sidebarHeight, window.ui.explorer.placement.bounds().height());
            assertEditorBackgroundOnLeft(window, notice);
            assertTrue(window.screenRow(window.height - 1).indexOf("Ln ") >= left,
                "The document status belongs to the same footer surface");
        }
    }

    @Test
    void saveAsFieldUsesTheDocumentPaneForPaintAndMouseInput() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            int sidebarHeight = window.ui.explorer.placement.bounds().height();
            int left = documentLeft(window);
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);

            int row = rowContaining(window, "Save As: ");
            assertEquals(left, window.screenRow(row).indexOf("Save As: "));
            assertEditorBackgroundOnLeft(window, row);
            assertEquals(sidebarHeight, window.ui.explorer.placement.bounds().height());
            window.pointer(PointerEvent.Kind.PRESS, row, left + "Save As: ".length());
            window.pointer(PointerEvent.Kind.RELEASE, row, left + "Save As: ".length());
            window.event(new TextInput("X"));
            assertTrue(window.filename.text().contains("X"));
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeTabIsPaneLocalWhileQuitUsesTheWholeWindow(boolean quit) throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            openTree(window);
            int sidebarHeight = window.ui.explorer.placement.bounds().height();
            int left = documentLeft(window);
            window.command(quit ? TuinkCommand.APP_QUIT : TuinkCommand.TAB_CLOSE);

            String prompt = window.promptText();
            assertFalse(prompt.isEmpty());
            int row = window.height - 1 - prompt.split("\n", -1).length;
            var alert = window.host.styles.windows().promptAlert();
            assertEquals(alert.bg(), window.screenStyle(row, left + 1).bg());
            if (quit) assertEquals(alert.bg(), window.screenStyle(row, 2).bg(),
                "Quit confirmation intentionally spans the entire window");
            else assertEditorBackgroundOnLeft(window, row);
            if (!quit) assertEquals(sidebarHeight, window.ui.explorer.placement.bounds().height());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
        }
    }

    @Test
    void hidingAndRestoringTheSidebarReflowsTheSameSearchControls() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("needle original"))) {
            openTree(window);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            int left = documentLeft(window);
            assertEquals(left, window.screenRow(rowContaining(window, "Find: ")).indexOf("Find: "));

            window.resize(24, 40);
            assertNull(window.ui.explorer.placement);
            assertEquals(0, window.screenRow(rowContaining(window, "Find: ")).indexOf("Find: "));
            assertEquals("needle", window.query.text());

            window.resize(24, 80);
            assertNotNull(window.ui.explorer.placement);
            assertEquals(left, window.screenRow(rowContaining(window, "Find: ")).indexOf("Find: "));
            assertEquals("needle", window.query.text());
        }
    }

    private static void openTree(EditorWindowFixture window) throws Exception {
        window.controller.openFolder(window.document.getParent());
        window.await(() -> !window.ui.explorer.tree.loading());
        window.notification.clear();
        window.frame();
    }

    private static int documentLeft(EditorWindowFixture window) {
        return window.ui.explorer.placement.documentBounds().x();
    }

    private static void assertEditorBackgroundOnLeft(EditorWindowFixture window, int row) {
        assertEquals(window.host.styles.windows().renderBody().bg(), window.screenStyle(row, 2).bg(),
            "A document footer must not paint over the explorer");
    }

    private static int rowContaining(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return row;
        fail("No visible row contains " + text);
        return -1;
    }
}

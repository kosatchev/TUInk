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

class DeletedDocumentBannerIntegrationTest {
    @TempDir Path directory;

    @Test
    void persistentWarningHasItsOwnDocumentFooterBesideTheFullHeightExplorer() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original text"))) {
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.explorer.tree.loading());
            var document = TerminalEditorApp.documentId(window.selected());
            window.host.deletedNotices.show(document, window.document, "File deleted");
            window.frame();
            int sidebarHeight = window.ui.explorer.placement.bounds().height();
            int row = row(window, "File deleted");
            int left = window.ui.explorer.placement.documentBounds().x();
            assertEquals(left, window.screenRow(row).indexOf("File deleted"));
            assertEquals(window.host.styles.windows().notifyWarning(), window.screenStyle(row, left));
            assertEquals(window.host.styles.windows().renderBody().bg(), window.screenStyle(row, 2).bg());

            window.notification.info("temporary message");
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("query"));
            window.frame();
            assertTrue(row(window, "temporary message") > row(window, "File deleted"));
            assertTrue(row(window, "Find:") > row(window, "File deleted"));
            assertEquals(sidebarHeight, window.ui.explorer.placement.bounds().height());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("draft "));
            assertTrue(window.host.deletedNotices.visible(document));
            window.resize(24, 40);
            assertEquals(0, window.screenRow(row(window, "File deleted")).indexOf("File deleted"));
            window.resize(24, 80);
            assertTrue(window.screenRow(row(window, "File deleted")).contains("Dismiss"));

            int bannerRow = row(window, "Dismiss");
            int dismiss = window.screenRow(bannerRow).indexOf("Dismiss");
            window.pointer(PointerEvent.Kind.PRESS, bannerRow, dismiss + 1);
            window.pointer(PointerEvent.Kind.RELEASE, bannerRow, dismiss + 1);
            assertFalse(window.host.deletedNotices.visible(document));
            assertTrue(window.active().state().isDirty(), "Dismiss must not save or discard the draft");
            assertEquals(List.of("draft original text"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveAndFrozenPreviewUseTheSameDocumentNotice(boolean frozen) throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Header", "", "text"))) {
            var document = TerminalEditorApp.documentId(window.selected());
            window.host.deletedNotices.show(document, window.document, "File deleted");
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            assertEquals(document, TerminalEditorApp.documentId(window.selected()));
            if (frozen) {
                window.command(TuinkCommand.TAB_PREVIOUS);
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab preview
                    && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
            }
            assertTrue(window.screenRow(row(window, "File deleted")).contains("Dismiss"));
            window.host.deletedNotices.dismiss(document);
            window.frame();
            assertFalse(java.util.stream.IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("File deleted")));
        }
    }

    private static int row(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return row;
        throw new AssertionError("No visible row contains " + text);
    }
}

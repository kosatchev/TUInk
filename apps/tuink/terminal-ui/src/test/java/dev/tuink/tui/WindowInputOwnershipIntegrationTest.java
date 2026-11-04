package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowInputOwnershipIntegrationTest {
    @TempDir Path directory;

    @Test void normalAndBlockingContentUseTheSameDragAndCapturedReleaseForEveryPresentation() throws Exception {
        for (var kind : ActiveSurface.Kind.values()) {
            String ordinary;
            try (var window = window("ordinary-" + kind, kind)) {
                ordinary = dragAndCopy(window);
                assertFalse(ordinary.isEmpty(), kind.name());
            }
            try (var window = window("modal-" + kind, kind)) {
                window.command(TuinkCommand.DOCUMENT_SAVE_AS);
                assertTrue(window.selected().uiState().modalState.isSaveAs());
                String filename = window.filename.text();
                assertEquals(ordinary, dragAndCopy(window), kind.name());
                assertEquals(filename, window.filename.text(), "Readonly content must not edit the modal field");
                assertTrue(window.selected().uiState().modalState.isSaveAs());
                assertEquals(content(), java.nio.file.Files.readAllLines(window.document));
                window.command(TuinkCommand.UI_ESCAPE);
                assertTrue(window.selected().uiState().modalState.isNone());
            }
        }
    }

    @Test void localDialogKeepsItsDraftAcrossTabChromeWhileAWindowConfirmationOwnsChrome() throws Exception {
        try (var window = window("chrome", ActiveSurface.Kind.LIVE_PREVIEW)) {
            var previewId = window.selected().presentationId();
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.event(new TextInput("draft-name.md"));
            int row = tabRow(window);
            int sourceColumn = window.screenRow(row).indexOf("document.md");
            window.pointer(PointerEvent.Kind.PRESS, row, sourceColumn + 2);
            assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
            window.command(TuinkCommand.TAB_NEXT);
            assertEquals(previewId, window.selected().presentationId());
            assertEquals("draft-name.md", window.filename.text());
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            window.command(TuinkCommand.UI_ESCAPE);
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("modified "));
            window.command(TuinkCommand.APP_QUIT);
            assertTrue(window.controller.activeSurface().modalScope() == ActiveSurface.ModalScope.WINDOW);
            var sourceId = window.selected().presentationId();
            row = tabRow(window);
            int previewColumn = window.screenRow(row).lastIndexOf("document.md");
            window.pointer(PointerEvent.Kind.PRESS, row, previewColumn + 2);
            assertEquals(sourceId, window.selected().presentationId());
            assertEquals(ActiveSurface.ModalScope.WINDOW, window.controller.activeSurface().modalScope());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
        }
    }

    @Test void disablingMouseStopsPreviewContentAndCancelsTheExistingCapture() throws Exception {
        try (var window = window("mouse", ActiveSurface.Kind.LIVE_PREVIEW)) {
            int row = visibleRow(window, "row-000");
            int column = window.screenRow(row).indexOf("row-000");
            window.pointer(PointerEvent.Kind.PRESS, row, column + 1);
            window.pointer(PointerEvent.Kind.DRAG, row, column + 5);
            window.command(TuinkCommand.SETTINGS_TOGGLE_MOUSE);
            assertFalse(window.ui.mouseModeSetting);
            var selection = window.preview().selection.snapshot();
            int top = window.preview().top;
            window.pointer(PointerEvent.Kind.DRAG, row + 3, column + 10);
            window.pointer(PointerEvent.Kind.RELEASE, row + 3, column + 10);
            window.pointer(PointerEvent.Kind.SCROLL_DOWN, row + 3, column + 10);
            assertEquals(selection, window.preview().selection.snapshot());
            assertEquals(top, window.preview().top);
            assertFalse(window.preview().pointerCaptureActive());
        }
    }

    private static String dragAndCopy(EditorWindowFixture window) {
        int row = visibleRow(window, "row-000");
        int column = window.screenRow(row).indexOf("row-000");
        window.pointer(PointerEvent.Kind.PRESS, row, column + 1);
        window.pointer(PointerEvent.Kind.DRAG, row + 1, column + 4);
        window.pointer(PointerEvent.Kind.RELEASE, row + 2, column + 7);
        window.command(TuinkCommand.EDIT_COPY);
        return window.host.clipboard.text();
    }

    private EditorWindowFixture window(String name, ActiveSurface.Kind kind) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(name), "document.md", content(),
            new AppSettingsSession(dev.tuink.tui.flow.AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
        if (kind == ActiveSurface.Kind.SOURCE) return window;
        window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
        window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        }
        return window;
    }

    private static List<String> content() {
        var lines = new ArrayList<>(List.of("# Input ownership", "", "```text"));
        for (int row = 0; row < 60; row++) lines.add("row-%03d content for selection and capture".formatted(row));
        lines.add("```");
        return List.copyOf(lines);
    }

    private static int tabRow(EditorWindowFixture window) {
        String strip = window.screenRow(1);
        assertTrue(strip.contains("document.md"), strip);
        assertNotEquals(strip.indexOf("document.md"), strip.lastIndexOf("document.md"),
            "The tab strip must contain both adjacent presentations");
        return 1;
    }
    private static int visibleRow(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return row;
        throw new AssertionError("Missing " + text);
    }
}

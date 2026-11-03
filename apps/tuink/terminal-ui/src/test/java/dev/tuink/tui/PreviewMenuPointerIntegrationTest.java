package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewMenuPointerIntegrationTest {
    @TempDir Path directory;

    private record Setting(TuinkCommand menu, String label, Predicate<UiSessionState> enabled) {}
    private record Navigation(PreviewSelection.Snapshot selection, PreviewSelection.VisualPosition caret,
                              int top, int left) {
        static Navigation capture(MarkdownPreviewState preview) {
            return preview == null ? null : new Navigation(preview.selection.snapshot(),
                preview.selection.visualPosition(preview.result), preview.top, preview.left);
        }
    }

    @Test void menuCheckboxClicksApplyOnceForSourceLiveAndFrozenPresentations() throws Exception {
        var settings = List.of(
            new Setting(TuinkCommand.MENU_VIEW, "Syntax highlighting", ui -> ui.syntaxHighlightingSetting),
            new Setting(TuinkCommand.MENU_SETTINGS, "Reading mode", ui -> ui.readingModeSetting),
            new Setting(TuinkCommand.MENU_SETTINGS, "MD code syntax", ui -> ui.markdownCodeHighlightingSetting));
        for (var kind : ActiveSurface.Kind.values()) {
            try (var window = window("checkbox-" + kind, kind)) {
                var presentation = window.selected().presentationId();
                if (kind != ActiveSurface.Kind.SOURCE) selectPreviewText(window);
                for (var setting : settings) {
                    boolean before = setting.enabled().test(window.ui);
                    var navigation = Navigation.capture(kind == ActiveSurface.Kind.SOURCE ? null : window.preview());
                    window.command(setting.menu());
                    clickMenu(window, setting.label());
                    assertEquals(!before, setting.enabled().test(window.ui), kind + " / " + setting.label());
                    assertFalse(window.menu.isOpen());
                    assertEquals(presentation, window.selected().presentationId());
                    assertEquals(navigation, Navigation.capture(kind == ActiveSurface.Kind.SOURCE ? null : window.preview()),
                        "The semantic menu action must not also move the Preview caret");
                    if (kind != ActiveSurface.Kind.SOURCE) assertFalse(window.preview().pointerCaptureActive());
                }
                assertEquals(String.join("\n", content()), Files.readString(window.document));
            }
        }
    }

    @Test void fileOpenMouseActionReachesTheCommonWorkflowFromEveryPresentation() throws Exception {
        for (var kind : ActiveSurface.Kind.values()) {
            try (var window = window("open-" + kind, kind)) {
                var presentation = window.selected().presentationId();
                if (kind != ActiveSurface.Kind.SOURCE) selectPreviewText(window);
                var navigation = Navigation.capture(kind == ActiveSurface.Kind.SOURCE ? null : window.preview());
                window.command(TuinkCommand.MENU_FILE);
                clickMenu(window, "Open...");
                assertTrue(window.selected().uiState().openFileMode, kind.name());
                assertFalse(window.menu.isOpen());
                assertEquals(presentation, window.selected().presentationId());
                assertEquals(navigation, Navigation.capture(kind == ActiveSurface.Kind.SOURCE ? null : window.preview()));
            }
        }
    }

    @Test void previewSearchDoesNotConsumeAMouseInvokedSettingsCommand() throws Exception {
        for (var kind : List.of(ActiveSurface.Kind.LIVE_PREVIEW, ActiveSurface.Kind.FROZEN_PREVIEW)) {
            try (var window = window("search-" + kind, kind)) {
                window.command(TuinkCommand.SEARCH_OPEN);
                window.event(new TextInput("row-00"));
                assertTrue(window.preview().searchEditing);
                assertEquals("row-00", window.preview().searchQuery);
                var navigation = Navigation.capture(window.preview());
                boolean before = window.ui.readingModeSetting;
                window.command(TuinkCommand.MENU_SETTINGS);
                clickMenu(window, "Reading mode");
                assertEquals(!before, window.ui.readingModeSetting);
                assertTrue(window.preview().searchEditing);
                assertEquals("row-00", window.preview().searchQuery);
                assertEquals(navigation, Navigation.capture(window.preview()));
                assertFalse(window.menu.isOpen());
            }
        }
    }

    @Test void openMenuOwnsWheelAndOutsideDismissalWithoutPanningOrSelectingPreview() throws Exception {
        for (var kind : List.of(ActiveSurface.Kind.LIVE_PREVIEW, ActiveSurface.Kind.FROZEN_PREVIEW)) {
            try (var window = window("dismiss-" + kind, kind)) {
                selectPreviewText(window);
                var navigation = Navigation.capture(window.preview());
                window.command(TuinkCommand.MENU_SETTINGS);
                window.event(new PointerEvent(window.height - 3, window.width - 3,
                    PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of()));
                assertEquals(navigation, Navigation.capture(window.preview()));
                if (!window.menu.isOpen()) window.command(TuinkCommand.MENU_SETTINGS);
                window.pointer(PointerEvent.Kind.PRESS, window.height - 3, window.width - 3);
                window.pointer(PointerEvent.Kind.RELEASE, window.height - 3, window.width - 3);
                assertFalse(window.menu.isOpen());
                assertEquals(navigation, Navigation.capture(window.preview()));
                assertFalse(window.preview().pointerCaptureActive());
            }
        }
    }

    @Test void menuOpeningCancelsAPreviewDragAndItsReleaseCannotMoveTheSelection() throws Exception {
        try (var window = window("drag", ActiveSurface.Kind.LIVE_PREVIEW)) {
            int row = visibleRow(window, "row-000");
            int column = window.screenRow(row).indexOf("row-000");
            window.pointer(PointerEvent.Kind.PRESS, row, column + 1);
            window.pointer(PointerEvent.Kind.DRAG, row, column + 5);
            assertTrue(window.preview().pointerCaptureActive());
            var navigation = Navigation.capture(window.preview());
            window.command(TuinkCommand.MENU_VIEW);
            assertFalse(window.preview().pointerCaptureActive());
            clickMenu(window, "Syntax highlighting");
            window.pointer(PointerEvent.Kind.RELEASE, row + 3, column + 15);
            assertEquals(navigation, Navigation.capture(window.preview()));
            assertFalse(window.preview().pointerCaptureActive());
            assertFalse(window.ui.syntaxHighlightingSetting);
        }
    }

    private EditorWindowFixture window(String name, ActiveSurface.Kind kind) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(name), "document.md", content(),
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
        if (kind == ActiveSurface.Kind.SOURCE) return window;
        window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
        window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        }
        assertEquals(kind, window.controller.activeSurface().kind());
        return window;
    }

    private static List<String> content() {
        var lines = new ArrayList<String>();
        lines.add("# Menu pointer fixture"); lines.add(""); lines.add("```text");
        for (int row = 0; row < 80; row++) lines.add("row-%03d long enough to select with the pointer".formatted(row));
        lines.add("```");
        return List.copyOf(lines);
    }

    private static void selectPreviewText(EditorWindowFixture window) {
        var preview = window.preview();
        String text = preview.result.document().projection().text();
        int start = text.indexOf("row-000");
        preview.selection.selectRange(text, start + 1, start + 5);
        window.frame();
    }

    private static void clickMenu(EditorWindowFixture window, String label) {
        Point point = menuItem(window, label);
        window.pointer(PointerEvent.Kind.PRESS, point.y(), point.x());
        window.pointer(PointerEvent.Kind.RELEASE, point.y(), point.x());
    }

    private static Point menuItem(EditorWindowFixture window, String label) {
        assertTrue(window.menu.isOpen());
        int row = visibleRow(window, label);
        return new Point(window.screenRow(row).indexOf(label) + 2, row);
    }

    private static int visibleRow(EditorWindowFixture window, String label) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(label)) return row;
        throw new AssertionError("Missing displayed label: " + label);
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PopupSurfaceThemeTest {
    @TempDir Path directory;

    static Stream<Arguments> themesAndColorModes() {
        return Arrays.stream(UiStyles.Theme.values()).flatMap(theme ->
            Stream.of(Arguments.of(theme, true), Arguments.of(theme, false)));
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void popupChildrenShareHelpAndOpenRolesWithoutAcquiringTheEditorSurface(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        var windows = app.styles.windows();
        var popup = app.popupWidgetTheme(colorEnabled);
        assertEquals(windows.dialog(), popup.resolveStyle(WidgetThemeKeys.MODAL_BODY));
        assertEquals(windows.dialogBorder(), popup.resolveStyle(WidgetThemeKeys.MODAL_BORDER));
        assertEquals(windows.dialogTitle(), popup.resolveStyle(WidgetThemeKeys.MODAL_TITLE));
        assertEquals(windows.notifyError(), popup.resolveStyle(WidgetThemeKeys.MODAL_ERROR));
        assertEquals(windows.dialogField(), popup.resolveStyle(WidgetThemeKeys.TEXT_FIELD));
        assertEquals(windows.dialogFieldFocused(), popup.resolveStyle(WidgetThemeKeys.TEXT_FIELD_FOCUSED));
        assertEquals(windows.dialogFieldDisabled(), popup.resolveStyle(WidgetThemeKeys.TEXT_FIELD_DISABLED));
        assertEquals(windows.dialogFieldSelection(), popup.resolveStyle(WidgetThemeKeys.TEXT_FIELD_SELECTION));
        assertNotEquals(app.explorerWidgetTheme(colorEnabled).resolveStyle(WidgetThemeKeys.MODAL_BODY),
            popup.resolveStyle(WidgetThemeKeys.MODAL_BODY));
        for (var role : List.of(WidgetThemeKeys.MODAL_BODY, WidgetThemeKeys.MODAL_BORDER,
            WidgetThemeKeys.MODAL_TITLE, WidgetThemeKeys.BUTTON, WidgetThemeKeys.BUTTON_FOCUSED,
            WidgetThemeKeys.BUTTON_DISABLED, WidgetThemeKeys.TEXT_FIELD, WidgetThemeKeys.TEXT_FIELD_FOCUSED,
            WidgetThemeKeys.TEXT_FIELD_SELECTION, WidgetThemeKeys.TEXT_FIELD_DISABLED,
            WidgetThemeKeys.LIST_ITEM_SELECTED, WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED,
            WidgetThemeKeys.TREE_BRANCH, WidgetThemeKeys.MODAL_ERROR)) {
            assertEquals(popup.resolveStyle(role), app.resolveOverlayWidgetStyle(role, colorEnabled),
                "Help/License/About and file actions share " + role);
            assertEquals(popup.resolveStyle(role), app.resolveFileTreeWidgetStyle(role, colorEnabled),
                "Open and file actions share " + role);
        }
        assertNull(popup.style(WidgetThemeKeys.LIST_ITEM), "Ordinary tree rows inherit the containing popup body");
        assertTrue(popup.resolveStyle(WidgetThemeKeys.TREE_BRANCH).bold());
        for (var role : List.of(WidgetThemeKeys.TEXT_FIELD, WidgetThemeKeys.TEXT_FIELD_FOCUSED,
            WidgetThemeKeys.TEXT_FIELD_DISABLED)) {
            assertEquals(windows.searchBar(), app.resolveSearchWidgetStyle(role),
                "Document search retains its own toolbar surface");
            assertEquals(windows.promptAlert(), app.resolveSaveAsWidgetStyle(role),
                "Inline Save As retains its own prompt surface");
            assertNotEquals(popup.resolveStyle(role), app.resolveSearchWidgetStyle(role));
            assertNotEquals(popup.resolveStyle(role), app.resolveSaveAsWidgetStyle(role));
        }
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void fileActionInputsContrastWithLightBodyAndButtonsAcrossFocusChanges(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        var theme = app.popupWidgetTheme(colorEnabled);
        try (var state = new ExplorerFileDialogView.State(ExplorerFileService.Action.CREATE_FILE,
                directory, directory)) {
            state.field.set("new.txt");
            var view = ExplorerFileDialogView.place(state, new Rect(0, 0, 80, 24));
            var cells = new CellBuffer(24, 80);
            view.paint(new Canvas(cells), theme, theme.resolveStyle(WidgetThemeKeys.MODAL_ERROR));
            var bounds = view.bounds();
            var body = app.styles.windows().dialog();
            assertEquals(body, cells.get(bounds.y() + 2, bounds.x() + 2).style());
            assertEquals(app.styles.windows().dialogFieldFocused(),
                cells.get(bounds.y() + 3, bounds.x() + 8).style());
            assertEquals(app.styles.windows().dialogFieldFocused(),
                cells.get(bounds.y() + 3, bounds.x() + 28).style(), "Empty input padding has the field surface");
            assertNotEquals(body.bg(), cells.get(bounds.y() + 3, bounds.x() + 28).style().bg());
            assertEquals(body, cells.get(bounds.bottomExclusive() - 2, bounds.x() + 3).style());
            assertEquals(CellStyle.DEFAULT, cells.get(0, 0).style(), "The popup paints only its allocation");

            view.handle(KeyStroke.of(KeyStroke.Key.TAB), new WindowPointerCapture(), this);
            var focused = new CellBuffer(24, 80);
            view.paint(new Canvas(focused), theme, theme.resolveStyle(WidgetThemeKeys.MODAL_ERROR));
            assertEquals(theme.resolveStyle(WidgetThemeKeys.BUTTON_FOCUSED),
                focused.get(bounds.bottomExclusive() - 2, bounds.x() + 3).style());
            assertEquals(app.styles.windows().dialogField(), focused.get(bounds.y() + 3, bounds.x() + 8).style());
            assertEquals(app.styles.windows().dialogField(), focused.get(bounds.y() + 3, bounds.x() + 28).style());
        }
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void renameSelectionAndBusyFieldRemainReadableInsideTheLightPopup(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        var theme = app.popupWidgetTheme(colorEnabled);
        try (var state = new ExplorerFileDialogView.State(ExplorerFileService.Action.RENAME,
                directory.resolve("old.txt"), directory)) {
            var view = ExplorerFileDialogView.place(state, new Rect(0, 0, 80, 24));
            var bounds = view.bounds();
            var cells = new CellBuffer(24, 80);
            view.paint(new Canvas(cells), theme, theme.resolveStyle(WidgetThemeKeys.MODAL_ERROR));
            var selected = cells.get(bounds.y() + 3, bounds.x() + 8).style();
            var padding = cells.get(bounds.y() + 3, bounds.x() + 28).style();
            assertEquals(app.styles.windows().dialogFieldSelection(), selected);
            assertEquals(app.styles.windows().dialogFieldFocused(), padding);
            assertNotEquals(selected.fg(), selected.bg());
            assertNotEquals(padding.fg(), padding.bg());
            assertNotEquals(padding.bg(), selected.bg(), "Selected text contrasts with the dark input");
            assertNotEquals(padding.underline(), selected.underline(),
                "Selection remains distinct when color capabilities are absent");

            state.busy = true;
            var busy = ExplorerFileDialogView.place(state, new Rect(0, 0, 80, 24));
            var disabled = new CellBuffer(24, 80);
            busy.paint(new Canvas(disabled), theme, theme.resolveStyle(WidgetThemeKeys.MODAL_ERROR));
            var field = app.styles.windows().dialogFieldDisabled();
            assertEquals(field, disabled.get(bounds.y() + 3, bounds.x() + 8).style(),
                "A busy field replaces the selection with its disabled style");
            assertEquals(field, disabled.get(bounds.y() + 3, bounds.x() + 28).style());
            assertEquals(padding.bg(), field.bg(), "Disabled inputs retain the dark field surface");
            assertNotEquals(field.fg(), field.bg());
            assertEquals(app.styles.windows().dialog(), disabled.get(bounds.y() + 2, bounds.x() + 2).style());
        }
    }

    private static TerminalEditorApp app(UiStyles.Theme theme) {
        var app = new TerminalEditorApp();
        app.theme = theme;
        app.styles = UiStyles.forTheme(theme);
        return app;
    }
}

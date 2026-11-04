package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Clicks displayed controls through the real window, not just the command which opens an overlay. */
class WindowModalPointerIntegrationTest {
    private static final List<String> ORIGINAL = List.of("# Original", "", "retained source and preview");
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void pickerDoubleClickOpensTheDisplayedFileForEveryPresentation(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("double", kind)) {
            var owner = window.selected();
            Path target = Files.writeString(window.document.getParent().resolve("target.txt"), "opened by double click");
            openPicker(window);
            click(window, "target.txt");
            assertEquals(target, window.tree.state().selectedId().path());
            click(window, "target.txt");
            assertOpened(window, target, "opened by double click", owner);
        }
    }

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void pickerOkUsesTheMouseSelectedRowForEveryPresentation(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("ok", kind)) {
            var owner = window.selected();
            Path target = Files.writeString(window.document.getParent().resolve("target.txt"), "opened by OK");
            openPicker(window);
            click(window, "target.txt");
            assertEquals(target, window.tree.state().selectedId().path());
            clickBottom(window, "OK");
            assertOpened(window, target, "opened by OK", owner);
        }
    }

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void pickerLocationAndCancelBelongToTheVisibleDialog(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("location", kind)) {
            var owner = window.selected();
            openPicker(window);
            clickLocation(window);
            assertEquals(OpenFileInputRouter.Focus.LOCATION, owner.uiState().openFileFocusState.focus());
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new TextInput("unsubmitted draft 中.txt"));
            assertEquals("unsubmitted draft 中.txt", owner.uiState().openFileFocusState.location().text());
            assertTrue(window.controller.activeSurface().editableField());
            clickBottom(window, "Cancel");
            assertFalse(owner.uiState().openFileMode);
            assertSame(owner, window.selected());
            assertOriginal(window, kind);
        }
    }

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void compactPickerCancelsTheOldFieldCaptureAndItsVisibleCloseButtonWorks(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("compact-picker", kind)) {
            var owner = window.selected();
            openPicker(window);
            Point field = location(window);
            window.pointer(PointerEvent.Kind.PRESS, field.y(), field.x());
            window.pointer(PointerEvent.Kind.DRAG, field.y(), field.x() + 8);
            var location = owner.uiState().openFileFocusState.location();
            assertTrue(location.mouseSelecting());
            String text = location.text();
            window.resize(6, 20);
            assertFalse(location.mouseSelecting(), "resize retires the old placed field capture");
            window.pointer(PointerEvent.Kind.RELEASE, field.y(), field.x() + 8);
            assertEquals(text, location.text());
            assertTrue(owner.uiState().openFileMode);
            clickBottom(window, "Esc: close");
            assertFalse(owner.uiState().openFileMode);
            assertSame(owner, window.selected());
            window.resize(24, 80);
            openPicker(window);
            clickBottom(window, "Cancel");
            assertFalse(owner.uiState().openFileMode);
            assertOriginal(window, kind);
        }
    }

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void helpWheelAndCloseFollowThePaintedFrameAcrossResize(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("help", kind)) {
            var owner = window.selected();
            window.command(TuinkCommand.HELP_KEY_HINTS);
            assertTrue(owner.uiState().modalState.isHelpHints());
            int before = window.host.helpScrollState().firstVisibleIndex();
            window.event(new PointerEvent(window.height / 2, window.width / 2,
                PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of()));
            assertTrue(window.host.helpScrollState().firstVisibleIndex() > before);
            int scrolled = window.host.helpScrollState().firstVisibleIndex();
            window.resize(6, 20);
            assertTrue(owner.uiState().modalState.isHelpHints());
            window.resize(24, 80);
            assertEquals(scrolled, window.host.helpScrollState().firstVisibleIndex());
            clickBottom(window, "Close");
            assertTrue(owner.uiState().modalState.isNone());
            window.command(TuinkCommand.HELP_KEY_HINTS);
            window.resize(6, 20);
            clickBottom(window, "Esc: close");
            assertTrue(owner.uiState().modalState.isNone());
            assertSame(owner, window.selected());
            assertOriginal(window, kind);
        }
    }

    @ParameterizedTest
    @MethodSource("encodingModes")
    void chooseFileUsesTheSamePermissionForItsButtonAndKeyboard(ActiveSurface.Kind kind, boolean mouse) throws Exception {
        try (var window = window("encoding-" + mouse, kind)) {
            var owner = window.selected();
            Path target = window.document.getParent().resolve("legacy.txt");
            Files.write(target, "Привет".getBytes(DocumentEncoding.WINDOWS_1251.charset()));
            window.command(TuinkCommand.DOCUMENT_OPEN_ENCODING);
            var draft = owner.uiState().formatDialog;
            assertNotNull(draft);
            int index = draft.encodings.indexOf(DocumentEncoding.WINDOWS_1251);
            for (int step = 0; step < index; step++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            if (mouse) clickBottom(window, "Choose file");
            else {
                window.event(KeyStroke.of(KeyStroke.Key.TAB));
                assertEquals(DocumentFormatDialogView.Target.APPLY, draft.focus.focused().orElseThrow());
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }
            assertTrue(owner.uiState().openFileMode, "Choose file must be enabled for every presentation");
            assertEquals(DocumentOpenMode.WINDOWS_1251, owner.uiState().pickerOpenMode);
            window.await(() -> !window.tree.loading());
            clickLocation(window);
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new TextInput("legacy.txt"));
            clickBottom(window, "OK");
            assertOpened(window, target, "Привет", owner);
        }
    }

    private static Stream<Arguments> encodingModes() {
        return Stream.of(ActiveSurface.Kind.values())
            .flatMap(kind -> Stream.of(Arguments.of(kind, true), Arguments.of(kind, false)));
    }

    private EditorWindowFixture window(String name, ActiveSurface.Kind kind) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(name + "-" + kind), "original.md", ORIGINAL,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
        if (kind != ActiveSurface.Kind.SOURCE) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab preview
                    && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
                assertTrue(window.tabs.isEmpty());
            }
        }
        return window;
    }

    private static void openPicker(EditorWindowFixture window) throws InterruptedException {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        assertTrue(window.selected().uiState().openFileMode);
    }

    private static void assertOpened(EditorWindowFixture window, Path target, String text, WindowTab owner) throws Exception {
        window.await(() -> !window.documentIo.busy() && window.selected() instanceof TerminalEditorApp.EditorTab
            && target.equals(JvmDocumentStorage.pathOf(window.active().state().getCurrentResource())));
        assertEquals(List.of(text), window.active().documentSession().document().snapshot().lines());
        assertFalse(owner.uiState().openFileMode);
        assertNotNull(window.context.presentation(owner.presentationId()));
        assertEquals(ORIGINAL, Files.readAllLines(window.document));
    }

    private static void assertOriginal(EditorWindowFixture window, ActiveSurface.Kind kind) throws Exception {
        assertEquals(ORIGINAL, Files.readAllLines(window.document));
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) assertTrue(window.tabs.isEmpty());
        else {
            assertEquals(ORIGINAL, window.active().documentSession().document().snapshot().lines());
            assertFalse(window.active().state().isDirty());
        }
    }

    private static Point location(EditorWindowFixture window) {
        Point label = text(window, "Path: ", false);
        return new Point(label.x() + "Path: ".length() + 1, label.y());
    }

    private static void clickLocation(EditorWindowFixture window) {
        Point point = location(window);
        click(window, point);
    }

    private static void click(EditorWindowFixture window, String label) {
        click(window, text(window, label, false));
    }

    private static void clickBottom(EditorWindowFixture window, String label) {
        click(window, text(window, label, true));
    }

    private static void click(EditorWindowFixture window, Point point) {
        window.pointer(PointerEvent.Kind.PRESS, point.y(), point.x());
        window.pointer(PointerEvent.Kind.RELEASE, point.y(), point.x());
    }

    private static Point text(EditorWindowFixture window, String value, boolean bottomUp) {
        for (int index = 0; index < window.height; index++) {
            int row = bottomUp ? window.height - 1 - index : index;
            int column = window.screenRow(row).indexOf(value);
            if (column >= 0) return new Point(column + Math.min(1, value.length() - 1), row);
        }
        fail("Missing displayed control: " + value);
        return null;
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}

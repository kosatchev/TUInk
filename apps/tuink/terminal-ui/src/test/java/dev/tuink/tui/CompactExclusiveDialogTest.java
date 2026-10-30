package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production event and frame pipeline, including resize and physical close controls. */
class CompactExclusiveDialogTest {
    private static final List<TuinkCommand> OWNERS = List.of(TuinkCommand.DOCUMENT_OPEN,
        TuinkCommand.HELP_KEY_HINTS, TuinkCommand.HELP_LICENSES, TuinkCommand.HELP_AUTHOR);

    @TempDir
    Path directory;

    @Test
    void tinyExclusiveOwnersPaintFeedbackAndKeepKeyboardFocusUntilExplicitlyClosed() throws Exception {
        for (TuinkCommand owner : OWNERS) {
            for (int[] size : List.of(new int[] {7, 80}, new int[] {24, 23},
                new int[] {2, 80}, new int[] {24, 1}, new int[] {2, 1})) {
                try (EditorWindowFixture fixture = fixture(owner + "-" + size[0] + "-" + size[1])) {
                    fixture.resize(size[0], size[1]);
                    List<String> before = screen(fixture);
                    fixture.command(owner);
                    assertNotEquals(before, screen(fixture), "exclusive owner must be visible: " + owner);
                    if (size[0] >= 5 && size[1] >= 23) {
                        assertTrue(screen(fixture).stream().anyMatch(row -> row.contains("Enlarge terminal")));
                        assertTrue(screen(fixture).stream().anyMatch(row -> row.contains("Minimum: 24x8")));
                    }
                    fixture.event(new TextInput("blocked"));
                    fixture.event(KeyStroke.of(KeyStroke.Key.TAB));
                    fixture.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
                    assertEquals("alpha", fixture.active().tabSession().model().getLine(0));
                    fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                    fixture.event(new TextInput("X"));
                    assertEquals("Xalpha", fixture.active().tabSession().model().getLine(0));
                }
            }
        }
    }

    @Test
    void focusedCloseAcceptsEnterSpaceAndClicksOnTheActualPaintedFooterAndFrame() throws Exception {
        for (TuinkCommand owner : OWNERS) {
            for (TuiInputEvent close : List.of(KeyStroke.of(KeyStroke.Key.ENTER),
                KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "))) {
                try (EditorWindowFixture fixture = fixture(owner + "-" + close)) {
                    fixture.resize(7, 80);
                    fixture.command(owner);
                    fixture.event(close);
                    fixture.event(new TextInput("X"));
                    assertEquals("Xalpha", fixture.active().tabSession().model().getLine(0));
                }
            }
            for (boolean header : List.of(false, true)) {
                try (EditorWindowFixture fixture = fixture(owner + "-pointer-" + header)) {
                    fixture.resize(24, 23);
                    fixture.command(owner);
                    var compact = CompactDialogView.place(new Rect(0, 0, fixture.width, fixture.height), "");
                    Point point = header
                        ? new Point(compact.bounds().rightExclusive() - 3, compact.bounds().y())
                        : new Point(compact.closeButton().cursorCol(), compact.closeButton().row());
                    assertEquals(header ? 'X' : 'E', fixture.screenRow(point.y()).charAt(point.x()));
                    fixture.pointer(PointerEvent.Kind.PRESS, point.y(), point.x());
                    fixture.event(new TextInput("X"));
                    assertEquals("Xalpha", fixture.active().tabSession().model().getLine(0));
                }
            }
        }
    }

    @Test
    void helpResizeKeepsItsReadingPositionAndCompactFocus() throws Exception {
        try (EditorWindowFixture fixture = fixture("help-resize")) {
            fixture.command(TuinkCommand.HELP_KEY_HINTS);
            fixture.event(KeyStroke.of(KeyStroke.Key.PAGE_DOWN));
            int before = fixture.host.helpScrollState().firstVisibleIndex();
            assertTrue(before > 0);
            fixture.resize(7, 80);
            assertEquals(before, fixture.host.helpScrollState().firstVisibleIndex());
            fixture.event(KeyStroke.of(KeyStroke.Key.TAB));
            fixture.resize(24, 80);
            assertEquals(before, fixture.host.helpScrollState().firstVisibleIndex());
            fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));
            fixture.event(new TextInput("X"));
            assertEquals("Xalpha", fixture.active().tabSession().model().getLine(0));
        }
    }

    @Test
    void filePickerResizeEndsCaptureAndPreservesItsSelectionAndViewport() throws Exception {
        Path root = directory.resolve("tree-resize");
        Files.createDirectories(root);
        for (int index = 0; index < 40; index++) {
            Files.writeString(root.resolve("file-%02d.txt".formatted(index)), "file");
        }
        try (EditorWindowFixture fixture = new EditorWindowFixture(root, false, List.of("alpha"))) {
            fixture.command(TuinkCommand.DOCUMENT_OPEN);
            fixture.await(() -> !fixture.tree.loading());
            fixture.event(KeyStroke.of(KeyStroke.Key.PAGE_DOWN));
            var selected = fixture.tree.state().selectedId();
            Point thumb = fixture.verticalThumb(false);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertTrue(fixture.tree.state().widgetState().drag() != null);
            int first = fixture.tree.state().widgetState().firstVisibleIndex();
            fixture.resize(7, 80);
            assertNull(fixture.tree.state().widgetState().drag());
            assertEquals(selected, fixture.tree.state().selectedId());
            assertEquals(first, fixture.tree.state().widgetState().firstVisibleIndex());
            fixture.pointer(PointerEvent.Kind.RELEASE, thumb.y(), thumb.x());
            fixture.resize(24, 80);
            assertEquals(selected, fixture.tree.state().selectedId());
            assertEquals(first, fixture.tree.state().widgetState().firstVisibleIndex());
            assertFalse(fixture.editorView.isMouseSelecting());
            fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            fixture.event(new TextInput("X"));
            assertEquals("Xalpha", fixture.active().tabSession().model().getLine(0));
        }
    }

    private EditorWindowFixture fixture(String name) throws Exception {
        return new EditorWindowFixture(directory.resolve(Integer.toHexString(name.hashCode())), false, List.of("alpha"));
    }

    @Test
    void reopeningAfterCompactCloseStartsAtTheTreeRatherThanItsPreviousOkOrCancel() throws Exception {
        for (int tabs : List.of(1, 2)) {
            try (EditorWindowFixture fixture = fixture("reopen-" + tabs)) {
                fixture.command(TuinkCommand.DOCUMENT_OPEN);
                fixture.await(() -> !fixture.tree.loading());
                Path parent = fixture.tree.root().getParent();
                for (int index = 0; index < tabs; index++) {
                    fixture.event(KeyStroke.of(KeyStroke.Key.TAB));
                }
                fixture.resize(7, 80);
                fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                fixture.resize(24, 80);
                fixture.command(TuinkCommand.DOCUMENT_OPEN);
                fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertEquals(parent, fixture.tree.root(), "a new picker must focus its selected parent row");
                fixture.event(new TextInput("blocked"));
                assertEquals("alpha", fixture.active().tabSession().model().getLine(0));
                fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            }
        }
    }

    private static List<String> screen(EditorWindowFixture fixture) {
        List<String> result = new ArrayList<>();
        for (int row = 0; row < fixture.height; row++) {
            result.add(fixture.screenRow(row));
        }
        return result;
    }
}

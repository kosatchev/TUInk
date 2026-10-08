package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TextFieldInteractiveTest {
    private static final Rect ROOT_CLIP = new Rect(0, 0, 80, 24);
    private static final FocusContext FOCUSED = new FocusContext(
        true,
        true,
        FocusSpace.root(ROOT_CLIP)
    );
    private static final FocusContext UNFOCUSED = new FocusContext(
        false,
        true,
        FocusSpace.root(ROOT_CLIP)
    );
    private static final FocusContext DISABLED = new FocusContext(
        false,
        false,
        FocusSpace.root(ROOT_CLIP)
    );

    @Test
    void bothSpaceRepresentationsInsertAtTheCaretAndReplaceTheLocalSelection() {
        for (TuiInputEvent space : List.of(KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "))) {
            TextField field = new TextField();
            field.set("A👩‍💻B");
            field.moveLeft();

            assertEquals(TextField.Action.VALUE_CHANGED, handle(field, space, FOCUSED).output().orElseThrow());
            assertEquals("A👩‍💻 B", field.text());
            assertEquals("A👩‍💻 ".length(), field.cursor());

            field.selectAll();
            assertEquals(TextField.Action.VALUE_CHANGED, handle(field, space, FOCUSED).output().orElseThrow());
            assertEquals(" ", field.text());
            assertEquals(1, field.cursor());
            assertFalse(field.hasSelection());
        }
    }

    @Test
    void spaceRespectsFocusAndLeavesModifiedShortcutsForTheApplication() {
        TextField field = new TextField();
        field.set("keep");
        field.selectAll();
        for (FocusContext context : List.of(UNFOCUSED, DISABLED)) {
            for (TuiInputEvent space : List.of(KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "))) {
                assertFalse(handle(field, space, context).consumed());
            }
        }
        for (KeyStroke.Modifier modifier : KeyStroke.Modifier.values()) {
            assertFalse(handle(field, KeyStroke.of(KeyStroke.Key.SPACE, modifier), FOCUSED).consumed());
        }
        assertEquals("keep", field.text());
        assertEquals("keep", field.selectedText());
    }

    @Test
    void focusedRawTextAndExactKeysProduceTypedEffects() {
        TextField field = new TextField();

        assertFalse(handle(field, new TextInput("ignored"), UNFOCUSED).consumed());
        assertFalse(handle(field, new TextInput("ignored"), DISABLED).consumed());
        InteractionResult<TextField.Action> inserted = handle(
            field,
            new TextInput("A😀e\u0301"),
            FOCUSED
        );
        assertEquals(TextField.Action.VALUE_CHANGED, inserted.output().orElseThrow());
        assertEquals("A😀e\u0301", field.text());

        int cursorBeforeModified = field.cursor();
        assertTrue(handle(
            field,
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT),
            FOCUSED
        ).consumed());
        assertEquals("A😀".length(), field.cursor());
        assertEquals("e\u0301", field.selectedText());
        assertTrue(field.cursor() < cursorBeforeModified);
        assertFalse(handle(field, KeyStroke.of(KeyStroke.Key.TAB), FOCUSED).consumed());
        assertFalse(handle(field, KeyStroke.of(KeyStroke.Key.ESCAPE), FOCUSED).consumed());

        InteractionResult<TextField.Action> submitted = handle(
            field,
            KeyStroke.of(KeyStroke.Key.ENTER),
            FOCUSED
        );
        assertEquals(TextField.Action.SUBMITTED, submitted.output().orElseThrow());
        assertEquals("A😀e\u0301", field.text());
    }

    @Test
    void boundaryArrowsEditingAndAbsoluteNavigationStayInTheField() {
        TextField field = new TextField();
        field.set("e\u0301中");
        field.moveHome();

        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.ARROW_LEFT), FOCUSED).consumed());
        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.BACKSPACE), FOCUSED).consumed());
        assertEquals("e\u0301中", field.text());

        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.ARROW_RIGHT), FOCUSED).consumed());
        assertEquals("e\u0301".length(), field.cursor());
        assertEquals(TextField.Action.VALUE_CHANGED, handle(
            field,
            KeyStroke.of(KeyStroke.Key.DELETE),
            FOCUSED
        ).output().orElseThrow());
        assertEquals(TextField.Action.VALUE_CHANGED, handle(
            field,
            new TextInput("中"),
            FOCUSED
        ).output().orElseThrow());

        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.END), FOCUSED).consumed());
        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.ARROW_RIGHT), FOCUSED).consumed());
        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.DELETE), FOCUSED).consumed());
        assertTrue(handle(field, KeyStroke.of(KeyStroke.Key.HOME), FOCUSED).consumed());
        assertEquals(0, field.cursor());
    }

    @Test
    void completePasteIsSingleLineAndTruncatedPasteIsObservableWithoutMutation() {
        TextField field = new TextField();
        field.set("prefix:");

        InteractionResult<TextField.Action> complete = handle(
            field,
            new PasteEvent("first\r\n\tsecond\u0000 😀\n"),
            FOCUSED
        );
        assertEquals(TextField.Action.VALUE_CHANGED, complete.output().orElseThrow());
        assertEquals("prefix:first second 😀", field.text());

        String beforeTruncated = field.text();
        InteractionResult<TextField.Action> truncated = handle(
            field,
            new PasteEvent("partial", true),
            FOCUSED
        );
        assertEquals(TextField.Action.PASTE_REJECTED, truncated.output().orElseThrow());
        assertEquals(beforeTruncated, field.text());

        field.clear();
        InteractionResult<TextField.Action> separatorsOnly = handle(
            field,
            new PasteEvent("\r\n\t\u0000"),
            FOCUSED
        );
        assertTrue(separatorsOnly.consumed());
        assertTrue(separatorsOnly.output().isEmpty());
        assertTrue(field.isEmpty());
    }

    @Test
    void leftPressUsesMappedVisualCellsAndRequestsFocus() {
        TextField field = new TextField();
        field.set("a中b");
        TextField.Placement placement = field.layout(new Rect(2, 1, 5, 1));
        FocusContext unfocused = new FocusContext(
            false,
            true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 20, 5))
        );

        InteractionResult<TextField.Action> pressed = placement.handle(
            pointer(6, 14, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            unfocused
        );

        assertTrue(pressed.consumed());
        assertEquals(FocusRequest.SELF, pressed.focusRequest());
        assertTrue(pressed.output().isEmpty());
        assertEquals(1, field.cursor(), "tie inside a wide grapheme uses its left boundary");
        assertFalse(placement.handle(
            pointer(6, 14, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT),
            unfocused
        ).consumed());
        assertFalse(placement.handle(
            pointer(6, 20, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            unfocused
        ).consumed());
    }

    @Test
    void layoutPaintsAGraphemeSafeCursorViewportAndRestoresCanvasState() {
        TextField field = new TextField();
        field.set("ab中d");
        TextField.Placement placement = field.layout(new Rect(2, 1, 4, 1));
        CellStyle focusedStyle = CellStyle.fg(CellColor.BRIGHT_GREEN);
        AtomicReference<WidgetStyleRole> selectedRole = new AtomicReference<>();
        Theme theme = role -> {
            selectedRole.set(role);
            return focusedStyle;
        };
        CellBuffer buffer = filled(3, 9);
        Canvas canvas = new Canvas(buffer);

        placement.paint(canvas, theme, WidgetState.FOCUSED);

        assertEquals(WidgetThemeKeys.TEXT_FIELD_FOCUSED, selectedRole.get());
        assertEquals("中", buffer.get(1, 2).grapheme());
        assertTrue(buffer.get(1, 3).continuation());
        assertEquals("d", buffer.get(1, 4).grapheme());
        assertEquals(" ", buffer.get(1, 5).grapheme());
        assertEquals(focusedStyle, buffer.get(1, 5).style());
        assertEquals(".", buffer.get(1, 1).grapheme());
        assertEquals(".", buffer.get(1, 6).grapheme());
        assertTrue(canvas.put(0, 0, "z", focusedStyle));
        assertEquals("z", buffer.get(0, 0).grapheme());

        PlacedCursorSource cursorSource = placement;
        assertEquals(new Point(5, 1), cursorSource.cursorPosition(FOCUSED).orElseThrow());
        assertTrue(cursorSource.cursorPosition(UNFOCUSED).isEmpty());
        assertTrue(cursorSource.cursorPosition(DISABLED).isEmpty());
        FocusContext clipped = new FocusContext(
            true,
            true,
            FocusSpace.root(new Rect(0, 0, 5, 3))
        );
        assertTrue(cursorSource.cursorPosition(clipped).isEmpty());
    }

    @Test
    void strictFieldClipDoesNotBreakAnExistingWidePairAtItsEdge() {
        TextField field = new TextField();
        field.set("x");
        CellBuffer buffer = filled(1, 5);
        CellStyle wideStyle = CellStyle.fg(CellColor.BRIGHT_CYAN);
        buffer.set(0, 1, "中", wideStyle);
        Canvas canvas = new Canvas(buffer);

        field.layout(new Rect(2, 0, 2, 1)).paint(
            canvas,
            Theme.fixed(CellStyle.DEFAULT),
            WidgetState.NORMAL
        );

        assertEquals("中", buffer.get(0, 1).grapheme());
        assertTrue(buffer.get(0, 2).continuation());
        assertEquals(wideStyle, buffer.get(0, 1).style());
        assertTrue(canvas.put(4, 0, "z", CellStyle.DEFAULT));
    }

    @Test
    void translatedCanvasHonorsItsNarrowParentClipAndRestoresState() {
        TextField field = new TextField();
        field.set("abcdef");
        TextField.Placement placement = field.layout(new Rect(0, 1, 6, 1));
        CellStyle fieldStyle = CellStyle.fg(CellColor.BRIGHT_GREEN);
        CellBuffer buffer = filled(4, 10);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(new Rect(1, 1, 3, 1)));

        placement.paint(canvas, Theme.fixed(fieldStyle), WidgetState.FOCUSED);

        assertEquals(".", buffer.get(2, 2).grapheme());
        assertEquals("c", buffer.get(2, 3).grapheme());
        assertEquals("d", buffer.get(2, 4).grapheme());
        assertEquals("e", buffer.get(2, 5).grapheme());
        assertEquals(".", buffer.get(2, 6).grapheme());
        assertEquals(".", buffer.get(1, 3).grapheme());
        assertEquals(fieldStyle, buffer.get(2, 3).style());
        assertFalse(canvas.put(0, 1, "x", fieldStyle));
        assertTrue(canvas.put(1, 1, "z", fieldStyle));
        assertEquals("z", buffer.get(2, 3).grapheme());

        FocusContext narrowed = new FocusContext(
            true,
            true,
            new FocusSpace(new Point(2, 1), new Rect(1, 1, 3, 1))
        );
        assertTrue(placement.cursorPosition(narrowed).isEmpty());
    }

    @Test
    void deterministicPagedViewportLetsCaretMoveBeforeChangingPage() {
        TextField field = new TextField();
        field.set("abcdefghij");
        Rect bounds = new Rect(2, 1, 5, 1);

        assertEquals(
            new Point(6, 1),
            field.layout(bounds).cursorPosition(FOCUSED).orElseThrow()
        );
        field.moveLeft();
        assertEquals(
            new Point(5, 1),
            field.layout(bounds).cursorPosition(FOCUSED).orElseThrow()
        );
        field.moveLeft();
        field.moveLeft();
        field.moveLeft();
        assertEquals(
            new Point(2, 1),
            field.layout(bounds).cursorPosition(FOCUSED).orElseThrow()
        );
        field.moveLeft();
        assertEquals(
            new Point(3, 1),
            field.layout(bounds).cursorPosition(FOCUSED).orElseThrow(),
            "leaving the tail window starts a stable page without retained state"
        );
        field.moveLeft();
        assertEquals(
            new Point(2, 1),
            field.layout(bounds).cursorPosition(FOCUSED).orElseThrow(),
            "caret movement inside the page must not shift the viewport"
        );
    }

    @Test
    void pointerUsesScrolledViewportAndWideContinuationTieGoesLeft() {
        TextField field = new TextField();
        field.set("012345中789");
        field.moveLeft();
        field.moveLeft();
        field.moveLeft();
        field.moveLeft();
        TextField.Placement placement = field.layout(new Rect(2, 1, 5, 1));
        CellBuffer buffer = filled(3, 10);
        placement.paint(
            new Canvas(buffer),
            Theme.fixed(CellStyle.DEFAULT),
            WidgetState.FOCUSED
        );
        FocusContext context = new FocusContext(
            false,
            true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 20, 5))
        );

        InteractionResult<TextField.Action> result = placement.handle(
            pointer(6, 15, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            context
        );

        assertEquals("4", buffer.get(1, 2).grapheme());
        assertEquals("5", buffer.get(1, 3).grapheme());
        assertEquals("中", buffer.get(1, 4).grapheme());
        assertTrue(buffer.get(1, 5).continuation());
        assertEquals("7", buffer.get(1, 6).grapheme());
        assertEquals(6, field.cursor());
        assertEquals(FocusRequest.SELF, result.focusRequest());
    }

    @Test
    void placementIsAnExactFrameSnapshotAndThemeRolesFollowVisualState() {
        TextField field = new TextField();
        field.set("old");
        TextField.Placement oldPlacement = field.layout(new Rect(1, 0, 4, 1));
        field.set("new");
        AtomicReference<WidgetStyleRole> role = new AtomicReference<>();
        Theme theme = key -> {
            role.set(key);
            return CellStyle.DEFAULT;
        };
        CellBuffer oldFrame = filled(1, 6);

        oldPlacement.paint(new Canvas(oldFrame), theme, WidgetState.DISABLED);

        assertEquals(WidgetThemeKeys.TEXT_FIELD_DISABLED, role.get());
        assertEquals("o", oldFrame.get(0, 1).grapheme());
        assertEquals("l", oldFrame.get(0, 2).grapheme());
        assertEquals("d", oldFrame.get(0, 3).grapheme());
        CellBuffer newFrame = filled(1, 6);
        field.layout(new Rect(1, 0, 4, 1)).paint(new Canvas(newFrame), theme, WidgetState.NORMAL);
        assertEquals(WidgetThemeKeys.TEXT_FIELD, role.get());
        assertEquals("n", newFrame.get(0, 1).grapheme());
    }

    @Test
    void placementStaysStaleAfterHandledCursorAndValueChanges() {
        TextField field = new TextField();
        field.set("abc");
        Rect bounds = new Rect(1, 0, 6, 1);
        TextField.Placement oldPlacement = field.layout(bounds);

        assertTrue(oldPlacement.handle(
            KeyStroke.of(KeyStroke.Key.HOME),
            FOCUSED
        ).consumed());
        assertEquals(0, field.cursor());
        assertEquals(new Point(4, 0), oldPlacement.cursorPosition(FOCUSED).orElseThrow());
        assertEquals(
            new Point(1, 0),
            field.layout(bounds).cursorPosition(FOCUSED).orElseThrow()
        );
        assertEquals(TextField.Action.VALUE_CHANGED, oldPlacement.handle(
            new TextInput("z"),
            FOCUSED
        ).output().orElseThrow());
        assertEquals("zabc", field.text());

        CellBuffer oldFrame = filled(1, 8);
        CellBuffer freshFrame = filled(1, 8);
        oldPlacement.paint(
            new Canvas(oldFrame),
            Theme.fixed(CellStyle.DEFAULT),
            WidgetState.FOCUSED
        );
        TextField.Placement freshPlacement = field.layout(bounds);
        freshPlacement.paint(
            new Canvas(freshFrame),
            Theme.fixed(CellStyle.DEFAULT),
            WidgetState.FOCUSED
        );

        assertEquals("a", oldFrame.get(0, 1).grapheme());
        assertEquals("z", freshFrame.get(0, 1).grapheme());
        assertEquals(new Point(4, 0), oldPlacement.cursorPosition(FOCUSED).orElseThrow());
        assertEquals(new Point(2, 0), freshPlacement.cursorPosition(FOCUSED).orElseThrow());
    }

    @Test
    void measureAndTinyPlacementsReserveACaretCellWithoutEscapingBounds() {
        TextField field = new TextField();
        assertEquals(new Size(1, 1), field.measure(new Size(20, 3)));
        field.set("中");
        assertEquals(new Size(3, 1), field.measure(new Size(20, 3)));
        assertEquals(new Size(2, 0), field.measure(new Size(2, 0)));

        TextField.Placement empty = field.layout(new Rect(2, 1, 0, 1));
        CellBuffer frame = filled(3, 6);
        empty.paint(new Canvas(frame), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        assertTrue(empty.cursorPosition(FOCUSED).isEmpty());
        assertFalse(empty.handle(
            pointer(1, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            FOCUSED
        ).consumed());

        TextField.Placement oneCell = field.layout(new Rect(2, 1, 1, 1));
        assertEquals(new Point(2, 1), oneCell.cursorPosition(FOCUSED).orElseThrow());
        CellStyle oneCellStyle = CellStyle.fg(CellColor.BRIGHT_GREEN);
        oneCell.paint(new Canvas(frame), Theme.fixed(oneCellStyle), WidgetState.FOCUSED);
        assertEquals(" ", frame.get(1, 2).grapheme());
        assertEquals(oneCellStyle, frame.get(1, 2).style());
        assertFalse(frame.get(1, 2).continuation());

        TextField.Placement zeroHeight = field.layout(new Rect(2, 1, 3, 0));
        long signature1 = frame.rowSignature1(1);
        long signature2 = frame.rowSignature2(1);
        zeroHeight.paint(new Canvas(frame), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        assertTrue(zeroHeight.cursorPosition(FOCUSED).isEmpty());
        assertEquals(signature1, frame.rowSignature1(1));
        assertEquals(signature2, frame.rowSignature2(1));
    }

    @Test
    void trustedCompatibilityMutatorsKeepTabsAndControlsUnchanged() {
        TextField field = new TextField();

        field.set("a\tb\u0000");
        field.insert("\u001B");

        assertEquals("a\tb\u0000\u001B", field.text());
        assertEquals(field.length(), field.cursor());
    }

    @Test
    void trustedTabsAndControlsPaintSafelyWithTextRelativeTabStops() {
        TextField field = new TextField();
        field.set("a\tb\u0000\u001B");
        TextField.Placement placement = field.layout(new Rect(1, 0, 9, 1));
        CellStyle style = CellStyle.fg(CellColor.BRIGHT_CYAN);
        CellBuffer buffer = filled(1, 12);

        placement.paint(new Canvas(buffer), Theme.fixed(style), WidgetState.NORMAL);

        assertEquals("a", buffer.get(0, 1).grapheme());
        assertEquals(" ", buffer.get(0, 2).grapheme());
        assertEquals(" ", buffer.get(0, 3).grapheme());
        assertEquals(" ", buffer.get(0, 4).grapheme());
        assertEquals("b", buffer.get(0, 5).grapheme());
        assertEquals("␀", buffer.get(0, 6).grapheme());
        assertEquals("␛", buffer.get(0, 7).grapheme());
        assertEquals(" ", buffer.get(0, 8).grapheme());
        assertEquals(style, buffer.get(0, 2).style());
        assertEquals(".", buffer.get(0, 0).grapheme());
        assertEquals(".", buffer.get(0, 10).grapheme());
    }

    private static InteractionResult<TextField.Action> handle(
        TextField field,
        TuiInputEvent event,
        FocusContext context
    ) {
        return field.layout(new Rect(0, 0, 20, 1)).handle(event, context);
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private static CellBuffer filled(int rows, int columns) {
        CellBuffer buffer = new CellBuffer(rows, columns);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }
}

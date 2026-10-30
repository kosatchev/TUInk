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
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class ButtonTest {
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_CYAN);

    @Test
    void canvasPainterDrawsCompleteUnicodeLabelWithMatchingHitGeometry() {
        Button button = new Button(1, 2, "中e\u0301");
        CellBuffer actual = filled(3, 12);

        button.draw(new Canvas(actual), ACCENT);

        assertEquals("[", actual.get(1, 2).grapheme());
        assertEquals("中", actual.get(1, 3).grapheme());
        assertTrue(actual.get(1, 4).continuation());
        assertEquals("e\u0301", actual.get(1, 5).grapheme());
        assertEquals("]", actual.get(1, 6).grapheme());
        for (int col = 2; col <= 6; col++) {
            assertEquals(ACCENT, actual.get(1, col).style());
        }
        assertEquals(".", actual.get(1, 1).grapheme());
        assertEquals(".", actual.get(1, 7).grapheme());
        assertEquals(5, button.width());
        assertEquals(3, button.cursorCol());
        assertTrue(button.hit(1, 2));
        assertTrue(button.hit(1, 6));
        assertFalse(button.hit(1, 7));
    }

    @Test
    void buttonClipProtectsCrossingWidePairAndCanvasState() {
        CellBuffer buffer = filled(1, 5);
        buffer.set(0, 0, "中", ACCENT);
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);
        Canvas canvas = new Canvas(buffer);

        Button.plain(0, 1, "x").draw(canvas, ACCENT);

        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
        assertEquals("中", buffer.get(0, 0).grapheme());
        assertTrue(buffer.get(0, 1).continuation());
        assertTrue(canvas.put(4, 0, "z", ACCENT));
    }

    @Test
    void translatedDrawAtUsesTextRelativeTabsWithoutChangingHitGeometry() {
        CellBuffer buffer = filled(3, 10);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 1);
        Button button = Button.plain(0, 1, "\tX");

        button.drawAt(canvas, 1, ACCENT);

        for (int col = 2; col < 6; col++) {
            assertEquals(" ", buffer.get(2, col).grapheme());
            assertEquals(ACCENT, buffer.get(2, col).style());
        }
        assertEquals("X", buffer.get(2, 6).grapheme());
        assertTrue(button.hit(0, 1));
        assertTrue(button.hit(0, 5));
        assertFalse(button.hit(1, 1));
        assertEquals(5, button.cursorCol());
        assertTrue(canvas.put(0, 0, "r", ACCENT));
        assertEquals("r", buffer.get(1, 1).grapheme());
    }

    @Test
    void wideLabelAtPhysicalRightEdgeRemainsTransparent() {
        CellBuffer buffer = filled(1, 4);

        Button.plain(0, 3, "中").draw(new Canvas(buffer), ACCENT);

        assertEquals(".", buffer.get(0, 3).grapheme());
    }

    @Test
    void genericLayoutMeasuresPaintsAndHitsInParentCoordinates() {
        CellStyle normal = CellStyle.fg(CellColor.WHITE);
        CellStyle focused = CellStyle.fg(CellColor.BRIGHT_GREEN);
        CellStyle disabled = CellStyle.fg(CellColor.BRIGHT_BLACK);
        Theme theme = key -> {
            if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
                return focused;
            }
            if (WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
                return disabled;
            }
            return normal;
        };
        Button button = new Button("中e\u0301");

        assertEquals(new Size(5, 1), button.measure(new Size(20, 3)));
        assertEquals(new Size(3, 0), button.measure(new Size(3, 0)));
        PlacedWidget placed = button.layout(new Rect(2, 1, 5, 1));
        assertEquals(new Rect(2, 1, 5, 1), placed.bounds());
        assertTrue(placed.hit(1, 2));
        assertTrue(placed.hit(new Point(6, 1)));
        assertFalse(placed.hit(2, 2));
        assertFalse(placed.hit(1, 7));

        CellBuffer buffer = filled(3, 10);
        placed.paint(new Canvas(buffer), theme, WidgetState.FOCUSED);

        assertEquals("[", buffer.get(1, 2).grapheme());
        assertEquals(focused, buffer.get(1, 2).style());
        assertEquals("中", buffer.get(1, 3).grapheme());
        assertTrue(buffer.get(1, 4).continuation());
        assertEquals("e\u0301", buffer.get(1, 5).grapheme());
        assertEquals("]", buffer.get(1, 6).grapheme());
    }

    @Test
    void genericButtonHonorsItsAllocatedClipAndDisabledThemeRole() {
        CellStyle disabled = CellStyle.fg(CellColor.BRIGHT_BLACK);
        CellBuffer buffer = filled(2, 8);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 1);
        PlacedWidget placed = Button.plain("a中b").layout(new Rect(1, 0, 2, 1));

        assertTrue(placed.hit(0, 1));
        assertFalse(placed.hit(1, 2));

        placed.paint(
            canvas,
            key -> WidgetThemeKeys.BUTTON_DISABLED.equals(key) ? disabled : ACCENT,
            WidgetState.DISABLED
        );

        assertEquals("a", buffer.get(1, 2).grapheme());
        assertEquals(disabled, buffer.get(1, 2).style());
        assertEquals(".", buffer.get(1, 3).grapheme());
        assertEquals(".", buffer.get(1, 4).grapheme());
        assertTrue(canvas.put(0, 0, "r", ACCENT));
        assertEquals("r", buffer.get(1, 1).grapheme());
    }

    @Test
    void interactivePlacementActivatesFromFocusedKeyboardAndMappedPointer() {
        PlacedInteractiveWidget<Button.Action> placed = Button.plain("Go").layout(new Rect(2, 1, 2, 1));
        FocusSpace space = new FocusSpace(new Point(5, 3), new Rect(0, 0, 8, 4));
        FocusContext focused = new FocusContext(true, true, space);

        assertEquals(
            Button.Action.ACTIVATED,
            placed.handle(KeyStroke.of(KeyStroke.Key.ENTER), focused).output().orElseThrow()
        );
        assertEquals(
            Button.Action.ACTIVATED,
            placed.handle(KeyStroke.of(KeyStroke.Key.SPACE), focused).output().orElseThrow()
        );
        assertEquals(
            Button.Action.ACTIVATED,
            placed.handle(new TextInput(" "), focused).output().orElseThrow()
        );
        assertEquals(
            Button.Action.ACTIVATED,
            placed.handle(
                new PointerEvent(4, 7, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()),
                focused
            ).output().orElseThrow()
        );
        assertFalse(
            placed.handle(
                new PointerEvent(4, 6, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()),
                focused
            ).consumed()
        );
        assertFalse(
            placed.handle(
                KeyStroke.of(KeyStroke.Key.ENTER),
                new FocusContext(false, true, space)
            ).consumed()
        );
        assertFalse(
            placed.handle(
                KeyStroke.of(KeyStroke.Key.ENTER),
                new FocusContext(false, false, space)
            ).consumed()
        );
    }

    @Test
    void directHandlersRejectEmptyHiddenAndPartialButtonsDespiteEnabledFocusedContext() {
        Button button = Button.plain("中e\u0301Go");
        Rect bounds = new Rect(2, 1, button.width(), 1);
        var placed = button.layout(bounds);
        for (Rect clip : List.of(new Rect(0, 0, bounds.rightExclusive() - 1, 4),
            new Rect(3, 0, 20, 4), new Rect(0, 2, 20, 2), new Rect(0, 0, 0, 0))) {
            FocusSpace space = new FocusSpace(new Point(5, 3), clip);
            FocusContext context = new FocusContext(true, true, space);
            for (TuiInputEvent event : List.of(KeyStroke.of(KeyStroke.Key.ENTER),
                KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "),
                new PointerEvent(4, 8, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()))) {
                assertFalse(placed.handle(event, context).consumed(), clip + ": " + event);
            }
        }
        FocusContext visible = new FocusContext(true, true, FocusSpace.root(new Rect(0, 0, 20, 4)));
        for (Rect empty : List.of(new Rect(2, 1, 0, 1), new Rect(2, 1, button.width(), 0))) {
            assertFalse(button.layout(empty).handle(KeyStroke.of(KeyStroke.Key.ENTER), visible).consumed());
        }
        assertEquals(Button.Action.ACTIVATED,
            placed.handle(KeyStroke.of(KeyStroke.Key.ENTER), visible).output().orElseThrow());
    }

    @Test
    void measuredUnicodeButtonsCannotReceiveFocusOrActivateUntilTheirCompleteLabelFits() {
        FocusSpace space = FocusSpace.root(new Rect(0, 0, 80, 24));
        FocusContext forcedFocus = new FocusContext(true, true, space);
        for (Button button : List.of(Button.plain("a中b"), new Button("中e\u0301👩‍💻"))) {
            Size measured = button.measure(new Size(2, 1));
            assertEquals(new Size(2, 1), measured);
            var narrow = button.layout(new Rect(2, 1, measured.width(), measured.height()));
            var narrowTarget = new RoutedTarget<>("button", narrow, space);
            var narrowScope = FocusScope.single("dialog", FocusResetPolicy.FIRST_FOCUSABLE, narrowTarget);
            FocusManager<String> focus = new FocusManager<>();

            assertFalse(narrow.interactionReady());
            assertFalse(narrowTarget.interactive());
            assertFalse(narrowTarget.focusEligible());
            assertFalse(new RoutedTarget<>("button", narrow, space, true, true,
                InteractionVisibility.VISIBLE_INTERSECTION).interactive(), "clip policy cannot bypass intrinsic fit");
            focus.focus(narrowScope, "button");
            assertTrue(focus.focused().isEmpty());
            assertTrue(focus.route(narrowScope, KeyStroke.of(KeyStroke.Key.TAB)).target().isEmpty());
            for (TuiInputEvent event : List.of(KeyStroke.of(KeyStroke.Key.ENTER),
                KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "),
                new PointerEvent(1, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()))) {
                assertFalse(narrow.handle(event, forcedFocus).consumed(), "direct input: " + event);
                var routed = focus.route(narrowScope, event);
                if (routed.target().isPresent()) {
                    assertFalse(routed.context().orElseThrow().enabled());
                    assertFalse(routed.target().orElseThrow().placement()
                        .handle(event, routed.context().orElseThrow()).consumed());
                }
                assertTrue(focus.focused().isEmpty());
            }

            var grown = button.layout(new Rect(2, 1, button.width(), 1));
            var grownTarget = new RoutedTarget<>("button", grown, space);
            var grownScope = FocusScope.single("dialog", FocusResetPolicy.FIRST_FOCUSABLE, grownTarget);
            assertTrue(grown.interactionReady());
            assertTrue(grownTarget.focusEligible());
            var enter = focus.route(grownScope, KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("button", focus.focused().orElseThrow());
            assertEquals(Button.Action.ACTIVATED, enter.target().orElseThrow().placement()
                .handle(KeyStroke.of(KeyStroke.Key.ENTER), enter.context().orElseThrow()).output().orElseThrow());
            assertEquals(Button.Action.ACTIVATED,
                grown.handle(new PointerEvent(1, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()),
                    forcedFocus).output().orElseThrow());
        }
    }

    @Test
    void anEmptyPlainLabelCannotCreateAnInvisibleActionInPaddedBounds() {
        var placement = Button.plain("").layout(new Rect(2, 1, 4, 1));
        FocusSpace space = FocusSpace.root(new Rect(0, 0, 80, 24));
        assertFalse(placement.interactionReady());
        assertFalse(new RoutedTarget<>("empty", placement, space).focusEligible());
        assertFalse(placement.handle(KeyStroke.of(KeyStroke.Key.ENTER), new FocusContext(true, true, space)).consumed());
    }

    private static CellBuffer filled(int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }
}

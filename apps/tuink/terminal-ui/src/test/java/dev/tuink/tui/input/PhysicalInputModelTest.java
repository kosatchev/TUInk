package dev.tuink.tui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PhysicalInputModelTest {
    @Test
    void keyStrokeDefensivelyCopiesModifiers() {
        EnumSet<KeyStroke.Modifier> source = EnumSet.of(KeyStroke.Modifier.CTRL);

        KeyStroke stroke = new KeyStroke(KeyStroke.Key.S, source);
        source.add(KeyStroke.Modifier.SHIFT);

        assertEquals(Set.of(KeyStroke.Modifier.CTRL), stroke.modifiers());
        assertThrows(
            UnsupportedOperationException.class,
            () -> stroke.modifiers().add(KeyStroke.Modifier.ALT)
        );
        assertThrows(NullPointerException.class, () -> new KeyStroke(null, Set.of()));
        assertThrows(NullPointerException.class, () -> new KeyStroke(KeyStroke.Key.A, null));
    }

    @Test
    void textInputAcceptsPrintableScalarTextAndRejectsInvalidText() {
        assertEquals("A😀é", new TextInput("A😀é").text());

        assertThrows(NullPointerException.class, () -> new TextInput(null));
        assertThrows(IllegalArgumentException.class, () -> new TextInput(""));
        assertThrows(IllegalArgumentException.class, () -> new TextInput("line\n"));
        assertThrows(IllegalArgumentException.class, () -> new TextInput("\uD83D"));
    }

    @Test
    void pastePreservesEmptyMultilineAndControlText() {
        assertEquals("", new PasteEvent("").text());
        assertEquals("a\n\tb\u001B", new PasteEvent("a\n\tb\u001B").text());
        assertEquals(new PasteEvent("prefix", true), new PasteEvent("prefix", true));
        assertFalse(new PasteEvent("complete").truncated());
        assertThrows(NullPointerException.class, () -> new PasteEvent(null));
    }

    @Test
    void pointerUsesZeroBasedCoordinatesAndImmutableModifiers() {
        PointerEvent pointer = new PointerEvent(
            0,
            4,
            PointerEvent.Kind.DRAG,
            PointerEvent.Button.LEFT,
            EnumSet.of(KeyStroke.Modifier.SHIFT)
        );

        assertEquals(0, pointer.row());
        assertEquals(4, pointer.column());
        assertEquals(Set.of(KeyStroke.Modifier.SHIFT), pointer.modifiers());
        assertThrows(
            UnsupportedOperationException.class,
            () -> pointer.modifiers().add(KeyStroke.Modifier.CTRL)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PointerEvent(-1, 0, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of())
        );
        assertThrows(
            NullPointerException.class,
            () -> new PointerEvent(0, 0, null, PointerEvent.Button.LEFT, Set.of())
        );
    }

    @Test
    void resizeRequiresPositiveTerminalDimensions() {
        assertEquals(new ResizeEvent(24, 80), new ResizeEvent(24, 80));
        assertThrows(IllegalArgumentException.class, () -> new ResizeEvent(0, 80));
        assertThrows(IllegalArgumentException.class, () -> new ResizeEvent(24, -1));
    }
}

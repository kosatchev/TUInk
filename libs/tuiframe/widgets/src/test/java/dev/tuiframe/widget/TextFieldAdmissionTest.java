package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.style.CellStyle;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TextFieldAdmissionTest {
    private static final Rect BOUNDS = new Rect(0, 0, 8, 1);
    private static final FocusContext FOCUSED = new FocusContext(true, true, FocusSpace.root(BOUNDS));

    @Test
    void rejectedTypingPasteClipboardAndTrustedSetPreserveSelectionAndMouseViewport() {
        AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
        TextField field = new TextField(TextField.PastePolicy.PRESERVE_TABS, budget::get);
        assertTrue(field.set("0123456789界TAIL"));
        // A click retains the scrolled window even though the caret moves away from its tail.
        field.layout(BOUNDS).handle(pointer(1, PointerEvent.Kind.PRESS), FOCUSED);
        field.layout(BOUNDS).handle(pointer(3, PointerEvent.Kind.DRAG), FOCUSED);
        field.layout(BOUNDS).handle(pointer(3, PointerEvent.Kind.RELEASE), FOCUSED);
        String text = field.text();
        String selection = field.selectedText();
        int cursor = field.cursor();
        String viewport = paint(field);
        budget.set(0);

        assertEquals(TextField.Action.INPUT_REJECTED,
            field.layout(BOUNDS).handle(new TextInput("界"), FOCUSED).output().orElseThrow());
        assertEquals(TextField.Action.INPUT_REJECTED,
            field.layout(BOUNDS).handle(new PasteEvent("new\r\nvalue"), FOCUSED).output().orElseThrow());
        assertEquals(TextField.Action.INPUT_REJECTED,
            field.layout(BOUNDS).handle(KeyStroke.of(KeyStroke.Key.SPACE), FOCUSED).output().orElseThrow());
        assertFalse(field.paste("clipboard\tvalue"));
        assertFalse(field.insert("trusted\u0000"));
        assertFalse(field.set("different\u0000value"));
        assertEquals(text, field.text());
        assertEquals(selection, field.selectedText());
        assertEquals(cursor, field.cursor());
        assertEquals(viewport, paint(field));

        budget.set(Long.MAX_VALUE);
        assertTrue(field.insert("B"));
        assertFalse(field.hasSelection());
        assertEquals(text.substring(0, cursor - selection.length()) + "B" + text.substring(cursor), field.text());
    }

    @Test
    void metricsAdmissionAfterCandidateCopiesStillLeavesThePublishedFieldUntouched() {
        AtomicInteger checks = new AtomicInteger();
        AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
        TextField field = new TextField(TextField.PastePolicy.NORMALIZE_TABS, () -> {
            checks.incrementAndGet();
            return budget.getAndSet(0);
        });
        assertTrue(field.set("left RIGHT"));
        field.moveHome();
        field.navigate(KeyStroke.Key.ARROW_RIGHT, true, true);
        String selected = field.selectedText();
        int cursor = field.cursor();
        checks.set(0);
        budget.set(Long.MAX_VALUE);

        assertFalse(field.insert("界"));

        assertEquals(2, checks.get(), "candidate copying and metrics arrays need separate admission");
        assertEquals("left RIGHT", field.text());
        assertEquals(selected, field.selectedText());
        assertEquals(cursor, field.cursor());
    }

    @Test
    void emptyAndIdenticalTrustedUpdatesAreAcceptedWithoutAllocationBudget() {
        AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
        TextField field = new TextField(TextField.PastePolicy.NORMALIZE_TABS, budget::get);
        assertTrue(field.set("a\tb\u0000\u001B"));
        field.selectAll();
        budget.set(0);
        assertTrue(field.insert(""));
        assertTrue(field.paste(""));
        assertTrue(field.set("a\tb\u0000\u001B"));
        assertEquals("a\tb\u0000\u001B", field.text());
        assertEquals(field.length(), field.cursor());
        assertFalse(field.hasSelection());
        field.selectAll();
        assertTrue(field.deleteSelection(), "clearing the field needs no replacement arrays");
        assertTrue(field.isEmpty());
        assertTrue(field.set(null));
    }

    @Test
    void aRejectedWordDeletionDoesNotPublishItsTemporarySelection() {
        AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
        TextField field = new TextField(TextField.PastePolicy.NORMALIZE_TABS, budget::get);
        field.set("left right");
        budget.set(0);
        assertEquals(TextField.Action.INPUT_REJECTED, field.layout(BOUNDS)
            .handle(KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.CTRL), FOCUSED)
            .output().orElseThrow());
        assertEquals("left right", field.text());
        assertEquals(10, field.cursor());
        assertFalse(field.hasSelection());
    }

    private static PointerEvent pointer(int column, PointerEvent.Kind kind) {
        return new PointerEvent(0, column, kind, PointerEvent.Button.LEFT, Set.of());
    }

    private static String paint(TextField field) {
        CellBuffer cells = new CellBuffer(1, BOUNDS.width());
        field.layout(BOUNDS).paint(new Canvas(cells), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        StringBuilder rendered = new StringBuilder();
        for (int column = 0; column < BOUNDS.width(); column++) rendered.append(cells.get(0, column));
        return rendered.toString();
    }
}

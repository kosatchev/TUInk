package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextFieldUnicodeTest {
    @Test
    void cursorMovesAndDeletesByExtendedGraphemeCluster() {
        TextField field = new TextField();
        field.set("A👨‍👩‍👧‍👦e\u0301中B");

        field.moveLeft();
        assertEquals("A👨‍👩‍👧‍👦e\u0301中".length(), field.cursor());
        field.moveLeft();
        assertEquals("A👨‍👩‍👧‍👦e\u0301".length(), field.cursor());
        field.backspace();

        assertEquals("A👨‍👩‍👧‍👦中B", field.text());
        assertEquals("A👨‍👩‍👧‍👦".length(), field.cursor());
    }

    @Test
    void reportsTerminalCellCursorColumnInsteadOfUtf16Length() {
        TextField field = new TextField();
        field.set("A😀中e\u0301");

        assertEquals(6, field.cursorCellColumn());
        assertEquals(6, field.displayWidth());
        assertEquals(6, field.cursor());
    }

    @Test
    void tabAndOrphanMarksKeepPaintMouseSelectionAndScrolledCaretAligned() {
        Rect bounds = new Rect(0, 0, 10, 1);
        FocusContext focused = new FocusContext(true, true, FocusSpace.root(bounds));
        for (String orphan : List.of("\u0301", "\u200D", "\uFE0F", "🏽")) {
            String display = orphan.equals("\u0301") ? "◌\u0301" : "�";
            String source = "\t" + orphan + "X";
            TextField field = new TextField();
            field.set(source);
            CellBuffer wide = paint(field.layout(bounds));
            assertEquals(display, wide.get(0, 4).grapheme());
            assertEquals("X", wide.get(0, 5).grapheme());
            assertEquals(new Point(6, 0), field.layout(bounds).cursorPosition(focused).orElseThrow());

            field.layout(bounds).handle(new PointerEvent(0, 4, PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT, Set.of()), focused);
            assertEquals(1, field.cursor());
            field.layout(bounds).handle(new PointerEvent(0, 5, PointerEvent.Kind.DRAG,
                PointerEvent.Button.LEFT, Set.of()), focused);
            assertEquals(orphan, field.selectedText());
            field.layout(bounds).handle(new PointerEvent(0, 5, PointerEvent.Kind.RELEASE,
                PointerEvent.Button.LEFT, Set.of()), focused);
            assertEquals(new Point(5, 0), field.layout(bounds).cursorPosition(focused).orElseThrow());

            field.moveEnd();
            Rect narrowBounds = new Rect(0, 0, 4, 1);
            TextField.Placement narrow = field.layout(narrowBounds);
            CellBuffer scrolled = paint(narrow);
            assertEquals(display, scrolled.get(0, 0).grapheme());
            assertEquals("X", scrolled.get(0, 1).grapheme());
            assertEquals(new Point(2, 0), narrow.cursorPosition(focused).orElseThrow());
            assertEquals(source, field.text(), "display preparation must preserve the editable value");
        }
    }

    @Test
    void supplementaryInputIsInsertedAtomically() {
        TextField field = new TextField();
        field.insert("😀");
        field.insert("👍🏽");
        field.moveLeft();
        field.deleteForward();

        assertEquals("😀", field.text());
        assertEquals(2, field.cursor());
    }

    @Test
    void editsRenormalizeCursorWhenSurroundingGraphemesRecluster() {
        TextField inserted = new TextField();
        inserted.set("\u0301x");
        inserted.moveHome();
        inserted.insert("a");

        assertEquals("a\u0301x", inserted.text());
        assertEquals("a\u0301".length(), inserted.cursor());
        assertTrue(TerminalText.metrics(inserted.text(), 4).isBoundary(inserted.cursor()));

        TextField deleted = new TextField();
        deleted.set("🇦X🇧🇨");
        deleted.moveLeft();
        deleted.backspace();

        assertEquals("🇦🇧🇨", deleted.text());
        assertEquals("🇦🇧".length(), deleted.cursor());
        assertTrue(TerminalText.metrics(deleted.text(), 4).isBoundary(deleted.cursor()));
    }

    @Test
    void everyDeletionKeepsTheCaretAfterTheGraphemeJoinedAcrossTheEdit() {
        for (String operation : List.of("delete", "backspace", "selection")) {
            TextField field = new TextField();
            field.set("👩‍x💻TAIL");
            field.moveHome();
            field.moveRight();
            if (operation.equals("delete")) {
                field.deleteForward();
            } else {
                field.navigate(KeyStroke.Key.ARROW_RIGHT, false, operation.equals("selection"));
                if (operation.equals("selection")) field.deleteSelection();
                else field.backspace();
            }
            assertEquals("👩‍💻TAIL", field.text(), operation);
            assertEquals("👩‍💻".length(), field.cursor(), operation);
            field.insert("B");
            assertEquals("👩‍💻BTAIL", field.text(), operation);
        }
    }

    @Test
    void randomizedEditsNeverLeaveCursorInsideAGrapheme() {
        TextField field = new TextField();
        List<String> inputs = List.of("a", "\u0301", "中", "😀", "🇦", "🇧", "\u200D");
        Random random = new Random(0x54_45_58_54L);

        for (int step = 0; step < 1_000; step++) {
            switch (random.nextInt(8)) {
                case 0, 1 -> field.insert(inputs.get(random.nextInt(inputs.size())));
                case 2 -> field.backspace();
                case 3 -> field.deleteForward();
                case 4 -> field.moveLeft();
                case 5 -> field.moveRight();
                case 6 -> field.moveHome();
                case 7 -> field.moveEnd();
                default -> throw new AssertionError();
            }
            assertTrue(field.cursor() >= 0 && field.cursor() <= field.length());
            assertTrue(
                TerminalText.metrics(field.text(), 4).isBoundary(field.cursor()),
                "cursor split a grapheme at step " + step
            );
            assertTrue(field.cursorCellColumn() <= field.displayWidth());
            if (field.length() > 256) {
                field.clear();
            }
        }
    }

    private static CellBuffer paint(TextField.Placement placement) {
        CellBuffer buffer = new CellBuffer(1, placement.bounds().width());
        placement.paint(new Canvas(buffer), role -> CellStyle.DEFAULT, WidgetState.FOCUSED);
        return buffer;
    }
}

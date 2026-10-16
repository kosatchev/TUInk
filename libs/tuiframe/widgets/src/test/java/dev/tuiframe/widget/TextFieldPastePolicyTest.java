package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextFieldPastePolicyTest {
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final FocusContext FOCUSED = new FocusContext(true, true, FocusSpace.root(VIEWPORT));

    @Test
    void defaultPolicyRetainsExistingTabNormalizationForBothPasteRoutes() {
        for (boolean rawPaste : List.of(false, true)) {
            TextField field = new TextField();
            paste(field, "\tfirst\t\tsecond\t", rawPaste);

            assertEquals("first second", field.text());
            assertEquals(field.length(), field.cursor());
        }
    }

    @Test
    void preservingTabsKeepsLeadingTrailingRepeatedAndTabsOnlyValues() {
        for (boolean rawPaste : List.of(false, true)) {
            for (String text : List.of("\t", "\t\t", "\tfirst\t\tsecond\t", "👩‍💻\te\u0301\t中")) {
                TextField field = preservingTabs();
                field.set("replaced selection");
                field.selectAll();

                paste(field, text, rawPaste);

                assertEquals(text, field.text());
                assertEquals(text.length(), field.cursor());
                assertFalse(field.hasSelection());
            }
        }
    }

    @Test
    void bothPoliciesJoinLineBreaksAndFilterControlsWithoutCollapsingOrdinarySpaces() {
        for (boolean rawPaste : List.of(false, true)) {
            for (TextField.PastePolicy policy : TextField.PastePolicy.values()) {
                TextField field = new TextField(policy);
                paste(field, "\r\nfirst  value\r\n\nsecond\u0000\u001B value\n", rawPaste);

                assertEquals("first  value second value", field.text());
            }
        }
    }

    @Test
    void preservedTabsRemainContentWhenLineBreaksJoinAdjacentLines() {
        for (boolean rawPaste : List.of(false, true)) {
            TextField field = preservingTabs();
            paste(field, "\r\n\tfirst\r\n\t\tsecond\n\n\t\r\n", rawPaste);

            assertEquals("\tfirst \t\tsecond \t", field.text());
            field.clear();
            paste(field, "\t\u0000\u001B\t", rawPaste);
            assertEquals("\t\t", field.text());
        }
    }

    @Test
    void aPlacementHandlesPasteWithTheOwningFieldsPolicy() {
        TextField field = preservingTabs();
        field.set("before");
        field.selectAll();
        TextField.Placement placement = field.layout(new Rect(1, 1, 20, 1));

        InteractionResult<TextField.Action> result = placement.handle(new PasteEvent("\ta\tb\t"), FOCUSED);

        assertEquals(TextField.Action.VALUE_CHANGED, result.output().orElseThrow());
        assertEquals("\ta\tb\t", field.text());
    }

    @Test
    void truncatedOrUnfocusedRawPastePreservesTheBufferCaretAndSelection() {
        TextField field = preservingTabs();
        field.set("\tkeep\t");
        field.selectAll();
        TextField.Placement placement = field.layout(new Rect(1, 1, 20, 1));

        InteractionResult<TextField.Action> truncated = placement.handle(new PasteEvent("\tlost\t", true), FOCUSED);

        assertEquals(TextField.Action.PASTE_REJECTED, truncated.output().orElseThrow());
        assertEquals("\tkeep\t", field.text());
        assertEquals(field.length(), field.cursor());
        assertEquals(field.text(), field.selectedText());
        for (FocusContext context : List.of(
            new FocusContext(false, true, FocusSpace.root(VIEWPORT)),
            new FocusContext(false, false, FocusSpace.root(VIEWPORT))
        )) {
            assertFalse(placement.handle(new PasteEvent("\tlost\t"), context).consumed());
            assertEquals("\tkeep\t", field.text());
            assertEquals(field.text(), field.selectedText());
        }
    }

    @Test
    void preservedTabsUseTheExistingCaretClippingAndPointerGeometry() {
        for (boolean rawPaste : List.of(false, true)) {
            TextField field = preservingTabs();
            paste(field, "\tA\t中", rawPaste);
            TextField.Placement full = field.layout(new Rect(0, 0, 12, 1));
            CellBuffer buffer = new CellBuffer(1, 12);
            full.paint(new Canvas(buffer), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);

            assertEquals("A", buffer.get(0, 4).grapheme());
            assertEquals("中", buffer.get(0, 8).grapheme());
            assertTrue(buffer.get(0, 9).continuation());
            assertEquals(new Point(10, 0), full.cursorPosition(FOCUSED).orElseThrow());

            TextField.Placement clipped = field.layout(new Rect(0, 0, 6, 1));
            CellBuffer narrow = new CellBuffer(1, 6);
            clipped.paint(new Canvas(narrow), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
            assertEquals(" ", narrow.get(0, 0).grapheme());
            assertEquals("中", narrow.get(0, 3).grapheme());
            assertTrue(narrow.get(0, 4).continuation());
            assertEquals(new Point(5, 0), clipped.cursorPosition(FOCUSED).orElseThrow());

            full.handle(new PointerEvent(0, 3, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()), FOCUSED);
            assertEquals(1, field.cursor(), "a click near the end of a tab reaches its trailing boundary");
        }
    }

    @Test
    void printableTextInputRetainsItsExactTextInBothPastePolicies() {
        for (TextField.PastePolicy policy : TextField.PastePolicy.values()) {
            TextField field = new TextField(policy);
            field.layout(new Rect(0, 0, 30, 1)).handle(new TextInput("  A👩‍💻 e\u0301  "), FOCUSED);

            assertEquals("  A👩‍💻 e\u0301  ", field.text());
        }
    }

    private static TextField preservingTabs() {
        return new TextField(TextField.PastePolicy.PRESERVE_TABS);
    }

    private static void paste(TextField field, String text, boolean rawPaste) {
        if (rawPaste) {
            InteractionResult<TextField.Action> result = field.layout(new Rect(0, 0, 30, 1))
                .handle(new PasteEvent(text), FOCUSED);
            assertEquals(TextField.Action.VALUE_CHANGED, result.output().orElseThrow());
        } else {
            field.paste(text);
        }
    }
}

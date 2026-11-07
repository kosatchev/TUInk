package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoticeBannerTest {
    private static final Rect SCREEN = new Rect(0, 0, 60, 12);
    private static final CellStyle WARNING = new CellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW, false, false);
    private static final Theme THEME = role -> WidgetThemeKeys.NOTICE_WARNING.equals(role) ? WARNING : null;

    @Test
    void paintsOnlyItsAllocationAndKeepsWarningAndDismissIndependentOfDocumentText() {
        var banner = new NoticeBanner("File deleted: draft.txt", NoticeBanner.Severity.WARNING);
        var placement = banner.place(this, new Rect(11, 8, 35, 1), SCREEN, true);
        var cells = new CellBuffer(12, 60);
        placement.paint(new Canvas(cells), THEME, WidgetState.NORMAL);

        assertEquals(CellStyle.DEFAULT, cells.get(8, 10).style());
        assertEquals(WARNING, cells.get(8, 11).style());
        assertEquals(CellStyle.DEFAULT, cells.get(8, 46).style());
        assertEquals(CellColor.BRIGHT_YELLOW, cells.get(8, placement.dismissBounds().x()).style().bg());
        assertTrue(cells.get(8, placement.dismissBounds().x()).style().underline());
        assertEquals("[", cells.get(8, placement.dismissBounds().x()).grapheme());
        assertEquals("Dismiss", text(cells, placement.dismissBounds()).substring(1, 8));
    }

    @Test
    void dismissUsesTheExactButtonForMouseAndFocusedKeyboardWithoutAcquiringFocus() {
        var placement = new NoticeBanner("File deleted", NoticeBanner.Severity.WARNING)
            .place(this, new Rect(11, 8, 35, 1), SCREEN, true);
        var context = new FocusContext(false, true, FocusSpace.root(SCREEN));
        assertFalse(placement.handle(mouse(8, 11), context).consumed());
        var result = placement.handle(mouse(8, placement.dismissBounds().x() + 1), context);
        assertEquals(NoticeBanner.Action.DISMISS, result.output().orElseThrow());
        assertEquals(FocusRequest.NONE, result.focusRequest(), "Showing or clicking a banner must not steal editor focus");
        assertFalse(placement.handle(KeyStroke.of(KeyStroke.Key.ENTER), context).consumed());
        assertEquals(NoticeBanner.Action.DISMISS, placement.handle(KeyStroke.of(KeyStroke.Key.ENTER),
            new FocusContext(true, true, FocusSpace.root(SCREEN))).output().orElseThrow());
    }

    @Test
    void narrowLayoutWrapsTheButtonAndClippedOrDisabledButtonsCannotActivate() {
        var banner = new NoticeBanner("File deleted", NoticeBanner.Severity.WARNING);
        assertEquals(2, banner.preferredHeight(10));
        var narrow = banner.place(this, new Rect(7, 7, 10, 2), SCREEN, true);
        assertEquals(8, narrow.dismissBounds().y());
        assertTrue(narrow.interactionReady());

        var clipped = banner.place(this, new Rect(7, 7, 8, 2), SCREEN, true);
        assertFalse(clipped.interactionReady());
        assertFalse(clipped.handle(mouse(8, 8), new FocusContext(false, true, FocusSpace.root(SCREEN))).consumed());
        var disabled = banner.place(this, new Rect(7, 7, 30, 1), SCREEN, false);
        assertFalse(disabled.handle(mouse(7, disabled.dismissBounds().x()),
            new FocusContext(false, true, FocusSpace.root(SCREEN))).consumed());
    }

    @Test
    void scopeHasStableOwnerIdentityAndDoesNotFocusTheDismissButtonWhenShown() {
        Object owner = new Object();
        var banner = new NoticeBanner("A\nB\u001B", NoticeBanner.Severity.WARNING);
        assertEquals("A\\nB\\u001B", banner.text());
        var placement = banner.place(owner, new Rect(11, 8, 35, 1), SCREEN, true);
        assertSame(owner, placement.scope().id());
        var focus = new FocusManager<NoticeBanner.Action>();
        focus.context(placement.scope(), NoticeBanner.Action.DISMISS);
        assertTrue(focus.focused().isEmpty());
    }

    @Test
    void ownerDefinedActionsHaveSeparateMouseAndKeyboardReceiversAndStableFocus() {
        var keep = new NoticeBanner.Action("keep-current", "Keep");
        var update = new NoticeBanner.Action("read-new", "Update");
        Object owner = new Object();
        var banner = new NoticeBanner("Version changed", NoticeBanner.Severity.WARNING, List.of(keep, update));
        var placed = banner.place(owner, new Rect(11, 8, 45, 1), SCREEN, true);
        var context = new FocusContext(false, true, FocusSpace.root(SCREEN));
        assertEquals(keep, placed.handle(mouse(8, placed.actionBounds(keep).x() + 1), context).output().orElseThrow());
        assertEquals(update, placed.handle(mouse(8, placed.actionBounds(update).x() + 1), context).output().orElseThrow());
        var focus = new FocusManager<NoticeBanner.Action>();
        focus.context(placed.scope(), keep);
        assertTrue(focus.focused().isEmpty(), "Showing a notice does not take the document's focus");
        assertTrue(focus.focus(placed.scope(), keep));
        assertTrue(placed.handle(KeyStroke.of(KeyStroke.Key.TAB), focus).consumed());
        assertEquals(update, focus.focused().orElseThrow());
        var rebuilt = banner.place(owner, new Rect(11, 8, 45, 1), SCREEN, true);
        assertEquals(update, rebuilt.handle(KeyStroke.of(KeyStroke.Key.ENTER), focus).output().orElseThrow());
        var disabled = banner.place(owner, new Rect(11, 8, 45, 1), SCREEN, false);
        assertTrue(disabled.handle(mouse(8, disabled.actionBounds(update).x()), context).output().isEmpty());
    }

    @Test
    void multipleActionsWrapWithoutActivatingPartiallyVisibleButtons() {
        var first = new NoticeBanner.Action("first", "Keep");
        var second = new NoticeBanner.Action("second", "Update");
        var banner = new NoticeBanner("Changed", NoticeBanner.Severity.WARNING, List.of(first, second));
        assertEquals(3, banner.preferredHeight(8));
        var placed = banner.place(this, new Rect(7, 7, 8, 2), SCREEN, true);
        assertTrue(placed.scope().target(first).orElseThrow().interactive());
        assertFalse(placed.scope().target(second).orElseThrow().interactive());
        assertTrue(placed.handle(mouse(9, 8), new FocusContext(false, true, FocusSpace.root(SCREEN))).output().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new NoticeBanner("x", NoticeBanner.Severity.INFO,
            List.of(first, new NoticeBanner.Action(first.id(), "Again"))));
    }

    private static PointerEvent mouse(int row, int column) {
        return new PointerEvent(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
    }

    private static String text(CellBuffer cells, Rect bounds) {
        StringBuilder text = new StringBuilder();
        for (int column = bounds.x(); column < bounds.rightExclusive(); column++) text.append(cells.get(bounds.y(), column).grapheme());
        return text.toString();
    }
}

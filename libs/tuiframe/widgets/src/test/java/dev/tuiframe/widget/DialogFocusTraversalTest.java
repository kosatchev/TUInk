package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DialogFocusTraversalTest {
    private static final KeyStroke TAB = KeyStroke.of(KeyStroke.Key.TAB);
    private static final KeyStroke BACK_TAB = KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT);
    private static final Rect CLIP = new Rect(0, 0, 30, 6);

    @Test
    void forwardAndReverseTraversalIncludeContentExactlyOnceBetweenLastAndFirstControls() {
        FocusManager<String> focus = new FocusManager<>();
        FocusScope<String, TextField.Action> scope = scope("dialog", control("first", 0), control("last", 4));
        DialogFocusTraversal.focusFirst(focus, scope);
        assertEquals("first", focus.focused().orElseThrow());

        assertFalse(DialogFocusTraversal.advance(focus, scope, false, true, TAB));
        assertEquals("last", focus.focused().orElseThrow());
        assertTrue(DialogFocusTraversal.advance(focus, scope, false, true, TAB));
        assertTrue(focus.focused().isEmpty());
        focus.context(scope, "first");
        assertTrue(focus.focused().isEmpty(), "reconciliation must preserve content ownership");
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, true, TAB));
        assertEquals("first", focus.focused().orElseThrow());

        assertTrue(DialogFocusTraversal.advance(focus, scope, false, true, BACK_TAB));
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, true, BACK_TAB));
        assertEquals("last", focus.focused().orElseThrow());
        assertFalse(DialogFocusTraversal.advance(focus, scope, false, true, BACK_TAB));
        assertEquals("first", focus.focused().orElseThrow());
    }

    @Test
    void hiddenContentIsSkippedAndControlsWrapInBothDirections() {
        FocusManager<String> focus = new FocusManager<>();
        var scope = scope("dialog", control("first", 0), control("last", 4));
        DialogFocusTraversal.focusFirst(focus, scope);
        assertFalse(DialogFocusTraversal.advance(focus, scope, false, false, BACK_TAB));
        assertEquals("last", focus.focused().orElseThrow());
        assertFalse(DialogFocusTraversal.advance(focus, scope, false, false, TAB));
        assertEquals("first", focus.focused().orElseThrow());
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, false, BACK_TAB));
        assertEquals("last", focus.focused().orElseThrow());
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, false, TAB));
        assertEquals("first", focus.focused().orElseThrow());
    }

    @Test
    void onlyEnabledFocusableNonemptyAndFullyVisibleControlsParticipate() {
        FocusManager<String> focus = new FocusManager<>();
        var scope = scope("dialog",
            target("disabled", new Rect(0, 1, 3, 1), true, false, CLIP),
            target("not-focusable", new Rect(4, 1, 3, 1), false, true, CLIP),
            target("empty", new Rect(8, 1, 0, 1), true, true, CLIP),
            target("partial", new Rect(28, 1, 3, 1), true, true, CLIP),
            target("hidden", new Rect(0, 6, 3, 1), true, true, CLIP),
            control("visible", 12)
        );
        DialogFocusTraversal.focusFirst(focus, scope);
        assertEquals("visible", focus.focused().orElseThrow());
        assertTrue(DialogFocusTraversal.advance(focus, scope, false, true, TAB));
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, true, BACK_TAB));
        assertEquals("visible", focus.focused().orElseThrow());
    }

    @Test
    void resizingAwayAllControlsClearsFocusAndRestoringThemRejoinsTheRing() {
        FocusManager<String> focus = new FocusManager<>();
        Object id = new Object();
        var visible = scope(id, control("first", 0), control("last", 4));
        DialogFocusTraversal.focusFirst(focus, visible);
        focus.focus(visible, "last");
        Rect hiddenClip = new Rect(0, 0, 30, 1);
        var hidden = scope(id,
            target("first", new Rect(0, 1, 3, 1), true, true, hiddenClip),
            target("last", new Rect(4, 1, 3, 1), true, true, hiddenClip));

        assertTrue(DialogFocusTraversal.advance(focus, hidden, false, true, TAB));
        assertTrue(focus.focused().isEmpty());
        assertFalse(DialogFocusTraversal.advance(focus, visible, true, true, BACK_TAB));
        assertEquals("last", focus.focused().orElseThrow());

        assertFalse(DialogFocusTraversal.advance(focus, hidden, false, false, TAB));
        assertTrue(focus.focused().isEmpty());
        focus.context(hidden, "first");
        assertTrue(focus.focused().isEmpty(), "no visible surface must remain explicitly unfocused");
        assertFalse(DialogFocusTraversal.advance(focus, visible, false, false, TAB));
        assertEquals("first", focus.focused().orElseThrow());
    }

    @Test
    void aRemovedControlOrAnotherScopesFocusDoesNotSupplyAnIncorrectStartingIndex() {
        FocusManager<String> focus = new FocusManager<>();
        Object id = new Object();
        var initial = scope(id, control("first", 0), control("removed", 4), control("last", 8));
        focus.focus(initial, "removed");
        var removed = scope(id, control("first", 0), control("last", 8));
        assertFalse(DialogFocusTraversal.advance(focus, removed, false, true, BACK_TAB));
        assertEquals("last", focus.focused().orElseThrow());

        var another = scope("other", control("last", 0));
        focus.focus(another, "last");
        assertFalse(DialogFocusTraversal.advance(focus, removed, false, true, TAB));
        assertEquals("first", focus.focused().orElseThrow());
    }

    @Test
    void emptyScopesSelectOnlyAvailableContentAndNeverInventAnInvisibleOwner() {
        FocusManager<String> focus = new FocusManager<>();
        FocusScope<String, TextField.Action> empty = new FocusScope<>(
            "empty", FocusResetPolicy.FIRST_FOCUSABLE, List.of(), List.of(), List.of()
        );
        for (KeyStroke key : List.of(TAB, BACK_TAB)) {
            assertTrue(DialogFocusTraversal.advance(focus, empty, false, true, key));
            assertTrue(focus.focused().isEmpty());
            assertFalse(DialogFocusTraversal.advance(focus, empty, true, false, key));
            assertTrue(focus.focused().isEmpty());
        }
    }

    @Test
    void onlyExactTabAndShiftTabTraverseWithoutChangingStateForOtherKeys() {
        assertTrue(DialogFocusTraversal.isTraversal(TAB));
        assertTrue(DialogFocusTraversal.isTraversal(BACK_TAB));
        FocusManager<String> focus = new FocusManager<>();
        var scope = scope("dialog", control("first", 0), control("last", 4));
        DialogFocusTraversal.focusFirst(focus, scope);
        for (KeyStroke key : List.of(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.ALT),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SUPER),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.ENTER), KeyStroke.of(KeyStroke.Key.ARROW_LEFT)
        )) {
            assertFalse(DialogFocusTraversal.isTraversal(key));
            assertFalse(DialogFocusTraversal.advance(focus, scope, false, true, key));
            assertEquals("first", focus.focused().orElseThrow());
        }
        focus.clear(scope);
        assertTrue(DialogFocusTraversal.advance(focus, scope, true, true,
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)));
        assertTrue(focus.focused().isEmpty());
        for (TuiInputEvent event : List.of(new TextInput(" "), new PasteEvent("\t"),
            new ResizeEvent(24, 80),
            new PointerEvent(1, 0, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()))) {
            assertFalse(DialogFocusTraversal.isTraversal(event));
        }
    }

    @Test
    void focusFirstClearsPreviousOwnershipAndUsesTheFirstEligibleTabTarget() {
        FocusManager<String> focus = new FocusManager<>();
        var scope = scope("dialog",
            target("partial", new Rect(28, 1, 3, 1), true, true, CLIP),
            control("first", 0), control("last", 4));
        focus.focus(scope, "last");
        DialogFocusTraversal.focusFirst(focus, scope);
        assertEquals("first", focus.focused().orElseThrow());

        var hidden = scope("hidden", target("partial", new Rect(28, 1, 3, 1), true, true, CLIP));
        DialogFocusTraversal.focusFirst(focus, hidden);
        assertTrue(focus.focused().isEmpty());
        focus.context(hidden, "partial");
        assertTrue(focus.focused().isEmpty());
    }

    @Test
    void eligibilityUsesParentLocalClipsAndTraversalDoesNotEditFieldValuesOrCarets() {
        TextField first = new TextField();
        first.set("A😀e\u0301");
        first.moveLeft();
        int caret = first.cursor();
        FocusSpace translated = new FocusSpace(new Point(100, 50), new Rect(0, 0, 10, 3));
        RoutedTarget<String, TextField.Action> target = new RoutedTarget<>(
            "field", first.layout(new Rect(1, 1, 5, 1)), translated
        );
        var scope = scope("translated", target);
        FocusManager<String> focus = new FocusManager<>();
        DialogFocusTraversal.focusFirst(focus, scope);
        assertEquals("field", focus.focused().orElseThrow());
        assertTrue(DialogFocusTraversal.advance(focus, scope, false, true, TAB));
        assertFalse(DialogFocusTraversal.advance(focus, scope, true, true, BACK_TAB));
        assertEquals("A😀e\u0301", first.text());
        assertEquals(caret, first.cursor());
    }

    @Test
    void pureNavigationSupportsCustomControlIdsAndAnOptionalDocumentStop() {
        List<String> controls = List.of("list", "ok", "cancel");
        assertEquals(new DialogFocusTraversal.Target<>(false, "list"),
            DialogFocusTraversal.navigate(controls, null, true, true, false));
        assertEquals(new DialogFocusTraversal.Target<>(false, "list"),
            DialogFocusTraversal.navigate(controls, null, false, true, false));
        assertEquals(new DialogFocusTraversal.Target<>(false, "ok"),
            DialogFocusTraversal.navigate(controls, "list", false, true, false));
        assertEquals(new DialogFocusTraversal.Target<>(false, "cancel"),
            DialogFocusTraversal.navigate(controls, "ok", false, true, false));
        assertEquals(new DialogFocusTraversal.Target<String>(true, null),
            DialogFocusTraversal.navigate(controls, "cancel", false, true, false));
        assertEquals(new DialogFocusTraversal.Target<>(false, "cancel"),
            DialogFocusTraversal.navigate(controls, null, true, true, true));
        assertEquals(new DialogFocusTraversal.Target<String>(true, null),
            DialogFocusTraversal.navigate(controls, "list", false, true, true));
        assertEquals(new DialogFocusTraversal.Target<>(false, "cancel"),
            DialogFocusTraversal.navigate(controls, "list", false, false, true));
        assertEquals(new DialogFocusTraversal.Target<>(false, "list"),
            DialogFocusTraversal.navigate(controls, "gone", false, true, false));
        assertEquals(new DialogFocusTraversal.Target<String>(false, null),
            DialogFocusTraversal.navigate(List.of(), "gone", true, false, false));
        assertEquals(new DialogFocusTraversal.Target<String>(true, null),
            DialogFocusTraversal.navigate(List.of(), null, false, true, true));
        assertThrows(IllegalArgumentException.class, () -> new DialogFocusTraversal.Target<>(true, "ok"));
    }

    @SafeVarargs
    private static FocusScope<String, TextField.Action> scope(
        Object id, RoutedTarget<String, TextField.Action>... targets
    ) {
        List<RoutedTarget<String, TextField.Action>> ordered = List.of(targets);
        List<String> ids = ordered.stream().map(RoutedTarget::id).toList();
        return new FocusScope<>(id, FocusResetPolicy.FIRST_FOCUSABLE, ordered, ids, ids);
    }

    private static RoutedTarget<String, TextField.Action> control(String id, int x) {
        return target(id, new Rect(x, 1, 3, 1), true, true, CLIP);
    }

    private static RoutedTarget<String, TextField.Action> target(
        String id, Rect bounds, boolean focusable, boolean enabled, Rect clip
    ) {
        return new RoutedTarget<>(id, new TextField().layout(bounds), FocusSpace.root(clip), focusable, enabled);
    }
}

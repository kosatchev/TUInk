package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.SearchOptions;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SearchBarView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchInputRouterTest {
    private static final List<SearchFocus> TARGET_ORDER = List.of(
        SearchFocus.FIND,
        SearchFocus.CASE,
        SearchFocus.WORD,
        SearchFocus.WILDCARD,
        SearchFocus.ALL,
        SearchFocus.REPLACE,
        SearchFocus.NEXT_BUTTON,
        SearchFocus.PREV_BUTTON,
        SearchFocus.REPLACE_BUTTON,
        SearchFocus.ALL_BUTTON
    );

    @Test
    void exactTabAndShiftTabTraverseTheFocusRingInOppositeDirections() {
        Fixture fixture = fixture(80);
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());

        for (int index = 1; index <= TARGET_ORDER.size(); index++) {
            assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
            assertEquals(
                TARGET_ORDER.get(index % TARGET_ORDER.size()),
                fixture.focus.focused().orElseThrow()
            );
        }

        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        ));
        assertEquals(SearchFocus.ALL_BUTTON, fixture.focus.focused().orElseThrow());
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)
        ));
        assertEquals(SearchFocus.ALL_BUTTON, fixture.focus.focused().orElseThrow());
    }

    @Test
    void horizontalArrowsStayInFieldsAndTraverseButtons() {
        TextField query = new TextField();
        query.set("ab");
        Fixture fixture = fixture(query, new TextField(), 80, viewport(5, 80));

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(1, query.cursor());
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(0, query.cursor());

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.REPLACE);
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(SearchFocus.REPLACE, fixture.focus.focused().orElseThrow());
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.NEXT_BUTTON);
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(SearchFocus.PREV_BUTTON, fixture.focus.focused().orElseThrow());
    }

    @Test
    void upAndDownConnectEachSearchOptionToItsActionOrField() {
        Fixture fixture = fixture(80);
        SearchFocus[][] pairs = {
            {SearchFocus.CASE, SearchFocus.NEXT_BUTTON},
            {SearchFocus.WORD, SearchFocus.PREV_BUTTON},
            {SearchFocus.WILDCARD, SearchFocus.REPLACE_BUTTON},
            {SearchFocus.ALL, SearchFocus.ALL_BUTTON}
        };

        for (SearchFocus[] pair : pairs) {
            fixture.focus.focus(fixture.placement.focusScope(), pair[0]);
            assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)));
            assertEquals(pair[1], fixture.focus.focused().orElseThrow());
            assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_UP)));
            assertEquals(pair[0], fixture.focus.focused().orElseThrow());
        }
    }

    @Test
    void verticalArrowsAreCapturedWhenTheSearchBarIsEntirelyClipped() {
        TextField query = new TextField();
        query.set("keep");
        TextField replacement = new TextField();
        replacement.set("also-keep");
        Fixture fixture = fixture(query, replacement, 80, viewport(2, 80));

        assertTrue(fixture.focus.focused().isEmpty());
        for (KeyStroke.Key key : List.of(KeyStroke.Key.ARROW_UP, KeyStroke.Key.ARROW_DOWN)) {
            assertCaptured(fixture.route(KeyStroke.of(key)));
            assertTrue(fixture.focus.focused().isEmpty());
        }
        assertEquals("keep", query.text());
        assertEquals("also-keep", replacement.text());
    }

    @Test
    void partiallyClippedSearchKeepsVerticalFocusAndSkipsInvisibleControlsInTheRing() {
        Fixture fixture = fixture(new TextField(), new TextField(), 80, viewport(3, 80));

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        for (SearchFocus expected : List.of(
            SearchFocus.CASE, SearchFocus.WORD, SearchFocus.WILDCARD, SearchFocus.ALL, SearchFocus.FIND
        )) {
            assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
            assertEquals(expected, fixture.focus.focused().orElseThrow());
        }
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_UP)));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
    }

    @Test
    void verticalNavigationRecoversAfterResizeClipsAndRestoresTheSearchBar() {
        Fixture fixture = fixture(80);
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.REPLACE);
        SearchBarView view = SearchBarView.build(
            fixture.query,
            fixture.replacement,
            80,
            false,
            SearchOptions.MatchMode.SUBSTRING,
            false
        );
        Object scopeId = fixture.placement.focusScope().id();
        SearchBarView.Placement clipped = view.place(scopeId, 2, viewport(2, 80));

        for (KeyStroke.Key key : List.of(KeyStroke.Key.ARROW_UP, KeyStroke.Key.ARROW_DOWN)) {
            assertCaptured(SearchInputRouter.route(fixture.focus, clipped, KeyStroke.of(key)));
            assertTrue(fixture.focus.focused().isEmpty());
        }

        SearchBarView.Placement restored = view.place(scopeId, 2, viewport(5, 80));
        restored.activate(fixture.focus);
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertCaptured(SearchInputRouter.route(
            fixture.focus,
            restored,
            KeyStroke.of(KeyStroke.Key.ARROW_DOWN)
        ));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertCaptured(SearchInputRouter.route(
            fixture.focus,
            restored,
            KeyStroke.of(KeyStroke.Key.ARROW_UP)
        ));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
    }

    @Test
    void fieldsOwnUnicodeImeAndNormalizedPasteWithTypedChangeIntents() {
        Fixture fixture = fixture(80);

        assertAction(
            SearchBarView.Action.QUERY_CHANGED,
            fixture.route(new TextInput("A😀e\u0301"))
        );
        assertEquals("A😀e\u0301", fixture.query.text());
        assertAction(
            SearchBarView.Action.QUERY_CHANGED,
            fixture.route(new PasteEvent(" first\r\n\tsecond\u0000 😀\n", false))
        );
        assertEquals("A😀e\u0301 first second 😀", fixture.query.text());

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.REPLACE);
        assertAction(
            SearchBarView.Action.REPLACEMENT_CHANGED,
            fixture.route(new TextInput("замена"))
        );
        assertEquals("замена", fixture.replacement.text());
        String before = fixture.replacement.text();
        assertAction(
            SearchBarView.Action.INPUT_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertEquals(before, fixture.replacement.text());

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.CASE);
        assertCaptured(fixture.route(new TextInput("ignored")));
        assertCaptured(fixture.route(new PasteEvent("ignored", false)));
        assertAction(
            SearchBarView.Action.INPUT_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertEquals(before, fixture.replacement.text());
    }

    @Test
    void backspaceDeleteAreFieldOnlyAndModifiedGlobalKeysBubble() {
        TextField query = new TextField();
        query.set("ab");
        Fixture fixture = fixture(query, new TextField(), 80, viewport(5, 80));

        assertAction(
            SearchBarView.Action.QUERY_CHANGED,
            fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE))
        );
        assertEquals("a", query.text());
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.CASE);
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.DELETE)));
        assertEquals("a", query.text());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.END)));

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.FIND);
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT)
        ));
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.ENTER, KeyStroke.Modifier.SHIFT)
        ));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.ALT)
        ));
        assertEquals("a", query.text());
        assertEquals(0, query.cursor());
    }

    @Test
    void enterSubmitsFieldsAndEveryButtonUsesStandardActivationKeys() {
        Fixture fixture = fixture(80);

        assertAction(
            SearchBarView.Action.FIND_NEXT,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.REPLACE);
        assertAction(
            SearchBarView.Action.FIND_NEXT,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        List<SearchFocus> buttons = List.of(
            SearchFocus.CASE, SearchFocus.WORD, SearchFocus.WILDCARD, SearchFocus.ALL,
            SearchFocus.NEXT_BUTTON, SearchFocus.PREV_BUTTON,
            SearchFocus.REPLACE_BUTTON, SearchFocus.ALL_BUTTON
        );
        List<SearchBarView.Action> actions = List.of(
            SearchBarView.Action.TOGGLE_CASE, SearchBarView.Action.TOGGLE_WORD,
            SearchBarView.Action.TOGGLE_WILDCARD, SearchBarView.Action.TOGGLE_ALL_TABS,
            SearchBarView.Action.FIND_NEXT, SearchBarView.Action.FIND_PREVIOUS,
            SearchBarView.Action.REPLACE, SearchBarView.Action.REPLACE_ALL
        );
        for (int index = 0; index < buttons.size(); index++) {
            fixture.focus.focus(fixture.placement.focusScope(), buttons.get(index));
            for (TuiInputEvent event : List.of(
                KeyStroke.of(KeyStroke.Key.ENTER),
                KeyStroke.of(KeyStroke.Key.SPACE),
                new TextInput(" ")
            )) {
                assertAction(actions.get(index), fixture.route(event));
            }
            assertCaptured(fixture.route(new TextInput("not a button key")));
        }

        assertAction(
            SearchBarView.Action.CLOSE_REQUESTED,
            fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE))
        );
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.ESCAPE, KeyStroke.Modifier.SHIFT)
        ));
    }

    @Test
    void bothSpaceEventFormsInsertIntoTheFocusedField() {
        Fixture fixture = fixture(80);
        List<SearchFocus> fields = List.of(SearchFocus.FIND, SearchFocus.REPLACE);
        List<SearchBarView.Action> actions = List.of(
            SearchBarView.Action.QUERY_CHANGED,
            SearchBarView.Action.REPLACEMENT_CHANGED
        );
        for (int index = 0; index < fields.size(); index++) {
            fixture.focus.focus(fixture.placement.focusScope(), fields.get(index));
            assertAction(actions.get(index), fixture.route(KeyStroke.of(KeyStroke.Key.SPACE)));
            assertAction(actions.get(index), fixture.route(new TextInput(" ")));
        }
        assertEquals("  ", fixture.query.text());
        assertEquals("  ", fixture.replacement.text());
    }

    @Test
    void fieldDragKeepsCaptureAcrossOtherControlsAndOutsideTheBar() {
        for (SearchFocus id : List.of(SearchFocus.FIND, SearchFocus.REPLACE)) {
            TextField query = new TextField();
            query.set("abc👩‍💻def");
            TextField replacement = new TextField();
            replacement.set("abc👩‍💻def");
            Fixture fixture = fixture(query, replacement, 80, viewport(5, 80));
            TextField field = id == SearchFocus.FIND ? query : replacement;
            Rect bounds = fixture.placement.targetBounds(id);
            assertCaptured(fixture.route(pointer(bounds.y(), bounds.x() + 1,
                PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)));
            Rect button = fixture.placement.targetBounds(SearchFocus.CASE);
            assertCaptured(fixture.route(pointer(button.y(), button.x(),
                PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT)));
            assertEquals("bc👩‍💻def", field.selectedText());
            assertEquals(id, fixture.focus.focused().orElseThrow());
            assertTrue(fixture.capture.active());
            assertCaptured(fixture.route(pointer(23, 79,
                PointerEvent.Kind.RELEASE, PointerEvent.Button.NONE)));
            assertFalse(field.mouseSelecting());
            assertEquals("bc👩‍💻def", field.selectedText());
            assertIgnored(fixture.route(pointer(0, 0, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT)));
        }
    }

    @Test
    void shiftClickExtendsFieldSelectionAndFocusOrKeyboardChangesCancelCapture() {
        TextField query = new TextField();
        query.set("abcdef");
        Fixture fixture = fixture(query, new TextField(), 80, viewport(5, 80));
        Rect bounds = fixture.placement.targetBounds(SearchFocus.FIND);
        fixture.route(pointer(bounds.y(), bounds.x() + 1, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT));
        fixture.route(pointer(bounds.y(), bounds.x() + 1, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT));
        assertCaptured(fixture.route(new PointerEvent(bounds.y(), bounds.x() + 4,
            PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of(KeyStroke.Modifier.SHIFT))));
        assertEquals("bcd", query.selectedText());
        assertAction(SearchBarView.Action.QUERY_CHANGED, fixture.route(new TextInput("X")));
        assertEquals("aXef", query.text());
        assertFalse(query.mouseSelecting());

        fixture.route(pointer(bounds.y(), bounds.x() + 1, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT));
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.CASE);
        fixture.route(pointer(0, 0, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT));
        assertFalse(query.mouseSelecting());
        assertEquals(1, query.cursor());
    }

    @Test
    void clippingAnActiveFieldCancelsItsMouseCapture() {
        TextField query = new TextField();
        query.set("abcdef");
        Fixture fixture = fixture(query, new TextField(), 80, viewport(5, 80));
        Rect bounds = fixture.placement.targetBounds(SearchFocus.FIND);
        fixture.route(pointer(bounds.y(), bounds.x() + 1, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT));
        SearchBarView.Placement clipped = SearchBarView.build(query, fixture.replacement, 80,
            false, SearchOptions.MatchMode.SUBSTRING, false)
            .place(fixture.placement.focusScope().id(), 2, viewport(2, 80));
        fixture.capture.beforeInput(WindowPointerCapture.frame(fixture)
            .addScope(clipped.focusScope(), fixture.focus, action -> { }),
            pointer(0, 0, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT));
        assertFalse(fixture.capture.active());
        assertFalse(query.mouseSelecting());
        assertEquals(1, query.cursor());
    }

    @Test
    void pointerHitsFocusAndActivateControlsWhileEveryMissBubbles() {
        TextField query = new TextField();
        query.set("abcd");
        Fixture fixture = fixture(query, new TextField(), 80, viewport(5, 80));
        fixture.focus.clear(fixture.placement.focusScope());
        Rect field = fixture.placement.targetBounds(SearchFocus.FIND);

        assertCaptured(fixture.route(pointer(
            field.y(),
            field.x() + 1,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertEquals(1, query.cursor());

        Rect caseButton = fixture.placement.targetBounds(SearchFocus.CASE);
        assertAction(
            SearchBarView.Action.TOGGLE_CASE,
            fixture.route(pointer(
                caseButton.y(),
                caseButton.x(),
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT
            ))
        );
        assertEquals(SearchFocus.CASE, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(pointer(
            caseButton.y(),
            caseButton.x(),
            PointerEvent.Kind.RELEASE,
            PointerEvent.Button.LEFT
        )));
        assertCaptured(fixture.route(pointer(
            caseButton.y(),
            caseButton.x(),
            PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Button.NONE
        )));

        assertIgnored(fixture.route(pointer(
            field.y(),
            0,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )), "the label is not one of the exact control placements");
        assertIgnored(fixture.route(pointer(
            0,
            0,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )));
    }

    @Test
    void disabledPartialHitIsModalButTinyRawInputCannotMutateOrSubmit() {
        int columns = 10;
        TextField query = new TextField();
        query.set("keep");
        TextField replacement = new TextField();
        replacement.set("also-keep");
        Rect narrowClip = new Rect(0, 0, 4, 5);
        Fixture fixture = fixture(query, replacement, columns, narrowClip);

        assertTrue(fixture.focus.focused().isEmpty());
        assertCaptured(fixture.route(new TextInput("hidden")));
        assertCaptured(fixture.route(new PasteEvent("hidden", false)));
        assertAction(
            SearchBarView.Action.INPUT_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.DELETE)));
        int hiddenCursor = query.cursor();
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.END)));
        assertEquals(hiddenCursor, query.cursor());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.SPACE)));
        assertCaptured(fixture.route(new TextInput(" ")));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.CTRL)
        ));
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.ENTER, KeyStroke.Modifier.SHIFT)
        ));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertEquals("keep", query.text());
        assertEquals("also-keep", replacement.text());
        assertTrue(fixture.focus.focused().isEmpty());

        Rect partialCase = fixture.placement.targetBounds(SearchFocus.CASE);
        assertFalse(fixture.placement.focusScope().target(SearchFocus.CASE).orElseThrow().enabled());
        assertIgnored(fixture.route(pointer(
            partialCase.y(),
            partialCase.x(),
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )));
        assertIgnored(fixture.route(pointer(
            partialCase.y(),
            narrowClip.rightExclusive(),
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )));
    }

    private static Fixture fixture(int columns) {
        return fixture(
            new TextField(),
            new TextField(),
            columns,
            viewport(5, columns)
        );
    }

    private static Fixture fixture(
        TextField query,
        TextField replacement,
        int columns,
        Rect clip
    ) {
        SearchBarView.Placement placement = SearchBarView.build(
            query,
            replacement,
            columns,
            false,
            SearchOptions.MatchMode.SUBSTRING,
            false
        ).place(new Object(), 2, clip);
        FocusManager<SearchFocus> focus = new FocusManager<>();
        placement.activate(focus);
        return new Fixture(query, replacement, focus, placement);
    }

    private static Rect viewport(int rows, int columns) {
        return new Rect(0, 0, columns, rows);
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private static void assertAction(
        SearchBarView.Action expected,
        SearchInputRouter.Result result
    ) {
        assertTrue(result.consumed());
        assertEquals(expected, result.action());
    }

    private static void assertCaptured(SearchInputRouter.Result result) {
        assertTrue(result.consumed());
        assertNull(result.action());
    }

    private static void assertIgnored(SearchInputRouter.Result result) {
        assertIgnored(result, null);
    }

    private static void assertIgnored(SearchInputRouter.Result result, String message) {
        assertFalse(result.consumed(), message);
        assertNull(result.action(), message);
    }

    private record Fixture(
        TextField query,
        TextField replacement,
        FocusManager<SearchFocus> focus,
        SearchBarView.Placement placement,
        WindowPointerCapture capture
    ) {
        private Fixture(TextField query, TextField replacement, FocusManager<SearchFocus> focus,
            SearchBarView.Placement placement) {
            this(query, replacement, focus, placement, new WindowPointerCapture());
        }

        private SearchInputRouter.Result route(TuiInputEvent event) {
            WindowPointerCapture.Frame frame = WindowPointerCapture.frame(this)
                .addScope(placement.focusScope(), focus, action -> { });
            capture.beforeInput(frame, event);
            if (event instanceof PointerEvent pointer && capture.dispatch(frame, pointer)) {
                return new SearchInputRouter.Result(true, null);
            }
            return SearchInputRouter.route(focus, placement, event, capture, this);
        }
    }
}

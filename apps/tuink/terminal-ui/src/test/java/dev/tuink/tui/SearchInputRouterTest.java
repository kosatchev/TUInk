package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.SearchOptions;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PasteEvent;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.input.TuiInputEvent;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.TextField;
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
    void horizontalArrowsEditFieldsUntilBoundaryThenTraverseTheRing() {
        TextField query = new TextField();
        query.set("ab");
        Fixture fixture = fixture(query, new TextField(), 80, viewport(5, 80));

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(1, query.cursor());
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(0, query.cursor());

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(SearchFocus.ALL_BUTTON, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.REPLACE);
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(SearchFocus.NEXT_BUTTON, fixture.focus.focused().orElseThrow());
    }

    @Test
    void upAndDownUseTheFiveLegacyVerticalPairs() {
        Fixture fixture = fixture(80);
        SearchFocus[][] pairs = {
            {SearchFocus.FIND, SearchFocus.REPLACE},
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
        assertEquals(SearchFocus.REPLACE, fixture.focus.focused().orElseThrow());
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
            SearchBarView.Action.PASTE_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertEquals(before, fixture.replacement.text());

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.CASE);
        assertCaptured(fixture.route(new TextInput("ignored")));
        assertCaptured(fixture.route(new PasteEvent("ignored", false)));
        assertAction(
            SearchBarView.Action.PASTE_REJECTED,
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
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.END)));

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.FIND);
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.CTRL)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.ENTER, KeyStroke.Modifier.SHIFT)
        ));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.ALT)
        ));
        assertEquals("a", query.text());
    }

    @Test
    void typedIntentsPreserveMigrationEnterAndSpaceParity() {
        Fixture fixture = fixture(80);

        assertAction(
            SearchBarView.Action.FIND_NEXT,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.CASE);
        assertAction(
            SearchBarView.Action.FIND_NEXT,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        assertAction(
            SearchBarView.Action.TOGGLE_CASE,
            fixture.route(KeyStroke.of(KeyStroke.Key.SPACE))
        );
        assertAction(
            SearchBarView.Action.TOGGLE_CASE,
            fixture.route(new TextInput(" "))
        );

        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.NEXT_BUTTON);
        assertAction(
            SearchBarView.Action.FIND_NEXT,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.SPACE)));
        assertCaptured(fixture.route(new TextInput(" ")));
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.PREV_BUTTON);
        assertAction(
            SearchBarView.Action.FIND_PREVIOUS,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.REPLACE_BUTTON);
        assertAction(
            SearchBarView.Action.REPLACE,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );
        fixture.focus.focus(fixture.placement.focusScope(), SearchFocus.ALL_BUTTON);
        assertAction(
            SearchBarView.Action.REPLACE_ALL,
            fixture.route(KeyStroke.of(KeyStroke.Key.ENTER))
        );

        assertAction(
            SearchBarView.Action.CLOSE_REQUESTED,
            fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE))
        );
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.ESCAPE, KeyStroke.Modifier.SHIFT)
        ));
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
            SearchBarView.Action.PASTE_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.DELETE)));
        int hiddenCursor = query.cursor();
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.END)));
        assertEquals(hiddenCursor, query.cursor());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.CTRL)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.ENTER, KeyStroke.Modifier.SHIFT)
        ));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertEquals("keep", query.text());
        assertEquals("also-keep", replacement.text());
        assertTrue(fixture.focus.focused().isEmpty());

        Rect partialCase = fixture.placement.targetBounds(SearchFocus.CASE);
        assertFalse(fixture.placement.focusScope().target(SearchFocus.CASE).orElseThrow().enabled());
        assertCaptured(fixture.route(pointer(
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
        SearchBarView.Placement placement
    ) {
        private SearchInputRouter.Result route(TuiInputEvent event) {
            return SearchInputRouter.route(focus, placement, event);
        }
    }
}

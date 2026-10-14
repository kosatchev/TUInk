package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Explicit whitespace searches use the same window behavior as other literal queries. */
class SearchWhitespaceIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\u2003"})
    void whitespaceQueriesKeepTheirCountAndMatchingTabMarkers(String query) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false,
            List.of("left" + query + "middle" + query + "right"))) {
            window.event(ctrl(KeyStroke.Key.N));
            window.event(new TextInput("nomatch"));
            window.event(ctrl(KeyStroke.Key.TAB));
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput(query));

            assertEquals(query, window.query.text());
            assertTrue(window.screenRow(window.height - 1).contains("Find 2"), window.screenRow(window.height - 1));
            assertTrue(window.screenRow(1).contains("#document.txt"), window.screenRow(1));
            assertFalse(window.screenRow(1).contains("#--no name--"), window.screenRow(1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\u2003"})
    void ctrlFUsesAnExplicitlySelectedWhitespaceQuery(String query) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(query + "word"))) {
            for (int index = 0; index < query.codePointCount(0, query.length()); index++) {
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
            }

            window.event(ctrl(KeyStroke.Key.F));

            assertEquals(query, window.query.text());
            assertTrue(window.screenRow(window.height - 1).contains("Find 1"), window.screenRow(window.height - 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\u2003"})
    void nextFirstRevealsTheCurrentWhitespaceMatchAfterManualScrolling(String query) throws Exception {
        List<String> lines = new ArrayList<>(Collections.nCopies(120, "unchanged"));
        lines.set(0, "a" + query + "b");
        lines.set(70, "c" + query + "d");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, lines)) {
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput(query));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            Position cursor = window.active().state().getCursor();
            Position anchor = window.active().state().getSelectionAnchor();
            assertEquals(new Position(0, 1 + query.length()), cursor);

            window.wheel(PointerEvent.Kind.SCROLL_DOWN, 8);
            assertTrue(window.active().state().getTopLine() > cursor.line());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(anchor, window.active().state().getSelectionAnchor());
            assertEquals(0, window.active().state().getTopLine());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(new Position(70, 1 + query.length()), window.active().state().getCursor());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

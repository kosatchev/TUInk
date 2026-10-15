package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class LongSearchRevisitIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {256, 257, 600})
    void nextRevealsTheCurrentOffscreenMatchRegardlessOfTheQueryLength(int length) throws Exception {
        String query = "q".repeat(length);
        List<String> lines = new ArrayList<>(Collections.nCopies(120, "unchanged"));
        lines.set(0, query);
        lines.set(70, query);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, lines)) {
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput(query));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            Position cursor = window.active().state().getCursor();
            Position anchor = window.active().state().getSelectionAnchor();
            assertEquals(new Position(0, length), cursor);
            window.wheel(PointerEvent.Kind.SCROLL_DOWN, 8);
            assertTrue(window.active().state().getTopLine() > cursor.line());

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(anchor, window.active().state().getSelectionAnchor());
            assertEquals(0, window.active().state().getTopLine());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(new Position(70, length), window.active().state().getCursor());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 257})
    void ctrlFStillLimitsAutomaticQueryPrefillFromADocumentSelection(int length) throws Exception {
        String selected = "q".repeat(length);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(selected))) {
            window.event(ctrl(KeyStroke.Key.A));

            window.event(ctrl(KeyStroke.Key.F));

            assertEquals(length <= 256 ? selected : "", window.query.text());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

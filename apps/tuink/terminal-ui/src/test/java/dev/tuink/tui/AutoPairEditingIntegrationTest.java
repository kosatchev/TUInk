package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutoPairEditingIntegrationTest {
    @TempDir Path directory;

    @Test
    void backspaceAfterAnEscapedQuoteRetainsTheClosingQuoteAndRoundTripsThroughHistoryAndSave()
        throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(""))) {
            window.event(new TextInput("\""));
            window.event(new TextInput("\\"));
            window.event(new TextInput("\""));
            List<String> beforeBackspace = List.of("\"\\\"\"");
            assertEquals(beforeBackspace, window.active().tabSession().model().snapshotLines());

            window.event(KeyStroke.of(KeyStroke.Key.BACKSPACE));

            List<String> expected = List.of("\"\\\"");
            assertEquals(expected, window.active().tabSession().model().snapshotLines());
            assertArrayEquals(new byte[0], Files.readAllBytes(window.document));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(beforeBackspace, window.active().tabSession().model().snapshotLines());
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(expected, window.active().tabSession().model().snapshotLines());
            assertArrayEquals(new byte[0], Files.readAllBytes(window.document));
            assertTrue(window.active().state().isDirty());

            window.event(ctrl(KeyStroke.Key.S));

            assertArrayEquals(new byte[] {34, 92, 34}, Files.readAllBytes(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacingAnApostropheSelectionHasTheSameResultInBothDirections(boolean reverse) throws Exception {
        String original = "x' ";
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(original))) {
            if (reverse) {
                window.event(ctrl(KeyStroke.Key.END));
            } else {
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            }
            KeyStroke select = KeyStroke.of(reverse ? KeyStroke.Key.ARROW_LEFT : KeyStroke.Key.ARROW_RIGHT,
                KeyStroke.Modifier.SHIFT);
            window.event(select);
            window.event(select);
            Position cursor = new Position(0, reverse ? 1 : 3);
            Position anchor = new Position(0, reverse ? 3 : 1);
            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(anchor, window.active().state().getSelectionAnchor());

            window.event(new TextInput("'"));

            assertEquals(List.of("x'"), window.active().tabSession().model().snapshotLines());
            assertEquals(original, Files.readString(window.document));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of(original), window.active().tabSession().model().snapshotLines());
            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(anchor, window.active().state().getSelectionAnchor());
            assertFalse(window.active().state().isDirty());
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(List.of("x'"), window.active().tabSession().model().snapshotLines());
            assertEquals(original, Files.readString(window.document));
            assertTrue(window.active().state().isDirty());

            window.event(ctrl(KeyStroke.Key.S));

            assertArrayEquals(new byte[] {120, 39}, Files.readAllBytes(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.Position;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The application's literal fields retain tabs through both physical paste routes. */
class FieldTabPasteIntegrationTest {
    private static final String QUERY = "\t\tneedle\t\t";
    private static final String REPLACEMENT = "\t\treplacement\t\t";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedDocumentTabsRoundTripThroughFindAndMatchOnlyTheLiteralText(boolean terminalPaste) throws Exception {
        List<String> original = List.of(QUERY, QUERY.replace('\t', ' '));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, original)) {
            copyFirstLine(window, QUERY);
            window.event(ctrl(KeyStroke.Key.F));
            assertEquals(QUERY, window.query.text(), "explicit selection prefill retains the literal query");
            window.event(ctrl(KeyStroke.Key.A));

            paste(window, QUERY, terminalPaste);

            assertEquals(QUERY, window.query.text());
            assertFieldClipboardRoundTrip(window, window.query, QUERY);
            assertTrue(window.screenRow(window.height - 1).contains("Find 1"), window.screenRow(window.height - 1));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(new Position(0, 0), window.active().state().getSelectionAnchor());
            assertEquals(new Position(0, QUERY.length()), window.active().state().getCursor());
            assertEquals(original, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedDocumentTabsRoundTripThroughReplaceAndRemainInTheReplacement(boolean terminalPaste) throws Exception {
        List<String> original = List.of(REPLACEMENT, "needle needle", "needle");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, original)) {
            copyFirstLine(window, REPLACEMENT);
            window.event(ctrl(KeyStroke.Key.F));
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new PasteEvent("needle"));
            window.focusSearch(SearchFocus.REPLACE);

            paste(window, REPLACEMENT, terminalPaste);

            assertEquals(REPLACEMENT, window.replacement.text());
            assertFieldClipboardRoundTrip(window, window.replacement, REPLACEMENT);
            window.focusSearch(SearchFocus.ALL_BUTTON);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of(REPLACEMENT, REPLACEMENT + " " + REPLACEMENT, REPLACEMENT),
                window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.active().tabSession().engine().undo());
            assertEquals(original, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedPathTabsRoundTripThroughSaveAsAndCreateTheExactFilename(boolean terminalPaste) throws Exception {
        Path target = directory.resolve("\t\tchosen\t\t.txt\t");
        String name = target.toString();
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(name))) {
            copyFirstLine(window, name);
            window.event(ctrl(KeyStroke.Key.K));
            window.event(ctrl(KeyStroke.Key.A));

            paste(window, name, terminalPaste);

            assertEquals(name, window.filename.text());
            assertFieldClipboardRoundTrip(window, window.filename, name);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(Files.isRegularFile(target));
            assertEquals(name, Files.readString(target));
            assertFalse(Files.exists(Path.of(name.replace('\t', ' '))), "a space filename must not replace the tab filename");
            assertFalse(Files.exists(directory.resolve(" chosen .txt")), "collapsed whitespace must not change the target");
            assertEquals(target, window.active().state().getCurrentFile());
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @MethodSource("newlinePasteTargets")
    void literalTabPolicyStillJoinsPastedLinesWithSpaces(Field target, boolean terminalPaste) throws Exception {
        String multiline = "first\r\nsecond\n\nthird\rfourth";
        String singleLine = "first second third fourth";
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("document"))) {
            TextField field;
            if (target == Field.SAVE_AS) {
                window.event(ctrl(KeyStroke.Key.K));
                field = window.filename;
            } else {
                window.event(ctrl(KeyStroke.Key.F));
                if (target == Field.REPLACE) {
                    window.focusSearch(SearchFocus.REPLACE);
                }
                field = target == Field.FIND ? window.query : window.replacement;
            }
            window.host.clipboard.setText(multiline);
            window.event(ctrl(KeyStroke.Key.A));

            paste(window, multiline, terminalPaste);

            assertEquals(singleLine, field.text());
            assertEquals(List.of("document"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    private static Stream<Arguments> newlinePasteTargets() {
        return Stream.of(Field.values()).flatMap(field -> Stream.of(Arguments.of(field, false), Arguments.of(field, true)));
    }

    private static void copyFirstLine(EditorWindowFixture window, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
        }
        window.event(ctrl(KeyStroke.Key.C));
        assertEquals(expected, window.host.clipboard.text());
    }

    private static void assertFieldClipboardRoundTrip(EditorWindowFixture window, TextField field, String expected) {
        window.event(ctrl(KeyStroke.Key.A));
        window.event(ctrl(KeyStroke.Key.C));
        assertEquals(expected, window.host.clipboard.text());
        window.event(KeyStroke.of(KeyStroke.Key.BACKSPACE));
        assertEquals("", field.text());
        window.event(ctrl(KeyStroke.Key.V));
        assertEquals(expected, field.text());
    }

    private static void paste(EditorWindowFixture window, String text, boolean terminalPaste) {
        window.event(terminalPaste ? new PasteEvent(text) : ctrl(KeyStroke.Key.V));
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }

    private enum Field { FIND, REPLACE, SAVE_AS }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NarrowSearchIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {30, 35})
    void narrowSearchKeepsBothValuesEditableAndReplacementShortcutsUsable(int columns) throws Exception {
        try (EditorWindowFixture window = searchWindow()) {
            window.focusSearch(SearchFocus.ALL_BUTTON);
            window.resize(24, columns);

            assertTrue(window.screenRow(window.rowStartingWith("Find: ")).startsWith("Find: alpha"));
            assertTrue(window.screenRow(window.rowStartingWith("Replace: ")).startsWith("Replace: beta"));
            assertTrue(screen(window).contains("[Replace all]"));
            assertEquals(SearchFocus.ALL_BUTTON, window.host.searchFocusManager().focused().orElseThrow());
            window.focusSearch(SearchFocus.REPLACE);
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("gamma"));
            assertTrue(window.screenRow(window.rowStartingWith("Replace: ")).startsWith("Replace: gamma"));

            window.event(KeyStroke.of(KeyStroke.Key.R, KeyStroke.Modifier.CTRL));
            assertEquals(List.of("gamma alpha"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            assertEquals(List.of("gamma gamma"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {43, 44, 49, 50})
    void longerLabelsWrapWithoutTakingSpaceFromTheFields(int columns) throws Exception {
        try (EditorWindowFixture window = searchWindow()) {
            window.resize(24, columns);

            assertTrue(screen(window).contains("[All tabs: ]"));
            assertTrue(screen(window).contains("[Replace all]"));
            assertEquals("alpha", window.query.text());
            assertEquals("beta", window.replacement.text());

            window.command(TuinkCommand.SEARCH_REPLACE_ALL);

            assertEquals(List.of("beta beta"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @CsvSource({"中,😀", "😀,中"})
    void wideValuesNeedAGlyphAndCaretCellInBothFieldsBeforeReplacing(String query, String replacement)
        throws Exception {
        String original = query + " " + query;
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(original))) {
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(query));
            window.focusSearch(SearchFocus.REPLACE);
            window.event(new TextInput(replacement));
            window.resize(24, 8);
            long revision = window.active().tabSession().model().revision();

            window.event(KeyStroke.of(KeyStroke.Key.R, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.SEARCH_REPLACE);
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);

            assertEquals(List.of(original), window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertEquals(query, window.query.text());
            assertEquals(replacement, window.replacement.text());
            assertEquals("Enlarge terminal to show Find and Replace fields before replacing", window.notification.message);

            window.resize(24, 12);
            assertTrue(window.screenRow(window.rowStartingWith("Find: ")).contains(query));
            assertTrue(window.screenRow(window.rowStartingWith("Replace: ")).contains(replacement));
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            assertEquals(List.of(replacement + " " + replacement),
                window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @CsvSource({"24,1", "24,7", "24,8", "2,80", "3,80"})
    void hiddenFieldContextBlocksPhysicalAndSemanticReplacementUntilTheTerminalGrows(int rows, int columns)
        throws Exception {
        try (EditorWindowFixture window = searchWindow()) {
            window.focusSearch(SearchFocus.ALL_BUTTON);
            window.resize(rows, columns);
            long revision = window.active().tabSession().model().revision();

            window.event(KeyStroke.of(KeyStroke.Key.R, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.SEARCH_REPLACE);
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);

            assertEquals(List.of("alpha alpha"), window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
            assertEquals("alpha", window.query.text());
            assertEquals("beta", window.replacement.text());
            assertEquals("Enlarge terminal to show Find and Replace fields before replacing", window.notification.message);

            window.resize(24, 80);
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            assertEquals(List.of("beta beta"), window.active().tabSession().model().snapshotLines());
        }
    }

    private EditorWindowFixture searchWindow() throws Exception {
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("alpha alpha"));
        window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
        window.event(new TextInput("alpha"));
        window.focusSearch(SearchFocus.REPLACE);
        window.event(new TextInput("beta"));
        return window;
    }

    private static String screen(EditorWindowFixture window) {
        return java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow)
            .collect(java.util.stream.Collectors.joining("\n"));
    }
}

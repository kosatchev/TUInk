package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoundaryNavigationIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void documentBoundariesAndSelectionsRevealTheirTargetInReadingAndWrappedViews(boolean reading, boolean wrapped)
        throws Exception {
        List<String> lines = new ArrayList<>(IntStream.range(0, 60).mapToObj(i -> "row " + i + " 中😀").toList());
        lines.add("");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, reading, lines)) {
            window.ui.wrapLinesSetting = wrapped;
            window.frame();
            long revision = window.active().tabSession().model().revision();
            Position end = new Position(60, 0);

            window.event(KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.CTRL));
            assertEquals(end, window.active().state().getCursor());
            assertTrue(window.active().state().getTopLine() > 0);
            window.command(TuinkCommand.CURSOR_DOCUMENT_START);
            assertEquals(new Position(0, 0), window.active().state().getCursor());
            assertEquals(0, window.active().state().getTopLine());
            window.event(KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT));
            assertEquals(end, window.active().state().getCursor());
            assertEquals(new Position(0, 0), window.active().state().getSelectionAnchor());
            window.command(TuinkCommand.CURSOR_SELECT_DOCUMENT_START);
            assertEquals(new Position(0, 0), window.active().state().getCursor());
            window.command(TuinkCommand.CURSOR_DOCUMENT_END);
            assertNull(window.active().state().getSelectionAnchor());
            window.event(KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.CURSOR_SELECT_DOCUMENT_END);
            assertEquals(end, window.active().state().getCursor());
            window.event(KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT));
            assertEquals(new Position(0, 0), window.active().state().getCursor());

            assertEquals(lines, window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void shiftLineBoundariesKeepTheAnchorAndReplaceTheSelectedUnicodeRange(boolean smart, boolean semantic)
        throws Exception {
        String text = "  e\u0301😀  ";
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(text))) {
            window.ui.smartHomeEndSetting = smart;
            window.active().tabSession().engine().moveCursorTo(new Position(0, 4));
            window.frame();
            if (semantic) window.command(TuinkCommand.CURSOR_SELECT_HOME);
            else window.event(KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT));
            assertEquals(new Position(0, smart ? 2 : 0), window.active().state().getCursor());
            assertEquals(new Position(0, 4), window.active().state().getSelectionAnchor());
            if (semantic) window.command(TuinkCommand.CURSOR_SELECT_END);
            else window.event(KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.SHIFT));
            assertEquals(new Position(0, smart ? 6 : 8), window.active().state().getCursor());
            assertEquals(new Position(0, 4), window.active().state().getSelectionAnchor());

            window.event(new TextInput("X"));
            assertEquals(List.of(smart ? "  e\u0301X  " : "  e\u0301X"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of(text), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundaryCommandsStayWithSearchAndFilenameFields(boolean saveAs) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("document", "last"))) {
            window.command(saveAs ? TuinkCommand.DOCUMENT_SAVE_AS : TuinkCommand.SEARCH_OPEN);
            if (saveAs) window.filename.set("a😀bc");
            else window.query.set("a😀bc");
            window.frame();
            var field = saveAs ? window.filename : window.query;

            window.command(TuinkCommand.CURSOR_SELECT_DOCUMENT_START);
            assertEquals("a😀bc", field.selectedText());
            window.event(KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.CTRL));
            assertEquals(field.length(), field.cursor());
            assertFalse(field.hasSelection());
            window.command(TuinkCommand.CURSOR_SELECT_HOME);
            assertEquals("a😀bc", field.selectedText());
            window.command(TuinkCommand.CURSOR_DOCUMENT_START);
            window.command(TuinkCommand.CURSOR_SELECT_END);
            assertEquals("a😀bc", field.selectedText());
            window.command(TuinkCommand.CURSOR_SELECT_DOCUMENT_END);
            assertEquals("a😀bc", field.selectedText());
            if (saveAs) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            else window.focusSearch(SearchFocus.CASE);
            for (TuinkCommand command : List.of(TuinkCommand.CURSOR_DOCUMENT_START, TuinkCommand.CURSOR_DOCUMENT_END,
                TuinkCommand.CURSOR_SELECT_HOME, TuinkCommand.CURSOR_SELECT_END,
                TuinkCommand.CURSOR_SELECT_DOCUMENT_START, TuinkCommand.CURSOR_SELECT_DOCUMENT_END)) {
                window.command(command);
                assertEquals("a😀bc", field.text());
                assertEquals(new Position(0, 0), window.active().state().getCursor());
                assertEquals(List.of("document", "last"), window.active().tabSession().model().snapshotLines());
            }
            if (saveAs) assertEquals(SaveAsBarView.Target.SAVE, window.host.saveAsFocusManager.focused().orElseThrow());
            else assertEquals(SearchFocus.CASE, window.host.searchFocusManager.focused().orElseThrow());
        }
    }
}

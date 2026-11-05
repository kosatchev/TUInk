package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.render.EditorStyleAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Syntax and bracket decorations must agree after a JavaScript expression continues on the next line. */
class JavaScriptContinuationIntegrationTest {
    private static final CellStyle STRING = new CellStyle(CellColor.BRIGHT_GREEN, CellColor.DEFAULT, false, false);
    private static final String REGEX = "/[{}]/.test(value);";

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"js", "ts"})
    void multilineDivisionKeepsCodeBracketsWhileARegexAfterABlockStillHasStringColor(String extension) throws Exception {
        List<String> lines = List.of("const result = numerator", "  / denominator[i] / scale;",
            "if (ready) {", "  consume(result);", "}", REGEX);
        try (EditorWindowFixture window = open(extension, lines)) {
            assertDivision(window, lines.get(1), 1);
            assertStyle(window, lines.get(1), lines.get(1).lastIndexOf('/'), CellStyle.DEFAULT);
            assertStyle(window, lines.get(1), lines.get(1).indexOf("scale"), CellStyle.DEFAULT);
            assertRealRegex(window, 5);
            assertEquals(lines, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"js", "ts"})
    void splittingJoiningAndUndoingDivisionRepaintsLexicalStateAndBracketPairs(String extension) throws Exception {
        String expression = "const result = numerator / denominator[i] / scale;";
        List<String> original = List.of(expression, "if (ready) {", "  consume(result);", "}", REGEX);
        try (EditorWindowFixture window = open(extension, original)) {
            var engine = window.active().tabSession().engine();
            var model = window.active().tabSession().model();
            long revision = model.revision();
            int slash = expression.indexOf('/');
            String continuation = expression.substring(slash);
            engine.moveCursorTo(new Position(0, slash));
            window.frame();

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(expression.substring(0, slash), model.getLine(0));
            assertEquals(continuation, model.getLine(1));
            assertTrue(model.revision() > revision);
            assertDivision(window, continuation, 1);
            assertRealRegex(window, 5);

            window.command(TuinkCommand.EDIT_UNDO);

            assertEquals(original, model.snapshotLines());
            assertFalse(window.active().state().isDirty());
            assertDivision(window, expression, 0);
            assertRealRegex(window, 4);

            window.command(TuinkCommand.EDIT_REDO);
            assertDivision(window, continuation, 1);
            assertRealRegex(window, 5);
            engine.moveCursorTo(new Position(1, 0));
            window.frame();

            window.event(KeyStroke.of(KeyStroke.Key.BACKSPACE));

            assertEquals(original, model.snapshotLines());
            assertDivision(window, expression, 0);
            assertRealRegex(window, 4);
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(continuation, model.getLine(1));
            assertDivision(window, continuation, 1);
            assertRealRegex(window, 5);
        }
    }

    private EditorWindowFixture open(String extension, List<String> lines) throws Exception {
        Path file = directory.resolve("continuation." + extension);
        Files.writeString(file, String.join("\n", lines));
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("initial"));
        try {
            window.ui.rainbowBracketsSetting = false;
            window.ui.autoIndentSetting = false;
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            for (int attempt = 0; attempt < 10; attempt++) {
                if (window.tree.state().selectedId() != null && window.tree.state().selectedId().path().equals(file)) {
                    window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                    assertEquals(file, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
                    assertTrue(PresentationSyntaxPolicy.source(window.ui, window.host.theme).enabled());
                    return window;
                }
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            }
            throw new AssertionError("Script is missing from the open-file picker");
        } catch (Exception | Error failure) {
            window.close();
            throw failure;
        }
    }

    private static void assertDivision(EditorWindowFixture window, String line, int sourceLine) {
        assertStyle(window, line, line.indexOf('/'), CellStyle.DEFAULT);
        assertStyle(window, line, line.indexOf("denominator"), CellStyle.DEFAULT);
        window.active().tabSession().engine().moveCursorTo(new Position(sourceLine, line.indexOf('[')));
        window.frame();
        CellStyle pair = EditorStyleAdapter.toCell(window.host.styles.syntax().bracketMatch());
        assertStyle(window, line, line.indexOf('['), pair);
        assertStyle(window, line, line.indexOf(']'), pair);
    }

    private static void assertRealRegex(EditorWindowFixture window, int sourceLine) {
        window.active().tabSession().engine().moveCursorTo(new Position(sourceLine, REGEX.indexOf('[')));
        window.frame();
        for (int offset = 0; offset < "/[{}]/".length(); offset++) assertStyle(window, REGEX, offset, STRING);
    }

    private static void assertStyle(EditorWindowFixture window, String source, int offset, CellStyle expected) {
        String content = source.stripLeading();
        int contentOffset = offset - (source.length() - content.length());
        assertTrue(contentOffset >= 0, source);
        for (int row = 0; row < window.height; row++) {
            int start = window.screenRow(row).indexOf(content);
            if (start >= 0) {
                assertEquals(expected, window.screenStyle(row, start + contentOffset), source + " at " + offset);
                return;
            }
        }
        throw new AssertionError("Source line is not painted: " + source);
    }
}

package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeywordIndentIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void typingElseAfterAnAlignedPythonCommentKeepsTheFunctionScope() throws Exception {
        List<String> original = List.of("def work():", "    if ready:", "        run()", "    # fallback");
        try (EditorWindowFixture window = prepared("main.py", original)) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "else:");

            assertEquals("    else:", window.active().tabSession().model().getLine(4));
            assertEquals(new Position(4, 9), window.active().state().getCursor());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals("    ", window.active().tabSession().model().getLine(4));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(original, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void typingTheNextInlinePythonCasePreservesItsMatchIndent() throws Exception {
        try (EditorWindowFixture window = prepared("main.py", List.of("match value:", "    case 1: run()"))) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "case 2:");

            assertEquals("    case 2:", window.active().tabSession().model().getLine(2));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("        ", window.active().tabSession().model().getLine(3));
        }
    }

    @Test
    void typingElseAfterAnAlignedJavaClosingBraceKeepsTheBranchIndent() throws Exception {
        List<String> original = List.of("class Main {", "    void work() {", "        if (ready) {",
            "            run();", "        }");
        try (EditorWindowFixture window = prepared("Main.java", original)) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "else ");

            assertEquals("        else ", window.active().tabSession().model().getLine(5));
        }
    }

    @Test
    void aBodyToClauseDedentRemainsPartOfTheTypedUndoStep() throws Exception {
        List<String> original = List.of("def work():", "    if ready:", "        run()");
        try (EditorWindowFixture window = prepared("main.py", original)) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "else:");

            assertEquals("    else:", window.active().tabSession().model().getLine(3));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals("        ", window.active().tabSession().model().getLine(3));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(original, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            window.event(ctrl(KeyStroke.Key.Y));
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals("    else:", window.active().tabSession().model().getLine(3));
        }
    }

    @Test
    void ordinaryQuotedCodeKeepsBodyToClauseAssistAvailable() throws Exception {
        try (EditorWindowFixture window = prepared("main.py", List.of("title = \"hello\"", "if ready:", "    run()"))) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "else:");
            assertEquals("else:", window.active().tabSession().model().getLine(3));
        }
        try (EditorWindowFixture window = prepared("Main.java", List.of("switch (value) {", "    case \"one\":", "        emit(\"x\");"))) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "case \"two\":");
            assertEquals("    case \"two\":", window.active().tabSession().model().getLine(3));
        }
    }

    @Test
    void aTypedClosingBraceAfterAStringStatementKeepsTheFollowingElseAligned() throws Exception {
        try (EditorWindowFixture window = prepared("Main.java", List.of("class Main {", "    void work() {",
            "        if (ready) {", "            run(\"x\");"))) {
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "}");
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            type(window, "else ");
            assertEquals("        else ", window.active().tabSession().model().getLine(5));
        }
    }

    private EditorWindowFixture prepared(String filename, List<String> lines) throws Exception {
        EditorWindowFixture window = new EditorWindowFixture(directory, false, lines);
        var engine = window.active().tabSession().engine();
        engine.saveAs(directory.resolve(filename));
        engine.moveCursorTo(new Position(lines.size() - 1, lines.getLast().length()));
        window.frame();
        return window;
    }

    private static void type(EditorWindowFixture window, String text) {
        for (char ch : text.toCharArray()) {
            window.event(new TextInput(String.valueOf(ch)));
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}

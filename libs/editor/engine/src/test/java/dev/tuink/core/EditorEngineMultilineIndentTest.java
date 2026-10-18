package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineMultilineIndentTest {
    @TempDir Path directory;

    @Test
    void multilineLiteralsAndCommentsCopyWhitespaceWithoutTreatingTheirContentsAsCode() {
        record Example(String file, String opening, String body) {}
        for (Example example : List.of(
            new Example("main.py", "payload = '''", "    if ready:"),
            new Example("main.py", "payload = r'''", "    \\''' if ready:"),
            new Example("main.py", "payload = f\"\"\"", "    {"),
            new Example("Main.java", "/*", "    prose {"),
            new Example("Main.java", "String value = \"\"\"", "    prose {"),
            new Example("main.js", "const value = `", "    prose {"),
            new Example("main.ts", "const value = `${(() => { return `nested", "    prose {"),
            new Example("main.go", "value := `", "    prose {"),
            new Example("main.kt", "val value = \"\"\"", "    prose {"),
            new Example("main.cs", "var value = @\"", "    prose {"),
            new Example("query.sql", "SELECT 'value", "    prose {"),
            new Example("config.toml", "value = '''", "    prose {"),
            new Example("config.yaml", "value: |", "    child:"),
            new Example("page.html", "<!--", "    prose {"),
            new Example("page.xml", "<node value=\"", "    prose {"),
            new Example("main.rs", "let value = r##\"", "    prose {"),
            new Example("main.cpp", "auto value = R\"tag(", "    prose {")
        )) {
            assertNewline(example.file(), List.of(example.opening(), example.body()), "    ");
        }
    }

    @Test
    void closingAMultilineTokenResumesCodeOnTheSameLine() {
        record Example(String file, List<String> lines) {}
        for (Example example : List.of(
            new Example("Main.java", List.of("/*", "    */ if (ready) {")),
            new Example("main.py", List.of("payload = '''", "    '''; call(")),
            new Example("main.js", List.of("const value = `", "    `; if (ready) {")),
            new Example("main.ts", List.of("const value = `${(() => { return `nested", "text`; })()}`;", "    if (ready) {")),
            new Example("main.go", List.of("value := `", "    `; if ready {")),
            new Example("main.kt", List.of("val value = \"\"\"", "    \\\"\"\"; call(")),
            new Example("main.cs", List.of("var value = @\"", "    \"\"still literal\"; if (ready) {")),
            new Example("main.rs", List.of("let value = r##\"", "    \"##; if ready {")),
            new Example("main.cpp", List.of("auto value = R\"tag(", "    )tag\"; if (ready) {")),
            new Example("config.yaml", List.of("value: |", "    body:", "    more:", "next:"))
        )) {
            assertNewline(example.file(), example.lines(), example.file().endsWith("yaml") ? "    " : "        ");
        }
    }

    @Test
    void commentsRegexAndQuotesCannotInventALiteralBoundaryInAnotherToken() {
        record Example(String file, List<String> prefix) {}
        for (Example example : List.of(
            new Example("Main.java", List.of("String value = \"/*\";", "if (ready) {")),
            new Example("main.py", List.of("value = \"'''\"", "if ready:")),
            new Example("main.js", List.of("const pattern = /[/*`]/;", "if (ready) {")),
            new Example("main.js", List.of("if (ready) /[/*`]/.test(value);", "if (ready) {")),
            new Example("main.cpp", List.of("if (value == 0xA'B) {")),
            new Example("main.cpp", List.of("if (value == 12'000'000) {"))
        )) {
            assertNewline(example.file(), example.prefix(), "    ");
        }
        for (String file : List.of("main.py", "Main.java", "main.js")) {
            assertNewline(file, List.of("    value = \"unfinished {"), "    ");
        }
    }

    @Test
    void nestedCommentsKeepTheOuterCommentActiveUntilItsOwnClosingDelimiter() {
        for (String file : List.of("main.rs", "main.kt", "main.swift", "main.scala")) {
            assertNewline(file, List.of("/* outer", "/* inner */", "    prose {"), "    ");
            assertNewline(file, List.of("/* outer", "/* inner */", "    */ if (ready) {"), "        ");
        }
    }

    @Test
    void literalContentsCannotTriggerClosingOrClauseOutdentsAndUndoRestoresTheTrigger() {
        for (String file : List.of("main.py", "Main.java", "main.js")) {
            String opening = file.endsWith("py") ? "payload = '''" : file.endsWith("js") ? "const value = `" : "/*";
            Fixture fixture = fixture(file, List.of(opening, "if ready:", "    run()", "    else"));
            fixture.engine().insertChar(':');
            assertEquals("    else:", fixture.model().getLine(3), file);
            assertTrue(fixture.engine().undo());
            assertEquals("    else", fixture.model().getLine(3));
            assertFalse(fixture.state().isDirty());
            assertTrue(fixture.engine().redo());
            assertEquals("    else:", fixture.model().getLine(3));
            assertNewline(file, List.of(opening, "    {", "        }"), "        ");
        }
    }

    @Test
    void saveAsLanguageChangesInvalidateLexicalCheckpointsWithoutAContentRevision() throws Exception {
        Fixture fixture = fixture("Main.java", List.of("payload = '''", "    if ready:"));
        fixture.engine().insertNewline();
        assertTrue(fixture.engine().undo());
        long revision = fixture.model().revision();
        fixture.engine().saveAs(directory.resolve("main.py"));
        assertEquals(revision, fixture.model().revision());
        fixture.engine().insertNewline();
        assertEquals("    ", fixture.model().getLine(2));
        assertTrue(fixture.engine().undo());
        assertFalse(fixture.state().isDirty());
        fixture.engine().saveAs(directory.resolve("main.txt"));
        fixture.engine().moveEnd();
        fixture.engine().insertInput(" {");
        fixture.engine().insertNewline();
        assertEquals("        ", fixture.model().getLine(2));
    }

    private static void assertNewline(String file, List<String> original, String expected) {
        Fixture fixture = fixture(file, original);
        Position cursor = fixture.state().getCursor();
        fixture.engine().insertNewline();
        assertEquals(expected, fixture.model().getLine(original.size()), file + " " + original);
        assertTrue(fixture.engine().undo());
        assertEquals(original, fixture.model().snapshotLines());
        assertEquals(cursor, fixture.state().getCursor());
        assertFalse(fixture.state().isDirty());
        assertTrue(fixture.engine().redo());
        assertEquals(expected, fixture.model().getLine(original.size()));
    }

    private static Fixture fixture(String filename, List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of(filename));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveCursorTo(new Position(lines.size() - 1, lines.getLast().length()));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineNewlineIndentTest {
    @Test
    void pythonBlocksRecognizeCommentsParenthesesAndWhitespaceAfterTheKeyword() {
        for (String text : List.of(
            "if ready: # explanation", "if ready:# explanation", "if(ready):", "while(flag):",
            "if\tready:", "elif(ready):", "if name == \"?\":", "if name == \"#?\":# comment",
            "if name == \"\\\"#\": # comment", "if name == '#': # comment",
            "if name == '''it's # literal''': # comment", "if name == \"\"\"a\" # literal\"\"\": # comment",
            "with(resource): # comment", "async\twith resource: # comment", "async def run():",
            "async for item in stream:", "except(Error): # comment", "except* Error: # comment",
            "else: # comment", "try: # comment", "finally: # comment", "match value:", "case value:"
        )) {
            Fixture fixture = fixture("test.py", text);

            fixture.engine.insertNewline();

            assertEquals(List.of(text, "    "), fixture.model.snapshotLines(), text);
            assertEquals(new Position(1, 4), fixture.state.getCursor(), text);
        }
    }

    @Test
    void pythonKeywordsRemainCaseSensitiveAndDoNotMatchIdentifierPrefixes() {
        for (String text : List.of(
            "IF ready:", "While(flag):", "if_ready:", "ifready:", "except_value:", "exception:",
            "asyncwith resource:", "async if ready:", "value = ':'", "# if ready:", "'if ready:'"
        )) {
            Fixture fixture = fixture("test.py", text);

            fixture.engine.insertNewline();

            assertEquals("", fixture.model.getLine(1), text);
        }
    }

    @Test
    void commentsAreExcludedWhileQuotedCommentMarkersRemainLiteral() {
        record Example(String filename, String text, String indent) {}
        for (Example example : List.of(
            new Example("Main.java", "// comment {", ""),
            new Example("Main.java", "/* comment { */", ""),
            new Example("Main.java", "/* comment {", ""),
            new Example("Main.java", "if (ready) { // explanation", "    "),
            new Example("Main.java", "if (ready) { /* explanation */", "    "),
            new Example("Main.java", "if (text.equals(\"//\")) { // explanation", "    "),
            new Example("Main.java", "if (a / b > 0) { // explanation", "    "),
            new Example("Main.java", "/* prefix */ if (ready) {", "    "),
            new Example("Main.java", "String url = \"https://example.org/{\";", ""),
            new Example("test.py", "# comment (", ""),
            new Example("test.py", "if ready: # explanation {", "    "),
            new Example("config.yaml", "key: # explanation", "    "),
            new Example("config.yaml", "key#part: # explanation", "    "),
            new Example("config.yaml", "# comment:", "")
        )) {
            Fixture fixture = fixture(example.filename(), example.text());

            fixture.engine.insertNewline();

            assertEquals(example.indent(), fixture.model.getLine(1), example.toString());
        }
    }

    @Test
    void regularExpressionContentsAreNotCommentsOrIndentationTokens() {
        for (String filename : List.of("example.js", "example.ts")) {
            for (String text : List.of("const pattern = /[/*]/;", "const pattern = /[//]/;",
                "if (ready) /[/*]/.test(value);", "const pattern = /\\/[/*]/;")) {
                Fixture fixture = fixture(filename, text);

                fixture.engine.insertNewline();

                assertEquals("", fixture.model.getLine(1), filename + " " + text);
            }
        }
        Fixture conditional = fixture("example.js", "if (/[/*]/.test(value)) { // comment");
        conditional.engine.insertNewline();
        assertEquals("    ", conditional.model.getLine(1));
        Fixture division = fixture("example.js", "if (a / b > 0) { // comment");
        division.engine.insertNewline();
        assertEquals("    ", division.model.getLine(1));
    }

    @Test
    void javascriptDivisionAfterCallsAndGroupsStillRecognizesTrailingComments() {
        for (String text : List.of(
            "if ((a + b) / 2 > 0) { // explanation",
            "if (getValue() / 2 > 0) { // explanation",
            "if ((getValue() + 1) / (count + 1) > 0) { /* explanation */",
            "if (object.if(ready) / 2 > 0) { // explanation",
            "if ($if(ready) / 2 > 0) { // explanation",
            "if (object.return / 2 > 0) { // explanation"
        )) {
            Fixture fixture = fixture("example.js", text);

            fixture.engine.insertNewline();

            assertEquals(List.of(text, "    "), fixture.model.snapshotLines(), text);
        }
    }

    @Test
    void javascriptControlConditionsCanBeFollowedByRegularExpressionStatements() {
        for (String text : List.of(
            "if (ready) /[/*]/.test(value);",
            "while (ready) /[//]/.test(value);",
            "for (const item of values) /[/*]/.test(item);",
            "for await (const item of values) /[/*]/.test(item);",
            "if /* explanation */ ((isReady())) /[//]/.test(value);"
        )) {
            Fixture fixture = fixture("example.js", text);

            fixture.engine.insertNewline();

            assertEquals(List.of(text, ""), fixture.model.snapshotLines(), text);
        }
        Fixture nested = fixture("example.js", "if (/[()/*]/.test(value)) { // explanation");
        nested.engine.insertNewline();
        assertEquals("    ", nested.model.getLine(1), "parentheses in a regexp do not affect control nesting");
    }

    @Test
    void incompleteRegularExpressionsPreserveTheOriginalIndentationPrefix() {
        for (String text : List.of("const pattern = /[/*]", "if (ready) /[//]")) {
            Fixture fixture = fixture("example.js", text);

            fixture.engine.insertNewline();

            assertEquals(List.of(text, ""), fixture.model.snapshotLines(), text);
        }
    }

    @Test
    void ambiguousSlashLanguagesKeepTheirExistingIndentationBehavior() {
        record Example(String filename, String text) {}
        for (Example example : List.of(
            new Example("example.rb", "puts /[ # ]/"),
            new Example("example.pl", "my $re = qr/[ # ]/;"),
            new Example("example.groovy", "println /[/*]/"),
            new Example("build.gradle", "println /[//]/"),
            new Example("scratch", "const pattern = /[/*]/;")
        )) {
            Fixture fixture = fixture(example.filename(), example.text());

            fixture.engine.insertNewline();

            assertEquals(List.of(example.text(), ""), fixture.model.snapshotLines(), example.toString());
        }
    }

    @Test
    void nestedIndentAndNewlineRoundTripThroughOneUndoEntry() {
        String original = "    if ready: # explanation";
        Fixture fixture = fixture("test.py", original);

        fixture.engine.insertNewline();

        assertEquals(List.of(original, "        "), fixture.model.snapshotLines());
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of(original), fixture.model.snapshotLines());
        assertEquals(new Position(0, original.length()), fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of(original, "        "), fixture.model.snapshotLines());
        assertEquals(new Position(1, 8), fixture.state.getCursor());
        assertFalse(fixture.engine.redo());
    }

    @Test
    void indentationUsesOnlyCodeBeforeTheCursor() {
        Fixture fixture = fixture("test.py", "if ready: suffix");
        fixture.engine.moveCursorTo(new Position(0, "if ready:".length()));

        fixture.engine.insertNewline();

        assertEquals(List.of("if ready:", "     suffix"), fixture.model.snapshotLines());
        assertEquals(new Position(1, 4), fixture.state.getCursor());
    }

    @Test
    void newlineRetainsTheConfiguredIndentUnitAndHonorsDisabledIndentation() {
        Fixture tabs = fixture("test.py", "\tif ready: # comment");
        tabs.engine.setTabInsertMode(TabInsertMode.ALWAYS_TAB);
        tabs.engine.insertNewline();
        assertEquals("\t\t", tabs.model.getLine(1));

        Fixture spaces = fixture("test.py", "if ready: # comment");
        spaces.engine.setTabWidth(2);
        spaces.engine.insertNewline();
        assertEquals("  ", spaces.model.getLine(1));

        Fixture disabled = fixture("test.py", "    if ready: # comment");
        disabled.engine.setAutoIndentEnabled(false);
        disabled.engine.insertNewline();
        assertEquals("", disabled.model.getLine(1));
    }

    private static Fixture fixture(String filename, String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(text));
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of(filename));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveEnd();
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}

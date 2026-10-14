package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineTypingAssistTest {
    @Test
    void keywordPrefixesDoNotDedentIdentifiersWhileTheyAreBeingTyped() {
        for (String name : List.of("elsewhere", "elif_value", "excepting", "finallyDone", "casefold")) {
            Fixture fixture = indentedFixture("test.py");

            type(fixture.engine, name + " = 2");

            assertEquals("    " + name + " = 2", fixture.model.getLine(2), name);
        }
        for (String name : List.of("elsewhere", "catching", "finallyDone", "casefold", "defaultValue")) {
            Fixture fixture = indentedFixture("Test.java");

            type(fixture.engine, name + " = 2");

            assertEquals("    " + name + " = 2", fixture.model.getLine(2), name);
        }
    }

    @Test
    void pythonDedentKeywordsAreCaseSensitive() {
        for (String keyword : List.of("Else", "ELIF", "Except", "FINALLY", "Case")) {
            Fixture fixture = indentedFixture("test.py");

            type(fixture.engine, keyword + ":");

            assertEquals("    " + keyword + ":", fixture.model.getLine(2), keyword);
        }
    }

    @Test
    void pythonClausesDedentOnceTheirKeywordIsTerminated() {
        for (String clause : List.of(
            "else:", "finally:", "elif condition:", "elif(condition):", "except:",
            "except Error:", "except(Error):", "except* Error:", "case value:", "else\t"
        )) {
            boolean matchCase = clause.startsWith("case ");
            String owner = clause.startsWith("except") || clause.startsWith("finally") ? "try:" : "if ready:";
            Fixture fixture = fixture("test.py", matchCase
                ? List.of("match value:", "    case 0:", "        x = 1", "        ")
                : List.of(owner, "    x = 1", "    "));
            int lineIndex = matchCase ? 3 : 2;
            fixture.engine.moveCursorTo(new Position(lineIndex, matchCase ? 8 : 4));
            fixture.engine.setAutoPairsEnabled(false);

            type(fixture.engine, clause);

            assertEquals((matchCase ? "    " : "") + clause, fixture.model.getLine(lineIndex), clause);
        }
    }

    @Test
    void genericCodeClausesKeepTheirDelimiterTriggeredDedent() {
        for (String clause : List.of("else ", "else{", "catch(", "finally {", "case ", "default:")) {
            boolean label = clause.startsWith("case") || clause.startsWith("default");
            String owner = clause.startsWith("else") ? "if (ready) {" : "try {";
            Fixture fixture = fixture("Test.java", label
                ? List.of("switch (value) {", "    case 0:", "        break;", "        ")
                : List.of(owner, "    }", "    "));
            int lineIndex = label ? 3 : 2;
            fixture.engine.moveCursorTo(new Position(lineIndex, label ? 8 : 4));
            fixture.engine.setAutoPairsEnabled(false);

            type(fixture.engine, clause);

            assertEquals((label ? "    " : "") + clause, fixture.model.getLine(lineIndex), clause);
        }
    }

    @Test
    void undoAndRedoKeepTheDedentTogetherWithItsTrigger() {
        Fixture fixture = indentedFixture("test.py");
        type(fixture.engine, "else");
        assertEquals("    else", fixture.model.getLine(2));
        fixture.engine.moveCursorTo(fixture.state.getCursor());

        fixture.engine.insertChar(':');

        assertEquals("else:", fixture.model.getLine(2));
        assertEquals(new Position(2, 5), fixture.state.getCursor());
        assertTrue(fixture.engine.undo());
        assertEquals("    else", fixture.model.getLine(2));
        assertEquals(new Position(2, 8), fixture.state.getCursor());
        assertTrue(fixture.engine.redo());
        assertEquals("else:", fixture.model.getLine(2));
        assertEquals(new Position(2, 5), fixture.state.getCursor());
    }

    @Test
    void escapedQuotesAreInsertedBeforeExistingQuotesAndRoundTripThroughHistory() {
        for (char quote : new char[] {'\'', '"'}) {
            for (int backslashes : new int[] {1, 3}) {
                String prefix = "abc" + "\\".repeat(backslashes);
                String original = prefix + quote;
                Fixture fixture = fixture("test.txt", List.of(original));
                fixture.engine.moveCursorTo(new Position(0, prefix.length()));

                fixture.engine.insertChar(quote);

                String expected = prefix + quote + quote;
                assertEquals(expected, fixture.model.getLine(0));
                assertEquals(new Position(0, prefix.length() + 1), fixture.state.getCursor());
                assertTrue(fixture.engine.undo());
                assertEquals(original, fixture.model.getLine(0));
                assertFalse(fixture.state.isDirty());
                assertTrue(fixture.engine.redo());
                assertEquals(expected, fixture.model.getLine(0));
            }
        }
    }

    @Test
    void evenBackslashRunsStillAllowSkippingExistingClosingQuotes() {
        for (char quote : new char[] {'\'', '"'}) {
            for (int backslashes : new int[] {0, 2, 4}) {
                String prefix = "abc" + "\\".repeat(backslashes);
                String original = prefix + quote;
                Fixture fixture = fixture("test.txt", List.of(original));
                fixture.engine.moveCursorTo(new Position(0, prefix.length()));
                long revision = fixture.model.revision();

                fixture.engine.insertChar(quote);

                assertEquals(original, fixture.model.getLine(0));
                assertEquals(new Position(0, original.length()), fixture.state.getCursor());
                assertEquals(revision, fixture.model.revision());
                assertFalse(fixture.state.isDirty());
                assertFalse(fixture.engine.undo());
            }
        }
    }

    @Test
    void quotePairCreationAlsoUsesBackslashParity() {
        for (char quote : new char[] {'\'', '"'}) {
            for (int backslashes : new int[] {1, 2, 3, 4}) {
                String original = " " + "\\".repeat(backslashes);
                Fixture fixture = fixture("test.txt", List.of(original));
                fixture.engine.moveCursorTo(new Position(0, original.length()));

                fixture.engine.insertChar(quote);

                String expected = original + String.valueOf(quote).repeat(backslashes % 2 == 0 ? 2 : 1);
                assertEquals(expected, fixture.model.getLine(0));
                assertEquals(new Position(0, original.length() + 1), fixture.state.getCursor());
                assertTrue(fixture.engine.undo());
                assertEquals(original, fixture.model.getLine(0));
                assertTrue(fixture.engine.redo());
                assertEquals(expected, fixture.model.getLine(0));
            }
        }
    }

    private static void type(EditorEngine engine, String text) {
        for (char ch : text.toCharArray()) {
            engine.insertChar(ch);
        }
    }

    private static Fixture indentedFixture(String fileName) {
        String owner = fileName.endsWith(".py") ? "if ready:" : "if (ready) {";
        Fixture fixture = fixture(fileName, List.of(owner, "    x = 1", "    "));
        fixture.engine.moveCursorTo(new Position(2, 4));
        return fixture;
    }

    private static Fixture fixture(String fileName, List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of(fileName));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}

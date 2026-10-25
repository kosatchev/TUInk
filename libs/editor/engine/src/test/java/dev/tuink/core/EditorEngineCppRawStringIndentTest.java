package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorEngineCppRawStringIndentTest {
    private static final Path FILE = Path.of("literal.cpp");
    private static final List<String> DELIMITERS = List.of("", "tag", "abcdefghijklmnop");

    @ParameterizedTest
    @ValueSource(strings = {"R", "u8R", "uR", "UR", "LR"})
    void enteringRawContentCopiesOnlyItsExistingIndentAndRoundTripsThroughHistory(String prefix) {
        for (String delimiter : DELIMITERS) {
            for (String body : List.of("{", "    quote \" and wrong )other\" \\ {")) {
                List<String> original = List.of(opening(prefix, delimiter), body, closing(delimiter));
                EditorEngine engine = engine(original, 1);
                Position before = engine.state().getCursor();
                String indent = body.startsWith(" ") ? "    " : "";

                engine.insertNewline();

                List<String> edited = List.of(original.get(0), body, indent, original.get(2));
                assertEquals(edited, engine.model().snapshotLines(), prefix + " delimiter=" + delimiter);
                assertEquals(new Position(2, indent.length()), engine.state().getCursor());
                assertTrue(engine.state().isDirty());
                assertTrue(engine.undo());
                assertEquals(original, engine.model().snapshotLines());
                assertEquals(before, engine.state().getCursor());
                assertFalse(engine.state().isDirty());
                assertTrue(engine.redo());
                assertEquals(edited, engine.model().snapshotLines());
                assertEquals(new Position(2, indent.length()), engine.state().getCursor());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"R", "u8R", "uR", "UR", "LR"})
    void theExactRawCloserResumesCodeOnItsOwnLine(String prefix) {
        for (String delimiter : DELIMITERS) {
            EditorEngine engine = engine(List.of(opening(prefix, delimiter), "    " + closing(delimiter)
                + " if (ready) {"), 1);

            engine.insertNewline();

            assertEquals("        ", engine.model().getLine(2), prefix + " delimiter=" + delimiter);
            assertEquals(new Position(2, 8), engine.state().getCursor());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"R", "u8R", "uR", "UR", "LR"})
    void closingBracesInsideRawContentCannotRemoveExistingWhitespace(String prefix) {
        EditorEngine engine = engine(List.of(opening(prefix, "tag"), "    {", "        }", closing("tag")), 2);

        engine.insertNewline();

        assertEquals("        ", engine.model().getLine(3));
        assertEquals("        }", engine.model().getLine(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"R", "u8R", "uR", "UR", "LR"})
    void aRawPrefixInsideAnIdentifierCannotOpenAMultilineLiteral(String prefix) {
        for (String identifier : List.of("name", "_", "$", "é", "𝒜")) {
            assertCodeOnFollowingLine(identifier + prefix + "\"tag(");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"R", "u8R", "uR", "UR", "LR"})
    void invalidOrOverlongRawDelimitersCannotOpenAMultilineLiteral(String prefix) {
        for (String delimiter : List.of("abcdefghijklmnopq", "bad delimiter", "bad)delimiter", "bad\\delimiter")) {
            assertCodeOnFollowingLine(opening(prefix, delimiter));
        }
    }

    private static void assertCodeOnFollowingLine(String opening) {
        IndentationLexer.Result first = IndentationLexer.scan(opening, opening.length(), FILE.getFileName().toString(),
            IndentationLexer.State.CODE, false);
        IndentationLexer.Result next = IndentationLexer.scan("{", 1, FILE.getFileName().toString(), first.afterLine(), true);
        assertTrue(next.codeAtCursor(), opening);
        assertEquals("{", next.code(), opening);
    }

    private static String opening(String prefix, String delimiter) {
        return "auto text = " + prefix + "\"" + delimiter + "(";
    }

    private static String closing(String delimiter) {
        return ")" + delimiter + "\";";
    }

    private static EditorEngine engine(List<String> lines, int cursorLine) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentResource(JvmDocumentStorage.resource(FILE));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.moveCursorTo(new Position(cursorLine, lines.get(cursorLine).length()));
        return engine;
    }
}

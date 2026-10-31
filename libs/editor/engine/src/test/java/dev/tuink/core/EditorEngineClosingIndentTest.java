package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineClosingIndentTest {
    @Test
    void nearbyCloserStillDedentsAfterThreeHundredUnrelatedLines() {
        List<String> lines = new ArrayList<>(Collections.nCopies(300, "work();"));
        lines.addAll(List.of("    void work() {", "        run();", "        }"));

        assertNewlineIndent("Main.java", lines, "    ");
    }

    @Test
    void alignedClosingDelimitersKeepTheContainingBlockIndent() {
        assertNewlineIndent("Main.java", List.of(
            "class Main {", "    void work() {", "        run();", "    }"
        ), "    ");
        for (String closing : List.of(")", ")  # result", "]", "}")) {
            String opening = closing.startsWith(")") ? "call(" : closing.equals("]") ? "[" : "{";
            assertNewlineIndent("main.py", List.of(
                "def work():", "    result = " + opening, "        value,", "    " + closing
            ), "    ");
        }
        assertNewlineIndent("Main.java", List.of("    void work() {", "    }"), "    ");
        assertNewlineIndent("main.py", List.of("def work():", "    call(", "    value,", "    )"), "    ");
    }

    @Test
    void tabsAndMultipleClosingDelimitersKeepTheirAlignedIndent() {
        assertNewlineIndent("Main.java", List.of("class Main {", "\tvoid work() {", "\t\trun();", "\t}"), "\t");
        assertNewlineIndent("main.py", List.of("def work():", "    call([", "        value,", "    ])"), "    ");
    }

    @Test
    void aProvenOverIndentedCloserStillDedentsTheFollowingLine() {
        assertNewlineIndent("Main.java", List.of("    void work() {", "        run();", "        }"), "    ");
        assertNewlineIndent("Main.java", List.of("    void work() {", "        }"), "    ");
        assertNewlineIndent("Main.java", List.of(
            "    void work() {", "        if (ready) {", "            run();", "        }", "        }"
        ), "    ");
        assertNewlineIndent("main.py", List.of("def work():", "    call([", "        value,", "        ])"), "    ");
        assertNewlineIndent("Main.java", List.of(
            "    void work() { // misleading }", "        run(); /* misleading { */", "        } // complete"
        ), "    ");
    }

    @Test
    void uncertainOrMismatchedContextDoesNotChangeTheIndent() {
        assertNewlineIndent("Main.java", List.of("    }"), "    ");
        assertNewlineIndent("main.py", List.of("    values = [", "        value,", "        )"), "        ");
        for (String text : List.of("        String literal = \"{\";", "        String literal = \"}\";",
            "        char literal = '{';", "        emit(\"x\");")) {
            assertNewlineIndent("Main.java", List.of("    void work() {", text, "        }"), "    ");
        }
        for (String text : List.of("        const literal = `}`;", "        result = a / b;")) {
            assertNewlineIndent("Main.java", List.of("    void work() {", text, "        }"), "        ");
        }
        assertNewlineIndent("Main.java", List.of("/* comment", "    {", "        }"), "        ");
        assertNewlineIndent("Main.java", List.of("String value = \"\"\"", "{", "    }"), "    ");
        assertNewlineIndent("main.js", List.of("const value = `", "{", "    }"), "    ");
        assertNewlineIndent("Main.java", List.of("String value = \"\"\"", "*/", "{", "    }"), "    ");
    }

    @Test
    void anOrdinaryQuoteContinuedAcrossANewlineCannotProveAClosingIndentRemoval() {
        assertNewlineIndent("Main.java", List.of(
            "String value = \"unfinished\\", "closed\";", "    work() {", "        run();", "        }"
        ), "        ");
    }

    @Test
    void textWithBracketIndentDisabledAndCursorBeforeACloserKeepTheirIndent() {
        for (String filename : List.of("notes.md", "settings.yaml")) {
            assertNewlineIndent(filename, List.of("if (ready) {", "    run();", "    }"), "    ");
        }
        Fixture fixture = fixture("Main.java", List.of("class Main {", "    }"));
        fixture.engine.moveCursorTo(new Position(1, 4));

        fixture.engine.insertNewline();

        assertEquals(List.of("class Main {", "    ", "    }"), fixture.model.snapshotLines());
        assertEquals(new Position(2, 4), fixture.state.getCursor());
    }

    @Test
    void lookbackHasLineAndCharacterBudgets() {
        List<String> lines = new ArrayList<>();
        lines.add("    void work() {");
        for (int index = 0; index < 256; index++) {
            lines.add("        run();");
        }
        lines.add("        }");
        assertNewlineIndent("Main.java", lines, "        ");
        assertNewlineIndent("Main.java", List.of(
            "    void work() {", "        " + "x".repeat(16_384), "        }"
        ), "        ");
    }

    @Test
    void closingLookbackIncludesItsExactLineAndRawUtf16Boundaries() {
        String owner = "    void work() {";
        for (int distance : new int[] {256, 257}) {
            List<String> lines = new ArrayList<>(List.of(owner));
            lines.addAll(Collections.nCopies(distance - 1, "        // filler 😀"));
            lines.add("        }");

            assertNewlineIndent("Main.java", lines, distance == 256 ? "    " : "        ");
        }
        for (int codeUnits : new int[] {16_384, 16_385}) {
            int payloadUnits = codeUnits - owner.length() - "        //".length();
            String comment = "        //" + "😀".repeat(payloadUnits / 2) + "x".repeat(payloadUnits % 2);

            assertNewlineIndent("Main.java", List.of(owner, comment, "        }"),
                codeUnits == 16_384 ? "    " : "        ");
        }
    }

    private static void assertNewlineIndent(String filename, List<String> lines, String expected) {
        Fixture fixture = fixture(filename, lines);
        Position before = fixture.state.getCursor();

        fixture.engine.insertNewline();

        assertEquals(expected, fixture.model.getLine(lines.size()), filename + " " + lines);
        assertEquals(new Position(lines.size(), expected.length()), fixture.state.getCursor());
        assertTrue(fixture.engine.undo());
        assertEquals(lines, fixture.model.snapshotLines());
        assertEquals(before, fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertEquals(expected, fixture.model.getLine(lines.size()));
    }

    private static Fixture fixture(String filename, List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of(filename).toString()));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveCursorTo(new Position(lines.size() - 1, lines.getLast().length()));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineKeywordIndentTest {
    @Test
    void nearbyPythonOwnerStillDedentsAfterThreeHundredUnrelatedLines() {
        List<String> original = new ArrayList<>(Collections.nCopies(300, "value = 1"));
        original.addAll(List.of("if ready:", "    run()", "    else"));
        Fixture fixture = fixture("main.py", original);
        Position cursor = fixture.state.getCursor();

        fixture.engine.insertChar(':');

        assertEquals("else:", fixture.model.getLine(original.size() - 1));
        assertTrue(fixture.engine.undo());
        assertEquals(original, fixture.model.snapshotLines());
        assertEquals(cursor, fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertEquals("else:", fixture.model.getLine(original.size() - 1));
    }

    @Test
    void nearbySwitchSiblingStillDedentsAfterThreeHundredUnrelatedLines() {
        for (String clause : List.of("case 2:", "default:")) {
            List<String> lines = new ArrayList<>(Collections.nCopies(300, "work();"));
            lines.addAll(List.of("switch (value) {", "    case 1:", "        run();"));
            Fixture fixture = afterEnter("Main.java", lines);

            type(fixture.engine, clause);

            assertEquals("    " + clause, fixture.model.getLine(lines.size()), clause);
        }
    }

    @Test
    void pythonCommentsDoNotMakeAlignedClausesLoseTheirContainingScope() {
        record Example(List<String> prefix, String clause) {}
        for (Example example : List.of(
            new Example(List.of("def work():", "    if ready:", "        run()", "    # fallback"), "else:"),
            new Example(List.of("def work():", "    if ready:", "        run()", "    # alternative"), "elif other:"),
            new Example(List.of("def work():", "    try:", "        run()", "    # error handling"), "except Error:"),
            new Example(List.of("def work():", "    try:", "        run()", "    # cleanup"), "finally:")
        )) {
            Fixture fixture = afterEnter("main.py", example.prefix());
            type(fixture.engine, example.clause());
            assertEquals("    " + example.clause(), fixture.model.getLine(example.prefix().size()), example.toString());
        }
    }

    @Test
    void inlineCaseBodiesKeepSubsequentCasesAligned() {
        Fixture fixture = afterEnter("main.py", List.of("match value:", "    case 1: run()"));
        type(fixture.engine, "case 2:");
        assertEquals("    case 2:", fixture.model.getLine(2));

        Fixture nested = afterEnter("main.py", List.of("def work():", "    match value:", "        case 1: run()"));
        type(nested.engine, "case 2:");
        assertEquals("        case 2:", nested.model.getLine(3));
    }

    @Test
    void ordinaryQuotedStatementsBeforeAnOwnerDoNotDisablePythonDedent() {
        for (String statement : List.of("title = \"hello\"", "title = 'hello'", "title = \"say \\\"hello\\\"\"",
            "title = \"https://example.org\"")) {
            Fixture fixture = afterEnter("main.py", List.of(statement, "if ready:", "    run()"));
            type(fixture.engine, "else:");
            assertEquals("else:", fixture.model.getLine(3), statement);
        }
    }

    @Test
    void ordinaryJavaStringAndCharacterLiteralsDoNotHideSiblingSwitchLabels() {
        Fixture fixture = afterEnter("Main.java", List.of("String title = \"hello\";", "switch (value) {",
            "    case \"one\":", "        emit(\"x\");", "        emit('{');"));
        type(fixture.engine, "case \"two\":");
        assertEquals("    case \"two\":", fixture.model.getLine(5));
    }

    @Test
    void uncertainLiteralsAndFakeCommentBoundariesCannotProveClauseOwnership() {
        for (String statement : List.of("String value = \"\"\"", "String value = \"unfinished", "const value = `")) {
            Fixture fixture = afterEnter("Main.java", List.of(statement, "switch (value) {", "    case 1:", "        run();"));
            type(fixture.engine, "case 2:");
            assertEquals("        case 2:", fixture.model.getLine(4), statement);
        }
        Fixture comment = afterEnter("Main.java", List.of("/*", "String fake = \"*/\";", "switch (value) {",
            "    case 1:", "        run();"));
        type(comment.engine, "case 2:");
        assertEquals("        case 2:", comment.model.getLine(5));
        for (String statement : List.of("    title = \"unfinished", "    title = value + \\")) {
            Fixture continuation = afterEnter("main.py", List.of("if ready:", statement));
            type(continuation.engine, "else:");
            assertEquals("    else:", continuation.model.getLine(2), statement);
        }
    }

    @Test
    void pythonBodyToClauseDedentUsesTheCompatibleOwner() {
        record Example(List<String> prefix, String clause) {}
        for (Example example : List.of(
            new Example(List.of("    if ready:", "        run()"), "else:"),
            new Example(List.of("    if ready:", "        run()"), "elif other:"),
            new Example(List.of("    for item in items:", "        run()"), "else:"),
            new Example(List.of("    while ready:", "        run()"), "else:"),
            new Example(List.of("    async for item in items:", "        run()"), "else:"),
            new Example(List.of("    try:", "        run()"), "except Error:"),
            new Example(List.of("    try:", "        run()"), "finally:"),
            new Example(List.of("    try:", "        run()", "    except Error:", "        recover()"), "else:"),
            new Example(List.of("    try:", "        run()", "    except Error:", "        recover()", "    else:", "        done()"), "finally:"),
            new Example(List.of("match value:", "    case 1:", "        run()"), "case 2:")
        )) {
            Fixture fixture = afterEnter("main.py", example.prefix());
            type(fixture.engine, example.clause());
            assertEquals("    " + example.clause(), fixture.model.getLine(example.prefix().size()), example.toString());
        }
    }

    @Test
    void alignedOwnerAndFunctionBoundariesKeepExplicitIndentation() {
        for (List<String> lines : List.of(
            List.of("def work():", "    if ready:", "        run()", "    "),
            List.of("if outer:", "    def work():", "        run()", "        "),
            List.of("if outer:", "    class Work:", "        run()", "        "),
            List.of("def work():", "    run()", "    "),
            List.of("text = '''", "if ready:", "    run()", "    ")
        )) {
            Fixture fixture = fixture("main.py", lines);
            String indent = lines.getLast();
            type(fixture.engine, "else:");
            assertEquals(indent + "else:", fixture.model.getLine(lines.size() - 1), lines.toString());
        }
    }

    @Test
    void softCaseKeywordDoesNotDedentAnAssignmentWhileItIsTyped() {
        for (String text : List.of("case = 1", "casefold = 1", "case_value = 1")) {
            Fixture fixture = afterEnter("main.py", List.of("match value:", "    case 1:", "        run()"));
            type(fixture.engine, text);
            assertEquals("        " + text, fixture.model.getLine(3), text);
        }
    }

    @Test
    void incompatibleOrIncompleteHeadersDoNotReuseAnOlderOwner() {
        record Example(List<String> prefix, String clause) {}
        for (Example example : List.of(
            new Example(List.of("    if earlier:", "        run()", "    with resource:", "        run()"), "else:"),
            new Example(List.of("    if earlier:", "        run()", "    if name == ':'", "        run()"), "else:"),
            new Example(List.of("    if earlier:", "        run()", "    if values[1:]", "        run()"), "else:"),
            new Example(List.of("    if earlier:", "        run()", "    else:", "        run()"), "elif other:"),
            new Example(List.of("    try:", "        run()", "    with resource:", "        run()"), "finally:"),
            new Example(List.of("match value:", "    case 1:", "        if ready:", "            run()", "        # next pattern"), "case 2:")
        )) {
            Fixture fixture = afterEnter("main.py", example.prefix());
            String indent = fixture.model.getLine(example.prefix().size());
            type(fixture.engine, example.clause());
            assertEquals(indent + example.clause(), fixture.model.getLine(example.prefix().size()), example.toString());
        }
        Fixture ended = fixture("main.py", List.of("    if earlier:", "        run()", "    work_again()", "        "));
        type(ended.engine, "else:");
        assertEquals("        else:", ended.model.getLine(3));
    }

    @Test
    void javaClausesStayAlignedWithAlreadyAlignedClosingBraces() {
        for (String clause : List.of("else ", "else{", "catch(", "finally {")) {
            String owner = clause.startsWith("else") ? "        if (ready) {" : "        try {";
            Fixture fixture = afterEnter("Main.java", List.of("class Main {", "    void work() {", owner,
                "            run();", "        }", "        // next branch"));
            fixture.engine.setAutoPairsEnabled(false);
            type(fixture.engine, clause);
            assertEquals("        " + clause, fixture.model.getLine(6), clause);
        }
    }

    @Test
    void javaOverIndentedClosersStillAllowClauseDedent() {
        for (String clause : List.of("else ", "catch(", "finally {")) {
            String owner = clause.startsWith("else") ? "    if (ready) {" : "    try {";
            Fixture fixture = fixture("Main.java", List.of(owner, "        run();", "        }", "        // branch", "        "));
            fixture.engine.setAutoPairsEnabled(false);
            type(fixture.engine, clause);
            assertEquals("    " + clause, fixture.model.getLine(4), clause);
        }
    }

    @Test
    void switchLabelsUseSiblingIndentAndSkipClosedNestedBlocks() {
        for (String clause : List.of("case 2:", "default:")) {
            Fixture aligned = afterEnter("Main.java", List.of("switch (value) {", "    case 1: run();"));
            type(aligned.engine, clause);
            assertEquals("    " + clause, aligned.model.getLine(2), clause);

            Fixture body = afterEnter("Main.java", List.of("switch (value) {", "    case 1:", "        run();"));
            type(body.engine, clause);
            assertEquals("    " + clause, body.model.getLine(3), clause);

            Fixture nested = afterEnter("Main.java", List.of("switch (value) {", "    case 1:", "        switch (other) {",
                "            case 0: run();", "        }"));
            type(nested.engine, clause);
            assertEquals("    " + clause, nested.model.getLine(5), clause);
        }
        Fixture first = afterEnter("Main.java", List.of("switch (value) {"));
        type(first.engine, "case 1:");
        assertEquals("    case 1:", first.model.getLine(1));
    }

    @Test
    void disabledIndentAndUnknownContextPreserveTheTypedIndent() {
        Fixture disabled = afterEnter("main.py", List.of("if ready:", "    run()"));
        disabled.engine.setAutoIndentEnabled(false);
        type(disabled.engine, "else:");
        assertEquals("    else:", disabled.model.getLine(2));

        List<String> lines = new ArrayList<>();
        lines.add("if ready:");
        for (int index = 0; index < 256; index++) {
            lines.add("    run()");
        }
        Fixture bounded = afterEnter("main.py", lines);
        type(bounded.engine, "else:");
        assertEquals("    else:", bounded.model.getLine(lines.size()));

        Fixture unknown = fixture("Main.java", List.of("    work();", "    "));
        type(unknown.engine, "else ");
        assertEquals("    else ", unknown.model.getLine(1));
    }

    @Test
    void aDedentAndItsTriggerRestoreCursorMetadataAndSavepointTogether() {
        List<String> original = List.of("if ready:", "    run()", "    else");
        Fixture fixture = fixture("main.py", original);
        fixture.model.restoreFileMetadata(fixture.model.fileMetadata().withLineSeparators(List.of("\r\n", "\n")));
        DocumentFileMetadata metadata = fixture.model.fileMetadata();
        Position cursor = fixture.state.getCursor();

        fixture.engine.insertChar(':');

        assertEquals("else:", fixture.model.getLine(2));
        assertEquals(new Position(2, 5), fixture.state.getCursor());
        assertEquals(metadata, fixture.model.fileMetadata());
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals(original, fixture.model.snapshotLines());
        assertEquals(cursor, fixture.state.getCursor());
        assertEquals(metadata, fixture.model.fileMetadata());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertEquals("else:", fixture.model.getLine(2));
        assertEquals(new Position(2, 5), fixture.state.getCursor());
    }

    private static void type(EditorEngine engine, String text) {
        for (char ch : text.toCharArray()) {
            engine.insertChar(ch);
        }
    }

    private static Fixture afterEnter(String filename, List<String> lines) {
        Fixture fixture = fixture(filename, lines);
        fixture.engine.insertNewline();
        return fixture;
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

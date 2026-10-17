package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineLexicalScanner;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaScriptContinuationTest {
    private static final Style STRING = Style.fg(Color.BRIGHT_GREEN);
    private static final String DIVISION = "  / denominator[i] / scale;";
    private static final String REGEX = "/[{}]/.test(value);";

    @Test
    void valuesContinueAcrossLinesInBothTheScannerAndContextualHighlighting() {
        for (String file : List.of("app.js", "app.ts")) {
            for (String value : List.of("numerator", "call(value)", "table[index]", "[1, 2]", "{value: 1}",
                "(function () {})", "function () {}", "async function () {}", "class {}", "(() => {})",
                "`numerator`", "/numerator/", "async",
                "left ?? right", "object?.method()", "ready ? left : {value: 1}", "{function: 1, class: 2}")) {
                assertDivision(file, List.of("const ratio = " + value, DIVISION));
            }
            assertDivision(file, List.of("const ratio = (numerator", "  / denominator[i] / scale);"));
            assertDivision(file, List.of("const ratio = {", "  nested: {value: 1}", "}", DIVISION));
            assertDivision(file, List.of("const ratio = ready ?", "  {value: 1} :", "  {value: 2}", DIVISION));
        }
    }

    @Test
    void blankLinesAndCommentsPreserveThePrecedingValue() {
        for (String file : List.of("app.js", "app.ts")) {
            assertDivision(file, List.of("const ratio = numerator // explanation", "", "  // more", DIVISION));
            assertDivision(file, List.of("const ratio = numerator /* explanation", "", "  continued */", DIVISION));
            assertDivision(file, List.of("const ratio = `first", "  last` /* explanation", "*/", DIVISION));
        }
    }

    @Test
    void sameLineFunctionAndClassExpressionsRemainDivision() {
        for (String file : List.of("app.js", "app.ts")) {
            for (String value : List.of("function(){}", "async function(){}", "class{}",
                "function(a = function(){}){}", "class extends mixin({value: 1}) {}")) {
                assertDivision(file, List.of("const ratio = " + value + " / denominator[i] / scale;"));
            }
        }
    }

    @Test
    void statementBoundariesStillPermitRegexLiterals() {
        for (String file : List.of("app.js", "app.ts")) {
            for (List<String> before : List.of(
                List.of("return"), List.of("return // comment"), List.of("return /* comment", "*/"), List.of("debugger"),
                List.of("return", "{}"), List.of("if (ready)"), List.of("while (ready)"),
                List.of("for (const value of values)"), List.of("if (ready) {}"),
                List.of("function f() {}"), List.of("async function f() {}"), List.of("class C {}"),
                List.of("const f = () => {}"), List.of("const f = () => {} /* comment", "*/"),
                List.of("const f = async", "function f() {}"),
                List.of("try {} finally {}"), List.of("{}"),
                List.of("label: {}"), List.of("switch (value) {", "case 1: {}"),
                List.of("outer: while (true) {", "break outer"),
                List.of("outer: while (true) {", "continue outer"),
                List.of("switch (value) {", "default: {}"),
                List.of("if (ready) {", "  const object = {value: 1};", "}"))) {
                List<String> lines = new ArrayList<>(before);
                lines.add(REGEX);
                assertRegex(file, lines);
            }
            // A same-line return still takes an object operand, unlike return\n{}.
            assertDivision(file, List.of("function f() {", "return {value: 1}", DIVISION));
        }
    }

    @Test
    void typescriptFunctionReturnAnnotationsKeepTheExpressionBodyGoal() {
        for (String type : List.of("number", "{value: number}", "{nested: {value: number}}",
            "number | {value: number}", "() => {value: number}")) {
            String function = "function(): " + type + " {}";
            assertDivision("app.ts", List.of("const ratio = " + function + " / denominator[i] / scale;"));
            assertDivision("app.ts", List.of("const ratio = " + function, DIVISION));
            assertRegex("app.ts", List.of("function f(): " + type + " {}", REGEX));
        }
    }

    @Test
    void typescriptSignaturesDoNotRetainCompletedBodyHeaders() {
        LineLexicalScanner scanner = highlighter("app.ts").lexicalScanner();
        LineLexicalScanner.State state = null;
        String signature = "declare function f(): {value: number};";
        for (int index = 0; index <= CStyleHighlighter.MAX_CONTEXT_DEPTH; index++) {
            var result = scanner.scan(signature, signature.length(), state);
            assertTrue(result.contextKnown());
            if (state != null) assertEquals(state, result.endState());
            state = result.endState();
        }
    }

    @Test
    void markdownFencesUseTheSameContinuationRules() {
        for (String tag : List.of("js", "ts")) {
            assertDivision("README.md", List.of("```" + tag, "const ratio = numerator", DIVISION));
            assertRegex("README.md", List.of("```" + tag, "if (ready) {}", REGEX));
        }
    }

    @Test
    void revisionChangesReplaceThePreviousLexicalGoal() {
        for (String file : List.of("app.js", "app.ts")) {
            ContextualLineHighlighter highlighter = highlighter(file);
            DocumentId id = new DocumentId("javascript-goal-revision");
            DocumentSnapshot value = new DocumentSnapshot(id, 1, List.of("const ratio = numerator", DIVISION));
            assertTrue(highlighter.highlight(value, 1, DIVISION).isEmpty());
            DocumentSnapshot statement = new DocumentSnapshot(id, 2, List.of("if (ready) {}", REGEX));
            assertEquals(List.of(new StyleSpan(0, 6, STRING)), highlighter.highlight(statement, 1, REGEX));
            assertTrue(highlighter.highlight(value, 1, DIVISION).isEmpty());
        }
    }

    @Test
    void allRetainedDelimiterKindsShareTheExistingContextBudgets() {
        for (String file : List.of("app.js", "app.ts")) {
            for (String opening : List.of("[", "{value:", "condition ? ")) {
                LineLexicalScanner scanner = highlighter(file).lexicalScanner();
                String bounded = opening.repeat(CStyleHighlighter.MAX_CONTEXT_DEPTH);
                var known = scanner.scan(bounded, bounded.length(), null);
                assertTrue(known.contextKnown(), opening);
                var stopped = scanner.scan(opening, opening.length(), known.endState());
                assertFalse(stopped.contextKnown(), opening);
                assertEquals(LineLexicalScanner.State.UNKNOWN, stopped.endState());
            }
            String flat = "[]".repeat(CStyleHighlighter.MAX_CONTEXT_NODES_PER_SCAN + 1);
            var stopped = highlighter(file).lexicalScanner().scan(flat, flat.length(), null);
            assertFalse(stopped.contextKnown());
            assertEquals(2 * CStyleHighlighter.MAX_CONTEXT_NODES_PER_SCAN, stopped.scannedCodeUnits());
        }
    }

    private static void assertDivision(String file, List<String> lines) {
        assertEquals(List.of(), scanLast(file, lines).excludedRanges(), file + " " + lines);
        DocumentSnapshot document = document(lines);
        assertTrue(highlighter(file).highlight(document, lines.size() - 1, lines.getLast()).stream()
            .noneMatch(span -> span.style().equals(STRING)), file + " " + lines);
    }

    private static void assertRegex(String file, List<String> lines) {
        assertEquals(List.of(new LineLexicalScanner.Range(0, 6)), scanLast(file, lines).excludedRanges(),
            file + " " + lines);
        DocumentSnapshot document = document(lines);
        assertEquals(List.of(new StyleSpan(0, 6, STRING)),
            highlighter(file).highlight(document, lines.size() - 1, lines.getLast()), file + " " + lines);
    }

    private static LineLexicalScanner.Result scanLast(String file, List<String> lines) {
        LineLexicalScanner scanner = highlighter(file).lexicalScanner();
        LineLexicalScanner.State state = null;
        LineLexicalScanner.Result result = null;
        for (String line : lines) {
            result = scanner.scan(line, line.length(), state);
            assertTrue(result.contextKnown(), file + " " + lines);
            assertEquals(line.length(), result.scannedCodeUnits());
            state = result.endState();
        }
        return result;
    }

    private static DocumentSnapshot document(List<String> lines) {
        return new DocumentSnapshot(new DocumentId("javascript-continuation"), 1, lines);
    }

    private static ContextualLineHighlighter highlighter(String file) {
        return (ContextualLineHighlighter) RegexHighlighter.forFileName(file);
    }
}

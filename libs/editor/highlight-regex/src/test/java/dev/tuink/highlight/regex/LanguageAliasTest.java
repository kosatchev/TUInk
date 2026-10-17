package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineLexicalScanner;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageAliasTest {
    @Test
    void pythonAliasesPreserveMultilineStringsForColorsAndBracketExclusions() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("python-alias"), 0,
            List.of("\"\"\"", "documentation )", "\"\"\"", "def value(): pass"));
        for (String extension : List.of("py", "pyi", "pyw", "pyx")) {
            ContextualLineHighlighter highlighter = assertInstanceOf(ContextualLineHighlighter.class,
                RegexHighlighter.forFileName("module." + extension));
            assertEquals(List.of(new StyleSpan(0, document.line(1).length(), Style.fg(Color.BRIGHT_GREEN))),
                highlighter.highlight(document, 1, document.line(1)), extension);
            LineLexicalScanner scanner = highlighter.lexicalScanner();
            LineLexicalScanner.Result first = scanner.scan(document.line(0), document.line(0).length(), null);
            LineLexicalScanner.Result second = scanner.scan(document.line(1), document.line(1).length(), first.endState());
            assertEquals(List.of(new LineLexicalScanner.Range(0, document.line(1).length())),
                second.excludedRanges(), extension);
        }
    }

    @Test
    void cppAliasesPreserveMultilineCommentsInsteadOfFallingBackToGenericRegexes() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("cpp-alias"), 0,
            List.of("/*", "documentation )", "*/ return 7;"));
        for (String extension : List.of("cc", "cpp", "cxx", "hh", "hpp", "hxx")) {
            ContextualLineHighlighter highlighter = assertInstanceOf(ContextualLineHighlighter.class,
                RegexHighlighter.forFileName("module." + extension));
            assertEquals(List.of(new StyleSpan(0, document.line(1).length(), Style.fg(Color.BRIGHT_BLACK))),
                highlighter.highlight(document, 1, document.line(1)), extension);
            LineLexicalScanner scanner = highlighter.lexicalScanner();
            LineLexicalScanner.Result first = scanner.scan(document.line(0), document.line(0).length(), null);
            assertEquals(List.of(new LineLexicalScanner.Range(0, document.line(1).length())),
                scanner.scan(document.line(1), document.line(1).length(), first.endState()).excludedRanges(), extension);
        }
    }

    @Test
    void cppFenceAliasesUseTheSameKeywordsAsCppFiles() {
        String source = "template<class T> class Example { public: T value; };";
        List<StyleSpan> expected = RegexHighlighter.forFileName("example.cpp").highlight(source);
        for (String tag : List.of("cpp", "cxx", "cc", "hpp", "hxx")) {
            DocumentSnapshot document = new DocumentSnapshot(new DocumentId("cpp-fence-" + tag), 0,
                List.of("```" + tag, source, "```"));
            ContextualLineHighlighter markdown = assertInstanceOf(ContextualLineHighlighter.class,
                RegexHighlighter.forFileName("notes.md"));
            assertEquals(expected, markdown.highlight(document, 1, source), tag);
        }
    }

    @Test
    void markdownAliasesHaveTheSameFenceAndInlinePolicies() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("markdown-alias"), 0,
            List.of("```python", "def value():", "```", "# Title"));
        ContextualLineHighlighter canonical = assertInstanceOf(ContextualLineHighlighter.class,
            RegexHighlighter.forFileName("notes.md"));
        for (String extension : List.of("markdown", "mdown", "mkd")) {
            ContextualLineHighlighter highlighter = assertInstanceOf(ContextualLineHighlighter.class,
                RegexHighlighter.forFileName("notes." + extension));
            for (int line = 0; line < document.lineCount(); line++) {
                assertEquals(canonical.highlight(document, line, document.line(line)),
                    highlighter.highlight(document, line, document.line(line)), extension);
            }
        }
    }
}

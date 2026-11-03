package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageTagHighlighterTest {
    private static final DocumentId BLOCK_ID = new DocumentId("detached-code-block");

    @Test
    void publicResolverAndSourceFencesUseTheSameLanguageAliases() {
        record Example(String tag, String code) {}
        for (Example example : List.of(
            new Example("java", "public int count = 7; // comment"),
            new Example("py3", "def answer(): return 7"),
            new Example("lexer:lang-python", "async def answer(): return 7"),
            new Example("typescript", "interface User { readonly id: number; }"),
            new Example("nodejs", "const answer = `value ${7}`;"),
            new Example("bash", "export VALUE=7 # comment"),
            new Example("sql", "select 7 from items"),
            new Example("cpp", "constexpr auto answer = 7;"),
            new Example("markdown", "# Literal heading")
        )) {
            var source = assertInstanceOf(ContextualSyntaxHighlighter.class,
                RegexHighlighter.forFileName("notes.md"));
            var document = new DocumentSnapshot(BLOCK_ID, 1,
                List.of("```" + example.tag(), example.code(), "```"));
            assertEquals(source.highlight(document, 1, example.code()),
                RegexHighlighter.forLanguageTag(example.tag()).highlight(example.code()), example.tag());
        }
    }

    @Test
    void infoStringFormsAndCaseSelectTheSameRules() {
        String python = "def answer(): return 7";
        for (String tag : List.of("python", "PY", "py3", "{.language-python}",
                "LANG-PYTHON,", "lexer:python;", "  python linenums=1  ")) {
            assertEquals(RegexHighlighter.forPython().highlight(python),
                RegexHighlighter.forLanguageTag(tag).highlight(python), tag);
        }
    }

    @Test
    void missingAndUnknownTagsUseTheExistingGenericRules() {
        String code = "public answer = 7; // comment";
        var generic = RegexHighlighter.withDefaults().highlight(code);
        assertEquals(generic, RegexHighlighter.forLanguageTag(null).highlight(code));
        for (String tag : List.of("", "  ", "unknown-language", "unknown-language options=1")) {
            assertEquals(generic, RegexHighlighter.forLanguageTag(tag).highlight(code), tag);
        }
    }

    @Test
    void separatelyCreatedBlocksKeepMultilineCommentsAndStringsIndependent() {
        record Example(String tag, String opening, SemanticStyleKey continuedStyle) {}
        String code = "return 7;";
        var normal = List.of(new TokenSpan(0, 6, SemanticStyleKey.KEYWORD),
            new TokenSpan(7, 8, SemanticStyleKey.NUMBER));
        for (Example example : List.of(
            new Example("java", "/* unclosed comment", SemanticStyleKey.COMMENT),
            new Example("python", "\"\"\"unclosed string", SemanticStyleKey.STRING),
            new Example("js", "`unclosed template", SemanticStyleKey.STRING)
        )) {
            var first = assertInstanceOf(ContextualSyntaxHighlighter.class,
                RegexHighlighter.forLanguageTag(example.tag()));
            var document = new DocumentSnapshot(BLOCK_ID, 1, List.of(example.opening(), code));
            assertEquals(List.of(new TokenSpan(0, code.length(), example.continuedStyle())),
                first.highlight(document, 1, code), example.tag());

            var second = assertInstanceOf(ContextualSyntaxHighlighter.class,
                RegexHighlighter.forLanguageTag(example.tag()));
            assertNotSame(first, second);
            var nextBlock = new DocumentSnapshot(BLOCK_ID, 1, List.of(code));
            assertEquals(normal, second.highlight(nextBlock, 0, code), example.tag());
        }
    }

    @Test
    void markdownTagsHighlightLiteralSyntaxWithoutRecursiveFenceDelegation() {
        var highlighter = RegexHighlighter.forLanguageTag("markdown");
        assertEquals(RegexHighlighter.forMarkdown().highlight("# Heading"),
            highlighter.highlight("# Heading"));
        assertEquals(RegexHighlighter.forMarkdown().highlight("```java"),
            highlighter.highlight("```java"));
    }
}

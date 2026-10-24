package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalHighlighterTest {
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;

    @Test
    void editingTheLastJavaLineDoesNotReadTheUnchangedPrefixAgain() {
        CountingDocument document = new CountingDocument(Collections.nCopies(20_000, "return 7;"));
        ContextualSyntaxHighlighter highlighter = highlighter("test.java");
        int last = document.lineCount() - 1;
        highlight(highlighter, document, last);

        document.model.insertChar(new Position(last, 0), ' ');
        document.forbidReadsBefore(last);

        assertMatchesFresh(highlighter, document, last, "test.java");
    }

    @Test
    void editingMarkupNearTheEndRetainsItsCommentContextWithoutReadingTheUnchangedPrefix() {
        for (String fileName : List.of("page.html", "settings.xml")) {
            List<String> lines = new ArrayList<>(Collections.nCopies(20_000, "<hidden>"));
            lines.set(0, "<!--");
            CountingDocument document = new CountingDocument(lines);
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            int last = document.lineCount() - 1;
            highlight(highlighter, document, last);

            document.model.insertChar(new Position(last, 0), ' ');
            document.forbidReadsBefore(last);

            assertMatchesFresh(highlighter, document, last, fileName);
            assertEquals(List.of(new TokenSpan(0, document.model.getLine(last).length(), COMMENT)),
                highlight(highlighter, document, last));
        }
    }

    @Test
    void editingAControlConditionUpdatesItsJavascriptRegexContext() {
        CountingDocument document = new CountingDocument(List.of("call(", "value) /[/*]/.test(value);", "return 7;"));
        ContextualSyntaxHighlighter highlighter = highlighter("script.js");
        highlight(highlighter, document, 2);

        document.model.deleteRange(new Position(0, 0), new Position(0, 4));
        document.model.insertText(new Position(0, 0), "if");
        assertMatchesFresh(highlighter, document, 1, "script.js");
        assertMatchesFresh(highlighter, document, 2, "script.js");
    }

    @Test
    void changedCommentAndLineInsertionInvalidateOnlyTheAffectedSuffix() {
        CountingDocument document = new CountingDocument(List.of("return 1;", "return 2;", "return 3;"));
        ContextualSyntaxHighlighter highlighter = highlighter("test.java");
        highlight(highlighter, document, 2);

        document.model.insertText(new Position(1, 0), "/*\n");
        document.forbidReadsBefore(1);

        assertEquals(
            List.of(new TokenSpan(0, document.model.getLine(3).length(), COMMENT)),
            highlight(highlighter, document, 3)
        );
        document.model.deleteRange(new Position(1, 0), new Position(2, 0));
        assertMatchesFresh(highlighter, document, 2, "test.java");
    }

    @Test
    void editsAtDifferentLinesBetweenFramesUseTheEarliestChange() {
        CountingDocument document = new CountingDocument(List.of("return 1;", "return 2;", "return 3;"));
        ContextualSyntaxHighlighter highlighter = highlighter("test.java");
        highlight(highlighter, document, 2);

        document.model.insertChar(new Position(2, 0), ' ');
        document.model.insertText(new Position(1, 0), "/*");
        document.forbidReadsBefore(1);

        assertMatchesFresh(highlighter, document, 2, "test.java");
    }

    @Test
    void expiredChangeHistoryRecomputesCommentContext() {
        CountingDocument document = new CountingDocument(List.of("/*", "return 2;", "return 3;"));
        ContextualSyntaxHighlighter highlighter = highlighter("test.java");
        highlight(highlighter, document, 2);

        document.model.deleteRange(new Position(0, 0), new Position(0, 2));
        for (int index = 0; index < 256; index++) {
            document.model.insertChar(new Position(2, 0), ' ');
        }

        assertMatchesFresh(highlighter, document, 2, "test.java");
    }

    @Test
    void editingNearTheEndOfAMarkdownFencePreservesItsOpeningAndLexicalContext() {
        List<String> lines = new ArrayList<>(Collections.nCopies(20_000, "return 7;"));
        lines.set(0, "```java");
        lines.set(1, "/*");
        CountingDocument document = new CountingDocument(lines);
        ContextualSyntaxHighlighter highlighter = highlighter("test.md");
        int last = document.lineCount() - 1;
        highlight(highlighter, document, last);

        document.model.insertChar(new Position(last, 0), ' ');
        document.forbidReadsBefore(last);

        assertEquals(
            List.of(new TokenSpan(0, document.model.getLine(last).length(), COMMENT)),
            highlight(highlighter, document, last)
        );
    }

    @Test
    void changingAnEarlierFenceUpdatesItsLanguageAndClosingState() {
        CountingDocument document = new CountingDocument(List.of(
            "# Heading", "```java", "/*", "return 7;", "```", "return 8;"
        ));
        ContextualSyntaxHighlighter highlighter = highlighter("test.md");
        highlight(highlighter, document, 5);
        highlight(highlighter, document, 3);

        document.model.deleteRange(new Position(1, 3), new Position(1, 7));
        document.model.insertText(new Position(1, 3), "python");
        document.forbidReadsBefore(1);
        assertMatchesFresh(highlighter, document, 3, "test.md");
        assertMatchesFresh(highlighter, document, 5, "test.md");

        document.model.deleteRange(new Position(4, 0), new Position(4, 3));
        // The newly extended code block needs lexical state past the last code
        // line requested before this edit, even though that line did not change.
        document.forbidReadsBefore(3);
        assertMatchesFresh(highlighter, document, 5, "test.md");
    }

    @Test
    void changesOutsideTheComputedPrefixDoNotDiscardIt() {
        CountingDocument document = new CountingDocument(List.of("/*", "return 2;", "return 3;"));
        ContextualSyntaxHighlighter highlighter = highlighter("test.java");
        highlight(highlighter, document, 1);

        document.model.insertChar(new Position(2, 0), ' ');
        document.forbidReadsBefore(1);

        assertEquals(List.of(new TokenSpan(0, 9, COMMENT)), highlight(highlighter, document, 1));
    }

    private static void assertMatchesFresh(
        ContextualSyntaxHighlighter highlighter,
        CountingDocument document,
        int line,
        String fileName
    ) {
        CountingDocument fresh = new CountingDocument(document.model.snapshotLines());
        assertEquals(highlight(highlighter(fileName), fresh, line), highlight(highlighter, document, line));
    }

    private static ContextualSyntaxHighlighter highlighter(String fileName) {
        return (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(fileName);
    }

    private static List<TokenSpan> highlight(ContextualSyntaxHighlighter highlighter, CountingDocument document, int line) {
        return highlighter.highlight(document, line, document.model.getLine(line));
    }

    private static final class CountingDocument implements DocumentView {
        private final DocumentId id = new DocumentId("incremental-test");
        private final TextModel model = new TextModel(new SimpleLineBuffer());
        private int firstReadableLine;

        CountingDocument(List<String> lines) {
            model.replaceLines(lines);
        }

        void forbidReadsBefore(int line) {
            firstReadableLine = line;
        }

        @Override
        public DocumentId documentId() {
            return id;
        }

        @Override
        public long revision() {
            return model.revision();
        }

        @Override
        public int lineCount() {
            return model.lineCount();
        }

        @Override
        public String line(int index) {
            assertTrue(index >= firstReadableLine, "Unchanged prefix was read again at line " + index);
            return model.getLine(index);
        }

        @Override
        public int firstChangedLineSince(long earlierRevision) {
            return model.firstChangedLineSince(earlierRevision);
        }
    }
}

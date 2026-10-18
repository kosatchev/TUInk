package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps independently owned editing and highlighting rules aligned at Markdown code boundaries. */
class MarkdownEditingBoundaryContractTest {
    @Test
    void codeHighlightingAndAutomaticListsAgreeInsideListContainers() {
        record Example(List<String> prefix, String item, boolean insideCode) {}
        Style keyword = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
        for (Example example : List.of(
            new Example(List.of("- parent", "    ```python"), "    1. return", true),
            new Example(List.of("- parent", "\t```python"), "\t1. return", true),
            new Example(List.of("-\t```python"), "\t1. return", true),
            new Example(List.of("- parent", "", "    ~~~python"), "    1. return", true),
            new Example(List.of("- ```python"), "  1. return", true),
            new Example(List.of("123. ~~~python"), "     1. return", true),
            new Example(List.of("- parent", "    - child", "        ```python"), "        1. return", true),
            new Example(List.of("- parent", "    ```python", "    ```not-a-close"), "    1. return", true),
            new Example(List.of("- parent", "    ```python", "    ```"), "    1. return", false),
            new Example(List.of("- parent", "    ```python"), "1. return", false)
        )) {
            List<String> lines = new ArrayList<>(example.prefix());
            lines.add(example.item());
            DocumentSnapshot document = new DocumentSnapshot(new DocumentId("nested-markdown-boundaries"), 0, lines);
            ContextualLineHighlighter highlighter = (ContextualLineHighlighter) RegexHighlighter.forFileName("notes.md");
            StyleSpan returnKeyword = new StyleSpan(example.item().indexOf("return"), example.item().length(), keyword);
            assertEquals(example.insideCode(), highlighter.highlight(document, lines.size() - 1, example.item())
                .contains(returnKeyword), example.toString());

            for (int tabWidth : List.of(2, 4)) {
                TextModel model = new TextModel(new SimpleLineBuffer());
                model.replaceLines(lines);
                EditorState state = new EditorState();
                state.setCurrentFile(Path.of("notes.md"));
                EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
                engine.setTabWidth(tabWidth);
                engine.setAutoIndentEnabled(false);
                engine.moveCursorTo(new Position(lines.size() - 1, example.item().length()));
                engine.insertNewline();

                assertEquals(example.insideCode() ? "" : "2. ", model.getLine(lines.size()),
                    example + " tabWidth=" + tabWidth);
            }
        }
    }

    @Test
    void codeHighlightingAndAutomaticListsAgreeOnFenceBoundaries() {
        record Example(List<String> prefix, boolean insideCode) {}
        String item = "1. return";
        Style keyword = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
        StyleSpan returnKeyword = new StyleSpan(3, item.length(), keyword);
        for (Example example : List.of(
            new Example(List.of("```python", "```not-a-close"), true),
            new Example(List.of("~~~python", "~~~not-a-close"), true),
            new Example(List.of("```python", "    ```"), true),
            new Example(List.of("```python", "\t```"), true),
            new Example(List.of("   ```python", "   \t```"), true),
            new Example(List.of("````python", "```"), true),
            new Example(List.of("~~~~python", "~~~"), true),
            new Example(List.of("```python", "~~~"), true),
            new Example(List.of("```python", "   ``` \t"), false),
            new Example(List.of("  ~~~~python", " ~~~~~\t"), false),
            new Example(List.of("```python", "```not-a-close", "```"), false),
            new Example(List.of("    ```python"), false),
            new Example(List.of("\t~~~python"), false)
        )) {
            List<String> lines = new ArrayList<>(example.prefix());
            lines.add(item);
            DocumentSnapshot document = new DocumentSnapshot(new DocumentId("markdown-boundaries"), 0, lines);
            ContextualLineHighlighter highlighter = (ContextualLineHighlighter) RegexHighlighter.forFileName("notes.md");
            boolean highlightedAsCode = highlighter.highlight(document, lines.size() - 1, item).contains(returnKeyword);
            assertEquals(example.insideCode(), highlightedAsCode, example.prefix().toString());

            for (int tabWidth : List.of(2, 4)) {
                TextModel model = new TextModel(new SimpleLineBuffer());
                model.replaceLines(lines);
                EditorState state = new EditorState();
                state.setCurrentFile(Path.of("notes.md"));
                EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
                engine.setTabWidth(tabWidth);
                engine.setAutoIndentEnabled(false);
                engine.moveCursorTo(new Position(lines.size() - 1, item.length()));

                engine.insertNewline();

                assertEquals(highlightedAsCode ? "" : "2. ", model.getLine(lines.size()),
                    example.prefix() + " tabWidth=" + tabWidth);
            }
        }
    }
}

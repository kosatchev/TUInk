package dev.tuink.render;

import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.HighlightResult;

/** A render adapter can retain bounded syntax roles independently of its current palette. */
interface SemanticLineHighlighter extends ContextualLineHighlighter {
    HighlightResult highlightTokens(DocumentView document, int lineIndex, String line, HighlightRequest request);

    SyntaxStyleResolver syntaxStyleResolver();
}

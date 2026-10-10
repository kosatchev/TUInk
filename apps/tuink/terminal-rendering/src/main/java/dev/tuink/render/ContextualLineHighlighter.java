package dev.tuink.render;

import dev.tuink.editor.document.DocumentView;
import java.util.List;

/**
 * Optional highlighter contract when syntax rules depend on document context.
 */
public interface ContextualLineHighlighter extends LineHighlighter {
    List<StyleSpan> highlight(DocumentView document, int lineIndex, String line);

    /** Semantic string/comment scanner, or null when only display styles are available. */
    default LineLexicalScanner lexicalScanner() {
        return null;
    }
}

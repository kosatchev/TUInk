package dev.tuink.editor.highlight;

import dev.tuink.editor.document.DocumentView;
import java.util.List;

/**
 * Optional highlighter contract when syntax rules depend on document context.
 */
public interface ContextualSyntaxHighlighter extends SyntaxHighlighter {
    List<TokenSpan> highlight(DocumentView document, int lineIndex, String line);

    /** Bounded display styling; lexical continuation is supplied separately by the scanner. */
    default List<TokenSpan> highlight(DocumentView document, int lineIndex, String line, HighlightRequest request) {
        return request.allowsFullLine(line)
            ? request.clipSpans(highlight(document, lineIndex, line)) : List.of();
    }

    /**
     * Distinguishes finished styling from a bounded context scan that retained progress.
     * Implementations request a retry only when it can continue that work; ordinary
     * unstyled lines and permanent budget fallbacks remain cacheable.
     */
    default HighlightResult highlightResult(DocumentView document, int lineIndex, String line,
                                            HighlightRequest request) {
        return HighlightResult.completed(highlight(document, lineIndex, line, request));
    }

    /** Semantic string/comment scanner, or null when only display styles are available. */
    default LexicalScanner lexicalScanner() {
        return null;
    }
}

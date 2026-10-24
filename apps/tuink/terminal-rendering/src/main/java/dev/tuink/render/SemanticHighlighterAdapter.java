package dev.tuink.render;

import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies a frontend palette while preserving portable highlighting budgets and continuation. */
public final class SemanticHighlighterAdapter implements ContextualLineHighlighter {
    private final SyntaxHighlighter delegate;
    private final SyntaxStyleResolver styles;
    private final LineLexicalScanner scanner;

    public SemanticHighlighterAdapter(SyntaxHighlighter delegate) {
        this(delegate, SyntaxStyleResolver.standard());
    }

    public SemanticHighlighterAdapter(SyntaxHighlighter delegate, SyntaxStyleResolver styles) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.styles = Objects.requireNonNull(styles, "styles");
        var semanticScanner = delegate instanceof ContextualSyntaxHighlighter contextual
            ? contextual.lexicalScanner() : null;
        scanner = semanticScanner == null ? null : semanticScanner::scan;
    }

    @Override public List<StyleSpan> highlight(String line) {
        return styled(delegate.highlight(line));
    }

    @Override public List<StyleSpan> highlight(String line, HighlightRequest request) {
        var budget = portable(request);
        return styled(budget.clipSpans(delegate.highlight(line, budget)));
    }

    @Override public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line) {
        return styled(delegate instanceof ContextualSyntaxHighlighter contextual
            ? contextual.highlight(document, lineIndex, line) : delegate.highlight(line));
    }

    @Override public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line,
                                              HighlightRequest request) {
        return highlightResult(document, lineIndex, line, request).spans();
    }

    @Override public HighlightResult highlightResult(DocumentView document, int lineIndex, String line,
                                                     HighlightRequest request) {
        var budget = portable(request);
        var result = delegate instanceof ContextualSyntaxHighlighter contextual
            ? contextual.highlightResult(document, lineIndex, line, budget)
            : dev.tuink.editor.highlight.HighlightResult.completed(delegate.highlight(line, budget));
        return new HighlightResult(styled(budget.clipSpans(result.spans())), result.retryPending());
    }

    @Override public LineLexicalScanner lexicalScanner() {
        return scanner;
    }

    private static dev.tuink.editor.highlight.HighlightRequest portable(HighlightRequest request) {
        return new dev.tuink.editor.highlight.HighlightRequest(request.startUtf16(), request.endUtf16(),
            request.maxScanCodeUnits(), request.maxSpans(), request.maxWork());
    }

    private List<StyleSpan> styled(List<TokenSpan> tokens) {
        List<StyleSpan> result = new ArrayList<>(tokens.size());
        for (TokenSpan token : tokens) {
            Style style = Objects.requireNonNullElse(styles.resolve(token.styleKey()), Style.DEFAULT);
            result.add(new StyleSpan(token.start(), token.end(), style));
        }
        return List.copyOf(result);
    }
}

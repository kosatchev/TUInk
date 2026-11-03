package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.HighlightResult;
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.render.markdown.MarkdownLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

/** One worker-owned layout's bounded use of the existing language highlighters. */
final class MarkdownCodeHighlighter implements MarkdownLayout.CodeHighlighter {
    static final long MAX_WORK = 32_000_000L;
    static final int MAX_TOKENS = 32_768;
    static final int MAX_LINE_TOKENS = 1024;
    static final int MAX_SCAN_CODE_UNITS = 65_536;
    static final int MAX_LANGUAGE_CODE_UNITS = 4096;
    static final int MAX_BLOCKS = 512;
    static final long STYLED_ROW_BYTES = 48;
    static final long TOKEN_BYTES = 64;
    private static final int MAX_CONTEXT_ATTEMPTS = 3;
    private static final DocumentId BLOCK_ID = new DocumentId("markdown-code-block");

    private long remainingStyleBytes;
    private long remainingWork;
    private int remainingTokens;
    private int remainingBlocks = MAX_BLOCKS;

    MarkdownCodeHighlighter(long maxStyleBytes) {
        this(maxStyleBytes, MAX_WORK, MAX_TOKENS);
    }

    MarkdownCodeHighlighter(long maxStyleBytes, long maxWork, int maxTokens) {
        if (maxStyleBytes < 0 || maxWork < 0 || maxTokens < 0)
            throw new IllegalArgumentException("Code highlighting budgets must be nonnegative");
        remainingStyleBytes = maxStyleBytes;
        remainingWork = Math.min(MAX_WORK, maxWork);
        remainingTokens = Math.min(MAX_TOKENS, maxTokens);
    }

    @Override
    public List<List<TokenSpan>> highlight(String language, List<String> lines) {
        checkCancelled();
        List<List<TokenSpan>> plain = Collections.nCopies(lines.size(), List.of());
        if (lines.isEmpty() || lines.size() > MAX_TOKENS || remainingBlocks == 0 || exhausted()) return plain;
        boolean hasText = false;
        for (String line : lines) {
            checkCancelled();
            if (!line.isEmpty()) { hasText = true; break; }
        }
        if (!hasText) return plain;
        int languageLength = language == null ? 0 : language.length();
        if (languageLength > MAX_LANGUAGE_CODE_UNITS || !reserveWork(4096L + 16L * languageLength)) return plain;

        // A fresh delegate scopes every lexical cache to this block, including aliases
        // of a previously used language. The view borrows immutable code strings and
        // never touches an editing engine, source buffer or source presentation.
        remainingBlocks--;
        var highlighter = RegexHighlighter.forLanguageTag(language);
        var document = new CodeDocument(List.copyOf(lines));
        List<List<TokenSpan>> styled = null;
        int previousLength = 0;
        for (int row = 0; row < lines.size(); row++) {
            checkCancelled();
            if (exhausted()) break;
            String line = document.line(row);
            // A truncated line cannot establish trustworthy context for later rows.
            // A separate block can still use its own complete context and allowance.
            if (line.length() > MAX_SCAN_CODE_UNITS) break;
            if (line.isEmpty()) { previousLength = 0; continue; }
            long requestWork = 512L + 32L * (line.length() + previousLength);
            HighlightResult highlighted = null;
            for (int attempt = 0; attempt < MAX_CONTEXT_ATTEMPTS; attempt++) {
                checkCancelled();
                if (!reserveWork(requestWork)) break;
                var request = new HighlightRequest(0, line.length(), MAX_SCAN_CODE_UNITS,
                    Math.min(MAX_LINE_TOKENS, remainingTokens), requestWork);
                highlighted = highlighter instanceof ContextualSyntaxHighlighter contextual
                    ? contextual.highlightResult(document, row, line, request)
                    : HighlightResult.completed(highlighter.highlight(line, request));
                if (!highlighted.retryPending()) break;
            }
            previousLength = line.length();
            if (highlighted == null || highlighted.retryPending() || highlighted.spans().isEmpty()) continue;
            List<TokenSpan> spans = highlighted.spans();
            if (!valid(spans, line.length())) continue;
            long bytes = STYLED_ROW_BYTES + TOKEN_BYTES * spans.size();
            if (bytes > remainingStyleBytes) { remainingStyleBytes = 0; break; }
            remainingStyleBytes -= bytes;
            remainingTokens -= spans.size();
            if (styled == null) styled = new ArrayList<>(plain);
            styled.set(row, spans);
        }
        return styled == null ? plain : Collections.unmodifiableList(styled);
    }

    private boolean exhausted() {
        return remainingTokens == 0 || remainingWork == 0
            || remainingStyleBytes < STYLED_ROW_BYTES + TOKEN_BYTES;
    }

    private boolean reserveWork(long work) {
        if (work > remainingWork) { remainingWork = 0; return false; }
        remainingWork -= work;
        return true;
    }

    private boolean valid(List<TokenSpan> spans, int length) {
        if (spans.size() > Math.min(MAX_LINE_TOKENS, remainingTokens)) return false;
        int previousEnd = 0;
        for (TokenSpan span : spans) {
            if (span.start() < previousEnd || span.end() <= span.start() || span.end() > length) return false;
            previousEnd = span.end();
        }
        return true;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Preview code highlighting cancelled");
    }

    private record CodeDocument(List<String> lines) implements DocumentView {
        @Override public DocumentId documentId() { return BLOCK_ID; }
        @Override public long revision() { return 0; }
        @Override public int lineCount() { return lines.size(); }
        @Override public String line(int index) { return lines.get(index); }
    }
}

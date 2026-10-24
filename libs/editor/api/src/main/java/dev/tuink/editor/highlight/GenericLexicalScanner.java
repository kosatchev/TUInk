package dev.tuink.editor.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Conservative quote/comment scanner for display-only highlighters and Markdown prose. */
public final class GenericLexicalScanner implements LexicalScanner {
    private final LexicalConfig profile;

    public GenericLexicalScanner(LexicalConfig profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public Result scan(String line, int maxCodeUnits, State previous) {
        Objects.requireNonNull(line, "line");
        if (maxCodeUnits < 0) {
            throw new IllegalArgumentException("maxCodeUnits must be non-negative");
        }
        if (previous == State.UNKNOWN) {
            return new Result(0, previous, List.of(), List.of(), false);
        }
        int limit = Math.min(line.length(), maxCodeUnits);
        String prefix = limit == line.length() ? line : line.substring(0, limit);
        QuoteContinuationState state = previous instanceof QuoteContinuationState continuation
            ? continuation : QuoteContinuationState.NONE;
        QuoteMaskResult result = computeQuotedMask(prefix, profile, state);
        List<Range> ranges = new ArrayList<>();
        int start = -1;
        int scanned = result.scannedCodeUnits();
        boolean contextKnown = result.contextKnown() && limit == line.length();
        for (int i = 0; i <= scanned; i++) {
            if (i < scanned && result.mask()[i]) {
                if (start < 0) {
                    if (ranges.size() == MAX_EXCLUDED_RANGES) {
                        scanned = i;
                        contextKnown = false;
                        break;
                    }
                    start = i;
                }
            } else if (start >= 0) {
                ranges.add(new Range(start, i));
                start = -1;
            }
        }
        int provenEnd = scanned;
        boolean complete = contextKnown;
        List<Quote> quotes = result.quoteRanges().stream()
            .filter(quote -> quote.start() < provenEnd && quote.end() < provenEnd
                && (complete || quote.end() >= 0))
            .map(quote -> new Quote(quote.start(), quote.end())).toList();
        return new Result(scanned, result.endState(), ranges, quotes, contextKnown);
    }

    private static QuoteMaskResult computeQuotedMask(
        String line,
        LexicalConfig profile,
        QuoteContinuationState startState
    ) {
        boolean[] mask = new boolean[line.length()];
        if (profile.markdownNumberParen()) {
            int markerParen = markdownNumberParenIndex(line);
            if (markerParen >= 0) {
                mask[markerParen] = true;
            }
        }
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inBlockComment = startState.inSlashBlockComment();
        boolean inHtmlComment = startState.inHtmlComment();
        boolean escaped = false;
        int quoteStart = -1;
        List<QuoteRange> quoteRanges = null;
        int scanned = line.length();

        for (int i = 0; i < line.length(); i++) {
            if (mask[i]) {
                continue;
            }
            char ch = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            char next2 = i + 2 < line.length() ? line.charAt(i + 2) : '\0';
            char next3 = i + 3 < line.length() ? line.charAt(i + 3) : '\0';
            if (inBlockComment) {
                mask[i] = true;
                if (ch == '*' && next == '/') {
                    mask[i + 1] = true;
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inHtmlComment) {
                mask[i] = true;
                if (ch == '-' && next == '-' && next2 == '>') {
                    mask[i + 1] = true;
                    mask[i + 2] = true;
                    inHtmlComment = false;
                    i += 2;
                }
                continue;
            }
            if (inSingle || inDouble || inBacktick) {
                mask[i] = true;
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (inSingle && ch == '\'') {
                    inSingle = false;
                } else if (inDouble && ch == '"') {
                    inDouble = false;
                } else if (inBacktick && ch == '`') {
                    inBacktick = false;
                }
                if (quoteStart >= 0 && !inSingle && !inDouble) {
                    if (quoteRanges == null) {
                        quoteRanges = new ArrayList<>();
                    }
                    quoteRanges.add(new QuoteRange(quoteStart, i));
                    quoteStart = -1;
                }
                continue;
            }

            if (profile.slashLine() && ch == '/' && next == '/') {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (profile.slashBlock() && ch == '/' && next == '*') {
                mask[i] = true;
                mask[i + 1] = true;
                inBlockComment = true;
                i++;
                continue;
            }
            if (profile.htmlBlock() && ch == '<' && next == '!' && next2 == '-' && next3 == '-') {
                mask[i] = true;
                mask[i + 1] = true;
                mask[i + 2] = true;
                mask[i + 3] = true;
                inHtmlComment = true;
                i += 3;
                continue;
            }
            if (profile.hashLine() && ch == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (profile.semicolonLine() && ch == ';' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (profile.dashDashLine() && ch == '-' && next == '-') {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (ch == '\'' && !isApostropheInWord(line, i) && !isEscaped(line, i)) {
                if (quoteRanges != null && quoteRanges.size() == MAX_QUOTES) {
                    scanned = i;
                    break;
                }
                mask[i] = true;
                inSingle = true;
                quoteStart = i;
            } else if (ch == '"' && !isEscaped(line, i)) {
                if (quoteRanges != null && quoteRanges.size() == MAX_QUOTES) {
                    scanned = i;
                    break;
                }
                mask[i] = true;
                inDouble = true;
                quoteStart = i;
            } else if (ch == '`') {
                mask[i] = true;
                inBacktick = true;
            }
        }
        if (quoteStart >= 0) {
            if (quoteRanges == null) {
                quoteRanges = new ArrayList<>();
            }
            quoteRanges.add(new QuoteRange(quoteStart, -1));
        }
        return new QuoteMaskResult(
            mask,
            new QuoteContinuationState(inBlockComment, inHtmlComment),
            quoteRanges == null ? List.of() : List.copyOf(quoteRanges), scanned, scanned == line.length()
        );
    }

    private static int markdownNumberParenIndex(String line) {
        if (line == null || line.isEmpty()) {
            return -1;
        }
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        if (i >= line.length()) {
            return -1;
        }

        if (line.charAt(i) == '#') {
            int hashStart = i;
            while (i < line.length() && line.charAt(i) == '#') {
                i++;
            }
            int hashes = i - hashStart;
            if (hashes > 6) {
                return -1;
            }
            if (i >= line.length() || !Character.isWhitespace(line.charAt(i))) {
                return -1;
            }
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
        }

        int numberStart = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i == numberStart || i >= line.length() || line.charAt(i) != ')') {
            return -1;
        }
        if (i + 1 < line.length() && !Character.isWhitespace(line.charAt(i + 1))) {
            return -1;
        }
        return i;
    }

    private static boolean isEscaped(String line, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static boolean isApostropheInWord(String line, int index) {
        if (index < 0 || index >= line.length() || line.charAt(index) != '\'') {
            return false;
        }
        boolean leftWord = index > 0 && Character.isLetterOrDigit(line.codePointBefore(index));
        boolean rightWord = index + 1 < line.length() && Character.isLetterOrDigit(line.codePointAt(index + 1));
        return leftWord && rightWord;
    }

    private record QuoteRange(int start, int end) {
    }

    private record QuoteMaskResult(
        boolean[] mask, QuoteContinuationState endState, List<QuoteRange> quoteRanges,
        int scannedCodeUnits, boolean contextKnown
    ) {
    }

    private record QuoteContinuationState(boolean inSlashBlockComment, boolean inHtmlComment) implements State {
        private static final QuoteContinuationState NONE = new QuoteContinuationState(false, false);
    }
}

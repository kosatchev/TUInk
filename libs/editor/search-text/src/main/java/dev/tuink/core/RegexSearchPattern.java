package dev.tuink.core;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** One compiled expression; each scan owns its Matcher and budgeted input view. */
final class RegexSearchPattern {
    static final int MAX_QUERY_LENGTH = 4096;
    private static final long MAX_SCAN_WORK = 8_000_000L;
    private final Pattern pattern;

    RegexSearchPattern(String query, boolean insensitive) {
        if (query.length() > MAX_QUERY_LENGTH)
            throw new SearchPatternException("Regular expressions are limited to 4096 UTF-16 units");
        try {
            pattern = Pattern.compile(query, insensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException invalid) {
            String position = invalid.getIndex() < 0 ? "" : " at " + (invalid.getIndex() + 1);
            throw new SearchPatternException("Invalid regular expression" + position + ": " + invalid.getDescription());
        } catch (StackOverflowError excessiveNesting) {
            throw new SearchPatternException("Regular expression nesting is too deep");
        }
    }

    SearchMatcher.Cursor cursor(CharSequence source, int acceptedEnd, boolean afterEmpty, SearchOperationContext construction) {
        Input input = new Input(source, 0, source.length());
        input.context = construction.child(MAX_SCAN_WORK);
        final java.util.regex.Matcher matcher;
        try { matcher = pattern.matcher(input); }
        finally { input.context = null; }
        return new SearchMatcher.Cursor() {
            @Override boolean advance(SearchOperationContext context) {
                input.context = context.child(MAX_SCAN_WORK);
                try {
                    while (matcher.find()) {
                        int start = matcher.start(), end = matcher.end();
                        if (end < acceptedEnd || end == acceptedEnd && (start < end || afterEmpty)) continue;
                        if (!boundary(source, start) || !boundary(source, end)) continue;
                        return found(start, end);
                    }
                    return false;
                } catch (StackOverflowError excessiveBacktracking) {
                    throw new SearchLimitException();
                } finally { input.context = null; }
            }
        };
    }

    private static boolean boundary(CharSequence text, int offset) {
        return offset <= 0 || offset >= text.length()
            || !Character.isHighSurrogate(text.charAt(offset - 1)) || !Character.isLowSurrogate(text.charAt(offset));
    }

    /** Pattern reads through CharSequence, so input access shares cancellation and the operation budget. */
    private static final class Input implements CharSequence {
        final CharSequence text;
        final int from, to;
        SearchOperationContext context;
        Input(CharSequence text, int from, int to) { this.text = text; this.from = from; this.to = to; }
        public int length() { context.consume(1); return to - from; }
        public char charAt(int index) {
            context.consume(1);
            if (index < 0 || index >= to - from) throw new IndexOutOfBoundsException(index);
            return text.charAt(from + index);
        }
        public CharSequence subSequence(int start, int end) {
            context.consume(1);
            if (start < 0 || end < start || end > to - from) throw new IndexOutOfBoundsException();
            Input result = new Input(text, from + start, from + end);
            result.context = context;
            return result;
        }
        public String toString() {
            context.consume(to - from);
            SearchMemoryBudget.requireScratch(64L + 2L * (to - from));
            return text.subSequence(from, to).toString();
        }
    }
}

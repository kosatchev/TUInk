package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiled single-line search whose match ranges always address the original
 * source string in UTF-16 offsets.
 */
public final class SearchMatcher {
    private final SearchOptions.MatchMode matchMode;
    private final Pattern pattern;

    private SearchMatcher(String query, SearchOptions options) {
        String safeQuery = query == null ? "" : query;
        SearchOptions safeOptions = options == null ? SearchOptions.DEFAULT : options;
        this.matchMode = safeOptions.matchMode() == null
            ? SearchOptions.MatchMode.SUBSTRING
            : safeOptions.matchMode();
        this.pattern = safeQuery.isEmpty()
            ? null
            : compilePattern(safeQuery, safeOptions.caseInsensitive(), matchMode);
    }

    public static SearchMatcher compile(String query, SearchOptions options) {
        return new SearchMatcher(query, options);
    }

    /** Returns non-overlapping half-open UTF-16 ranges in {@code source}. */
    public List<Match> findAll(String source) {
        if (source == null || source.isEmpty() || pattern == null) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (start == end) {
                break;
            }
            if (acceptsBoundaries(source, start, end)) {
                matches.add(new Match(start, end));
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Returns whether the supplied source range is exactly one of this
     * matcher's results. The range is expressed in original-source UTF-16
     * offsets.
     */
    public boolean matchesRange(String source, int startUtf16, int endUtf16) {
        if (source == null
            || startUtf16 < 0
            || endUtf16 < startUtf16
            || endUtf16 > source.length()
            || pattern == null) {
            return false;
        }
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (start == end) {
                break;
            }
            if (!acceptsBoundaries(source, start, end)) {
                continue;
            }
            if (start == startUtf16 && end == endUtf16) {
                return true;
            }
            if (start > startUtf16) {
                return false;
            }
        }
        return false;
    }

    private boolean acceptsBoundaries(String source, int start, int end) {
        return matchMode != SearchOptions.MatchMode.WHOLE_WORD || isWholeWord(source, start, end);
    }

    private static Pattern compilePattern(
        String query,
        boolean caseInsensitive,
        SearchOptions.MatchMode matchMode
    ) {
        int flags = Pattern.DOTALL;
        if (caseInsensitive) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        String expression = matchMode == SearchOptions.MatchMode.WILDCARD
            ? wildcardExpression(query)
            : Pattern.quote(query);
        return Pattern.compile(expression, flags);
    }

    private static String wildcardExpression(String wildcard) {
        StringBuilder expression = new StringBuilder();
        for (int offset = 0; offset < wildcard.length();) {
            int codePoint = wildcard.codePointAt(offset);
            if (codePoint == '*') {
                expression.append(".*");
            } else if (codePoint == '?') {
                expression.append('.');
            } else {
                expression.append(Pattern.quote(new String(Character.toChars(codePoint))));
            }
            offset += Character.charCount(codePoint);
        }
        return expression.toString();
    }

    private static boolean isWholeWord(String source, int start, int end) {
        boolean leftBoundary = start == 0 || !isWordCodePoint(source.codePointBefore(start));
        boolean rightBoundary = end == source.length() || !isWordCodePoint(source.codePointAt(end));
        return leftBoundary && rightBoundary;
    }

    private static boolean isWordCodePoint(int codePoint) {
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }

    public record Match(int startUtf16, int endUtf16) {
        public Match {
            if (startUtf16 < 0 || endUtf16 < startUtf16) {
                throw new IllegalArgumentException("Invalid UTF-16 match range");
            }
        }

        public int lengthUtf16() {
            return endUtf16 - startUtf16;
        }
    }
}

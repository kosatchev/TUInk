package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search utilities that work across tab sessions.
 */
public final class TabSearch {
    private TabSearch() {
    }

    public static int countMatches(List<TabSession> tabs, String query, SearchOptions options) {
        if (tabs == null || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        int total = 0;
        for (TabSession tab : tabs) {
            TextModel model = tab.model();
            for (int i = 0; i < model.lineCount(); i++) {
                total += findMatchesInLine(model.getLine(i), query, safe).size();
            }
        }
        return total;
    }

    public static int countMatchesInModel(TextModel model, String query, SearchOptions options) {
        if (model == null || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        int total = 0;
        for (int i = 0; i < model.lineCount(); i++) {
            total += findMatchesInLine(model.getLine(i), query, safe).size();
        }
        return total;
    }

    public static int[] findMatchTabs(List<TabSession> tabs, String query, SearchOptions options) {
        if (tabs == null) {
            return new int[0];
        }
        int[] flags = new int[tabs.size()];
        if (query == null || query.isEmpty()) {
            return flags;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        for (int tabIndex = 0; tabIndex < tabs.size(); tabIndex++) {
            TextModel model = tabs.get(tabIndex).model();
            for (int line = 0; line < model.lineCount(); line++) {
                if (!findMatchesInLine(model.getLine(line), query, safe).isEmpty()) {
                    flags[tabIndex] = 1;
                    break;
                }
            }
        }
        return flags;
    }

    private static List<LineMatch> findMatchesInLine(String line, String query, SearchOptions options) {
        String haystack = options.caseInsensitive() ? line.toLowerCase(Locale.ROOT) : line;
        String needle = options.caseInsensitive() ? query.toLowerCase(Locale.ROOT) : query;
        return switch (options.matchMode()) {
            case SUBSTRING -> findLiteralMatches(haystack, needle, false);
            case WHOLE_WORD -> findLiteralMatches(haystack, needle, true);
            case WILDCARD -> findWildcardMatches(haystack, needle);
        };
    }

    private static List<LineMatch> findLiteralMatches(String text, String needle, boolean wholeWord) {
        if (needle.isEmpty()) {
            return List.of();
        }
        List<LineMatch> matches = new ArrayList<>();
        int at = 0;
        while (at <= text.length() - needle.length()) {
            int idx = text.indexOf(needle, at);
            if (idx < 0) {
                break;
            }
            int end = idx + needle.length();
            if (!wholeWord || isWholeWord(text, idx, end)) {
                matches.add(new LineMatch(idx, end));
            }
            at = end;
        }
        return matches;
    }

    private static List<LineMatch> findWildcardMatches(String text, String wildcard) {
        List<LineMatch> matches = new ArrayList<>();
        Pattern pattern = wildcardPattern(wildcard);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.start() == matcher.end()) {
                break;
            }
            matches.add(new LineMatch(matcher.start(), matcher.end()));
        }
        return matches;
    }

    private static Pattern wildcardPattern(String wildcard) {
        StringBuilder regex = new StringBuilder("(?s)");
        for (int i = 0; i < wildcard.length(); i++) {
            char ch = wildcard.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end == text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private record LineMatch(int start, int end) {
    }
}

package dev.tuink.highlight.regex;

import dev.tuink.editor.highlight.SemanticStyleKey;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies simple inline links without retrying an unmatched label at every opening bracket. */
final class MarkdownInlineHighlighter extends RegexHighlighter {
    private static final SemanticStyleKey LINK_STYLE = SemanticStyleKey.LINK;
    private static final SemanticStyleKey QUOTE_STYLE = SemanticStyleKey.QUOTE;
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s+.*$");

    MarkdownInlineHighlighter(List<RegexRule> rules) {
        super(rules);
    }

    @Override
    protected SemanticStyleKey[] highlightStyles(String line, HighlightWork work) {
        work.spend(8L * line.length());
        SemanticStyleKey[] styles = super.highlightStyles(line, work);
        highlightLinks(line, styles);
        Matcher quote = QUOTE.matcher(line);
        if (quote.find()) {
            Arrays.fill(styles, quote.start(), quote.end(), QUOTE_STYLE);
        }
        return styles;
    }

    private static void highlightLinks(String line, SemanticStyleKey[] styles) {
        int cursor = 0;
        while (cursor < line.length()) {
            int start = line.indexOf('[', cursor);
            if (start < 0) {
                return;
            }
            int labelEnd = line.indexOf(']', start + 1);
            if (labelEnd < 0) {
                return;
            }
            // Every opening bracket before this ']' has the same candidate destination.
            // If it fails, skip that label rather than rescanning its nested '[' suffixes.
            cursor = labelEnd + 1;
            if (labelEnd == start + 1 || cursor >= line.length() || line.charAt(cursor) != '(') {
                continue;
            }
            int destinationStart = cursor + 1;
            int end = line.indexOf(')', destinationStart);
            if (end < 0) {
                return;
            }
            if (end > destinationStart) {
                Arrays.fill(styles, start, end + 1, LINK_STYLE);
                cursor = end + 1;
            }
        }
    }
}

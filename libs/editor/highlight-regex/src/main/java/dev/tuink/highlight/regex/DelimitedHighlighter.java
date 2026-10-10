package dev.tuink.highlight.regex;

import dev.tuink.render.Style;
import java.util.Arrays;
import java.util.List;

/** Highlights delimited tokens without retrying the same unmatched suffix. */
final class DelimitedHighlighter extends RegexHighlighter {
    record Delimiter(String opening, String closing, boolean requiresContent) {
    }

    private final List<Delimiter> delimiters;
    private final Style style;
    private final List<RegexRule> followingRules;

    DelimitedHighlighter(
        List<RegexRule> precedingRules,
        List<Delimiter> delimiters,
        Style style,
        List<RegexRule> followingRules
    ) {
        super(precedingRules);
        this.delimiters = List.copyOf(delimiters);
        this.style = style;
        this.followingRules = List.copyOf(followingRules);
    }

    @Override
    protected Style[] highlightStyles(String line) {
        Style[] styles = super.highlightStyles(line);
        int[] nextOpening = new int[delimiters.size()];
        int[] lastClosing = new int[delimiters.size()];
        for (int index = 0; index < delimiters.size(); index++) {
            Delimiter delimiter = delimiters.get(index);
            nextOpening[index] = line.indexOf(delimiter.opening());
            lastClosing[index] = line.lastIndexOf(delimiter.closing());
        }
        int cursor = 0;
        while (cursor < line.length()) {
            int selected = -1;
            int start = line.length();
            for (int index = 0; index < delimiters.size(); index++) {
                int next = nextOpening[index];
                if (next >= 0 && next < cursor) {
                    next = line.indexOf(delimiters.get(index).opening(), cursor);
                    nextOpening[index] = next;
                }
                if (next >= 0 && next < start) {
                    start = next;
                    selected = index;
                }
            }
            if (selected < 0) {
                break;
            }
            Delimiter delimiter = delimiters.get(selected);
            int contentStart = start + delimiter.opening().length();
            // Cache absence of a closing delimiter: repeated openers then cost O(1) each.
            if (lastClosing[selected] < contentStart) {
                cursor = contentStart;
                continue;
            }
            int closing = line.indexOf(delimiter.closing(), contentStart);
            if (delimiter.requiresContent() && closing == contentStart) {
                cursor = contentStart;
                continue;
            }
            cursor = closing + delimiter.closing().length();
            Arrays.fill(styles, start, cursor, style);
        }
        applyRules(line, styles, followingRules);
        return styles;
    }
}

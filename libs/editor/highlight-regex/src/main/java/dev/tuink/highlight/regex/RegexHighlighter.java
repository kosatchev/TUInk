package dev.tuink.highlight.regex;

import dev.tuink.render.Color;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line highlighter using layered regex rules.
 */
public class RegexHighlighter implements LineHighlighter {
    private final List<RegexRule> rules;

    public RegexHighlighter(List<RegexRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RegexHighlighter withDefaults() {
        List<RegexRule> defaults = List.of(
            new RegexRule(Pattern.compile("\\b(public|class|void|if|else|for|while|return|static|new)\\b"),
                new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false)),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"),
                new Style(Color.BRIGHT_CYAN, Color.DEFAULT, false, false)),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""),
                new Style(Color.BRIGHT_GREEN, Color.DEFAULT, false, false)),
            new RegexRule(Pattern.compile("//.*$"),
                new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false)),
            new RegexRule(Pattern.compile("#.*$"),
                new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false))
        );
        return new RegexHighlighter(defaults);
    }

    @Override
    public List<StyleSpan> highlight(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }

        Style[] styleAt = new Style[line.length()];
        for (RegexRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(line);
            while (matcher.find()) {
                int start = Math.max(0, matcher.start());
                int end = Math.min(line.length(), matcher.end());
                for (int i = start; i < end; i++) {
                    styleAt[i] = rule.style();
                }
            }
        }

        List<StyleSpan> spans = new ArrayList<>();
        int start = -1;
        Style current = null;
        for (int i = 0; i < styleAt.length; i++) {
            Style style = styleAt[i];
            if (!Objects.equals(current, style)) {
                if (current != null) {
                    spans.add(new StyleSpan(start, i, current));
                }
                current = style;
                start = i;
            }
        }

        if (current != null) {
            spans.add(new StyleSpan(start, styleAt.length, current));
        }

        return spans;
    }
}

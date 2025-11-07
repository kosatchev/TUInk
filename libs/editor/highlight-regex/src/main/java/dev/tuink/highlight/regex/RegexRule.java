package dev.tuink.highlight.regex;

import dev.tuink.render.Style;
import java.util.regex.Pattern;

/**
 * Regex-to-style mapping rule.
 */
public record RegexRule(Pattern pattern, Style style) {
}

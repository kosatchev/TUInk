package dev.tuink.highlight.regex;

import dev.tuink.editor.highlight.SemanticStyleKey;
import java.util.regex.Pattern;

/**
 * Regex-to-style mapping rule.
 */
public record RegexRule(Pattern pattern, SemanticStyleKey styleKey) {
}

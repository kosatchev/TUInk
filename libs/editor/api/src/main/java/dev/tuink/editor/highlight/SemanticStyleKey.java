package dev.tuink.editor.highlight;

import java.util.Objects;
import java.util.regex.Pattern;

/** A semantic token role. Each frontend chooses its own visual representation. */
public record SemanticStyleKey(String value) {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+");
    public static final SemanticStyleKey KEYWORD = new SemanticStyleKey("syntax.keyword");
    public static final SemanticStyleKey TYPE = new SemanticStyleKey("syntax.type");
    public static final SemanticStyleKey NUMBER = new SemanticStyleKey("syntax.number");
    public static final SemanticStyleKey STRING = new SemanticStyleKey("syntax.string");
    public static final SemanticStyleKey COMMENT = new SemanticStyleKey("syntax.comment");
    public static final SemanticStyleKey DECORATOR = new SemanticStyleKey("syntax.decorator");
    public static final SemanticStyleKey MARKUP = new SemanticStyleKey("syntax.markup");
    public static final SemanticStyleKey LINK = new SemanticStyleKey("syntax.link");
    public static final SemanticStyleKey QUOTE = new SemanticStyleKey("syntax.quote");

    public SemanticStyleKey {
        Objects.requireNonNull(value, "value");
        if (value.length() > 128 || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid semantic style key: " + value);
        }
    }
}

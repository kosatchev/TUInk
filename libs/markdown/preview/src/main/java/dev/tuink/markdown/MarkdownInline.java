package dev.tuink.markdown;

import dev.tuink.editor.document.TextRange;
import java.util.List;
import java.util.Objects;

/** Inline semantics with UTF-16 source coordinates, independent of engine ASTs. */
public sealed interface MarkdownInline {
    TextRange source();
    record Text(String text, TextRange source) implements MarkdownInline {
        public Text { Objects.requireNonNull(text); Objects.requireNonNull(source); }
    }
    enum Style { EMPHASIS, STRONG, STRIKETHROUGH }
    record Styled(Style style, List<MarkdownInline> content, TextRange source) implements MarkdownInline {
        public Styled { Objects.requireNonNull(style); content = List.copyOf(content); Objects.requireNonNull(source); }
    }
    record Code(String text, TextRange source) implements MarkdownInline {
        public Code { Objects.requireNonNull(text); Objects.requireNonNull(source); }
    }
    record Link(List<MarkdownInline> content, String destination, String title, boolean image,
                TextRange source) implements MarkdownInline {
        public Link { content = List.copyOf(content); Objects.requireNonNull(destination); title = Objects.requireNonNullElse(title, ""); Objects.requireNonNull(source); }
    }
    record Math(String expression, TextRange source) implements MarkdownInline {
        public Math { Objects.requireNonNull(expression); Objects.requireNonNull(source); }
    }
    record Break(boolean hard, TextRange source) implements MarkdownInline {
        public Break { Objects.requireNonNull(source); }
    }
}

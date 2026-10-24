package dev.tuink.editor.highlight;

/** Conservative lexical capabilities without editing policy or frontend dependencies. */
public record LexicalConfig(boolean hashLine, boolean semicolonLine, boolean slashLine,
                            boolean slashBlock, boolean dashDashLine, boolean htmlBlock,
                            boolean markdownNumberParen) {
    public static final LexicalConfig GENERIC = new LexicalConfig(true, false, true, true, true, false, false);
    public static final LexicalConfig MARKDOWN = new LexicalConfig(false, false, false, false, false, true, true);
}

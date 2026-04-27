package dev.tuink.core;

/**
 * Comment syntax capabilities resolved from a document's file name.
 *
 * <p>The optional line prefix is used by editing commands. The remaining flags
 * are also consumed by rendering when comments must be excluded from bracket
 * and quote matching.</p>
 */
public enum CommentSyntaxProfile {
    NONE(null, false, false, false, false, false, false, false),
    HASH("#", true, false, false, false, false, false, false),
    SEMICOLON(";", false, true, false, false, false, false, false),
    C_STYLE("//", false, false, true, true, false, false, false),
    SQL("--", false, false, false, true, true, false, false),
    SLASH_BLOCK(null, false, false, false, true, false, false, false),
    HTML(null, false, false, false, false, false, true, false),
    MARKDOWN(null, false, false, false, false, false, true, true),
    GENERIC(null, true, false, true, true, true, false, false);

    private final String lineCommentPrefix;
    private final boolean hashLine;
    private final boolean semicolonLine;
    private final boolean slashLine;
    private final boolean slashBlock;
    private final boolean dashDashLine;
    private final boolean htmlBlock;
    private final boolean markdownNumberParen;

    CommentSyntaxProfile(
        String lineCommentPrefix,
        boolean hashLine,
        boolean semicolonLine,
        boolean slashLine,
        boolean slashBlock,
        boolean dashDashLine,
        boolean htmlBlock,
        boolean markdownNumberParen
    ) {
        this.lineCommentPrefix = lineCommentPrefix;
        this.hashLine = hashLine;
        this.semicolonLine = semicolonLine;
        this.slashLine = slashLine;
        this.slashBlock = slashBlock;
        this.dashDashLine = dashDashLine;
        this.htmlBlock = htmlBlock;
        this.markdownNumberParen = markdownNumberParen;
    }

    public String lineCommentPrefix() {
        return lineCommentPrefix;
    }

    public boolean supportsLineComments() {
        return lineCommentPrefix != null;
    }

    public boolean hashLine() {
        return hashLine;
    }

    public boolean semicolonLine() {
        return semicolonLine;
    }

    public boolean slashLine() {
        return slashLine;
    }

    public boolean slashBlock() {
        return slashBlock;
    }

    public boolean dashDashLine() {
        return dashDashLine;
    }

    public boolean htmlBlock() {
        return htmlBlock;
    }

    public boolean markdownNumberParen() {
        return markdownNumberParen;
    }
}

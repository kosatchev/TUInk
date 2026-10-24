package dev.tuink.render;

import dev.tuink.core.CommentSyntaxProfile;
import dev.tuink.editor.highlight.LexicalScanner;

/** Render-side lexical capability with TUInk's conservative fallback factories. */
public interface LineLexicalScanner extends LexicalScanner {
    static LineLexicalScanner generic() {
        return new GenericLineLexicalScanner(CommentSyntaxProfile.GENERIC);
    }

    static LineLexicalScanner markdownProse() {
        return new GenericLineLexicalScanner(CommentSyntaxProfile.MARKDOWN);
    }
}

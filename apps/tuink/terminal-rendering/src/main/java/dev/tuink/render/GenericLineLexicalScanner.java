package dev.tuink.render;

import dev.tuink.core.CommentSyntaxProfile;
import dev.tuink.editor.highlight.GenericLexicalScanner;
import dev.tuink.editor.highlight.LexicalConfig;
import java.util.Objects;

/** Adapts TUInk editing capabilities to the shared conservative lexical algorithm. */
final class GenericLineLexicalScanner implements LineLexicalScanner {
    private final GenericLexicalScanner delegate;

    GenericLineLexicalScanner(CommentSyntaxProfile profile) {
        Objects.requireNonNull(profile, "profile");
        delegate = new GenericLexicalScanner(new LexicalConfig(profile.hashLine(), profile.semicolonLine(),
            profile.slashLine(), profile.slashBlock(), profile.dashDashLine(), profile.htmlBlock(),
            profile.markdownNumberParen()));
    }

    @Override public Result scan(String line, int maxCodeUnits, State previous) {
        return delegate.scan(line, maxCodeUnits, previous);
    }
}

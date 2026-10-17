package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageProfilesTest {
    @Test
    void aliasesShareEditingAndCommentPolicies() {
        for (String extension : List.of("py", "pyi", "pyw", "pyx")) {
            assertEquals(LanguageProfile.PYTHON, LanguageProfiles.resolve(Path.of("module." + extension)));
            assertEquals(CommentSyntaxProfile.HASH,
                LanguageProfiles.resolveCommentSyntax(Path.of("module." + extension)));
        }
        for (String extension : List.of("md", "markdown", "mdown", "mkd")) {
            assertEquals(LanguageProfile.MARKDOWN, LanguageProfiles.resolve(Path.of("notes." + extension)));
            assertEquals(CommentSyntaxProfile.MARKDOWN,
                LanguageProfiles.resolveCommentSyntax(Path.of("notes." + extension)));
        }
        for (String extension : List.of("c", "h", "ino", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "groovy", "gradle")) {
            assertEquals(CommentSyntaxProfile.C_STYLE,
                LanguageProfiles.resolveCommentSyntax(Path.of("source." + extension)), extension);
        }
    }

    @Test
    void sharedIdentityRetainsConservativeSlashRulesAndSpecialNamePolicies() {
        for (String extension : List.of("js", "mjs", "cjs", "jsx", "ts", "tsx")) {
            assertTrue(LanguageProfiles.supportsSlashRegex(Path.of("file." + extension)), extension);
        }
        for (String extension : List.of("gradle", "groovy", "rb", "rake", "gemspec", "pl", "pm", "perl", "t")) {
            assertTrue(LanguageProfiles.hasAmbiguousSlashSyntax(Path.of("file." + extension)), extension);
            assertFalse(LanguageProfiles.supportsSlashRegex(Path.of("file." + extension)), extension);
        }
        for (String name : List.of("Dockerfile", "Makefile", ".editorconfig", ".gitignore", ".env.local")) {
            assertEquals(CommentSyntaxProfile.HASH, LanguageProfiles.resolveCommentSyntax(Path.of(name)), name);
        }
        assertEquals(LanguageProfile.GENERIC_CODE, LanguageProfiles.resolve(null));
        assertEquals(CommentSyntaxProfile.GENERIC, LanguageProfiles.resolveCommentSyntax(null));
    }
}

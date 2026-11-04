package dev.tuink.editor.language;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LanguageRegistryTest {
    @Test
    void canonicalIdentityIncludesAllEditingAndHighlightingAliases() {
        assertAliases(LanguageId.PYTHON, "py", "pyi", "pyw", "pyx");
        assertAliases(LanguageId.MARKDOWN, "md", "markdown", "mdown", "mkd", "mkdn");
        assertAliases(LanguageId.CPP, "cc", "cpp", "cxx", "hh", "hpp", "hxx");
        assertAliases(LanguageId.JAVASCRIPT, "js", "mjs", "cjs", "jsx");
        assertAliases(LanguageId.TYPESCRIPT, "ts", "tsx");
        assertAliases(LanguageId.GROOVY, "gradle", "groovy");
        assertAliases(LanguageId.PHP, "php", "phtml", "php5", "php7", "php8");
        assertAliases(LanguageId.RUBY, "rb", "rake", "gemspec");
        assertAliases(LanguageId.PERL, "pl", "pm", "perl", "t");
    }

    @Test
    void specialNamesAndUnknownNamesHaveStableIdentities() {
        assertEquals(LanguageId.DOCKERFILE, LanguageRegistry.forFileName("DOCKERFILE"));
        assertEquals(LanguageId.ENV, LanguageRegistry.forFileName(".ENV.local"));
        assertEquals(LanguageId.GIT_IGNORE, LanguageRegistry.forFileName(".gitignore"));
        assertEquals(LanguageId.EDITORCONFIG, LanguageRegistry.forFileName(".editorconfig"));
        assertEquals(LanguageId.MAKEFILE, LanguageRegistry.forFileName("Makefile"));
        assertEquals(LanguageId.YAML, LanguageRegistry.forFileName("docker-compose.yml"));
        assertEquals(LanguageId.GIT_COMMIT, LanguageRegistry.forFileName("git-commit-message"));
        for (String name : List.of("", " ", "new-document", "file.unknown", "file.")) {
            assertEquals(LanguageId.UNKNOWN, LanguageRegistry.forFileName(name), name);
        }
        assertEquals(LanguageId.UNKNOWN, LanguageRegistry.forFileName(null));
        assertEquals(LanguageId.UNKNOWN, LanguageRegistry.forExtension(null));
    }

    private static void assertAliases(LanguageId expected, String... aliases) {
        for (String alias : aliases) {
            assertEquals(expected, LanguageRegistry.forExtension(alias), alias);
            assertEquals(expected, LanguageRegistry.forFileName("example." + alias), alias);
            assertEquals(expected, LanguageRegistry.forFileName("EXAMPLE." + alias.toUpperCase(Locale.ROOT)), alias);
        }
    }
}

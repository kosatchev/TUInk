package dev.tuink.core;

import dev.tuink.editor.language.LanguageId;
import dev.tuink.editor.language.LanguageRegistry;

/** Editing policies for the shared, presentation-independent language identity. */
public final class LanguageProfiles {
    private LanguageProfiles() {}

    public static LanguageProfile resolve(String nameHint) {
        return switch (language(nameHint)) {
            case PYTHON -> LanguageProfile.PYTHON;
            case YAML -> LanguageProfile.YAML;
            case MARKDOWN -> LanguageProfile.MARKDOWN;
            case PLAIN_TEXT, CSV -> LanguageProfile.PLAIN_TEXT;
            case JSON, TOML, INI, CONFIG, PROPERTIES, XML -> LanguageProfile.STRUCTURED_TEXT;
            default -> LanguageProfile.GENERIC_CODE;
        };
    }

    public static CommentSyntaxProfile resolveCommentSyntax(String nameHint) {
        return switch (language(nameHint)) {
            case PYTHON, SHELL, RUBY, PERL, YAML, TOML, CONFIG, PROPERTIES,
                DOCKERFILE, ENV, GIT_IGNORE, MAKEFILE, EDITORCONFIG -> CommentSyntaxProfile.HASH;
            case INI -> CommentSyntaxProfile.SEMICOLON;
            case JAVA, C, CPP, JAVASCRIPT, TYPESCRIPT, GROOVY, GO, RUST, PHP,
                KOTLIN, CSHARP, SWIFT, SCALA, DART, PROTO, SCSS -> CommentSyntaxProfile.C_STYLE;
            case SQL -> CommentSyntaxProfile.SQL;
            case CSS -> CommentSyntaxProfile.SLASH_BLOCK;
            case HTML, XML -> CommentSyntaxProfile.HTML;
            case MARKDOWN -> CommentSyntaxProfile.MARKDOWN;
            case JSON, PLAIN_TEXT, CSV, DIFF -> CommentSyntaxProfile.NONE;
            default -> CommentSyntaxProfile.GENERIC;
        };
    }

    static boolean supportsSlashRegex(String nameHint) {
        LanguageId language = language(nameHint);
        return language == LanguageId.JAVASCRIPT || language == LanguageId.TYPESCRIPT;
    }

    static boolean hasAmbiguousSlashSyntax(String nameHint) {
        return switch (language(nameHint)) {
            case GROOVY, RUBY, PERL -> true;
            default -> false;
        };
    }

    static LanguageId language(String nameHint) {
        return LanguageRegistry.forFileName(nameHint);
    }
}

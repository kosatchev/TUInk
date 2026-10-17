package dev.tuink.core;

import dev.tuink.editor.language.LanguageId;
import dev.tuink.editor.language.LanguageRegistry;
import java.nio.file.Path;

/** Editing policies for the shared, presentation-independent language identity. */
public final class LanguageProfiles {
    private LanguageProfiles() {}

    public static LanguageProfile resolve(Path path) {
        return switch (language(path)) {
            case PYTHON -> LanguageProfile.PYTHON;
            case YAML -> LanguageProfile.YAML;
            case MARKDOWN -> LanguageProfile.MARKDOWN;
            case PLAIN_TEXT, CSV -> LanguageProfile.PLAIN_TEXT;
            case JSON, TOML, INI, CONFIG, PROPERTIES, XML -> LanguageProfile.STRUCTURED_TEXT;
            default -> LanguageProfile.GENERIC_CODE;
        };
    }

    public static CommentSyntaxProfile resolveCommentSyntax(Path path) {
        return switch (language(path)) {
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

    static boolean supportsSlashRegex(Path path) {
        LanguageId language = language(path);
        return language == LanguageId.JAVASCRIPT || language == LanguageId.TYPESCRIPT;
    }

    static boolean hasAmbiguousSlashSyntax(Path path) {
        return switch (language(path)) {
            case GROOVY, RUBY, PERL -> true;
            default -> false;
        };
    }

    static LanguageId language(Path path) {
        return LanguageRegistry.forFileName(path == null || path.getFileName() == null
            ? null : path.getFileName().toString());
    }
}

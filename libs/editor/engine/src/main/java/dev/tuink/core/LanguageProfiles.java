package dev.tuink.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves language profiles from file names/extensions.
 */
public final class LanguageProfiles {
    private static final Set<String> PYTHON_EXTENSIONS = Set.of("py", "pyi", "pyw", "pyx");
    private static final Set<String> YAML_EXTENSIONS = Set.of("yaml", "yml");
    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown", "mdown", "mkd");
    private static final Set<String> STRUCTURED_TEXT_EXTENSIONS = Set.of(
        "json",
        "toml",
        "ini",
        "cfg",
        "conf",
        "properties",
        "xml",
        "yaml",
        "yml"
    );
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
        "txt",
        "log",
        "csv"
    );
    private static final Set<String> HASH_COMMENT_EXTENSIONS = Set.of(
        "py", "pyi", "pyw", "pyx",
        "sh", "bash", "zsh",
        "rb", "rake", "gemspec",
        "pl", "pm", "perl", "t",
        "yaml", "yml", "toml", "cfg", "conf", "properties"
    );
    private static final Set<String> C_STYLE_COMMENT_EXTENSIONS = Set.of(
        "java",
        "c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "ino",
        "js", "mjs", "cjs", "jsx", "ts", "tsx",
        "go", "rs",
        "php", "phtml", "php5", "php7", "php8",
        "kt", "kts", "cs", "swift", "scala", "dart", "proto", "gradle",
        "scss"
    );
    private static final Set<String> HASH_COMMENT_FILE_NAMES = Set.of(
        "dockerfile", ".gitignore", ".editorconfig", "makefile"
    );

    private LanguageProfiles() {
    }

    public static LanguageProfile resolve(Path path) {
        String ext = extensionOf(path);
        if (ext == null) {
            return LanguageProfile.GENERIC_CODE;
        }
        if (PYTHON_EXTENSIONS.contains(ext)) {
            return LanguageProfile.PYTHON;
        }
        if (YAML_EXTENSIONS.contains(ext)) {
            return LanguageProfile.YAML;
        }
        if (MARKDOWN_EXTENSIONS.contains(ext)) {
            return LanguageProfile.MARKDOWN;
        }
        if (PLAIN_TEXT_EXTENSIONS.contains(ext)) {
            return LanguageProfile.PLAIN_TEXT;
        }
        if (STRUCTURED_TEXT_EXTENSIONS.contains(ext)) {
            return LanguageProfile.STRUCTURED_TEXT;
        }
        return LanguageProfile.GENERIC_CODE;
    }

    public static CommentSyntaxProfile resolveCommentSyntax(Path path) {
        String name = fileNameOf(path);
        if (name == null) {
            return CommentSyntaxProfile.GENERIC;
        }
        String ext = extensionOf(path);
        if (HASH_COMMENT_FILE_NAMES.contains(name) || name.startsWith(".env")) {
            return CommentSyntaxProfile.HASH;
        }
        if (ext != null && HASH_COMMENT_EXTENSIONS.contains(ext)) {
            return CommentSyntaxProfile.HASH;
        }
        if ("ini".equals(ext)) {
            return CommentSyntaxProfile.SEMICOLON;
        }
        if (ext != null && C_STYLE_COMMENT_EXTENSIONS.contains(ext)) {
            return CommentSyntaxProfile.C_STYLE;
        }
        if ("sql".equals(ext)) {
            return CommentSyntaxProfile.SQL;
        }
        if ("css".equals(ext)) {
            return CommentSyntaxProfile.SLASH_BLOCK;
        }
        if ("html".equals(ext) || "htm".equals(ext) || "xml".equals(ext)) {
            return CommentSyntaxProfile.HTML;
        }
        if (ext != null && MARKDOWN_EXTENSIONS.contains(ext)) {
            return CommentSyntaxProfile.MARKDOWN;
        }
        if (ext != null && ("json".equals(ext) || PLAIN_TEXT_EXTENSIONS.contains(ext)
            || "diff".equals(ext) || "patch".equals(ext))) {
            return CommentSyntaxProfile.NONE;
        }
        return CommentSyntaxProfile.GENERIC;
    }

    private static String extensionOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1);
    }

    private static String fileNameOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }
}

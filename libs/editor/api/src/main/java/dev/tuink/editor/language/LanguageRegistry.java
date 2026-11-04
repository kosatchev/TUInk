package dev.tuink.editor.language;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Canonical filename and extension aliases shared by editing and syntax consumers. */
public final class LanguageRegistry {
    private static final Map<String, LanguageId> EXTENSIONS = extensions();
    private static final Map<String, LanguageId> FILE_NAMES = Map.of(
        "dockerfile", LanguageId.DOCKERFILE,
        "docker-compose.yml", LanguageId.YAML,
        "docker-compose.yaml", LanguageId.YAML,
        ".gitignore", LanguageId.GIT_IGNORE,
        "makefile", LanguageId.MAKEFILE,
        ".editorconfig", LanguageId.EDITORCONFIG
    );

    private LanguageRegistry() {}

    public static LanguageId forFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return LanguageId.UNKNOWN;
        String name = fileName.toLowerCase(Locale.ROOT);
        LanguageId named = FILE_NAMES.get(name);
        if (named != null) return named;
        if (name.startsWith(".env")) return LanguageId.ENV;
        if (name.startsWith("git-commit")) return LanguageId.GIT_COMMIT;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? LanguageId.UNKNOWN : forExtension(name.substring(dot + 1));
    }

    public static LanguageId forExtension(String extension) {
        return extension == null ? LanguageId.UNKNOWN
            : EXTENSIONS.getOrDefault(extension.toLowerCase(Locale.ROOT), LanguageId.UNKNOWN);
    }

    private static Map<String, LanguageId> extensions() {
        Map<String, LanguageId> result = new HashMap<>();
        register(result, LanguageId.PLAIN_TEXT, "txt", "log");
        register(result, LanguageId.CSV, "csv");
        register(result, LanguageId.JAVA, "java");
        register(result, LanguageId.C, "c", "h", "ino");
        register(result, LanguageId.CPP, "cc", "cpp", "cxx", "hh", "hpp", "hxx");
        register(result, LanguageId.PYTHON, "py", "pyi", "pyw", "pyx");
        register(result, LanguageId.MARKDOWN, "md", "markdown", "mdown", "mkd", "mkdn");
        register(result, LanguageId.JAVASCRIPT, "js", "mjs", "cjs", "jsx");
        register(result, LanguageId.TYPESCRIPT, "ts", "tsx");
        register(result, LanguageId.GROOVY, "gradle", "groovy");
        register(result, LanguageId.SHELL, "sh", "bash", "zsh");
        register(result, LanguageId.SQL, "sql");
        register(result, LanguageId.GO, "go");
        register(result, LanguageId.RUST, "rs");
        register(result, LanguageId.PHP, "php", "phtml", "php5", "php7", "php8");
        register(result, LanguageId.RUBY, "rb", "rake", "gemspec");
        register(result, LanguageId.PERL, "pl", "pm", "perl", "t");
        register(result, LanguageId.KOTLIN, "kt", "kts");
        register(result, LanguageId.PROTO, "proto");
        register(result, LanguageId.CSHARP, "cs");
        register(result, LanguageId.SWIFT, "swift");
        register(result, LanguageId.SCALA, "scala");
        register(result, LanguageId.DART, "dart");
        register(result, LanguageId.HTML, "html", "htm");
        register(result, LanguageId.XML, "xml");
        register(result, LanguageId.CSS, "css");
        register(result, LanguageId.SCSS, "scss");
        register(result, LanguageId.DIFF, "diff", "patch");
        register(result, LanguageId.JSON, "json");
        register(result, LanguageId.YAML, "yaml", "yml");
        register(result, LanguageId.TOML, "toml");
        register(result, LanguageId.INI, "ini");
        register(result, LanguageId.CONFIG, "cfg", "conf");
        register(result, LanguageId.PROPERTIES, "properties");
        return Map.copyOf(result);
    }

    private static void register(Map<String, LanguageId> result, LanguageId language, String... extensions) {
        for (String extension : extensions) {
            if (result.putIfAbsent(extension, language) != null) {
                throw new IllegalStateException("Duplicate language extension: " + extension);
            }
        }
    }
}

package dev.tuink.core;

import java.nio.file.Path;
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

    private static String extensionOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1);
    }
}

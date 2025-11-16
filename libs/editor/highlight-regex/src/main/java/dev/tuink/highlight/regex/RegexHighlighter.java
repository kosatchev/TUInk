package dev.tuink.highlight.regex;

import dev.tuink.render.Color;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line highlighter using layered regex rules.
 */
public class RegexHighlighter implements LineHighlighter {
    private static final Style KEYWORD_STYLE = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE_STYLE = new Style(Color.BRIGHT_MAGENTA, Color.DEFAULT, false, false);
    private static final Style NUMBER_STYLE = new Style(Color.BRIGHT_CYAN, Color.DEFAULT, false, false);
    private static final Style STRING_STYLE = new Style(Color.BRIGHT_GREEN, Color.DEFAULT, false, false);
    private static final Style COMMENT_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style DECORATOR_STYLE = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, false, false);
    private static final Style MARKUP_STYLE = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false);

    private static final Set<String> JAVA_EXT = Set.of("java");
    private static final Set<String> C_EXT = Set.of("c", "h");
    private static final Set<String> PY_EXT = Set.of("py");
    private static final Set<String> MD_EXT = Set.of("md", "markdown");
    private static final Set<String> JS_EXT = Set.of("js", "mjs", "cjs", "jsx");
    private static final Set<String> TS_EXT = Set.of("ts", "tsx");
    private static final Set<String> CONFIG_EXT = Set.of(
        "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "xml"
    );
    private static final Set<String> CONFIG_FILE_NAMES = Set.of(
        "dockerfile", ".gitignore"
    );

    private final List<RegexRule> rules;

    public RegexHighlighter(List<RegexRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RegexHighlighter withDefaults() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(public|private|class|interface|enum|function|def|if|else|for|while|return|import|from|const|let|var|new)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forFileName(String fileName) {
        String name = normalizeName(fileName);
        if (name != null) {
            if (CONFIG_FILE_NAMES.contains(name)) {
                return forConfigFileName(name);
            }
            if (name.startsWith(".env")) {
                return forEnv();
            }
        }

        String ext = extensionOf(fileName);
        if (ext == null) {
            return withDefaults();
        }
        if (JAVA_EXT.contains(ext)) {
            return forJava();
        }
        if (C_EXT.contains(ext)) {
            return forC();
        }
        if (PY_EXT.contains(ext)) {
            return forPython();
        }
        if (MD_EXT.contains(ext)) {
            return forMarkdown();
        }
        if (JS_EXT.contains(ext)) {
            return forJavaScript();
        }
        if (TS_EXT.contains(ext)) {
            return forTypeScript();
        }
        if (CONFIG_EXT.contains(ext)) {
            return forConfig(ext);
        }
        return withDefaults();
    }

    public static RegexHighlighter forConfigFileName(String fileName) {
        String name = normalizeName(fileName);
        if (name == null) {
            return withDefaults();
        }
        return switch (name) {
            case "dockerfile" -> forDockerfile();
            case ".gitignore" -> forGitIgnore();
            default -> withDefaults();
        };
    }

    public static RegexHighlighter forJava() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(package|import|public|private|protected|class|interface|enum|extends|implements|static|final|abstract|new|if|else|for|while|do|switch|case|default|try|catch|finally|throw|throws|return|break|continue|this|super|var|void|true|false|null)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b(byte|short|int|long|float|double|boolean|char|String)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forC() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(include|define|ifdef|ifndef|endif|if|else|for|while|do|switch|case|default|return|break|continue|typedef|struct|enum|union|static|const|extern|sizeof|void|signed|unsigned)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b(char|short|int|long|float|double|_Bool|size_t)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:[uUlLfF]+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("#\\s*[A-Za-z_][A-Za-z0-9_]*.*$"), DECORATOR_STYLE)
        ));
    }

    public static RegexHighlighter forPython() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(def|class|import|from|as|if|elif|else|for|while|try|except|finally|with|return|yield|break|continue|pass|lambda|and|or|not|in|is|True|False|None|async|await|match|case)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("@[A-Za-z_][A-Za-z0-9_\\.]*"), DECORATOR_STYLE)
        ));
    }

    public static RegexHighlighter forMarkdown() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s{0,3}#{1,6}\\s+.*$"), MARKUP_STYLE),
            new RegexRule(Pattern.compile("^\\s{0,3}(\\*|-|\\+)\\s+.*$"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("^\\s{0,3}\\d+\\.\\s+.*$"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("`[^`]+`"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\*\\*[^*]+\\*\\*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\[[^\\]]+\\]\\([^\\)]+\\)"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s{0,3}>\\s+.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forJavaScript() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(function|class|extends|constructor|import|export|default|from|const|let|var|if|else|for|while|do|switch|case|return|break|continue|try|catch|finally|throw|new|this|super|typeof|instanceof|in|of|await|async|null|true|false|undefined)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("`([^`\\\\]|\\\\.)*`"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forTypeScript() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(function|class|interface|type|enum|namespace|implements|extends|import|export|default|from|const|let|var|if|else|for|while|do|switch|case|return|break|continue|try|catch|finally|throw|new|this|super|typeof|instanceof|in|of|await|async|null|true|false|undefined|as|readonly|public|private|protected)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b(string|number|boolean|any|unknown|never|void)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("`([^`\\\\]|\\\\.)*`"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forConfig(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase();
        return switch (ext) {
            case "json" -> forJson();
            case "yaml", "yml" -> forYaml();
            case "toml" -> forToml();
            case "ini", "cfg", "conf", "properties" -> forIniLike();
            case "xml" -> forXml();
            default -> withDefaults();
        };
    }

    public static RegexHighlighter forJson() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\"\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false|null)\\b"), TYPE_STYLE)
        ));
    }

    public static RegexHighlighter forYaml() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*[A-Za-z0-9_.-]+\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false|null|yes|no|on|off)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forToml() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s*[A-Za-z0-9_.-]+\\s*="), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forIniLike() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s*[A-Za-z0-9_.-]+\\s*[=:]"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false|yes|no|on|off)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("[#;].*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forXml() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("</?[A-Za-z_][A-Za-z0-9_.:-]*"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\s+[A-Za-z_][A-Za-z0-9_.:-]*\\s*="), TYPE_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("<!--.*-->"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forDockerfile() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("^\\s*(FROM|RUN|CMD|LABEL|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forEnv() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*(export\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*="), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forGitIgnore() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*!.*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s*#.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("([*?\\[\\]])"), TYPE_STYLE)
        ));
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(idx + 1).toLowerCase();
    }

    private static String normalizeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        return fileName.toLowerCase();
    }

    @Override
    public List<StyleSpan> highlight(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }

        Style[] styleAt = new Style[line.length()];
        for (RegexRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(line);
            while (matcher.find()) {
                int start = Math.max(0, matcher.start());
                int end = Math.min(line.length(), matcher.end());
                for (int i = start; i < end; i++) {
                    styleAt[i] = rule.style();
                }
            }
        }

        List<StyleSpan> spans = new ArrayList<>();
        int start = -1;
        Style current = null;
        for (int i = 0; i < styleAt.length; i++) {
            Style style = styleAt[i];
            if (!Objects.equals(current, style)) {
                if (current != null) {
                    spans.add(new StyleSpan(start, i, current));
                }
                current = style;
                start = i;
            }
        }

        if (current != null) {
            spans.add(new StyleSpan(start, styleAt.length, current));
        }

        return spans;
    }
}

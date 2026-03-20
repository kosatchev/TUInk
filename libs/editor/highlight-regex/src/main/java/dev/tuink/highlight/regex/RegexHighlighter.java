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
    private static final Set<String> C_EXT = Set.of("c", "h", "ino");
    private static final Set<String> PY_EXT = Set.of("py");
    private static final Set<String> MD_EXT = Set.of("md", "markdown");
    private static final Set<String> JS_EXT = Set.of("js", "mjs", "cjs", "jsx");
    private static final Set<String> TS_EXT = Set.of("ts", "tsx");
    private static final Set<String> GRADLE_EXT = Set.of("gradle");
    private static final Set<String> SHELL_EXT = Set.of("sh", "bash", "zsh");
    private static final Set<String> SQL_EXT = Set.of("sql");
    private static final Set<String> GO_EXT = Set.of("go");
    private static final Set<String> RUST_EXT = Set.of("rs");
    private static final Set<String> PHP_EXT = Set.of("php", "phtml", "php5", "php7", "php8");
    private static final Set<String> RUBY_EXT = Set.of("rb", "rake", "gemspec");
    private static final Set<String> PERL_EXT = Set.of("pl", "pm", "perl", "t");
    private static final Set<String> KOTLIN_EXT = Set.of("kt", "kts");
    private static final Set<String> PROTO_EXT = Set.of("proto");
    private static final Set<String> HTML_EXT = Set.of("html", "htm");
    private static final Set<String> CSS_EXT = Set.of("css", "scss");
    private static final Set<String> DIFF_EXT = Set.of("diff", "patch");
    private static final Set<String> CSV_EXT = Set.of("csv");
    private static final Set<String> CONFIG_EXT = Set.of(
        "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "xml"
    );
    private static final Set<String> CONFIG_FILE_NAMES = Set.of(
        "dockerfile", ".gitignore", "makefile", ".editorconfig", "docker-compose.yml", "docker-compose.yaml"
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

    public static LineHighlighter forFileName(String fileName) {
        String name = normalizeName(fileName);
        if (name != null) {
            if (CONFIG_FILE_NAMES.contains(name)) {
                return forConfigFileName(name);
            }
            if (name.startsWith("git-commit")) {
                return forGitCommit();
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
            return new MarkdownFenceHighlighter();
        }
        if (JS_EXT.contains(ext)) {
            return forJavaScript();
        }
        if (TS_EXT.contains(ext)) {
            return forTypeScript();
        }
        if (GRADLE_EXT.contains(ext)) {
            return forGradle();
        }
        if (SHELL_EXT.contains(ext)) {
            return forShell();
        }
        if (SQL_EXT.contains(ext)) {
            return forSql();
        }
        if (GO_EXT.contains(ext)) {
            return forGo();
        }
        if (RUST_EXT.contains(ext)) {
            return forRust();
        }
        if (PHP_EXT.contains(ext)) {
            return forPhp();
        }
        if (RUBY_EXT.contains(ext)) {
            return forRuby();
        }
        if (PERL_EXT.contains(ext)) {
            return forPerl();
        }
        if (KOTLIN_EXT.contains(ext)) {
            return forKotlin();
        }
        if (PROTO_EXT.contains(ext)) {
            return forProto();
        }
        if (HTML_EXT.contains(ext)) {
            return forHtml();
        }
        if (CSS_EXT.contains(ext)) {
            return forCssLike();
        }
        if (DIFF_EXT.contains(ext)) {
            return forDiff();
        }
        if (CSV_EXT.contains(ext)) {
            return forCsv();
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
            case "makefile" -> forMakefile();
            case ".editorconfig" -> forIniLike();
            case "docker-compose.yml", "docker-compose.yaml" -> forYaml();
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

    public static RegexHighlighter forGradle() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(plugins|pluginManagement|dependencyResolutionManagement|repositories|dependencies|buildscript|allprojects|subprojects|tasks|task|apply|from|id|version|group|name|implementation|api|compileOnly|runtimeOnly|testImplementation|classpath|ext|sourceSets|publishing|mavenCentral|google)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(def|if|else|for|while|return|new|true|false|null)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forShell() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(if|then|else|elif|fi|for|while|do|done|case|esac|function|in|select|until|return|break|continue|local|export|readonly)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forSql() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(SELECT|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|FULL|ON|GROUP|BY|ORDER|HAVING|LIMIT|OFFSET|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|ALTER|DROP|TABLE|VIEW|INDEX|DISTINCT|UNION|ALL|AS|AND|OR|NOT|NULL|IS|IN|EXISTS|CASE|WHEN|THEN|ELSE|END)\\b", Pattern.CASE_INSENSITIVE),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("--.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forGo() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(package|import|func|type|struct|interface|map|chan|go|defer|select|case|default|if|else|for|range|switch|return|break|continue|fallthrough|var|const)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(bool|byte|rune|string|int|int8|int16|int32|int64|uint|uint8|uint16|uint32|uint64|float32|float64|complex64|complex128|error)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("`[^`]*`"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forRust() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(fn|let|mut|const|static|struct|enum|impl|trait|mod|pub|use|crate|super|self|match|if|else|for|while|loop|in|where|return|break|continue|async|await|move)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|bool|char|str|String|Option|Result)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forPhp() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(namespace|use|class|interface|trait|extends|implements|public|private|protected|static|final|abstract|function|fn|if|elseif|else|for|foreach|while|do|switch|case|default|try|catch|finally|throw|return|break|continue|new|clone|instanceof|echo|include|include_once|require|require_once|match|null|true|false)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(int|float|string|bool|array|object|mixed|void|iterable|callable|self|parent|static)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("<\\?(?:php|=)?|\\?>"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forRuby() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(alias|and|BEGIN|begin|break|case|class|def|defined\\?|do|else|elsif|END|end|ensure|false|for|if|in|module|next|nil|not|or|redo|rescue|retry|return|self|super|then|true|undef|unless|until|when|while|yield)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile(":[A-Za-z_][A-Za-z0-9_]*[!?=]?"), TYPE_STYLE),
            new RegexRule(Pattern.compile("@@?[A-Za-z_][A-Za-z0-9_]*|\\$[A-Za-z_][A-Za-z0-9_]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("#\\{[^}]+\\}"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forPerl() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(use|my|our|state|local|sub|package|if|elsif|else|unless|while|until|for|foreach|given|when|continue|last|next|redo|return|do|eval|require|bless|undef)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("[\\$@%][A-Za-z_][A-Za-z0-9_]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b(qw|qq|qx|qr|m|s|tr|y)\\b"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forKotlin() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(package|import|class|interface|object|fun|val|var|typealias|if|else|when|for|while|do|return|break|continue|is|in|as|try|catch|finally|throw|this|super|companion)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(Int|Long|Short|Byte|Float|Double|Boolean|Char|String|Unit|Any|Nothing)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("@[A-Za-z_][A-Za-z0-9_\\.]*"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forProto() {
        return new RegexHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(syntax|import|package|option|message|enum|service|rpc|returns|oneof|repeated|map|reserved|to)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(string|bytes|bool|int32|int64|uint32|uint64|sint32|sint64|fixed32|fixed64|sfixed32|sfixed64|float|double)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE)
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

    public static RegexHighlighter forMakefile() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\$\\(([^\\)]+)\\)|\\$\\{([^\\}]+)\\}"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b(ifeq|ifneq|ifdef|ifndef|else|endif|include|override|export|unexport|define|endef)\\b"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forHtml() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("</?[A-Za-z][A-Za-z0-9:-]*"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\s+[A-Za-z_:][A-Za-z0-9:._-]*\\s*="), TYPE_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("<!--.*-->"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forCssLike() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("(^|\\{|;)\\s*([A-Za-z_-][A-Za-z0-9_-]*)\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\.[A-Za-z_-][A-Za-z0-9_-]*|#[A-Fa-f0-9]{3,8}|#[A-Za-z_-][A-Za-z0-9_-]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?(px|em|rem|%|vh|vw|s|ms)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), STRING_STYLE),
            new RegexRule(Pattern.compile("/\\*.*\\*/"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forDiff() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\+\\+\\+.*$|^---.*$|^diff\\s+.*$|^index\\s+.*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^@@.*@@.*$"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("^\\+.*$"), STRING_STYLE),
            new RegexRule(Pattern.compile("^-.*$"), TYPE_STYLE)
        ));
    }

    public static RegexHighlighter forGitCommit() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^#.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("^(feat|fix|docs|style|refactor|test|chore)(\\([^\\)]+\\))?:\\s+.*$"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b#[0-9]+\\b"), DECORATOR_STYLE)
        ));
    }

    public static RegexHighlighter forCsv() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("\"([^\"]|\"\")*\""), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
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
            try {
                Matcher matcher = rule.pattern().matcher(line);
                while (matcher.find()) {
                    int start = Math.max(0, matcher.start());
                    int end = Math.min(line.length(), matcher.end());
                    for (int i = start; i < end; i++) {
                        styleAt[i] = rule.style();
                    }
                }
            } catch (StackOverflowError ignored) {
                // Java regex can overflow stack on pathological long lines/patterns.
                // Ignore this rule for the current line to keep the editor responsive.
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

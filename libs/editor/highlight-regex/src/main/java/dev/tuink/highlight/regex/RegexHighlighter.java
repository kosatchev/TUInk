package dev.tuink.highlight.regex;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.render.Color;
import dev.tuink.render.HighlightRequest;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line highlighter using layered regex rules.
 */
public class RegexHighlighter implements LineHighlighter {
    private static final int LEGACY_MAX_SCAN = 1_048_576;
    private static final Style KEYWORD_STYLE = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE_STYLE = new Style(Color.BRIGHT_MAGENTA, Color.DEFAULT, false, false);
    private static final Style NUMBER_STYLE = new Style(Color.BRIGHT_CYAN, Color.DEFAULT, false, false);
    private static final Style STRING_STYLE = new Style(Color.BRIGHT_GREEN, Color.DEFAULT, false, false);
    private static final Style COMMENT_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style DECORATOR_STYLE = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, false, false);
    private static final Style MARKUP_STYLE = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false);

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
            new RegexRule(Pattern.compile("\"(?:[^\"\\\\]++|\\\\.)*+\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'(?:[^'\\\\]++|\\\\.)*+'"), STRING_STYLE),
            new RegexRule(Pattern.compile("//.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static LineHighlighter forFileName(String fileName) {
        return switch (LanguageRegistry.forFileName(fileName)) {
            case JAVA -> forJava();
            case C -> forC();
            case CPP -> forCpp();
            case PYTHON -> forPython();
            case MARKDOWN -> new MarkdownFenceHighlighter();
            case JAVASCRIPT -> forJavaScript();
            case TYPESCRIPT -> forTypeScript();
            case GROOVY -> forGradle();
            case SHELL -> forShell();
            case SQL -> forSql();
            case GO -> forGo();
            case RUST -> forRust();
            case PHP -> forPhp();
            case RUBY -> forRuby();
            case PERL -> forPerl();
            case KOTLIN -> forKotlin();
            case PROTO -> forProto();
            case HTML -> forHtml();
            case CSS, SCSS -> forCssLike();
            case DIFF -> forDiff();
            case CSV -> forCsv();
            case JSON -> forJson();
            case YAML -> forYaml();
            case TOML -> forToml();
            case INI, CONFIG -> forIniLike();
            case PROPERTIES -> forProperties();
            case XML -> forXml();
            case DOCKERFILE -> forDockerfile();
            case ENV -> forEnv();
            case GIT_IGNORE -> forGitIgnore();
            case MAKEFILE -> forMakefile();
            case EDITORCONFIG -> forEditorConfig();
            case GIT_COMMIT -> forGitCommit();
            default -> withDefaults();
        };
    }

    public static RegexHighlighter forConfigFileName(String fileName) {
        return switch (LanguageRegistry.forFileName(fileName)) {
            case DOCKERFILE -> forDockerfile();
            case GIT_IGNORE -> forGitIgnore();
            case MAKEFILE -> forMakefile();
            case EDITORCONFIG -> forEditorConfig();
            case YAML -> forYaml();
            default -> withDefaults();
        };
    }

    public static RegexHighlighter forJava() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(package|import|public|private|protected|class|interface|enum|extends|implements|static|final|abstract|new|if|else|for|while|do|switch|case|default|try|catch|finally|throw|throws|return|break|continue|this|super|var|void|true|false|null)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b(byte|short|int|long|float|double|boolean|char|String)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.JAVA);
    }

    public static RegexHighlighter forC() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(include|define|ifdef|ifndef|endif|if|else|for|while|do|switch|case|default|return|break|continue|typedef|struct|enum|union|static|const|extern|sizeof|void|signed|unsigned)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b(char|short|int|long|float|double|_Bool|size_t)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:[uUlLfF]+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("#\\s*[A-Za-z_][A-Za-z0-9_]*.*$"), DECORATOR_STYLE)
        ));
    }

    public static RegexHighlighter forCpp() {
        List<RegexRule> cppRules = new ArrayList<>(forC().rules);
        cppRules.add(new RegexRule(Pattern.compile(
            "\\b(namespace|using|class|public|protected|private|template|typename|virtual|override|final|"
                + "constexpr|consteval|constinit|decltype|auto|new|delete|try|catch|throw|noexcept|"
                + "static_cast|dynamic_cast|reinterpret_cast|const_cast|nullptr|true|false)\\b"), KEYWORD_STYLE));
        cppRules.add(new RegexRule(Pattern.compile("\\b(bool|wchar_t|char8_t|char16_t|char32_t)\\b"), TYPE_STYLE));
        return new CStyleHighlighter(cppRules);
    }

    public static RegexHighlighter forPython() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(def|class|import|from|as|if|elif|else|for|while|try|except|finally|with|return|yield|break|continue|pass|lambda|and|or|not|in|is|True|False|None|async|await|match|case)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("@[A-Za-z_][A-Za-z0-9_\\.]*"), DECORATOR_STYLE)
        ), CStyleHighlighter.Syntax.PYTHON);
    }

    public static RegexHighlighter forMarkdown() {
        return new MarkdownInlineHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s{0,3}#{1,6}\\s+.*$"), MARKUP_STYLE),
            new RegexRule(Pattern.compile("^\\s{0,3}(\\*|-|\\+)\\s+.*$"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("^\\s{0,3}\\d+\\.\\s+.*$"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("`[^`]+`"), STRING_STYLE),
            new RegexRule(Pattern.compile("\\*\\*[^*]+\\*\\*"), TYPE_STYLE)
        ));
    }

    public static RegexHighlighter forJavaScript() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(function|class|extends|constructor|import|export|default|from|const|let|var|if|else|for|while|do|switch|case|return|break|continue|try|catch|finally|throw|new|this|super|typeof|instanceof|in|of|await|async|null|true|false|undefined)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.BACKTICK);
    }

    public static RegexHighlighter forTypeScript() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(function|class|interface|type|enum|namespace|implements|extends|import|export|default|from|const|let|var|if|else|for|while|do|switch|case|return|break|continue|try|catch|finally|throw|new|this|super|typeof|instanceof|in|of|await|async|null|true|false|undefined|as|readonly|public|private|protected)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b(string|number|boolean|any|unknown|never|void)\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.BACKTICK);
    }

    public static RegexHighlighter forGradle() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(plugins|pluginManagement|dependencyResolutionManagement|repositories|dependencies|buildscript|allprojects|subprojects|tasks|task|apply|from|id|version|group|name|implementation|api|compileOnly|runtimeOnly|testImplementation|classpath|ext|sourceSets|publishing|mavenCentral|google)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(def|if|else|for|while|return|new|true|false|null)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.GROOVY);
    }

    public static RegexHighlighter forShell() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(if|then|else|elif|fi|for|while|do|done|case|esac|function|in|select|until|return|break|continue|local|export|readonly)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.SHELL);
    }

    public static RegexHighlighter forSql() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(SELECT|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|FULL|ON|GROUP|BY|ORDER|HAVING|LIMIT|OFFSET|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|ALTER|DROP|TABLE|VIEW|INDEX|DISTINCT|UNION|ALL|AS|AND|OR|NOT|NULL|IS|IN|EXISTS|CASE|WHEN|THEN|ELSE|END)\\b", Pattern.CASE_INSENSITIVE),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.SQL);
    }

    public static RegexHighlighter forGo() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(package|import|func|type|struct|interface|map|chan|go|defer|select|case|default|if|else|for|range|switch|return|break|continue|fallthrough|var|const)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(bool|byte|rune|string|int|int8|int16|int32|int64|uint|uint8|uint16|uint32|uint64|float32|float64|complex64|complex128|error)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.GO);
    }

    public static RegexHighlighter forRust() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(fn|let|mut|const|static|struct|enum|impl|trait|mod|pub|use|crate|super|self|match|if|else|for|while|loop|in|where|return|break|continue|async|await|move)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|bool|char|str|String|Option|Result)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.RUST);
    }

    public static RegexHighlighter forPhp() {
        return new CStyleHighlighter(List.of(
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
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.PHP);
    }

    public static RegexHighlighter forRuby() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(alias|and|BEGIN|begin|break|case|class|def|defined\\?|do|else|elsif|END|end|ensure|false|for|if|in|module|next|nil|not|or|redo|rescue|retry|return|self|super|then|true|undef|unless|until|when|while|yield)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"), TYPE_STYLE),
            new RegexRule(Pattern.compile(":[A-Za-z_][A-Za-z0-9_]*[!?=]?"), TYPE_STYLE),
            new RegexRule(Pattern.compile("@@?[A-Za-z_][A-Za-z0-9_]*|\\$[A-Za-z_][A-Za-z0-9_]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.RUBY);
    }

    public static RegexHighlighter forPerl() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(use|my|our|state|local|sub|package|if|elsif|else|unless|while|until|for|foreach|given|when|continue|last|next|redo|return|do|eval|require|bless|undef)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("[\\$@%][A-Za-z_][A-Za-z0-9_]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\\b(qw|qq|qx|qr|m|s|tr|y)\\b"), DECORATOR_STYLE)
        ), CStyleHighlighter.Syntax.PERL);
    }

    public static RegexHighlighter forKotlin() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(package|import|class|interface|object|fun|val|var|typealias|if|else|when|for|while|do|return|break|continue|is|in|as|try|catch|finally|throw|this|super|companion)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(Int|Long|Short|Byte|Float|Double|Boolean|Char|String|Unit|Any|Nothing)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("@[A-Za-z_][A-Za-z0-9_\\.]*"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.KOTLIN);
    }

    public static RegexHighlighter forProto() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("\\b(syntax|import|package|option|message|enum|service|rpc|returns|oneof|repeated|map|reserved|to)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(
                Pattern.compile("\\b(string|bytes|bool|int32|int64|uint32|uint64|sint32|sint64|fixed32|fixed64|sfixed32|sfixed64|float|double)\\b"),
                TYPE_STYLE
            ),
            new RegexRule(Pattern.compile("\\b\\d+\\b"), NUMBER_STYLE)
        ));
    }

    public static RegexHighlighter forConfig(String extension) {
        return switch (LanguageRegistry.forExtension(extension)) {
            case JSON -> forJson();
            case YAML -> forYaml();
            case TOML -> forToml();
            case INI, CONFIG -> forIniLike();
            case PROPERTIES -> forProperties();
            case XML -> forXml();
            default -> withDefaults();
        };
    }

    public static RegexHighlighter forJson() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("\"(?:[^\"\\\\]++|\\\\.)*+\"\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\"(?:[^\"\\\\]++|\\\\.)*+\""), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false|null)\\b"), TYPE_STYLE)
        ));
    }

    public static RegexHighlighter forYaml() {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*[A-Za-z0-9_.-]+\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false|null|yes|no|on|off)\\b"), TYPE_STYLE)
        ), CStyleHighlighter.Syntax.YAML);
    }

    public static RegexHighlighter forToml() {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s*[A-Za-z0-9_.-]+\\s*="), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false)\\b"), TYPE_STYLE)
        ), CStyleHighlighter.Syntax.TOML);
    }

    public static RegexHighlighter forIniLike() {
        return forIniLike(CStyleHighlighter.Syntax.INI);
    }

    public static RegexHighlighter forProperties() {
        return forIniLike(CStyleHighlighter.Syntax.PROPERTIES);
    }

    public static RegexHighlighter forEditorConfig() {
        return forIniLike(CStyleHighlighter.Syntax.EDITORCONFIG);
    }

    private static RegexHighlighter forIniLike(CStyleHighlighter.Syntax syntax) {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s*[A-Za-z0-9_.-]+\\s*[=:]"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("\\b(true|false|yes|no|on|off)\\b"), TYPE_STYLE)
        ), syntax);
    }

    public static RegexHighlighter forXml() {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("</?[A-Za-z_][A-Za-z0-9_.:-]*"), KEYWORD_STYLE),
            // Match only at the start of a whitespace run: find() must not retry every suffix.
            new RegexRule(Pattern.compile("(?<!\\s)\\s++[A-Za-z_][A-Za-z0-9_.:-]*+\\s*+="), TYPE_STYLE),
            new RegexRule(Pattern.compile("\"(?:[^\"\\\\]++|\\\\.)*+\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'(?:[^'\\\\]++|\\\\.)*+'"), STRING_STYLE)
        ), CStyleHighlighter.Syntax.MARKUP);
    }

    public static RegexHighlighter forDockerfile() {
        return new CStyleHighlighter(List.of(
            new RegexRule(
                Pattern.compile("^\\s*(FROM|RUN|CMD|LABEL|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\\b"),
                KEYWORD_STYLE
            ),
            new RegexRule(Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?"), TYPE_STYLE)
        ), CStyleHighlighter.Syntax.DOCKERFILE);
    }

    public static RegexHighlighter forEnv() {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*(export\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*="), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.ENV);
    }

    public static RegexHighlighter forGitIgnore() {
        return new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*!.*$"), DECORATOR_STYLE),
            new RegexRule(Pattern.compile("^\\s*#.*$"), COMMENT_STYLE),
            new RegexRule(Pattern.compile("([*?\\[\\]])"), TYPE_STYLE)
        ));
    }

    public static RegexHighlighter forMakefile() {
        return new DelimitedHighlighter(List.of(
            new RegexRule(Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*:"), KEYWORD_STYLE)
        ), List.of(
            new DelimitedHighlighter.Delimiter("$(", ")", true),
            new DelimitedHighlighter.Delimiter("${", "}", true)
        ), TYPE_STYLE, List.of(
            new RegexRule(Pattern.compile("\\b(ifeq|ifneq|ifdef|ifndef|else|endif|include|override|export|unexport|define|endef)\\b"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+\\b"), NUMBER_STYLE),
            new RegexRule(Pattern.compile("#.*$"), COMMENT_STYLE)
        ));
    }

    public static RegexHighlighter forHtml() {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("</?[A-Za-z][A-Za-z0-9:-]*"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("(?<!\\s)\\s++[A-Za-z_:][A-Za-z0-9:._-]*+\\s*+="), TYPE_STYLE),
            new RegexRule(Pattern.compile("\"(?:[^\"\\\\]++|\\\\.)*+\""), STRING_STYLE),
            new RegexRule(Pattern.compile("'(?:[^'\\\\]++|\\\\.)*+'"), STRING_STYLE)
        ), CStyleHighlighter.Syntax.MARKUP);
    }

    public static RegexHighlighter forCssLike() {
        return new CStyleHighlighter(List.of(
            new RegexRule(Pattern.compile("(^|\\{|;)\\s*([A-Za-z_-][A-Za-z0-9_-]*)\\s*:"), KEYWORD_STYLE),
            new RegexRule(Pattern.compile("\\.[A-Za-z_-][A-Za-z0-9_-]*|#[A-Fa-f0-9]{3,8}|#[A-Za-z_-][A-Za-z0-9_-]*"), TYPE_STYLE),
            new RegexRule(Pattern.compile("\\b\\d+(?:\\.\\d+)?(px|em|rem|%|vh|vw|s|ms)?\\b"), NUMBER_STYLE)
        ), CStyleHighlighter.Syntax.CSS);
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
            new RegexRule(Pattern.compile("\"(?:[^\"]++|\"\")*+\""), STRING_STYLE),
            new RegexRule(Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b"), NUMBER_STYLE)
        ));
    }

    @Override
    public List<StyleSpan> highlight(String line) {
        return highlight(line, legacyRequest(line));
    }

    @Override
    public List<StyleSpan> highlight(String line, HighlightRequest request) {
        Objects.requireNonNull(request, "request");
        // Regex rules may depend on a closing delimiter or EOL beyond the visible
        // interval. Never reinterpret a truncated substring as a complete line.
        if (line == null || line.isEmpty() || !request.allowsFullLine(line)) {
            return List.of();
        }
        HighlightWork work = new HighlightWork(request.maxWork());
        try {
            return spans(highlightStyles(line, work), request, work);
        } catch (HighlightWork.Exhausted ignored) {
            return List.of();
        }
    }

    static HighlightRequest legacyRequest(String line) {
        return new HighlightRequest(0, line == null ? 0 : line.length(), LEGACY_MAX_SCAN, 65_536, 32_000_000L);
    }

    protected Style[] highlightStyles(String line, HighlightWork work) {
        work.spend(line.length());
        Style[] styleAt = new Style[line.length()];
        applyRules(line, styleAt, rules, work);
        return styleAt;
    }

    protected static void applyRules(String line, Style[] styleAt, List<RegexRule> rules, HighlightWork work) {
        for (RegexRule rule : rules) {
            try {
                Matcher matcher = rule.pattern().matcher(new RegexInput(line, work));
                while (matcher.find()) {
                    int start = Math.max(0, matcher.start());
                    int end = Math.min(line.length(), matcher.end());
                    if (work != null) work.spend(end - start);
                    for (int i = start; i < end; i++) {
                        styleAt[i] = rule.style();
                    }
                }
            } catch (RegexInput.MatchLimitExceeded ignored) {
                // Keep completed matches, but bound total retry/backtracking work per rule.
            } catch (StackOverflowError ignored) {
                // Custom rules may still recurse deeply; built-in string rules are possessive.
            }
        }
    }

    /** Produces only requested spans; output exhaustion yields an unstyled result. */
    protected static List<StyleSpan> spans(Style[] styleAt, HighlightRequest request, HighlightWork work) {
        int from = Math.min(styleAt.length, request.startUtf16());
        int end = Math.min(styleAt.length, request.endUtf16());
        if (from >= end || request.maxSpans() == 0) return List.of();
        work.spend(end - from);
        List<StyleSpan> result = new ArrayList<>();
        Style current = styleAt[from];
        int start = from;
        for (int offset = from + 1; offset <= end; offset++) {
            Style next = offset == end ? null : styleAt[offset];
            if (!Objects.equals(current, next)) {
                if (current != null) {
                    if (result.size() == request.maxSpans()) return List.of();
                    result.add(new StyleSpan(start, offset, current));
                }
                current = next;
                start = offset;
            }
        }
        return List.copyOf(result);
    }

}

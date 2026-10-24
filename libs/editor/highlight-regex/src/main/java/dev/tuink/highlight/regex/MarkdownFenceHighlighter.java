package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.HighlightResult;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.GenericLexicalScanner;
import dev.tuink.editor.highlight.LexicalConfig;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Markdown highlighter that applies language-aware syntax inside fenced code blocks.
 */
final class MarkdownFenceHighlighter implements ContextualSyntaxHighlighter {
    static final int MAX_CONTAINER_DEPTH = 1024;
    private final SyntaxHighlighter markdown = RegexHighlighter.forMarkdown();
    private final SyntaxHighlighter fallback = RegexHighlighter.withDefaults();
    private final LexicalScanner proseScanner = new GenericLexicalScanner(LexicalConfig.MARKDOWN);
    private final LexicalScanner fallbackScanner = new GenericLexicalScanner(LexicalConfig.GENERIC);
    private final Map<String, SyntaxHighlighter> languageCache = new HashMap<>();
    private final BoundedLineContext<BlockState> contexts = new BoundedLineContext<>(
        BlockState.EMPTY, MarkdownFenceHighlighter::stateWeight, MarkdownFenceHighlighter::scanContext);

    @Override
    public List<TokenSpan> highlight(String line) {
        return highlight(line, RegexHighlighter.legacyRequest(line));
    }

    @Override
    public List<TokenSpan> highlight(String line, HighlightRequest request) {
        return markdown.highlight(line, request);
    }

    @Override
    public LexicalScanner lexicalScanner() {
        return this::scanLexically;
    }

    private record LexicalState(BlockState block, LexicalScanner.State delegateState)
        implements LexicalScanner.State {
    }

    private LexicalScanner.Result scanLexically(
        String line,
        int maxCodeUnits,
        LexicalScanner.State previous
    ) {
        Objects.requireNonNull(line, "line");
        if (maxCodeUnits < 0) {
            throw new IllegalArgumentException("maxCodeUnits must be non-negative");
        }
        if (previous == LexicalScanner.State.UNKNOWN) {
            return new LexicalScanner.Result(0, previous, List.of(), List.of(), false);
        }
        int limit = Math.min(line.length(), maxCodeUnits);
        String prefix = limit == line.length() ? line : line.substring(0, limit);
        LexicalState state = previous instanceof LexicalState prior ? prior : new LexicalState(BlockState.EMPTY, null);
        Boundary boundary;
        try {
            boundary = parseBoundary(prefix, -1, state.block(), limit == line.length());
        } catch (ContainerLimitExceeded ignored) {
            // No prose scanner has proven even the prefix yet (ordered list
            // markers can themselves contain parentheses), so expose none.
            return new LexicalScanner.Result(0, LexicalScanner.State.UNKNOWN,
                List.of(), List.of(), false);
        }
        if (boundary.markerLine()) {
            return fenceResult(limit, boundary.after(), limit == line.length());
        }
        Fence fence = boundary.codeFence();
        LexicalScanner scanner = proseScanner;
        if (fence != null) {
            SyntaxHighlighter embedded = embeddedHighlighter(fence.languageTag());
            scanner = embedded instanceof ContextualSyntaxHighlighter contextual
                ? contextual.lexicalScanner() : null;
            if (scanner == null) {
                scanner = fallbackScanner;
            }
        }
        LexicalScanner.State delegateState = state.block().fence() == fence ? state.delegateState() : null;
        // The scanner sees the original line so it can distinguish truncation from a real EOL.
        LexicalScanner.Result result = scanner.scan(line, limit, delegateState);
        return new LexicalScanner.Result(result.scannedCodeUnits(),
            new LexicalState(boundary.after(), result.endState()), result.excludedRanges(), result.quotes(),
            result.contextKnown() && result.scannedCodeUnits() == line.length());
    }

    private static LexicalScanner.Result fenceResult(int limit, BlockState after, boolean contextKnown) {
        List<LexicalScanner.Range> ranges = limit == 0
            ? List.of() : List.of(new LexicalScanner.Range(0, limit));
        return new LexicalScanner.Result(limit, new LexicalState(after, null), ranges, List.of(), contextKnown);
    }

    @Override
    public List<TokenSpan> highlight(DocumentView document, int lineIndex, String line) {
        return highlight(document, lineIndex, line, RegexHighlighter.legacyRequest(line));
    }

    @Override
    public List<TokenSpan> highlight(DocumentView document, int lineIndex, String line, HighlightRequest request) {
        return highlightResult(document, lineIndex, line, request).spans();
    }

    @Override
    public HighlightResult highlightResult(DocumentView document, int lineIndex, String line, HighlightRequest request) {
        if (document == null || line == null || lineIndex < 0 || lineIndex >= document.lineCount()) {
            return HighlightResult.completed(markdown.highlight(line, request));
        }
        if (request.startUtf16() == request.endUtf16() || request.maxSpans() == 0
            || request.maxWork() == 0 || request.maxScanCodeUnits() == 0) {
            return HighlightResult.completed(List.of());
        }
        HighlightWork work = new HighlightWork(request.maxWork());
        var context = contexts.before(document, lineIndex, 0, request.maxScanCodeUnits(), work);
        if (!context.complete() || line.length() > context.remainingCodeUnits()) {
            return new HighlightResult(List.of(), context.advanced());
        }
        try {
            work.spend(8L * line.length() + 1);
            Boundary boundary = parseBoundary(line, lineIndex, context.state(), true);
            contexts.remember(lineIndex + 1, boundary.after());
            HighlightRequest delegated = new HighlightRequest(request.startUtf16(), request.endUtf16(),
                context.remainingCodeUnits(), request.maxSpans(), work.remaining());
            Fence fence = boundary.codeFence();
            SyntaxHighlighter highlighter = fence == null ? markdown : embeddedHighlighter(fence.languageTag());
            if (highlighter instanceof CStyleHighlighter contextual) {
                return contextual.highlightResult(document, lineIndex, line, fence.firstLine(), delegated,
                    context.advanced());
            }
            return HighlightResult.completed(highlighter.highlight(line, delegated));
        } catch (HighlightWork.Exhausted ignored) {
            return new HighlightResult(List.of(), context.advanced());
        } catch (ContainerLimitExceeded ignored) {
            return HighlightResult.completed(List.of());
        }
    }

    private static BlockState scanContext(String line, int lineIndex, BlockState before, HighlightWork work) {
        work.spend(8L * line.length() + 1);
        try {
            return parseBoundary(line, lineIndex, before, true).after();
        } catch (ContainerLimitExceeded ignored) {
            return null;
        }
    }

    private static int stateWeight(BlockState state) {
        long container = state.container() == null ? 0 : state.container().depth();
        long fence = state.fence() == null || state.fence().container() == null ? 0 : state.fence().container().depth();
        long languageTag = state.fence() == null ? 0 : state.fence().languageTag().length();
        return (int) Math.min(Integer.MAX_VALUE, 1 + container + fence + languageTag);
    }

    private SyntaxHighlighter embeddedHighlighter(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) {
            return fallback;
        }
        SyntaxHighlighter cached = languageCache.get(languageTag);
        if (cached != null) {
            return cached;
        }
        SyntaxHighlighter created = createEmbeddedHighlighter(languageTag);
        // Unknown tags can change on every edit; retain only the finite supported set.
        if (created != fallback) {
            languageCache.put(languageTag, created);
        }
        return created;
    }

    private SyntaxHighlighter createEmbeddedHighlighter(String languageTag) {
        return switch (languageTag) {
            case "java" -> RegexHighlighter.forJava();
            case "c", "h" -> RegexHighlighter.forC();
            case "cpp", "cxx", "cc", "hpp", "hxx" -> RegexHighlighter.forCpp();
            case "python", "py", "py3" -> RegexHighlighter.forPython();
            case "js", "javascript", "node", "nodejs", "jsx" -> RegexHighlighter.forJavaScript();
            case "ts", "typescript", "tsx" -> RegexHighlighter.forTypeScript();
            case "gradle", "groovy" -> RegexHighlighter.forGradle();
            case "sh", "shell", "bash", "zsh" -> RegexHighlighter.forShell();
            case "sql" -> RegexHighlighter.forSql();
            case "go", "golang" -> RegexHighlighter.forGo();
            case "rs", "rust" -> RegexHighlighter.forRust();
            case "php" -> RegexHighlighter.forPhp();
            case "ruby", "rb" -> RegexHighlighter.forRuby();
            case "perl", "pl", "pm" -> RegexHighlighter.forPerl();
            case "kt", "kts", "kotlin" -> RegexHighlighter.forKotlin();
            case "proto", "protobuf" -> RegexHighlighter.forProto();
            case "html", "htm" -> RegexHighlighter.forHtml();
            case "css", "scss", "sass" -> RegexHighlighter.forCssLike();
            case "diff", "patch" -> RegexHighlighter.forDiff();
            case "json" -> RegexHighlighter.forJson();
            case "yaml", "yml" -> RegexHighlighter.forYaml();
            case "toml" -> RegexHighlighter.forToml();
            case "ini", "cfg", "conf" -> RegexHighlighter.forIniLike();
            case "properties" -> RegexHighlighter.forProperties();
            case "editorconfig" -> RegexHighlighter.forEditorConfig();
            case "xml" -> RegexHighlighter.forXml();
            case "dockerfile", "docker" -> RegexHighlighter.forDockerfile();
            case "make", "makefile" -> RegexHighlighter.forMakefile();
            case "markdown", "md" -> RegexHighlighter.forMarkdown();
            default -> fallback;
        };
    }

    private static String normalizeLanguageTag(String rawInfo) {
        if (rawInfo == null || rawInfo.isBlank()) {
            return "";
        }
        String token = rawInfo.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (token.startsWith("{") && token.endsWith("}") && token.length() > 2) {
            token = token.substring(1, token.length() - 1);
        }
        if (token.startsWith(".")) {
            token = token.substring(1);
        }
        if (token.startsWith("language-")) {
            token = token.substring("language-".length());
        }
        if (token.startsWith("lang-")) {
            token = token.substring("lang-".length());
        }
        int separator = token.indexOf(':');
        if (separator >= 0 && separator < token.length() - 1) {
            token = token.substring(separator + 1);
        }
        int end = token.length();
        while (end > 0) {
            char tail = token.charAt(end - 1);
            if (Character.isLetterOrDigit(tail) || tail == '+' || tail == '#') {
                break;
            }
            end--;
        }
        return token.substring(0, end);
    }

    /** One block transition shared by display delegation and bounded semantic scanning. */
    private static Boundary parseBoundary(String text, int lineIndex, BlockState before, boolean completeLine) {
        int start = skipIndent(text, 0);
        if (start == text.length()) {
            return new Boundary(before, before.fence(), false);
        }
        int column = indentColumn(text, 0, start, 0);
        Container container = before.container();
        while (container != null && column < container.contentColumn()) {
            container = container.parent();
        }
        Fence fence = before.fence();
        if (fence != null && fence.container() == container) {
            FenceMarker marker = fenceMarker(text, start);
            boolean closing = completeLine && column - contentColumn(container) < 4 && marker != null
                && marker.fenceChar() == fence.fenceChar() && marker.length() >= fence.length()
                && marker.info().isBlank();
            return closing ? new Boundary(new BlockState(container, null), null, true)
                : new Boundary(new BlockState(container, fence), fence, false);
        }
        if (column - contentColumn(container) >= 4) {
            return new Boundary(new BlockState(container, null), null, false);
        }
        ThematicBreak thematicBreak = thematicBreakSuffix(text);
        while (true) {
            if (thematicBreak != null && thematicBreak.contains(start)) {
                return new Boundary(new BlockState(container, null), null, false);
            }
            FenceMarker marker = fenceMarker(text, start);
            if (completeLine && marker != null && (marker.fenceChar() != '`' || marker.info().indexOf('`') < 0)) {
                Fence opened = new Fence(marker.fenceChar(), marker.length(), normalizeLanguageTag(marker.info()),
                    lineIndex + 1, container);
                return new Boundary(new BlockState(container, opened), null, true);
            }
            int markerEnd = listMarkerEnd(text, start);
            if (markerEnd < 0) {
                return new Boundary(new BlockState(container, null), null, false);
            }
            int contentStart = skipIndent(text, markerEnd);
            int markerColumn = column + markerEnd - start;
            int nextColumn = indentColumn(text, markerEnd, contentStart, markerColumn);
            boolean code = nextColumn - markerColumn > 4;
            int itemColumn = code || contentStart == text.length()
                ? indentColumn(text, markerEnd, markerEnd + 1, markerColumn) : nextColumn;
            if (container != null && container.depth() >= MAX_CONTAINER_DEPTH) {
                throw new ContainerLimitExceeded();
            }
            container = new Container(container, itemColumn);
            if (code || contentStart == text.length()) {
                return new Boundary(new BlockState(container, null), null, false);
            }
            start = contentStart;
            column = nextColumn;
        }
    }

    private static int contentColumn(Container container) {
        return container == null ? 0 : container.contentColumn();
    }

    private static int skipIndent(String text, int from) {
        int end = from;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return end;
    }

    /** Markdown indentation uses four-column tab stops, independently of display settings. */
    private static int indentColumn(String text, int from, int to, int column) {
        for (int offset = from; offset < to; offset++) {
            column = text.charAt(offset) == '\t' ? column + 4 - column % 4 : column + 1;
        }
        return column;
    }

    private static int listMarkerEnd(String text, int start) {
        int end = start;
        char first = text.charAt(start);
        if (first == '-' || first == '*' || first == '+') {
            end++;
        } else {
            while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
                end++;
            }
            if (end == start || end - start > 9 || end == text.length()
                || (text.charAt(end) != '.' && text.charAt(end) != ')')) {
                return -1;
            }
            end++;
        }
        return end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t') ? end : -1;
    }

    /** Precompute once so nested marker-like text cannot repeatedly rescan the same suffix. */
    private static ThematicBreak thematicBreakSuffix(String text) {
        int end = text.length() - 1;
        while (end >= 0 && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end--;
        }
        if (end < 0) {
            return null;
        }
        char marker = text.charAt(end);
        if (marker != '-' && marker != '*' && marker != '_') {
            return null;
        }
        int count = 0;
        int thirdFromEnd = -1;
        int first = end;
        for (; first >= 0; first--) {
            char character = text.charAt(first);
            if (character == marker) {
                if (++count == 3) {
                    thirdFromEnd = first;
                }
            } else if (character != ' ' && character != '\t') {
                break;
            }
        }
        return thirdFromEnd < 0 ? null : new ThematicBreak(first + 1, thirdFromEnd);
    }

    private static FenceMarker fenceMarker(String text, int start) {
        char character = text.charAt(start);
        if (character != '`' && character != '~') {
            return null;
        }
        int end = start + 1;
        while (end < text.length() && text.charAt(end) == character) {
            end++;
        }
        return end - start < 3 ? null : new FenceMarker(character, end - start, text.substring(end));
    }

    private record Container(Container parent, int contentColumn, int depth) {
        private Container(Container parent, int contentColumn) {
            this(parent, contentColumn, parent == null ? 1 : parent.depth() + 1);
        }
    }

    private static final class ContainerLimitExceeded extends RuntimeException {
        private ContainerLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private record Fence(char fenceChar, int length, String languageTag, int firstLine, Container container) {}

    private record FenceMarker(char fenceChar, int length, String info) {}

    private record BlockState(Container container, Fence fence) {
        private static final BlockState EMPTY = new BlockState(null, null);
    }

    private record Boundary(BlockState after, Fence codeFence, boolean markerLine) {}

    private record ThematicBreak(int first, int last) {
        private boolean contains(int start) {
            return start >= first && start <= last;
        }
    }
}

package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.LineLexicalScanner;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Markdown highlighter that applies language-aware syntax inside fenced code blocks.
 */
final class MarkdownFenceHighlighter implements ContextualLineHighlighter {
    private final LineHighlighter markdown = RegexHighlighter.forMarkdown();
    private final LineHighlighter fallback = RegexHighlighter.withDefaults();
    private final LineLexicalScanner proseScanner = LineLexicalScanner.markdownProse();
    private final LineLexicalScanner fallbackScanner = LineLexicalScanner.generic();
    private final Map<String, LineHighlighter> languageCache = new HashMap<>();
    private DocumentId cachedDocumentId;
    private long cachedRevision = Long.MIN_VALUE;
    private int computedLine = -1;
    private final List<LineDelegate> delegatesByLine = new ArrayList<>();
    private Fence activeFence;

    @Override
    public List<StyleSpan> highlight(String line) {
        return markdown.highlight(line);
    }

    @Override
    public LineLexicalScanner lexicalScanner() {
        return this::scanLexically;
    }

    private record LexicalState(Fence fence, LineLexicalScanner.State delegateState)
        implements LineLexicalScanner.State {
    }

    private LineLexicalScanner.Result scanLexically(
        String line,
        int maxCodeUnits,
        LineLexicalScanner.State previous
    ) {
        Objects.requireNonNull(line, "line");
        if (maxCodeUnits < 0) {
            throw new IllegalArgumentException("maxCodeUnits must be non-negative");
        }
        int limit = Math.min(line.length(), maxCodeUnits);
        String prefix = limit == line.length() ? line : line.substring(0, limit);
        LexicalState state = previous instanceof LexicalState prior ? prior : new LexicalState(null, null);
        FenceMarker marker = fenceMarker(prefix);
        Fence fence = state.fence();
        if (fence == null && marker != null) {
            Fence opened = new Fence(marker.fenceChar(), marker.length(), normalizeLanguageTag(marker.info()), 0);
            return fenceResult(limit, opened);
        }
        if (fence != null && marker != null && marker.fenceChar() == fence.fenceChar()
            && marker.length() >= fence.length() && marker.info().isBlank()) {
            return fenceResult(limit, null);
        }
        LineLexicalScanner scanner = proseScanner;
        if (fence != null) {
            LineHighlighter embedded = embeddedHighlighter(fence.languageTag());
            scanner = embedded instanceof ContextualLineHighlighter contextual
                ? contextual.lexicalScanner() : null;
            if (scanner == null) {
                scanner = fallbackScanner;
            }
        }
        // The scanner sees the original line so it can distinguish truncation from a real EOL.
        LineLexicalScanner.Result result = scanner.scan(line, limit, state.delegateState());
        return new LineLexicalScanner.Result(result.scannedCodeUnits(),
            new LexicalState(fence, result.endState()), result.excludedRanges(), result.quotes());
    }

    private static LineLexicalScanner.Result fenceResult(int limit, Fence fence) {
        List<LineLexicalScanner.Range> ranges = limit == 0
            ? List.of() : List.of(new LineLexicalScanner.Range(0, limit));
        return new LineLexicalScanner.Result(limit, new LexicalState(fence, null), ranges, List.of());
    }

    @Override
    public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line) {
        if (document == null || line == null) {
            return markdown.highlight(line);
        }
        if (lineIndex < 0 || lineIndex >= document.lineCount()) {
            return markdown.highlight(line);
        }
        ensureComputed(document, lineIndex);
        if (lineIndex < delegatesByLine.size()) {
            LineDelegate delegate = delegatesByLine.get(lineIndex);
            if (delegate.highlighter() instanceof CStyleHighlighter contextual) {
                return contextual.highlight(document, lineIndex, line, delegate.firstLine());
            }
            return delegate.highlighter().highlight(line);
        }
        return markdown.highlight(line);
    }

    private void ensureComputed(DocumentView document, int targetLine) {
        DocumentId documentId = document.documentId();
        long revision = document.revision();
        if (!Objects.equals(documentId, cachedDocumentId)) {
            cachedDocumentId = documentId;
            cachedRevision = revision;
            computedLine = -1;
            delegatesByLine.clear();
            activeFence = null;
        } else if (revision != cachedRevision) {
            int retainedLines = Math.min(
                delegatesByLine.size(),
                Math.max(0, document.firstChangedLineSince(cachedRevision))
            );
            delegatesByLine.subList(retainedLines, delegatesByLine.size()).clear();
            computedLine = retainedLines - 1;
            activeFence = retainedLines == 0 ? null : delegatesByLine.getLast().fenceAfter();
            cachedRevision = revision;
        }
        if (targetLine <= computedLine) {
            return;
        }
        for (int line = computedLine + 1; line <= targetLine; line++) {
            String text = document.line(line);
            delegatesByLine.add(delegateForLine(text, line));
        }
        computedLine = targetLine;
    }

    private LineDelegate delegateForLine(String line, int lineIndex) {
        FenceMarker marker = fenceMarker(line);
        if (activeFence == null) {
            if (marker == null) {
                return new LineDelegate(markdown, 0, null);
            }
            activeFence = new Fence(marker.fenceChar(), marker.length(), normalizeLanguageTag(marker.info()), lineIndex + 1);
            return new LineDelegate(markdown, 0, activeFence);
        }

        // Closing fence uses the same marker kind and at least the opening length.
        if (marker != null
            && marker.fenceChar() == activeFence.fenceChar()
            && marker.length() >= activeFence.length()
            && marker.info().isBlank()) {
            activeFence = null;
            return new LineDelegate(markdown, 0, null);
        }

        return new LineDelegate(embeddedHighlighter(activeFence.languageTag()), activeFence.firstLine(), activeFence);
    }

    private LineHighlighter embeddedHighlighter(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) {
            return fallback;
        }
        LineHighlighter cached = languageCache.get(languageTag);
        if (cached != null) {
            return cached;
        }
        LineHighlighter created = createEmbeddedHighlighter(languageTag);
        // Unknown tags can change on every edit; retain only the finite supported set.
        if (created != fallback) {
            languageCache.put(languageTag, created);
        }
        return created;
    }

    private LineHighlighter createEmbeddedHighlighter(String languageTag) {
        return switch (languageTag) {
            case "java" -> RegexHighlighter.forJava();
            case "c", "h", "cpp", "cxx", "cc", "hpp", "hxx" -> RegexHighlighter.forC();
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

    private static FenceMarker fenceMarker(String line) {
        if (line == null) {
            return null;
        }
        int i = 0;
        while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        if (i >= line.length()) {
            return null;
        }
        char fenceChar = line.charAt(i);
        if (fenceChar != '`' && fenceChar != '~') {
            return null;
        }
        int runEnd = i;
        while (runEnd < line.length() && line.charAt(runEnd) == fenceChar) {
            runEnd++;
        }
        int length = runEnd - i;
        if (length < 3) {
            return null;
        }
        String info = runEnd >= line.length() ? "" : line.substring(runEnd);
        return new FenceMarker(fenceChar, length, info);
    }

    private record LineDelegate(LineHighlighter highlighter, int firstLine, Fence fenceAfter) {
    }

    private record Fence(char fenceChar, int length, String languageTag, int firstLine) {
    }

    private record FenceMarker(char fenceChar, int length, String info) {
    }
}

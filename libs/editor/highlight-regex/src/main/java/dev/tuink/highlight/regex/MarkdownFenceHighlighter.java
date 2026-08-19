package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineHighlighter;
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
        if (!Objects.equals(documentId, cachedDocumentId) || revision != cachedRevision) {
            cachedDocumentId = documentId;
            cachedRevision = revision;
            computedLine = -1;
            delegatesByLine.clear();
            activeFence = null;
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
                return new LineDelegate(markdown, 0);
            }
            activeFence = new Fence(marker.fenceChar(), marker.length(), normalizeLanguageTag(marker.info()), lineIndex + 1);
            return new LineDelegate(markdown, 0);
        }

        // Closing fence uses the same marker kind and at least the opening length.
        if (marker != null
            && marker.fenceChar() == activeFence.fenceChar()
            && marker.length() >= activeFence.length()
            && marker.info().isBlank()) {
            activeFence = null;
            return new LineDelegate(markdown, 0);
        }

        return new LineDelegate(embeddedHighlighter(activeFence.languageTag()), activeFence.firstLine());
    }

    private LineHighlighter embeddedHighlighter(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) {
            return RegexHighlighter.withDefaults();
        }
        return languageCache.computeIfAbsent(languageTag, this::createEmbeddedHighlighter);
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
            case "ini", "cfg", "conf", "properties", "editorconfig" -> RegexHighlighter.forIniLike();
            case "xml" -> RegexHighlighter.forXml();
            case "dockerfile", "docker" -> RegexHighlighter.forDockerfile();
            case "make", "makefile" -> RegexHighlighter.forMakefile();
            case "markdown", "md" -> RegexHighlighter.forMarkdown();
            default -> RegexHighlighter.withDefaults();
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
        while (!token.isEmpty()) {
            char tail = token.charAt(token.length() - 1);
            if (Character.isLetterOrDigit(tail) || tail == '+' || tail == '#') {
                break;
            }
            token = token.substring(0, token.length() - 1);
        }
        return token;
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

    private record LineDelegate(LineHighlighter highlighter, int firstLine) {
    }

    private record Fence(char fenceChar, int length, String languageTag, int firstLine) {
    }

    private record FenceMarker(char fenceChar, int length, String info) {
    }
}

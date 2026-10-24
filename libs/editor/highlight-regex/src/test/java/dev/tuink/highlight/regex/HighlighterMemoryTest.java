package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HighlighterMemoryTest {
    @Test
    void denseAsciiAndUnicodeLinesStayBoundedBeforeStylesOrSpansAreAllocated() throws Exception {
        runProbe("dense");
    }

    @Test
    void contextualHighlightersDoNotMaterializeAStateForMillionsOfPriorLines() throws Exception {
        runProbe("context");
    }

    @Test
    void markdownCheckpointsIncludeRetainedLanguageTagPayload() throws Exception {
        runProbe("tags");
    }

    private static void runProbe(String mode) throws Exception {
        String classpath = String.join(File.pathSeparator, location(HighlighterMemoryTest.class),
            location(RegexHighlighter.class), location(HighlightRequest.class), location(DocumentView.class));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, MemoryProbe.class.getName(), mode).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Syntax memory probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        public static void main(String[] args) {
            if (args[0].equals("dense")) {
                verifyLine("1,".repeat(5 * 1024 * 1024));
                verifyLine("Ā,".repeat(5 * 1024 * 1024));
            } else if (args[0].equals("tags")) {
                DocumentView generated = new DocumentView() {
                    @Override public DocumentId documentId() { return new DocumentId("large-fence-tags"); }
                    @Override public long revision() { return 1; }
                    @Override public int lineCount() { return 256; }
                    @Override public String line(int index) {
                        return index % 2 == 0 ? "```" + "x".repeat(1024 * 1024 - 32) + index : "```";
                    }
                };
                ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName("notes.md");
                for (int line = 0; line < generated.lineCount(); line++) {
                    highlighter.highlight(generated, line, generated.line(line));
                }
            } else {
                DocumentView generated = new DocumentView() {
                    @Override public DocumentId documentId() { return new DocumentId("generated"); }
                    @Override public long revision() { return 1; }
                    @Override public int lineCount() { return 10_000_000; }
                    @Override public String line(int index) { return ""; }
                };
                for (String file : List.of("Main.java", "notes.md")) {
                    ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(file);
                    if (!highlighter.highlight(generated, generated.lineCount() - 1, "return 7;",
                        new HighlightRequest(0, 9, 4096, 256, 100_000)).isEmpty()) {
                        throw new AssertionError("Unknown context must remain unstyled");
                    }
                }
            }
        }

        private static void verifyLine(String text) {
            for (SyntaxHighlighter highlighter : List.of(RegexHighlighter.forCsv(), RegexHighlighter.forJson())) {
                if (!highlighter.highlight(text).isEmpty()) throw new AssertionError("Oversized legacy input must be bounded");
                for (int start : new int[] {0, text.length() / 2, text.length() - 80}) {
                    if (!highlighter.highlight(text, new HighlightRequest(start, start + 80, 65_536, 256, 1_000_000)).isEmpty()) {
                        throw new AssertionError("Oversized stateless input must remain unstyled");
                    }
                }
            }
        }
    }
}

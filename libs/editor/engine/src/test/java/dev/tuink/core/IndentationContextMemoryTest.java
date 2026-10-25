package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IndentationContextMemoryTest {
    @Test
    void oversizedUnicodePrefixesAndMillionsOfPriorLinesStayBoundedOnSmallHeap() throws Exception {
        String classpath = String.join(File.pathSeparator, location(IndentationContextMemoryTest.class),
            location(IndentationContext.class), location(TabInsertMode.class),
            location(LanguageRegistry.class), location(GraphemeText.class));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, MemoryProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Indentation memory probe exceeded its deadline");
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
            verifyOversizedCurrentLine();
            AtomicInteger reads = new AtomicInteger();
            IndentationContext context = new IndentationContext(line -> {
                reads.incrementAndGet();
                return "";
            });
            context.synchronize(1, 1, 0, 10_000_000, "Main.java");
            // A broken eviction policy would retain over two million states.
            for (int attempt = 0; attempt < 2000; attempt++) {
                reads.set(0);
                if (context.codeBeforeCursor(9_999_999, 0) != null) throw new AssertionError("Unknown context was trusted");
                if (reads.get() > IndentationContext.MAX_SCAN_LINES + 1) throw new AssertionError("Unbounded line traversal");
            }
        }

        private static void verifyOversizedCurrentLine() {
            String text = "    " + "Ā".repeat(11 * 1024 * 1024) + "{";
            IndentationDecisions decisions = new IndentationDecisions(1, line -> text, "Main.java",
                4, TabInsertMode.ALWAYS_SPACES_4);
            if (!decisions.newLineIndent(new Position(0, text.length())).equals("    ")) {
                throw new AssertionError("An oversized prefix must copy only its existing whitespace");
            }
            IndentationDecisions auto = new IndentationDecisions(513, line -> text, "notes.txt",
                4, TabInsertMode.AUTO);
            if (!auto.unit(256, "").equals("    ")) {
                throw new AssertionError("AUTO indentation must inspect only neighbouring indentation prefixes");
            }
        }
    }
}

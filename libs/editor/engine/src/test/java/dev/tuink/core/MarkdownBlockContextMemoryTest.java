package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MarkdownBlockContextMemoryTest {
    @Test
    void millionLineContextFitsInABoundedHeap() throws Exception {
        String classpath = String.join(File.pathSeparator,
            location(MarkdownBlockContextMemoryTest.class), location(MarkdownBlockContext.class));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx32m", "-cp", classpath, MemoryProbe.class.getName()
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Markdown context probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** Generated text isolates parser retention from the memory occupied by a document. */
    public static final class MemoryProbe {
        public static void main(String[] args) {
            int count = 1_000_000;
            boolean[] fenced = {true};
            MarkdownBlockContext context = new MarkdownBlockContext(line ->
                line == 0 && fenced[0] ? "```sh" : "- item");
            context.synchronize(0, 0, 0, count);
            require(!context.allowsListAssist(count - 1), "A long fence must retain its opener");
            require(!context.allowsListAssist(10), "An evicted prefix must be reparsed from its own state");
            require(!context.allowsListAssist(count - 1), "Returning to the tail must preserve fence state");

            fenced[0] = false;
            context.synchronize(0, 1, 0, count);
            require(context.allowsListAssist(count - 1), "An earlier edit must invalidate the fence");
            context.synchronize(0, 2, count - 1, count + 1);
            require(context.allowsListAssist(count), "A newly appended item must reuse valid context");

            fenced[0] = true;
            context.synchronize(1, 2, count + 1, count + 1);
            require(!context.allowsListAssist(count), "Replacing the document must reset all checkpoints");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }
}

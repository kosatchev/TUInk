package dev.tuink.unicode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GraphemeTextBoundaryMemoryTest {
    @Test
    void aSingleBoundaryLookupDoesNotIndexTheRestOfALongLine() throws Exception {
        String classpath = String.join(File.pathSeparator, location(GraphemeTextBoundaryMemoryTest.class),
            location(GraphemeText.class));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx48m", "-cp", classpath, MemoryProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Streaming boundary lookup exceeded its deadline");
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
            denseLine();
            longCluster();
        }

        private static void denseLine() {
            String text = "e\u0301".repeat(8_000_000);
            if (GraphemeText.ceilBoundary(text, 1) != 2) throw new AssertionError("Lost the first cluster boundary");
        }

        private static void longCluster() {
            String text = "e" + "\u0301".repeat(8_000_000) + "x";
            if (GraphemeText.ceilBoundary(text, 1) != text.length() - 1) {
                throw new AssertionError("Split a long combining sequence");
            }
        }
    }
}

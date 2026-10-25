package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.GutterDiffMode;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsMemoryTest {
    @TempDir Path directory;

    @Test
    void aLargePropertiesValueIsRejectedInASmallHeapBeforeParsing() throws Exception {
        runProbe("properties");
    }

    @Test
    void aHugeSparseSettingsFileIsRejectedAtStartupInASmallHeap() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        runProbe("sparse");
    }

    @Test
    void externalGrowthToAHugeFileProducesAConflictInASmallHeap() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        runProbe("growth");
    }

    private void runProbe(String mode) throws Exception {
        String classpath = String.join(File.pathSeparator,
            location(AppSettingsMemoryTest.class), location(AppSettingsStore.class), location(GutterDiffMode.class), location(dev.tuink.storage.jvm.JvmDocumentStorage.class), location(dev.tuink.editor.document.DocumentId.class));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, MemoryProbe.class.getName(),
            directory.resolve("tuink.properties").toString(), mode
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Settings memory probe exceeded its deadline");
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
        public static void main(String[] args) throws IOException {
            Path file = Path.of(args[0]);
            String mode = args[1];
            AppSettingsStore.save(file, AppSettings.defaults());
            var loaded = AppSettingsStore.loadSnapshot(file);
            if (mode.equals("properties")) {
                // A valid Properties file can contain an arbitrarily long value
                // overridden later by a normal, supported value for the same key.
                try (var output = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1,
                    StandardOpenOption.APPEND)) {
                    output.write("\nui.theme=");
                    String chunk = "x".repeat(8192);
                    for (int index = 0; index < 2048; index++) {
                        output.write(chunk);
                    }
                    output.write("\nui.theme=cyan\n");
                }
            } else {
                try (var output = new RandomAccessFile(file.toFile(), "rw")) {
                    output.setLength(2_147_483_648L);
                }
            }
            long size = Files.size(file);
            try {
                if (mode.equals("growth")) {
                    AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults());
                } else {
                    AppSettingsStore.loadSnapshot(file);
                }
                throw new AssertionError("Oversized settings were accepted");
            } catch (IOException expected) {
                if (mode.equals("growth") && !(expected instanceof AppSettingsStore.SettingsConflictException)) {
                    throw expected;
                }
                if (!mode.equals("growth") && !expected.getMessage().contains("Maximum size is 64 KiB")) {
                    throw expected;
                }
            }
            if (Files.size(file) != size) {
                throw new AssertionError("The oversized settings file was modified");
            }
            try (var files = Files.list(file.getParent())) {
                if (!files.toList().equals(java.util.List.of(file))) {
                    throw new AssertionError("A rejected settings file left temporary files behind");
                }
            }
        }
    }
}

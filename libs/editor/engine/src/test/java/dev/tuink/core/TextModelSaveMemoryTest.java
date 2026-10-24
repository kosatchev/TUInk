package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextModelSaveMemoryTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"64, 10", "128, 22"})
    void acceptedMultilineUnicodePastesCanBeSavedAndSavedAgainOnBoundedHeap(int heapMiB, int blocks)
        throws Exception {
        String classpath = String.join(File.pathSeparator, location(TextModelSaveMemoryTest.class),
            location(EditorEngine.class), location(GraphemeText.class),
            location(LanguageRegistry.class), LinuxXattrTestRuntime.classpath());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-Xmx" + heapMiB + "m", "-cp", classpath, MemoryProbe.class.getName(),
            directory.toString(), Integer.toString(blocks)).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Save memory probe exceeded its deadline");
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
        private static final int BLOCK_UNITS = 512 * 1024;

        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            int blocks = Integer.parseInt(args[1]);
            System.setProperty("user.home", directory.resolve("home").toString());
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.setAutoIndentEnabled(false);
            engine.setAutoListEnabled(false);
            engine.setAutoPairsEnabled(false);
            String line = "Ā".repeat(BLOCK_UNITS - 1);
            String pasted = "\n" + line;
            for (int block = 1; block <= blocks; block++) {
                System.gc();
                require(engine.pasteText(pasted), "Normal multiline paste was refused");
                require(model.lineCount() == block + 1, "Paste lost a logical line");
                require(engine.state().isDirty(), "Paste lost the dirty flag");
            }
            Path target = directory.resolve("saved.txt");

            System.gc();
            engine.saveAs(target);

            require(!engine.state().isDirty(), "Save As did not clear the dirty flag");
            verifyFile(target, blocks, "", model.fileBaseline());
            System.gc();
            require(engine.pasteText("\nlast😀\n"), "Small later paste was refused");
            require(engine.state().isDirty(), "Later paste lost the dirty flag");

            System.gc();
            engine.save();

            require(!engine.state().isDirty(), "Save did not clear the dirty flag");
            require(model.lineCount() == blocks + 3, "Save changed the logical lines");
            require(model.getLine(0).isEmpty(), "Save lost the leading EOL");
            for (int block = 1; block <= blocks; block++) {
                require(model.getLine(block).equals(line), "Save changed pasted Unicode text");
            }
            require(model.getLine(blocks + 1).equals("last😀"), "Save lost supplementary text");
            require(model.getLine(blocks + 2).isEmpty(), "Save lost the trailing EOL");
            verifyFile(target, blocks, "\nlast😀\n", model.fileBaseline());
        }

        private static void verifyFile(Path target, int blocks, String suffix, FileBaseline baseline)
            throws Exception {
            long bytesPerBlock = 1L + 2L * (BLOCK_UNITS - 1);
            long bodyBytes = blocks * bytesPerBlock;
            byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
            long expectedSize = bodyBytes + suffixBytes.length;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long offset = 0;
            try (InputStream input = Files.newInputStream(target)) {
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    digest.update(bytes, 0, read);
                    for (int index = 0; index < read; index++, offset++) {
                        require(offset < expectedSize, "Save appended unexpected bytes");
                        int expected;
                        if (offset < bodyBytes) {
                            long withinBlock = offset % bytesPerBlock;
                            expected = withinBlock == 0 ? '\n' : (withinBlock & 1) == 1 ? 0xC4 : 0x80;
                        } else {
                            expected = suffixBytes[(int) (offset - bodyBytes)] & 0xFF;
                        }
                        require((bytes[index] & 0xFF) == expected, "Saved UTF-8 bytes or EOLs changed");
                    }
                }
            }
            require(offset == expectedSize && Files.size(target) == expectedSize, "Saved size changed");
            require(baseline.byteSize() == expectedSize, "Saved baseline size changed");
            require(baseline.sha256().equals(HexFormat.of().formatHex(digest.digest())),
                "Saved baseline digest does not identify the committed bytes");
        }

        private static void require(boolean valid, String message) {
            if (!valid) throw new AssertionError(message);
        }
    }
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextModelLoadTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsDirectoriesWithoutChangingTheDocument() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "unfinished work");
        long revision = model.revision();

        IOException failure = assertThrows(IOException.class, () -> model.load(tempDir));

        assertTrue(failure.getMessage().contains("Not a regular file"));
        assertEquals(List.of("unfinished work"), model.snapshotLines());
        assertEquals(revision, model.revision());
    }

    @Test
    void missingFilesStillReportNoSuchFile() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        assertThrows(NoSuchFileException.class, () -> model.load(tempDir.resolve("new.txt")));
    }

    @Test
    void loadsOrdinaryFilesThroughSymbolicLinks() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("regular.txt");
        Files.writeString(target, "ordinary\r\nfile");
        Path link = Files.createSymbolicLink(tempDir.resolve("alias.txt"), target.getFileName());
        TextModel model = new TextModel(new SimpleLineBuffer());

        model.load(link);

        assertEquals(List.of("ordinary", "file"), model.snapshotLines());
        assertEquals(target.toRealPath(), model.fileBaseline().target());
        assertEquals(List.of("\r\n"), model.fileMetadata().lineSeparators());
    }

    @Test
    void fifoAndItsSymbolicLinkAreRejectedBeforeOpeningAStream() throws Exception {
        assumePosix();
        Path fifo = tempDir.resolve("input.fifo");
        createFifo(fifo);
        Path link = Files.createSymbolicLink(tempDir.resolve("alias.fifo"), fifo.getFileName());
        Path replacedFile = tempDir.resolve("was-regular.txt");
        Files.writeString(replacedFile, "original");
        runFifoProbe(fifo, link, replacedFile);
    }

    private void createFifo(Path fifo) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "mkfifo is unavailable: " + unavailable.getMessage());
            return;
        }
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "mkfifo did not terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assumeTrue(process.exitValue() == 0, "FIFO creation is unsupported: " + output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static void runFifoProbe(Path fifo, Path link, Path replacedFile) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = String.join(File.pathSeparator,
            classpathLocation(TextModelLoadTest.class),
            classpathLocation(TextModel.class),
            classpathLocation(SimpleLineBuffer.class),
            classpathLocation(GraphemeText.class)
        );
        Process process = new ProcessBuilder(
            java.toString(), "-Xmx64m", "-cp", classpath, FifoProbe.class.getName(),
            fifo.toString(), link.toString(), replacedFile.toString()
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "FIFO load/save probe blocked");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String classpathLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private void assumePosix() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
    }

    /** Kept in a disposable process: an attempted FIFO open cannot stall the test JVM. */
    public static final class FifoProbe {
        public static void main(String[] args) throws IOException {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.insertText(new Position(0, 0), "unfinished work");
            long revision = model.revision();
            for (int index = 0; index < 2; index++) {
                try {
                    model.load(Path.of(args[index]));
                    throw new AssertionError("Accepted a FIFO: " + args[index]);
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("Not a regular file")) {
                        throw expected;
                    }
                }
                if (model.revision() != revision || !model.getLine(0).equals("unfinished work")) {
                    throw new AssertionError("Rejected load changed the document");
                }
            }

            Path replaced = Path.of(args[2]);
            model.load(replaced);
            model.insertText(new Position(0, 0), "edited ");
            Files.delete(replaced);
            Files.createSymbolicLink(replaced, Path.of(args[0]));
            try {
                model.saveIfUnchanged(replaced, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);
                throw new AssertionError("Accepted a file replaced by a FIFO");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("File changed outside TUInk")) {
                    throw expected;
                }
            }
            if (!model.getLine(0).equals("edited original") || !Files.isSymbolicLink(replaced)) {
                throw new AssertionError("Rejected save changed the document or the FIFO link");
            }
        }
    }
}

package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.unicode.GraphemeText;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
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
    void utf32ByteOrderMarkersAreRejectedBeforeChangingTheActiveDocumentOrHistory() throws Exception {
        Path original = tempDir.resolve("original.txt");
        Files.writeString(original, "original\r\ncontents");
        for (String encoding : List.of("UTF-32LE", "UTF-32BE")) {
            byte[] encoded = "AB\n".getBytes(Charset.forName(encoding));
            byte[] unsupported = new byte[encoded.length + 4];
            byte[] bom = encoding.endsWith("LE")
                ? new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0}
                : new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF};
            System.arraycopy(bom, 0, unsupported, 0, bom.length);
            System.arraycopy(encoded, 0, unsupported, bom.length, encoded.length);
            Path target = tempDir.resolve(encoding + ".txt");
            Files.write(target, unsupported);
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorState state = new EditorState();
            EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
            engine.open(original);
            engine.insertInput("draft ");
            engine.restoreSelection(new Position(1, 3), new Position(0, 2));
            var lines = model.snapshotLines();
            var metadata = model.fileMetadata();
            var baseline = model.fileBaseline();
            long revision = model.revision();
            long epoch = model.contentEpoch();

            IOException error = assertThrows(IOException.class, () -> engine.openExisting(target));

            assertTrue(error.getMessage().contains(encoding));
            if (encoding.endsWith("LE")) {
                assertTrue(error instanceof AmbiguousDocumentEncodingException);
            }
            assertEquals(lines, model.snapshotLines());
            assertEquals(metadata, model.fileMetadata());
            assertEquals(baseline, model.fileBaseline());
            assertEquals(revision, model.revision());
            assertEquals(epoch, model.contentEpoch());
            assertEquals(original, state.getCurrentFile());
            assertEquals(new Position(1, 3), state.getCursor());
            assertEquals(new Position(0, 2), state.getSelectionAnchor());
            assertTrue(state.isDirty());
            assertTrue(java.util.Arrays.equals(unsupported, Files.readAllBytes(target)));
            assertTrue(engine.undo());
            assertEquals(List.of("original", "contents"), model.snapshotLines());
            assertTrue(!state.isDirty());
            assertTrue(engine.redo());
            assertEquals(lines, model.snapshotLines());
        }
    }

    @Test
    void oversizedSparseFilesAreRejectedWithoutChangingTextOrFileIdentity() throws Exception {
        assumePosix();
        Path original = tempDir.resolve("original.txt");
        Files.writeString(original, "original\r\ncontents");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(original);
        model.insertText(new Position(0, 0), "draft ");
        var lines = model.snapshotLines();
        var metadata = model.fileMetadata();
        var baseline = model.fileBaseline();
        long revision = model.revision();
        Path oversized = tempDir.resolve("oversized.txt");
        for (long size : new long[] {TextModel.MAX_LOAD_BYTES + 1L, 2_147_483_648L}) {
            try (RandomAccessFile file = new RandomAccessFile(oversized.toFile(), "rw")) {
                file.setLength(size);
            }

            IOException error = assertThrows(IOException.class, () -> model.load(oversized));

            assertTrue(error.getMessage().contains("File too large to open"));
            assertEquals(lines, model.snapshotLines());
            assertEquals(metadata, model.fileMetadata());
            assertEquals(baseline, model.fileBaseline());
            assertEquals(revision, model.revision());
        }
    }

    @Test
    void aStreamGrowingBeyondItsObservedSizeIsStillBounded() throws IOException {
        byte[] growing = "0123456789more bytes still arriving".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(growing);

        IOException error = assertThrows(IOException.class,
            () -> TextModel.readDocumentBytes(input, tempDir.resolve("growing.txt"), 10));

        assertTrue(error.getMessage().contains("File too large to open"));
        assertEquals(growing.length - 11, input.available(), "read only the limit plus one sentinel byte");
        assertEquals("0123456789", new String(TextModel.readDocumentBytes(
            new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)),
            tempDir.resolve("fits.txt"), 10), StandardCharsets.UTF_8));
    }

    @Test
    void lineDenseFilesAreRejectedWithARecoverableErrorInASmallHeap() throws Exception {
        Path dense = tempDir.resolve("many-lines.txt");
        Files.writeString(dense, "\n".repeat(1_000_000));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = String.join(File.pathSeparator,
            classpathLocation(TextModelLoadTest.class),
            classpathLocation(TextModel.class),
            classpathLocation(GraphemeText.class));
        Process process = new ProcessBuilder(java.toString(), "-Xmx64m", "-cp", classpath,
            DenseFileProbe.class.getName(), dense.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "bounded load probe did not terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class DenseFileProbe {
        public static void main(String[] args) throws IOException {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.insertText(new Position(0, 0), "unfinished work");
            long revision = model.revision();
            try {
                model.load(Path.of(args[0]));
                throw new AssertionError("A line-dense document exceeded the load memory budget");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("available memory")) {
                    throw expected;
                }
            }
            if (!model.snapshotLines().equals(List.of("unfinished work")) || model.revision() != revision) {
                throw new AssertionError("Rejected load changed the original document");
            }
        }
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

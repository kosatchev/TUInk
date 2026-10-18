package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.BomKind;
import dev.tuink.core.DocumentFileMetadata;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.FileBaseline;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import dev.tuink.core.TabSession;
import dev.tuink.core.ViewportState;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryFormatFiveTest {
    private static final String GENERATION = "0123456789abcdef0123456789abcdef";
    @TempDir Path directory;

    @Test
    void publishedFormatRestoresLiteralUnicodePathsMetadataAndAnUnchangedFileWithoutRewritingStorage() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "colon is a Unix filename character");
        Path original = Files.writeString(directory.resolve("file:заметки%FF #+中.txt"), "original");
        var metadata = new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n",
            List.of("\r\n", "\n", "\r"));
        RecoverySnapshot expected = new RecoverySnapshot(List.of("изменено", "second", "last", ""),
            new Position(1, 2), new ViewportState(1, 0, 0, 0), new Position(0, 1), true,
            original, SaveDisposition.REPLACE_EXISTING, metadata, baseline(original), Instant.ofEpochMilli(1234));
        LegacyFixture fixture = writeFormatFive("published", expected);
        byte[] metadataBefore = Files.readAllBytes(fixture.metadata());
        byte[] textBefore = Files.readAllBytes(fixture.text());

        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            var discovered = store.discover();
            assertTrue(discovered.skipped().isEmpty());
            assertEquals(1, discovered.entries().size());
            assertArrayEquals(metadataBefore, Files.readAllBytes(fixture.metadata()));
            RecoverySnapshot restored = discovered.entries().getFirst().snapshot();
            assertEquals(expected, restored);
            assertEquals(expected, store.loadAll().entries().getFirst().snapshot());
            assertArrayEquals(metadataBefore, Files.readAllBytes(fixture.metadata()));
            assertArrayEquals(textBefore, Files.readAllBytes(fixture.text()));

            TabSession session = session();
            session.engine().restoreRecoverySnapshot(restored);
            session.engine().save();
            assertArrayEquals("\uFEFFизменено\r\nsecond\nlast\r".getBytes(StandardCharsets.UTF_16LE),
                Files.readAllBytes(original));
            assertFalse(session.state().isDirty());

            store.save("published", session.engine().captureRecoverySnapshot());
            Properties rewritten = readMetadata(fixture.metadata());
            assertEquals("8", rewritten.getProperty("format"));
            assertEquals(original.toUri().toASCIIString(), rewritten.getProperty("currentFile"));
            assertTrue(rewritten.getProperty("lineSeparators").startsWith("packed2:"));
        }
    }

    @Test
    void restoredPublishedBaselineStillRejectsExternalChanges() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        LegacyFixture fixture = writeFormatFive("conflict", snapshot(original, SaveDisposition.REPLACE_EXISTING,
            baseline(original), true));
        byte[] metadataBefore = Files.readAllBytes(fixture.metadata());
        byte[] textBefore = Files.readAllBytes(fixture.text());
        Files.writeString(original, "external edit");

        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            RecoverySnapshot restored = store.discover().entries().getFirst().snapshot();
            assertEquals(-1L, restored.fileBaseline().byteSize());
            TabSession session = session();
            session.engine().restoreRecoverySnapshot(restored);
            assertThrows(IOException.class, session.engine()::save);
            assertEquals("external edit", Files.readString(original));
            assertTrue(session.state().isDirty());
            assertArrayEquals(metadataBefore, Files.readAllBytes(fixture.metadata()));
            assertArrayEquals(textBefore, Files.readAllBytes(fixture.text()));
        }
    }

    @Test
    void publishedExpectedAbsentPathCannotOverwriteAFileCreatedAfterTheSnapshot() throws Exception {
        Path original = directory.resolve("new %FF # notes.txt");
        writeFormatFive("new-file", snapshot(original, SaveDisposition.CREATE_NEW, null, true));
        Files.writeString(original, "created by another process");

        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            RecoverySnapshot restored = store.loadAll().entries().getFirst().snapshot();
            assertEquals(original, restored.currentFile());
            assertEquals(SaveDisposition.CREATE_NEW, restored.saveDisposition());
            assertNull(restored.fileBaseline());
            TabSession session = session();
            session.engine().restoreRecoverySnapshot(restored);
            assertThrows(IOException.class, session.engine()::save);
            assertEquals("created by another process", Files.readString(original));
        }
    }

    @Test
    void anUnnamedPublishedDraftRestoresAndAnOrdinaryAutosaveWritesTheCurrentFormat() throws Exception {
        RecoverySnapshot expected = snapshot(null, SaveDisposition.CREATE_NEW, null, true);
        LegacyFixture fixture = writeFormatFive("unnamed", expected);
        byte[] before = Files.readAllBytes(fixture.metadata());
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            assertEquals(expected, store.discover().entries().getFirst().snapshot());
            assertArrayEquals(before, Files.readAllBytes(fixture.metadata()));
            store.save("unnamed", expected);
            assertEquals("8", readMetadata(fixture.metadata()).getProperty("format"));
            assertEquals(expected, store.loadAll().entries().getFirst().snapshot());
        }
    }

    @Test
    void undecodableUnixFilenameRestoresAsADirtyUnnamedDraftAndCannotSaveToItsDisplayNameNeighbor() throws Exception {
        assumeTrue(Path.of("/").isAbsolute() && !System.getProperty("os.name").startsWith("Windows"));
        Path original = Path.of(URI.create(directory.toUri().toASCIIString() + "legacy-%FF.txt"));
        Files.writeString(original, "original");
        Path neighbor = Files.writeString(directory.resolve("legacy-\uFFFD.txt"), "original");
        LegacyFixture fixture = writeFormatFive("ambiguous", snapshot(original, SaveDisposition.REPLACE_EXISTING,
            baseline(original), false));
        byte[] metadataBefore = Files.readAllBytes(fixture.metadata());
        byte[] textBefore = Files.readAllBytes(fixture.text());

        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            RecoverySnapshot restored = store.discover().entries().getFirst().snapshot();
            assertDetached(restored);
            TabSession session = session();
            session.engine().restoreRecoverySnapshot(restored);
            assertThrows(IOException.class, session.engine()::save);
            assertEquals("original", Files.readString(original));
            assertEquals("original", Files.readString(neighbor));
            assertArrayEquals(metadataBefore, Files.readAllBytes(fixture.metadata()));
            assertArrayEquals(textBefore, Files.readAllBytes(fixture.text()));

            store.save("ambiguous", restored);
            Properties rewritten = readMetadata(fixture.metadata());
            assertEquals("8", rewritten.getProperty("format"));
            assertEquals("false", rewritten.getProperty("currentFile.present"));
            assertEquals("false", rewritten.getProperty("fileBaseline.present"));
            assertDetached(store.loadAll().entries().getFirst().snapshot());
        }
    }

    @Test
    void ambiguousCurrentOrBaselinePathAloneAlsoRequiresANewSaveAs() throws Exception {
        Path current = Files.writeString(directory.resolve("current.txt"), "original");
        Path replacement = directory.resolve("literal-\uFFFD.txt");
        FileBaseline baseline = baseline(current);
        for (String key : List.of("currentFile", "fileBaseline.target")) {
            String id = key.equals("currentFile") ? "current" : "baseline";
            LegacyFixture fixture = writeFormatFive(id, snapshot(current, SaveDisposition.REPLACE_EXISTING, baseline, false));
            Properties fields = readMetadata(fixture.metadata());
            fields.setProperty(key, replacement.toString());
            writeMetadata(fixture.metadata(), fields);
        }
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            var discovered = store.discover();
            assertTrue(discovered.skipped().isEmpty());
            assertEquals(2, discovered.entries().size());
            for (var entry : discovered.entries()) assertDetached(entry.snapshot());
        }
    }

    @Test
    void malformedPublishedMetadataIsRejectedWithItsTextPreservedAndCannotBeReplacedByAutosave() throws Exception {
        Path current = Files.writeString(directory.resolve("current.txt"), "original");
        RecoverySnapshot snapshot = snapshot(current, SaveDisposition.REPLACE_EXISTING, baseline(current), true);
        List<Map<String, String>> mutations = List.of(
            Map.of("pathPlatform", "unix"), Map.of("fileBaseline.byteSize", "8"),
            Map.of("currentFile", "relative.txt"), Map.of("currentFile", "file:///tmp/document.txt"),
            Map.of("currentFile", directory.resolve("parent/../current.txt").toString()),
            Map.of("currentFile", directory.toString() + "/bad\0name"),
            Map.of("currentFile", directory.toString() + "/bad\uD800name"),
            Map.of("fileBaseline.target", "relative.txt"), Map.of("fileBaseline.sha256", "abc"),
            Map.of("lineSeparators", "packed2:1:AA=="), Map.of("lineSeparators", "LF,"),
            Map.of("lineSeparators", "LF,NOPE"), Map.of("format", "4")
        );
        for (int index = 0; index < mutations.size(); index++) {
            String id = "invalid-" + index;
            LegacyFixture fixture = writeFormatFive(id, snapshot);
            Properties fields = readMetadata(fixture.metadata());
            fields.putAll(mutations.get(index));
            writeMetadata(fixture.metadata(), fields);
            byte[] metadataBefore = Files.readAllBytes(fixture.metadata());
            byte[] textBefore = Files.readAllBytes(fixture.text());
            try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
                var discovered = store.discover();
                assertTrue(discovered.entries().isEmpty(), mutations.get(index).toString());
                assertEquals(index + 1, discovered.skipped().size());
                assertThrows(IOException.class, () -> store.save(id, snapshot));
                assertArrayEquals(metadataBefore, Files.readAllBytes(fixture.metadata()));
                assertArrayEquals(textBefore, Files.readAllBytes(fixture.text()));
            }
        }
    }

    @Test
    void aPublishedPathForAnotherPlatformProducesActionableGuidance() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        LegacyFixture fixture = writeFormatFive("foreign", snapshot(null, SaveDisposition.CREATE_NEW, null, true));
        Properties fields = readMetadata(fixture.metadata());
        fields.setProperty("currentFile.present", "true");
        fields.setProperty("currentFile", "C:\\Users\\author\\draft.txt");
        writeMetadata(fixture.metadata(), fields);
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            var discovered = store.discover();
            assertTrue(discovered.entries().isEmpty());
            assertTrue(discovered.skipped().getFirst().reason().contains("old TUInk binary on the original platform"));
            assertTrue(discovered.skipped().getFirst().reason().contains("Save As"));
        }
    }

    private static void assertDetached(RecoverySnapshot snapshot) {
        assertEquals(List.of("draft", ""), snapshot.lines());
        assertNull(snapshot.currentFile());
        assertNull(snapshot.fileBaseline());
        assertEquals(SaveDisposition.CREATE_NEW, snapshot.saveDisposition());
        assertTrue(snapshot.dirty(), "an unnamed recovered document must keep its quit/save safeguards");
    }

    private static RecoverySnapshot snapshot(Path current, SaveDisposition disposition, FileBaseline baseline, boolean dirty) {
        return new RecoverySnapshot(List.of("draft", ""), new Position(0, 2), new ViewportState(0, 0, 0, 0), null,
            dirty, current, disposition, DocumentFileMetadata.newDocument(2), baseline, Instant.ofEpochMilli(1234));
    }

    private static FileBaseline baseline(Path file) throws Exception {
        return new FileBaseline(file.toRealPath(), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
    }

    /** Exact v0.13.0 schema: display-string paths, CSV separators, no platform or baseline size. */
    private LegacyFixture writeFormatFive(String id, RecoverySnapshot snapshot) throws IOException {
        Path recovery = Files.createDirectories(directory.resolve("recovery"));
        Path metadata = recovery.resolve(id + ".meta.properties");
        Path text = recovery.resolve(id + "." + GENERATION + ".txt");
        Properties p = new Properties();
        p.setProperty("format", "5");
        p.setProperty("createdAtEpochMillis", Long.toString(snapshot.createdAt().toEpochMilli()));
        p.setProperty("textGeneration", GENERATION);
        p.setProperty("dirty", Boolean.toString(snapshot.dirty()));
        p.setProperty("currentFile.present", Boolean.toString(snapshot.currentFile() != null));
        p.setProperty("currentFile", snapshot.currentFile() == null ? "" : snapshot.currentFile().toString());
        p.setProperty("saveDisposition", snapshot.saveDisposition().name());
        p.setProperty("fileEncoding", snapshot.fileMetadata().charset().name());
        p.setProperty("bomKind", snapshot.fileMetadata().bomKind().name());
        p.setProperty("defaultLineSeparator", separator(snapshot.fileMetadata().defaultLineSeparator()));
        p.setProperty("lineSeparators", String.join(",", snapshot.fileMetadata().lineSeparators().stream()
            .map(RecoveryFormatFiveTest::separator).toList()));
        p.setProperty("fileBaseline.present", Boolean.toString(snapshot.fileBaseline() != null));
        p.setProperty("fileBaseline.target", snapshot.fileBaseline() == null ? "" : snapshot.fileBaseline().target().toString());
        p.setProperty("fileBaseline.sha256", snapshot.fileBaseline() == null ? "" : snapshot.fileBaseline().sha256());
        p.setProperty("cursor.line", Integer.toString(snapshot.cursor().line()));
        p.setProperty("cursor.col", Integer.toString(snapshot.cursor().col()));
        p.setProperty("viewport.topLine", Integer.toString(snapshot.viewport().topLine()));
        p.setProperty("viewport.topWrapSegment", Integer.toString(snapshot.viewport().topWrapSegment()));
        p.setProperty("viewport.leftColumn", Integer.toString(snapshot.viewport().leftColumn()));
        p.setProperty("viewport.leftVisualColumn", Integer.toString(snapshot.viewport().leftVisualColumn()));
        p.setProperty("selection.present", Boolean.toString(snapshot.selectionAnchor() != null));
        p.setProperty("selection.line", Integer.toString(snapshot.selectionAnchor() == null ? 0 : snapshot.selectionAnchor().line()));
        p.setProperty("selection.col", Integer.toString(snapshot.selectionAnchor() == null ? 0 : snapshot.selectionAnchor().col()));
        Files.writeString(text, String.join("\n", snapshot.lines()), StandardCharsets.UTF_8);
        writeMetadata(metadata, p);
        return new LegacyFixture(metadata, text);
    }

    private static String separator(String value) {
        return switch (value) { case "\r" -> "CR"; case "\n" -> "LF"; case "\r\n" -> "CRLF";
            default -> throw new IllegalArgumentException("Unknown fixture separator"); };
    }

    private static Properties readMetadata(Path file) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }

    private static void writeMetadata(Path file, Properties properties) throws IOException {
        try (var output = Files.newOutputStream(file)) { properties.store(output, "v0.13.0 recovery fixture"); }
    }

    private static TabSession session() {
        return TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
    }

    private record LegacyFixture(Path metadata, Path text) {}
}

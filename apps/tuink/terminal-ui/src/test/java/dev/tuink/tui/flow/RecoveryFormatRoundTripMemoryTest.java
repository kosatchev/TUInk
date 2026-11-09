package dev.tuink.tui.flow;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.core.ViewportState;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.unicode.GraphemeText;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryFormatRoundTripMemoryTest {
    @Test
    void acceptedManyLineAndLegacyEncodedDocumentsRoundTripTheirOwnRecoveryStorage(@TempDir Path directory)
        throws Exception {
        String classpath = String.join(File.pathSeparator, location(getClass()), location(RecoveryStore.class),
            location(RecoverySnapshot.class), location(dev.tuink.core.SearchMatcher.class), location(ImmutableText.class), location(GraphemeText.class),
            location(dev.tuink.storage.jvm.JvmDocumentStorage.class));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx1g", "-cp", classpath, RoundTripProbe.class.getName(), directory.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "recovery format round trips timed out");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("format8 and legacy6 large round trips passed"), output);
        } finally {
            child.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class RoundTripProbe {
        private static final int LINE_COUNT = 2_800_001;
        private static final int LEGACY_CHARACTERS = 34_000_000;
        private static final Instant CREATED = Instant.parse("2026-10-18T00:00:00Z");

        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            manyLines(directory.resolve("uniform"), false);
            System.gc();
            manyLines(directory.resolve("mixed"), true);
            System.gc();
            utf8Expansion(directory.resolve("legacy-encoding"));
            System.out.println("format8 and legacy6 large round trips passed");
        }

        private static void manyLines(Path directory, boolean mixed) throws Exception {
            Files.createDirectories(directory);
            Path source = directory.resolve("source.txt");
            List<String> separators = new AbstractList<>() {
                @Override public int size() { return LINE_COUNT - 1; }
                @Override public String get(int index) { return separator(index, mixed); }
            };
            try (var output = new BufferedOutputStream(Files.newOutputStream(source))) {
                for (int index = 0; index < LINE_COUNT; index++) {
                    if (index > 0) output.write(separators.get(index - 1).getBytes(StandardCharsets.UTF_8));
                    if (mixed && index % 3 == 0) output.write('v');
                }
            }
            FileBaseline baseline = new FileBaseline(source, digest(source));
            List<String> lines = new AbstractList<>() {
                @Override public int size() { return LINE_COUNT; }
                @Override public String get(int index) {
                    return index == LINE_COUNT - 1 ? "dirty tail" : mixed && index % 3 == 0 ? "v" : "";
                }
            };
            var metadata = new DocumentFileMetadata(StandardCharsets.UTF_8, BomKind.NONE, "\n", separators);
            RecoverySnapshot snapshot = new RecoverySnapshot(lines, new Position(LINE_COUNT - 1, 10),
                new ViewportState(LINE_COUNT - 2, 0, 0, 0), new Position(0, 0), true, JvmDocumentStorage.resource(source),
                SaveDisposition.REPLACE_EXISTING, metadata, baseline, CREATED);
            Path recovery = directory.resolve("recovery");
            try (RecoveryStore store = new RecoveryStore(recovery)) { store.save("draft", snapshot); }
            snapshot = null;
            metadata = null;
            System.gc();
            Path meta = recovery.resolve("draft.meta.properties");
            require(Files.size(meta) < 1_000_000, "uniform and mixed EOL metadata must stay compact");
            restoreManyLines(recovery, source, baseline, mixed);
            if (!mixed) {
                // Recreate the previous writer's actual oversized format-6 CSV, without a migration step.
                Properties legacy = properties(meta);
                legacy.setProperty("format", "6");
                legacy.remove("fileBaseline.byteSize");
                legacy.setProperty("lineSeparators", String.join(",", Collections.nCopies(LINE_COUNT - 1, "LF")));
                write(meta, legacy);
                legacy = null;
                System.gc();
                require(Files.size(meta) > 8 * 1024 * 1024, "fixture must reproduce the old unreadable metadata");
                restoreManyLines(recovery, source, baseline, false);
            }
        }

        private static void restoreManyLines(Path recovery, Path source, FileBaseline baseline, boolean mixed)
            throws Exception {
            Path meta = recovery.resolve("draft.meta.properties");
            String metadataDigest = digest(meta);
            try (RecoveryStore store = new RecoveryStore(recovery)) {
                var discovered = store.discover();
                require(discovered.skipped().isEmpty() && discovered.entries().size() == 1, "large draft was not offered");
                RecoverySnapshot restored = discovered.entries().getFirst().snapshot();
                require(restored.lines().size() == LINE_COUNT, "line count changed");
                require(restored.lines().getLast().equals("dirty tail"), "unsaved tail changed");
                require(JvmDocumentStorage.pathOf(restored.currentResource()).equals(source) && JvmDocumentStorage.baselineOf(restored.fileBaseline()).equals(baseline), "identity changed");
                require(restored.saveDisposition() == SaveDisposition.REPLACE_EXISTING && restored.dirty(), "save intent changed");
                for (int index = 0; index < LINE_COUNT - 1; index++) {
                    require(restored.fileMetadata().lineSeparators().get(index).equals(separator(index, mixed)), "EOL changed");
                }
                TextModel model = new TextModel(new SimpleLineBuffer());
                EditorState state = new EditorState();
                EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
                engine.restoreRecoverySnapshot(restored);
                require(model.lineCount() == LINE_COUNT && model.getLine(LINE_COUNT - 1).equals("dirty tail"), "restore lost text");
                require(state.getCursor().equals(new Position(LINE_COUNT - 1, 10)), "cursor changed");
                require(state.viewport().topLine() == LINE_COUNT - 2 && state.getSelectionAnchor().equals(new Position(0, 0)),
                    "recovered view changed");
                require(digest(meta).equals(metadataDigest), "discovery/restoration silently rewrote legacy metadata");
                require(digest(source).equals(baseline.sha256()), "restoration changed the saved file");
            }
            System.out.println("many-line round trip passed mixed=" + mixed + " metadata=" + Files.size(meta));
        }

        private static void utf8Expansion(Path directory) throws Exception {
            Files.createDirectories(directory);
            Path source = directory.resolve("windows1251.txt");
            byte[] block = new byte[8192];
            java.util.Arrays.fill(block, (byte) 0xc0);
            try (var output = new BufferedOutputStream(Files.newOutputStream(source))) {
                for (int left = LEGACY_CHARACTERS; left > 0;) {
                    int count = Math.min(left, block.length);
                    output.write(block, 0, count);
                    left -= count;
                }
            }
            FileBaseline baseline = new FileBaseline(source, digest(source));
            var metadata = DocumentFileMetadata.canonical(Charset.forName("windows-1251"), BomKind.NONE, "\r\n", 1);
            RecoverySnapshot snapshot = new RecoverySnapshot(List.of("Б".repeat(LEGACY_CHARACTERS)),
                new Position(0, LEGACY_CHARACTERS), null, null, true, JvmDocumentStorage.resource(source), SaveDisposition.REPLACE_EXISTING,
                metadata, baseline, CREATED);
            Path recovery = directory.resolve("recovery");
            try (RecoveryStore store = new RecoveryStore(recovery)) { store.save("draft", snapshot); }
            snapshot = null;
            System.gc();
            Path meta = recovery.resolve("draft.meta.properties");
            for (boolean legacy : new boolean[] {false, true}) {
                if (legacy) {
                    Properties properties = properties(meta);
                    properties.setProperty("format", "6");
                    properties.remove("fileBaseline.byteSize");
                    properties.setProperty("lineSeparators", "");
                    write(meta, properties);
                }
                restoreExpanded(recovery, source, baseline, metadata);
                System.gc();
            }
        }

        private static void restoreExpanded(Path recovery, Path source, FileBaseline baseline, DocumentFileMetadata expected)
            throws Exception {
            String metadataDigest = digest(recovery.resolve("draft.meta.properties"));
            try (RecoveryStore store = new RecoveryStore(recovery)) {
                var discovered = store.discover();
                require(discovered.skipped().isEmpty() && discovered.entries().size() == 1, "legacy draft was not offered");
                var restored = discovered.entries().getFirst().snapshot();
                String text = restored.lines().getFirst();
                require(restored.lines().size() == 1 && text.length() == LEGACY_CHARACTERS, "expanded text was truncated");
                for (int index = 0; index < text.length(); index++) require(text.charAt(index) == 'Б', "dirty Unicode text changed");
                require(restored.fileMetadata().equals(expected), "legacy encoding metadata changed");
                require(JvmDocumentStorage.pathOf(restored.currentResource()).equals(source) && JvmDocumentStorage.baselineOf(restored.fileBaseline()).equals(baseline), "legacy identity changed");
                TextModel model = new TextModel(new SimpleLineBuffer());
                EditorState state = new EditorState();
                EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
                engine.restoreRecoverySnapshot(restored);
                require(model.getLine(0).equals(text) && state.isDirty(), "expanded text could not restore");
                require(digest(source).equals(baseline.sha256()), "saved legacy bytes changed");
                require(digest(recovery.resolve("draft.meta.properties")).equals(metadataDigest), "metadata was rewritten");
            }
            System.out.println("UTF8 expansion round trip passed source=" + Files.size(source));
        }

        private static String separator(int index, boolean mixed) {
            return !mixed ? "\n" : switch (index % 3) { case 0 -> "\n"; case 1 -> "\r\n"; default -> "\r"; };
        }

        private static Properties properties(Path path) throws IOException {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(path)) { properties.load(input); }
            return properties;
        }

        private static void write(Path path, Properties properties) throws IOException {
            try (var output = Files.newOutputStream(path)) { properties.store(output, "legacy compatibility fixture"); }
        }

        private static String digest(Path path) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}

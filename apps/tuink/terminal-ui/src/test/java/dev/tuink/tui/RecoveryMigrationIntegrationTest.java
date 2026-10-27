package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.core.ViewportState;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.RecoveryStoreFixtures;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryMigrationIntegrationTest {
    private static final String GENERATION = "0123456789abcdef0123456789abcdef";
    private static final String ENTRY_ID = "existing-format6-session";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingFormat6RestoresFileFidelityAndTheViewBeforeItsFirstVisibleFrame(boolean wrapped) throws Exception {
        List<String> original = new ArrayList<>(IntStream.range(0, 120)
            .mapToObj(index -> "ROW%04d|".formatted(index) + "Содержимое 中😀e\u0301 " + "abcdef ".repeat(40)).toList());
        original.add("");
        List<String> separators = IntStream.range(0, original.size() - 1)
            .mapToObj(index -> switch (index % 3) { case 0 -> "\r\n"; case 1 -> "\n"; default -> "\r"; }).toList();
        DocumentFileMetadata metadata = new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE,
            "\r\n", separators);
        byte[] diskBytes = encode(original, metadata);
        Path currentFile = directory.resolve("utf16-existing.txt");
        Files.write(currentFile, diskBytes);
        List<String> edited = new ArrayList<>(original);
        edited.set(0, "edited " + edited.getFirst());
        ViewportState expectedViewport = new ViewportState(60, wrapped ? 2 : 0, wrapped ? 0 : 40, wrapped ? 0 : 40);
        Path recoveryDirectory = writeFormat6(currentFile, edited, metadata, expectedViewport,
            SaveDisposition.REPLACE_EXISTING, diskBytes);
        byte[] originalMetadata = Files.readAllBytes(recoveryDirectory.resolve(ENTRY_ID + ".meta.properties"));
        byte[] originalRecoveryText = Files.readAllBytes(recoveryDirectory.resolve(ENTRY_ID + "." + GENERATION + ".txt"));

        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture fixture = new EditorWindowFixture(directory.resolve("window"), false)) {
            RecoveryStore.LoadResult loaded = store.loadAll();
            assertTrue(loaded.skipped().isEmpty());
            assertEquals(1, loaded.entries().size());
            fixture.ui.wrapLinesSetting = wrapped;
            fixture.ui.wrapWordsSetting = false;
            assertEquals(1, fixture.restore(loaded.entries()));
            var restored = fixture.active().tabSession();
            assertEquals(edited, restored.model().snapshotLines());
            assertEquals(edited, fixture.active().documentSession().document().snapshot().lines());
            assertEquals(restored.model().revision(), fixture.active().documentSession().document().snapshot().revision());
            assertEquals(metadata, restored.model().fileMetadata());
            assertEquals(currentFile.toAbsolutePath().normalize(), JvmDocumentStorage.pathOf(restored.state().getCurrentResource()));
            assertEquals(SaveDisposition.REPLACE_EXISTING, restored.state().getSaveDisposition());
            assertEquals(new Position(0, 3), restored.state().getCursor());
            assertEquals(new Position(0, 0), restored.state().getSelectionAnchor());
            assertEquals(expectedViewport, restored.state().viewport());
            assertTrue(restored.state().isDirty());
            assertFalse(restored.engine().undo(), "recovery does not invent persisted undo history");
            assertFalse(restored.engine().redo());

            fixture.resize(2, 1);
            assertEquals(expectedViewport, restored.state().viewport(), "a hidden intermediate view must retain its recovery position");
            fixture.resize(24, 80);
            assertEquals(expectedViewport, restored.state().viewport(), "first visible frame must retain recovered manual pan");
            assertEquals(new Position(0, 3), restored.state().getCursor());
            assertSame(restored, fixture.active().tabSession());

            assertNull(fixture.recoveryManager.finishSession(List.of(fixture.active().documentSession()), false));
            assertEquals(Set.of(ENTRY_ID), fixture.recoverySnapshots.keySet(), "the adopted entry must keep its stable session identity");
            RecoverySnapshot captured = fixture.recoverySnapshots.get(ENTRY_ID);
            assertEquals(metadata, captured.fileMetadata());
            assertEquals(expectedViewport, captured.viewport());
            assertEquals(JvmDocumentStorage.baselineOf(loaded.entries().getFirst().snapshot().fileBaseline()), JvmDocumentStorage.baselineOf(captured.fileBaseline()));

            restored.engine().save();
            assertArrayEquals(encode(edited, metadata), Files.readAllBytes(currentFile));
            assertFalse(restored.state().isDirty());
            assertEquals(metadata, restored.model().fileMetadata());
            assertArrayEquals(originalMetadata, Files.readAllBytes(recoveryDirectory.resolve(ENTRY_ID + ".meta.properties")));
            assertArrayEquals(originalRecoveryText, Files.readAllBytes(recoveryDirectory.resolve(ENTRY_ID + "." + GENERATION + ".txt")));
        }
    }

    @Test
    void existingFormat6CreateNewIntentSurvivesRestorationAndRejectsAFileThatAppearedLater() throws Exception {
        Path target = directory.resolve("expected-absent.txt");
        DocumentFileMetadata metadata = new DocumentFileMetadata(Charset.forName("windows-1251"), BomKind.NONE,
            "\r\n", List.of("\r\n", "\n"));
        List<String> edited = List.of("черновик", "вторая строка", "");
        Path recoveryDirectory = writeFormat6(target, edited, metadata, new ViewportState(0, 0, 0, 0),
            SaveDisposition.CREATE_NEW, null);
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture fixture = new EditorWindowFixture(directory.resolve("window"), false)) {
            var loaded = store.loadAll();
            assertTrue(loaded.skipped().isEmpty());
            assertEquals(1, fixture.restore(loaded.entries()));
            fixture.frame();
            var restored = fixture.active().tabSession();
            assertEquals(edited, restored.model().snapshotLines());
            assertEquals(edited, fixture.active().documentSession().document().snapshot().lines());
            assertEquals(metadata, restored.model().fileMetadata());
            assertEquals(SaveDisposition.CREATE_NEW, restored.state().getSaveDisposition());
            assertNull(JvmDocumentStorage.baselineOf(restored.model().fileBaseline()));

            byte[] appeared = "another writer's file".getBytes(StandardCharsets.UTF_8);
            Files.write(target, appeared);
            assertThrows(IOException.class, restored.engine()::save);
            assertArrayEquals(appeared, Files.readAllBytes(target));
            assertTrue(restored.state().isDirty());
            assertEquals(SaveDisposition.CREATE_NEW, restored.state().getSaveDisposition());
            assertEquals(metadata, restored.engine().captureRecoverySnapshot().fileMetadata());
        }
    }

    /** Deliberately independent of RecoveryStore's writer: this is the persisted format-6 contract. */
    private Path writeFormat6(Path currentFile, List<String> lines, DocumentFileMetadata metadata,
                             ViewportState viewport, SaveDisposition disposition, byte[] baseline) throws Exception {
        Path recovery = directory.resolve("existing-recovery");
        Files.createDirectories(recovery);
        String separatorTokens = metadata.lineSeparators().stream().map(separator -> switch (separator) {
            case "\r\n" -> "CRLF";
            case "\r" -> "CR";
            case "\n" -> "LF";
            default -> throw new AssertionError("unknown fixture separator");
        }).collect(Collectors.joining(","));
        String properties = """
            format=6
            pathPlatform=%s
            createdAtEpochMillis=1784851200000
            textGeneration=0123456789abcdef0123456789abcdef
            dirty=true
            currentFile.present=true
            currentFile=%s
            saveDisposition=%s
            fileEncoding=%s
            bomKind=%s
            defaultLineSeparator=CRLF
            lineSeparators=%s
            fileBaseline.present=%s
            fileBaseline.target=%s
            fileBaseline.sha256=%s
            cursor.line=0
            cursor.col=3
            viewport.topLine=%d
            viewport.topWrapSegment=%d
            viewport.leftColumn=%d
            viewport.leftVisualColumn=%d
            selection.present=true
            selection.line=0
            selection.col=0
            """.formatted(java.io.File.separatorChar == '\\' ? "windows" : "unix",
                propertyPath(currentFile), disposition.name(), metadata.charset().name(), metadata.bomKind().name(),
                separatorTokens, baseline != null, baseline == null ? "" : propertyPath(currentFile.toRealPath()),
                baseline == null ? "" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline)),
                viewport.topLine(), viewport.topWrapSegment(), viewport.leftColumn(), viewport.leftVisualColumn());
        Files.writeString(recovery.resolve(ENTRY_ID + ".meta.properties"), properties, StandardCharsets.ISO_8859_1);
        Files.writeString(recovery.resolve(ENTRY_ID + "." + GENERATION + ".txt"), String.join("\n", lines), StandardCharsets.UTF_8);
        return recovery;
    }

    private static String propertyPath(Path path) {
        return path.toAbsolutePath().normalize().toUri().toASCIIString();
    }

    private static byte[] encode(List<String> lines, DocumentFileMetadata metadata) {
        StringBuilder text = new StringBuilder(lines.getFirst());
        for (int index = 1; index < lines.size(); index++) {
            text.append(metadata.lineSeparators().get(index - 1)).append(lines.get(index));
        }
        byte[] body = text.toString().getBytes(metadata.charset());
        byte[] bom = metadata.bomKind() == BomKind.UTF_16_LE ? new byte[] {(byte) 0xFF, (byte) 0xFE} : new byte[0];
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        return bytes;
    }
}

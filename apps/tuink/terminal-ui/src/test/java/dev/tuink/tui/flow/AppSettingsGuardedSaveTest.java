package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsGuardedSaveTest {
    @TempDir Path directory;

    @Test
    void unchangedSettingsSaveAndTheImmutableSnapshotBecomesStale() throws IOException {
        Path file = seed(directory.resolve("tuink.properties"));
        var loaded = AppSettingsStore.loadSnapshot(file);
        assertEquals(AppSettings.defaults(), loaded.settings());

        AppSettingsStore.saveIfUnchanged(loaded, changed());

        assertEquals(changed(), AppSettingsStore.load(file));
        assertEquals(AppSettings.defaults(), loaded.settings());
        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));
        assertOnlyFiles(directory, file);
    }

    @Test
    void absentSettingsLoadIsLazyAndTheirFirstSaveCreatesACompleteFile() throws IOException {
        Path file = directory.resolve("missing/config/tuink.properties");
        var loaded = AppSettingsStore.loadSnapshot(file);
        assertEquals(AppSettings.defaults(), loaded.settings());
        assertFalse(Files.exists(directory.resolve("missing")));

        AppSettingsStore.saveIfUnchanged(loaded, changed());

        assertEquals(changed(), AppSettingsStore.load(file));
        assertOnlyFiles(file.getParent(), file);
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void manualSemanticEditsAndCommentOnlyChangesArePreservedByteForByte() throws IOException {
        for (boolean semanticEdit : List.of(false, true)) {
            Path file = seed(directory.resolve(semanticEdit + ".properties"));
            var loaded = AppSettingsStore.loadSnapshot(file);
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            if (semanticEdit) {
                text = text.replace("ui.tabWidth=4", "ui.tabWidth=2");
            }
            byte[] manual = ("# manually saved document\n" + text).getBytes(StandardCharsets.ISO_8859_1);
            Files.write(file, manual);

            var failure = assertThrows(AppSettingsStore.SettingsConflictException.class,
                () -> AppSettingsStore.saveIfUnchanged(loaded, changed()));

            assertTrue(failure.getMessage().contains("Settings file changed"));
            assertTrue(failure.getMessage().contains("preferences from this session were not saved"));
            assertArrayEquals(manual, Files.readAllBytes(file));
        }
        assertOnlyFiles(directory, directory.resolve("false.properties"), directory.resolve("true.properties"));
    }

    @Test
    void deletionIsNotUndoneByAnAutomaticSave() throws IOException {
        Path file = seed(directory.resolve("tuink.properties"));
        var loaded = AppSettingsStore.loadSnapshot(file);
        Files.delete(file);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, changed()));

        assertFalse(Files.exists(file));
        assertOnlyFiles(directory);
    }

    @Test
    void aSequentialWriterCannotOverwriteAnotherCompletedSessionSave() throws IOException {
        Path file = seed(directory.resolve("tuink.properties"));
        var first = AppSettingsStore.loadSnapshot(file);
        var second = AppSettingsStore.loadSnapshot(file);
        AppSettingsStore.saveIfUnchanged(first, changed());
        byte[] committed = Files.readAllBytes(file);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(second, AppSettings.defaults()));

        assertArrayEquals(committed, Files.readAllBytes(file));
        assertOnlyFiles(directory, file);
    }

    @Test
    void appearedFilesAndDanglingSymlinksConflictWithExpectedAbsence() throws IOException {
        Path regular = directory.resolve("regular.properties");
        var absent = AppSettingsStore.loadSnapshot(regular);
        byte[] external = "external writer's settings\n".getBytes(StandardCharsets.UTF_8);
        Files.write(regular, external);
        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(absent, changed()));
        assertArrayEquals(external, Files.readAllBytes(regular));

        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path alias = directory.resolve("alias.properties");
        var absentAlias = AppSettingsStore.loadSnapshot(alias);
        Path missing = Path.of("missing.properties");
        Files.createSymbolicLink(alias, missing);
        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(absentAlias, changed()));
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(missing, Files.readSymbolicLink(alias));
        assertFalse(Files.exists(directory.resolve(missing)));
        assertOnlyFiles(directory, regular, alias);
    }

    @Test
    void replacingAnExistingFileOrAnAbsentLeafWithADirectoryIsAConflict() throws IOException {
        for (boolean exists : List.of(false, true)) {
            Path file = directory.resolve(exists + ".properties");
            if (exists) {
                seed(file);
            }
            var loaded = AppSettingsStore.loadSnapshot(file);
            Files.deleteIfExists(file);
            Files.createDirectory(file);
            Path marker = Files.writeString(file.resolve("marker"), "preserve directory");

            assertThrows(AppSettingsStore.SettingsConflictException.class,
                () -> AppSettingsStore.saveIfUnchanged(loaded, changed()));

            assertEquals("preserve directory", Files.readString(marker));
            assertOnlyFiles(file, marker);
        }
    }

    @Test
    void unchangedFinalSymlinkIsPreservedAndAnEqualBytesRetargetIsRejected() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path first = seed(directory.resolve("first.properties"));
        Path second = directory.resolve("second.properties");
        Files.copy(first, second);
        Path alias = Files.createSymbolicLink(directory.resolve("alias.properties"), first.getFileName());
        var loaded = AppSettingsStore.loadSnapshot(alias);
        AppSettingsStore.saveIfUnchanged(loaded, changed());
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(first.getFileName(), Files.readSymbolicLink(alias));
        assertEquals(changed(), AppSettingsStore.load(alias));

        loaded = AppSettingsStore.loadSnapshot(alias);
        byte[] bytes = Files.readAllBytes(first);
        Files.write(second, bytes);
        Files.delete(alias);
        Files.createSymbolicLink(alias, second.getFileName());
        var beforeRetarget = loaded;

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(beforeRetarget, AppSettings.defaults()));

        assertEquals(second.getFileName(), Files.readSymbolicLink(alias));
        assertArrayEquals(bytes, Files.readAllBytes(first));
        assertArrayEquals(bytes, Files.readAllBytes(second));
        assertOnlyFiles(directory, first, second, alias);
    }

    @Test
    void parentRetargetIsRejectedForExistingAndInitiallyAbsentFiles() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        for (boolean exists : List.of(false, true)) {
            Path first = Files.createDirectory(directory.resolve("first-" + exists));
            Path second = Files.createDirectory(directory.resolve("second-" + exists));
            Path physical = first.resolve("tuink.properties");
            if (exists) {
                seed(physical);
                Files.copy(physical, second.resolve("tuink.properties"));
            }
            Path parent = Files.createSymbolicLink(directory.resolve("parent-" + exists), first.getFileName());
            var loaded = AppSettingsStore.loadSnapshot(parent.resolve("tuink.properties"));
            Files.delete(parent);
            Files.createSymbolicLink(parent, second.getFileName());

            assertThrows(AppSettingsStore.SettingsConflictException.class,
                () -> AppSettingsStore.saveIfUnchanged(loaded, changed()));

            if (exists) {
                assertEquals(AppSettings.defaults(), AppSettingsStore.load(physical));
                assertArrayEquals(Files.readAllBytes(physical), Files.readAllBytes(second.resolve("tuink.properties")));
            } else {
                assertOnlyFiles(first);
                assertOnlyFiles(second);
            }
        }
    }

    @Test
    void aNewAliasInsideInitiallyMissingParentsCannotRedirectTheFirstSave() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path requested = directory.resolve("missing/config/tuink.properties");
        var loaded = AppSettingsStore.loadSnapshot(requested);
        Path alternate = Files.createDirectory(directory.resolve("alternate"));
        Files.createSymbolicLink(directory.resolve("missing"), alternate.getFileName());

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, changed()));

        assertFalse(Files.exists(alternate.resolve("config")));
    }

    private static Path seed(Path file) throws IOException {
        AppSettingsStore.save(file, AppSettings.defaults());
        return file;
    }

    private static AppSettings changed() {
        return new AppSettings(false, true, false, true, false, false, false, false, false,
            false, false, false, false, false, false, false, false,
            false,
            GutterDiffMode.STABLE, 2, TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.LIGHT, false, false);
    }

    private static void assertOnlyFiles(Path directory, Path... expected) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(java.util.Set.of(expected), java.util.Set.copyOf(files.toList()));
        }
    }
}

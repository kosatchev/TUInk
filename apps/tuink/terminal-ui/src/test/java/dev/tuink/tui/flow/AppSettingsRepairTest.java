package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.storage.jvm.FileSaveGuard;
import dev.tuink.tui.flow.AppSettingsStore.UnsupportedSettingsException;
import dev.tuink.tui.style.UiStyles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsRepairTest {
    @TempDir Path directory;

    @Test
    void currentFormatRepairKeepsValidValuesResetsInvalidOrMissingFieldsAndDropsUnknownKeys() throws IOException {
        Path file = directory.resolve("tuink.properties");
        AppSettingsStore.save(file, customized());
        String invalid = read(file).replace("ui.wrapLines=true", "ui.wrapLines=maybe")
            .replace("ui.autoIndent=false\n", "")
            .replace("ui.tabWidth=2", "ui.tabWidth=3")
            .replace("ui.gutterDiffMode=stable", "ui.gutterDiffMode=unknown")
            .replace("ui.markdownCodeHighlighting=true", "ui.markdownCodeHighlighting=yes")
            + "ui.unknown=discard this value\n";
        Files.writeString(file, invalid, StandardCharsets.ISO_8859_1);
        var defaults = AppSettings.defaults();
        var expected = new AppSettings(false, defaults.wrapLines(), false, true, false, false, false, false,
            defaults.autoIndent(), false, false, false, false, false, false, true, true,
            defaults.markdownCodeHighlighting(), defaults.gutterDiffMode(), defaults.tabWidth(),
            TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.MONOCHROME, true, true);

        var unsupported = unsupported(file);
        assertEquals(expected, unsupported.repairedSettings());
        assertEquals(invalid, read(file), "Preparing a candidate must not modify the unsupported profile");
        assertThrows(UnsupportedSettingsException.class, () -> AppSettingsStore.loadSnapshot(file),
            "Ordinary loading must remain strict");

        var repaired = AppSettingsStore.repairIfUnchanged(unsupported);

        assertEquals(expected, repaired.settings());
        assertEquals(expected, AppSettingsStore.load(file));
        assertFalse(read(file).contains("ui.unknown"));
        assertFalse(read(file).contains("ui.markdownCodeHighlighting="));
        assertTrue(read(file).lines().anyMatch("ui.theme=monochrome"::equals));
        AppSettingsStore.saveIfUnchanged(repaired, defaults);
        assertEquals(defaults, AppSettingsStore.load(file), "The returned baseline authorizes a subsequent menu save");
    }

    @Test
    void unsupportedFormatsMissingMarkersAndMalformedPropertiesResetTheWholeProfile() throws IOException {
        for (String kind : List.of("format", "missing", "malformed")) {
            Path file = directory.resolve(kind + ".properties");
            AppSettingsStore.save(file, customized());
            String valid = read(file);
            String invalid = switch (kind) {
                case "format" -> valid.replace("format=2", "format=99");
                case "missing" -> valid.replace("format=2\n", "");
                default -> valid + "ui.invalid=\\u12xz\n";
            };
            Files.writeString(file, invalid, StandardCharsets.ISO_8859_1);
            var unsupported = unsupported(file);
            assertEquals(AppSettings.defaults(), unsupported.repairedSettings(), kind);
            assertEquals(invalid, read(file));

            var repaired = AppSettingsStore.repairIfUnchanged(unsupported);

            assertEquals(AppSettings.defaults(), repaired.settings());
            assertEquals(AppSettings.defaults(), AppSettingsStore.load(file));
        }
    }

    @Test
    void ordinaryIoErrorsAndOversizedFilesDoNotBecomeRepairable() throws IOException {
        Path oversized = Files.writeString(directory.resolve("oversized.properties"), "#" + "x".repeat(64 * 1024));
        byte[] original = Files.readAllBytes(oversized);
        Path nonregular = Files.createDirectory(directory.resolve("not-a-file"));
        for (Path file : List.of(oversized, nonregular)) {
            IOException failure = assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(file));
            assertFalse(failure instanceof UnsupportedSettingsException, failure.toString());
        }
        assertArrayEquals(original, Files.readAllBytes(oversized));
        assertTrue(Files.isDirectory(nonregular));
    }

    @Test
    void deletionReturnsDefaultsAndAFreshAbsentBaselineWithoutTouchingNeighbors() throws IOException {
        Path file = invalidProfile("delete.properties");
        Path neighbor = Files.writeString(directory.resolve("neighbor.properties"), "leave this file alone");

        var deleted = AppSettingsStore.deleteIfUnchanged(unsupported(file));

        assertFalse(Files.exists(file));
        assertEquals(AppSettings.defaults(), deleted.settings());
        assertEquals("leave this file alone", Files.readString(neighbor));
        AppSettingsStore.saveIfUnchanged(deleted, customized());
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
        assertEquals(customized(), AppSettingsStore.load(file));
    }

    @Test
    void repairingAFinalSymlinkPreservesTheAliasAndReturnsAUsablePhysicalBaseline() throws IOException {
        assumeSymlinks();
        Path target = invalidProfile("physical.properties");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.properties"), target.getFileName());

        var repaired = AppSettingsStore.repairIfUnchanged(unsupported(alias));

        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(target.getFileName(), Files.readSymbolicLink(alias));
        assertEquals(customized(), AppSettingsStore.load(target));
        AppSettingsStore.saveIfUnchanged(repaired, AppSettings.defaults());
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(alias));
    }

    @Test
    void deletingAFinalSymlinkRemovesOnlyTheAliasAndCanLaterCreateANewRegularProfile() throws IOException {
        assumeSymlinks();
        Path target = invalidProfile("physical.properties");
        byte[] original = Files.readAllBytes(target);
        Path alias = Files.createSymbolicLink(directory.resolve("alias.properties"), target.getFileName());

        var deleted = AppSettingsStore.deleteIfUnchanged(unsupported(alias));

        assertFalse(Files.exists(alias, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(original, Files.readAllBytes(target));
        AppSettingsStore.saveIfUnchanged(deleted, AppSettings.defaults());
        assertTrue(Files.isRegularFile(alias, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(alias));
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(alias));
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test
    void changedBytesConflictWithBothExplicitActionsAndRemainUntouched() throws IOException {
        for (boolean repair : List.of(false, true)) {
            Path file = invalidProfile("changed-" + repair + ".properties");
            var unsupported = unsupported(file);
            String external = "# another session edited this profile\n" + read(file);
            Files.writeString(file, external, StandardCharsets.ISO_8859_1);

            assertThrows(AppSettingsStore.SettingsConflictException.class, () -> decide(unsupported, repair));

            assertEquals(external, read(file));
        }
    }

    @Test
    void equalBytesRetargetingOfFinalOrParentSymlinksConflictsWithoutTouchingEitherTarget() throws IOException {
        assumeSymlinks();
        for (boolean parentLink : List.of(false, true)) for (boolean repair : List.of(false, true)) {
            Path area = Files.createDirectory(directory.resolve("retarget-" + parentLink + "-" + repair));
            Path firstDirectory = Files.createDirectory(area.resolve("first"));
            Path secondDirectory = Files.createDirectory(area.resolve("second"));
            Path first = firstDirectory.resolve("tuink.properties");
            AppSettingsStore.save(first, customized());
            Files.writeString(first, read(first) + "ui.unknown=true\n", StandardCharsets.ISO_8859_1);
            Path second = secondDirectory.resolve("tuink.properties");
            Files.copy(first, second);
            byte[] original = Files.readAllBytes(first);
            Path alias = area.resolve("alias");
            Files.createSymbolicLink(alias, area.relativize(parentLink ? firstDirectory : first));
            Path requested = parentLink ? alias.resolve("tuink.properties") : alias;
            var unsupported = unsupported(requested);
            Files.delete(alias);
            Path replacement = area.relativize(parentLink ? secondDirectory : second);
            Files.createSymbolicLink(alias, replacement);

            assertThrows(AppSettingsStore.SettingsConflictException.class, () -> decide(unsupported, repair));

            assertEquals(replacement, Files.readSymbolicLink(alias));
            assertArrayEquals(original, Files.readAllBytes(first));
            assertArrayEquals(original, Files.readAllBytes(second));
        }
    }

    @Test
    void bothActionsHonorTheExistingCooperatingWriterGuard() throws IOException {
        for (boolean repair : List.of(false, true)) {
            Path file = invalidProfile("locked-" + repair + ".properties");
            var unsupported = unsupported(file);
            byte[] original = Files.readAllBytes(file);
            try (var guard = FileSaveGuard.acquire(file)) {
                assertThrows(AppSettingsStore.SettingsConflictException.class, () -> decide(unsupported, repair));
                assertArrayEquals(original, Files.readAllBytes(file));
            }
            var accepted = decide(unsupported, repair);
            assertEquals(repair ? customized() : AppSettings.defaults(), accepted.settings());
        }
    }

    @Test
    void anOlderUnsupportedDecisionCannotDeleteAnotherCompletedRepair() throws IOException {
        Path file = invalidProfile("sequential.properties");
        var first = unsupported(file);
        var second = unsupported(file);
        AppSettingsStore.repairIfUnchanged(first);
        byte[] committed = Files.readAllBytes(file);

        assertThrows(AppSettingsStore.SettingsConflictException.class, () -> AppSettingsStore.deleteIfUnchanged(second));
        assertThrows(AppSettingsStore.SettingsConflictException.class, () -> AppSettingsStore.repairIfUnchanged(second));

        assertArrayEquals(committed, Files.readAllBytes(file));
    }

    @Test
    void deletionBaselineDoesNotOverwriteAFileThatAppearsBeforeTheNextAutosave() throws IOException {
        Path file = invalidProfile("appeared.properties");
        var deleted = AppSettingsStore.deleteIfUnchanged(unsupported(file));
        AppSettingsStore.save(file, customized());
        byte[] external = Files.readAllBytes(file);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(deleted, AppSettings.defaults()));

        assertArrayEquals(external, Files.readAllBytes(file));
    }

    private Path invalidProfile(String name) throws IOException {
        Path file = directory.resolve(name);
        AppSettingsStore.save(file, customized());
        Files.writeString(file, read(file) + "ui.unknown=true\n", StandardCharsets.ISO_8859_1);
        return file;
    }

    private static UnsupportedSettingsException unsupported(Path file) {
        return assertThrows(UnsupportedSettingsException.class, () -> AppSettingsStore.loadSnapshot(file));
    }

    private static AppSettingsStore.LoadedSettings decide(UnsupportedSettingsException unsupported, boolean repair) throws IOException {
        return repair ? AppSettingsStore.repairIfUnchanged(unsupported) : AppSettingsStore.deleteIfUnchanged(unsupported);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.ISO_8859_1);
    }

    private void assumeSymlinks() {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
    }

    private static AppSettings customized() {
        return new AppSettings(false, true, false, true, false, false, false, false, false, false, false, false,
            false, false, false, true, true, true, GutterDiffMode.STABLE, 2,
            TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.MONOCHROME, true, true);
    }
}

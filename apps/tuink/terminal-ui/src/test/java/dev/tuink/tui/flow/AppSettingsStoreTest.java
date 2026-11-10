package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.GutterDiffMode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void currentFormatRoundTrips() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettings expected = AppSettings.defaults();

        AppSettingsStore.save(path, expected);

        assertEquals(expected, AppSettingsStore.load(path));
        String stored = Files.readString(path);
        org.junit.jupiter.api.Assertions.assertTrue(stored.contains("format=2"));
    }

    @Test
    void aValidSettingsFileAtTheSizeLimitCanBeLoadedAndSaved() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String padding = "\n#" + "x".repeat(64 * 1024 - (int) Files.size(path) - 2);
        Files.writeString(path, padding, StandardCharsets.ISO_8859_1, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(64 * 1024, Files.size(path));

        var loaded = AppSettingsStore.loadSnapshot(path);
        AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults());

        assertEquals(AppSettings.defaults(), AppSettingsStore.load(path));
    }

    @Test
    void oversizedSettingsProduceAnIoErrorAndRemainUnchanged() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        String oversized = "#" + "x".repeat(64 * 1024);
        Files.writeString(path, oversized, StandardCharsets.ISO_8859_1);

        IOException failure = assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(path));

        assertTrue(failure.getMessage().contains("Maximum size is 64 KiB"));
        assertEquals(oversized, Files.readString(path, StandardCharsets.ISO_8859_1));
    }

    @Test
    void settingsLoadAndSaveResolveParentSymlinksBeforeDotDot() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path real = Files.createDirectories(tempDir.resolve("real/deep")).getParent();
        Path link = Files.createSymbolicLink(tempDir.resolve("parentlink"), real.resolve("deep"));
        Path raw = link.resolve("../settings.properties");
        Path physical = real.resolve("settings.properties");
        Path lexical = tempDir.resolve("settings.properties");
        Files.writeString(lexical, "leave this file untouched");
        AppSettings expected = AppSettings.defaults();
        AppSettingsStore.save(physical, expected);
        assertEquals(expected, AppSettingsStore.load(raw));
        Files.delete(physical);

        AppSettingsStore.save(raw, expected);

        assertTrue(Files.exists(physical));
        assertEquals(expected, AppSettingsStore.load(physical));
        assertEquals(expected, AppSettingsStore.load(raw));
        assertEquals("leave this file untouched", Files.readString(lexical));
    }

    @Test
    void savesThroughRelativeAbsoluteAndChainedSymlinksWithoutReplacingLinks() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path physical = Files.createDirectories(tempDir.resolve("physical")).resolve("settings.properties");
        Files.writeString(physical, "previous settings");
        Path relative = Files.createSymbolicLink(tempDir.resolve("relative.properties"), tempDir.relativize(physical));
        Path absolute = Files.createSymbolicLink(tempDir.resolve("absolute.properties"), physical);
        Path chain = Files.createSymbolicLink(tempDir.resolve("chain.properties"), relative.getFileName());

        for (Path alias : List.of(relative, absolute, chain)) {
            Files.writeString(physical, "previous settings for " + alias.getFileName());
            Path linkContents = Files.readSymbolicLink(alias);

            AppSettingsStore.save(alias, AppSettings.defaults());

            assertTrue(Files.isSymbolicLink(alias));
            assertEquals(linkContents, Files.readSymbolicLink(alias));
            assertEquals(AppSettings.defaults(), AppSettingsStore.load(physical));
            assertEquals(AppSettings.defaults(), AppSettingsStore.load(alias));
            assertTrue(Files.isSymbolicLink(relative));
            assertEquals(tempDir.relativize(physical), Files.readSymbolicLink(relative));
        }
    }

    @Test
    void savesChangedSettingsThroughSymlinksToLongAsciiAndUnicodeNamesWithoutTempFiles() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        AppSettings initial = AppSettings.defaults();
        AppSettings changed = new AppSettings(
            initial.showTabBar(), initial.wrapLines(), initial.wrapWords(), initial.readingMode(),
            initial.confirmOnClose(), initial.mouseMode(), initial.highlightActiveGutter(), initial.smartHomeEnd(),
            initial.autoIndent(), initial.autoPairs(), initial.autoList(), initial.visibleWhitespace(),
            initial.syntaxHighlighting(), initial.rainbowBrackets(), initial.autoSelectionHighlight(), false, false,
            initial.markdownCodeHighlighting(),
            initial.gutterDiffMode(), 2, initial.tabInsertMode(), initial.theme(), initial.rightClickClipboard(),
            initial.markdownVisualCopy()
        , true);
        int index = 0;
        for (String name : List.of("x".repeat(250), "я".repeat(125))) {
            assertEquals(250, name.getBytes(StandardCharsets.UTF_8).length);
            Path directory = Files.createDirectory(tempDir.resolve("case-" + index++));
            Path physicalDirectory = Files.createDirectory(directory.resolve("physical"));
            Path seed = physicalDirectory.resolve("seed.properties");
            AppSettingsStore.save(seed, initial);
            Path physical = Files.move(seed, physicalDirectory.resolve(name));
            Path linkContents = directory.relativize(physical);
            Path alias = Files.createSymbolicLink(directory.resolve("tuink.properties"), linkContents);
            assertEquals(initial, AppSettingsStore.load(alias));

            AppSettingsStore.save(alias, changed);

            assertTrue(Files.isSymbolicLink(alias));
            assertEquals(linkContents, Files.readSymbolicLink(alias));
            assertEquals(changed, AppSettingsStore.load(alias));
            assertEquals(changed, AppSettingsStore.load(physical));
            try (var files = Files.list(physicalDirectory)) {
                assertEquals(List.of(physical), files.toList());
            }
            try (var files = Files.list(directory)) {
                assertEquals(2, files.count());
            }
        }
    }

    @Test
    void finalSymlinkTargetResolvesItsOwnParentSymlinkBeforeDotDot() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path real = Files.createDirectories(tempDir.resolve("real/deep")).getParent();
        Files.createSymbolicLink(tempDir.resolve("parentlink"), real.resolve("deep"));
        Path physical = Files.writeString(real.resolve("settings.properties"), "old physical settings");
        Path lexical = Files.writeString(tempDir.resolve("settings.properties"), "leave this file untouched");
        Path linkContents = Path.of("parentlink", "..", "settings.properties");
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias.properties"), linkContents);

        AppSettingsStore.save(alias, AppSettings.defaults());

        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(linkContents, Files.readSymbolicLink(alias));
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(alias));
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(physical));
        assertEquals("leave this file untouched", Files.readString(lexical));
    }

    @Test
    void danglingSymlinksAreRejectedWithoutReplacingLinksOrCreatingTheirTargets() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path missing = tempDir.resolve("missing.properties");
        Path link = Files.createSymbolicLink(tempDir.resolve("alias.properties"), missing.getFileName());
        Path chain = Files.createSymbolicLink(tempDir.resolve("chain.properties"), link);

        for (Path alias : List.of(link, chain)) {
            Path linkContents = Files.readSymbolicLink(alias);
            IOException loadFailure = assertThrows(IOException.class, () -> AppSettingsStore.load(alias));
            IOException saveFailure = assertThrows(
                IOException.class, () -> AppSettingsStore.save(alias, AppSettings.defaults())
            );

            assertTrue(loadFailure.getMessage().contains("symlink target does not exist"));
            assertTrue(saveFailure.getMessage().contains("symlink target does not exist"));
            assertTrue(Files.isSymbolicLink(alias));
            assertEquals(linkContents, Files.readSymbolicLink(alias));
            assertFalse(Files.exists(missing));
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void directoriesAndTheirSymlinksAreRejectedWithoutChangingThem() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path directory = Files.createDirectory(tempDir.resolve("settings"));
        Path marker = Files.writeString(directory.resolve("marker"), "keep directory contents");
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias.properties"), directory.getFileName());

        for (Path target : List.of(directory, alias)) {
            IOException loadFailure = assertThrows(IOException.class, () -> AppSettingsStore.load(target));
            IOException saveFailure = assertThrows(
                IOException.class, () -> AppSettingsStore.save(target, AppSettings.defaults())
            );

            assertTrue(loadFailure.getMessage().contains("must be a regular file"));
            assertTrue(saveFailure.getMessage().contains("must be a regular file"));
            assertTrue(Files.isDirectory(directory));
            assertTrue(Files.isSymbolicLink(alias));
            assertEquals("keep directory contents", Files.readString(marker));
        }
    }

    @Test
    void fifosAndTheirSymlinksAreRejectedBeforeOpeningAStream() throws Exception {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path fifo = tempDir.resolve("settings.fifo");
        Process mkfifo;
        try {
            mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "mkfifo is unavailable: " + unavailable.getMessage());
            return;
        }
        try {
            assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS), "mkfifo did not terminate");
            String output = new String(mkfifo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assumeTrue(mkfifo.exitValue() == 0, "FIFO creation is unsupported: " + output);
        } finally {
            mkfifo.destroyForcibly();
        }
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias.properties"), fifo.getFileName());
        String classpath = String.join(File.pathSeparator,
            classpathLocation(AppSettingsStoreTest.class),
            classpathLocation(AppSettingsStore.class),
            classpathLocation(AppSettings.class),
            classpathLocation(dev.tuink.project.search.SearchPreferences.class),
            classpathLocation(GutterDiffMode.class),
            classpathLocation(dev.tuink.storage.jvm.JvmDocumentStorage.class),
            classpathLocation(dev.tuink.editor.document.DocumentId.class)
        );
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(
            java.toString(), "-Xmx64m", "-cp", classpath, FifoProbe.class.getName(),
            fifo.toString(), alias.toString()
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Settings FIFO load/save probe blocked");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
        assertTrue(Files.readAttributes(fifo, BasicFileAttributes.class).isOther());
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void missingFileUsesDefaults() throws IOException {
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(tempDir.resolve("missing.properties")));
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(tempDir.resolve("missing/settings.properties")));
    }

    @Test
    void rejectsMarkerlessFileWithoutChangingIt() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        String original = "ui.theme=cyan\n";
        Files.writeString(path, original);

        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
        assertEquals(original, Files.readString(path));
    }

    @Test
    void rejectsUnknownFormatAndHistoricalAlias() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String current = Files.readString(path);

        Files.writeString(path, current.replace("format=2", "format=3"));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        Files.writeString(path, current.replace("ui.tabInsertMode=auto", "ui.tabInsertMode=tab"));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
    }

    @Test
    void rejectsMissingInvalidAndUnknownProperties() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String current = Files.readString(path);

        Files.writeString(path, current.replace("ui.wrapLines=false\n", ""));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        Files.writeString(path, current.replace("ui.wrapLines=false", "ui.wrapLines=maybe"));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        Files.writeString(path, current + "ui.unknown=true\n");
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
    }

    private static String classpathLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** A separate process keeps a FIFO-open regression from blocking the test JVM. */
    public static final class FifoProbe {
        public static void main(String[] args) throws IOException {
            for (String argument : args) {
                Path path = Path.of(argument);
                try {
                    AppSettingsStore.load(path);
                    throw new AssertionError("Loaded settings from a FIFO: " + path);
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("must be a regular file")) {
                        throw expected;
                    }
                }
                try {
                    AppSettingsStore.save(path, AppSettings.defaults());
                    throw new AssertionError("Saved settings to a FIFO: " + path);
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("must be a regular file")) {
                        throw expected;
                    }
                }
            }
        }
    }
}

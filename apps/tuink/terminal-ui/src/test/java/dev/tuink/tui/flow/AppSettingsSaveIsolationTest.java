package dev.tuink.tui.flow;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.tui.style.UiStyles;
import java.io.BufferedReader;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppSettingsSaveIsolationTest {
    private static final int TIMEOUT_SECONDS = 10;

    @TempDir
    Path directory;
    @TempDir Path lockHome;
    private String previousHome;

    @BeforeEach void usePrivateLockNamespace() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", lockHome.toString());
    }

    @AfterEach void restoreUserHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @ParameterizedTest
    @ValueSource(strings = {"document:document", "document:settings", "settings:document"})
    void documentsAndSettingsShareTheSameCrossProfilePublicationGuard(String roles) throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        Path alias = Files.createSymbolicLink(directory.resolve("alias.properties"), target.getFileName());
        String[] pair = roles.split(":");
        Process first = settingsProcess(pair[0].equals("document") ? "hold-document" : "hold", target);
        try (var output = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8));
             var input = new PrintWriter(first.getOutputStream(), true, StandardCharsets.UTF_8)) {
            assertEquals("READY", readLine(output));
            Process second = settingsProcess(pair[1].equals("document") ? "document" : "save", alias);
            try {
                assertTrue(second.waitFor(TIMEOUT_SECONDS, SECONDS));
                String reported = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, second.exitValue(), reported);
                assertEquals("CONFLICT", reported.strip());
            } finally { second.destroyForcibly(); }
            assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
            input.println("release");
            assertTrue(first.waitFor(TIMEOUT_SECONDS, SECONDS));
            assertEquals("SAVED", output.readLine());
            assertEquals(0, first.exitValue());
            if (pair[0].equals("document")) assertTrue(Files.readString(target).startsWith("# document edits\n"));
            else assertEquals(settings(UiStyles.Theme.BLUE), AppSettingsStore.load(target));
            assertOnlyFiles("tuink.properties", "alias.properties");
        } finally { first.destroyForcibly(); }
    }

    @Test
    void aSecondProcessCannotPublishTheSameBaselineThroughASymlinkAlias() throws Exception {
        assertProcessConflictThroughAlias(false);
    }

    @Test
    void aSecondProcessCannotPublishTheSameBaselineThroughAParentDirectoryAlias() throws Exception {
        assertProcessConflictThroughAlias(true);
    }

    private void assertProcessConflictThroughAlias(boolean parentAlias) throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        Path link = Files.createSymbolicLink(directory.resolve(parentAlias ? "alias-directory" : "alias.properties"),
            parentAlias ? Path.of(".") : target.getFileName());
        Path alias = parentAlias ? link.resolve(target.getFileName()) : link;
        Process first = settingsProcess("hold", target);
        try (var firstOutput = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8));
             var firstInput = new PrintWriter(first.getOutputStream(), true, StandardCharsets.UTF_8)) {
            assertEquals("READY", readLine(firstOutput));
            // The first writer is inside its publication boundary and has not
            // changed the old settings bytes. Both processes therefore load the same baseline.
            assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
            Process second = settingsProcess("save", alias);
            try {
                assertTrue(second.waitFor(TIMEOUT_SECONDS, SECONDS), "The competing settings save blocked");
                String output = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, second.exitValue(), output);
                assertEquals("CONFLICT", output.strip());
            } finally {
                second.destroyForcibly();
            }
            assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
            firstInput.println("release");
            assertTrue(first.waitFor(TIMEOUT_SECONDS, SECONDS), "The settings writer did not finish");
            assertEquals("SAVED", firstOutput.readLine());
            assertEquals(0, first.exitValue());
            assertEquals(settings(UiStyles.Theme.BLUE), AppSettingsStore.load(target));
            assertTrue(Files.isSymbolicLink(link));

            Path sidecar = saveLock(target);
            Object identity = Files.readAttributes(sidecar, BasicFileAttributes.class).fileKey();
            AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(alias), settings(UiStyles.Theme.MONOCHROME));
            assertEquals(identity, Files.readAttributes(sidecar, BasicFileAttributes.class).fileKey(),
                "Releasing a guard must retain its inode across settings replacement");
            assertEquals(settings(UiStyles.Theme.MONOCHROME), AppSettingsStore.load(target));
            assertOnlyFiles("tuink.properties", link.getFileName().toString());
        } finally {
            first.destroyForcibly();
        }
    }

    @Test
    void anOverlappingGuardedSaveInTheSameJvmFailsWithoutOpeningAnotherLockChannel() throws Exception {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.PAUSE_FIRST_MOVE);
        var firstBaseline = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        var secondBaseline = AppSettingsStore.loadSnapshot(target);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var first = executor.submit(() -> {
                    AppSettingsStore.saveIfUnchanged(firstBaseline, settings(UiStyles.Theme.BLUE));
                    return null;
                });
                assertTrue(fileSystem.provider.firstMoveReady.await(TIMEOUT_SECONDS, SECONDS));
                assertThrows(AppSettingsStore.SettingsConflictException.class,
                    () -> AppSettingsStore.saveIfUnchanged(secondBaseline, settings(UiStyles.Theme.MONOCHROME)));

                Path independent = directory.resolve("independent.properties");
                AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(independent),
                    settings(UiStyles.Theme.MONOCHROME));
                assertEquals(settings(UiStyles.Theme.MONOCHROME), AppSettingsStore.load(independent));

                // A failed local acquisition must not close a second descriptor
                // for the locked inode and accidentally release this process's POSIX lock.
                Process otherProcess = settingsProcess("save", target);
                try {
                    assertTrue(otherProcess.waitFor(TIMEOUT_SECONDS, SECONDS), "The competing save blocked");
                    String output = new String(otherProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(0, otherProcess.exitValue(), output);
                    assertEquals("CONFLICT", output.strip());
                } finally {
                    otherProcess.destroyForcibly();
                }
                fileSystem.provider.releaseFirstMove.countDown();
                first.get(TIMEOUT_SECONDS, SECONDS);
                assertEquals(settings(UiStyles.Theme.BLUE), AppSettingsStore.load(target));
                assertThrows(AppSettingsStore.SettingsConflictException.class,
                    () -> AppSettingsStore.saveIfUnchanged(secondBaseline, settings(UiStyles.Theme.MONOCHROME)));
            } finally {
                fileSystem.provider.releaseFirstMove.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void unsafeLockSidecarsCannotRedirectTheGuardToAnotherFile() throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        for (String kind : List.of("symlink", "hardlink", "directory")) {
            Path target = directory.resolve(kind + ".properties");
            AppSettingsStore.save(target, AppSettings.defaults());
            byte[] original = Files.readAllBytes(target);
            Path sidecar = saveLock(target);
            Files.delete(sidecar); // Replace a released protocol inode with the unsafe fixture.
            Path unrelated = Files.writeString(directory.resolve(kind + ".unrelated"), "preserve this file");
            if (kind.equals("symlink")) Files.createSymbolicLink(sidecar, unrelated);
            else if (kind.equals("hardlink")) Files.createLink(sidecar, unrelated);
            else Files.createDirectory(sidecar);

            var loaded = AppSettingsStore.loadSnapshot(target);
            assertThrows(AppSettingsStore.SettingsConflictException.class,
                () -> AppSettingsStore.saveIfUnchanged(loaded, settings(UiStyles.Theme.MONOCHROME)));

            assertArrayEquals(original, Files.readAllBytes(target));
            assertEquals("preserve this file", Files.readString(unrelated));
            assertEquals(kind.equals("symlink"), Files.isSymbolicLink(sidecar));
        }
    }

    private static AppSettings settings(UiStyles.Theme theme) {
        AppSettings d = AppSettings.defaults();
        return new AppSettings(d.showTabBar(), d.wrapLines(), d.wrapWords(), d.readingMode(), d.confirmOnClose(),
            d.mouseMode(), d.highlightActiveGutter(), d.smartHomeEnd(), d.autoIndent(), d.autoPairs(), d.autoList(),
            d.visibleWhitespace(), d.syntaxHighlighting(), d.rainbowBrackets(), d.autoSelectionHighlight(), false, false,
            d.markdownCodeHighlighting(),
            d.gutterDiffMode(), d.tabWidth(), d.tabInsertMode(), theme, d.rightClickClipboard(), d.markdownVisualCopy());
    }

    private static Path saveLock(Path target) throws IOException {
        String identity = target.getParent().toRealPath().resolve(target.getFileName()).toUri().toASCIIString();
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
            return Path.of(System.getProperty("user.home"), ".local/state/tuink/save-locks-v1", digest + ".lock");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Process settingsProcess(String mode, Path target) throws Exception {
        String classpath = String.join(File.pathSeparator, location(AppSettingsSaveIsolationTest.class),
            location(AppSettingsStore.class), location(AppSettings.class), location(GutterDiffMode.class),
            location(Class.forName("dev.tuink.unicode.GraphemeText")), location(dev.tuink.editor.document.DocumentId.class), location(dev.tuink.storage.jvm.JvmDocumentStorage.class));
        ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx96m", "-Duser.home=" + System.getProperty("user.home"),
            "-cp", classpath, SettingsWriter.class.getName(), mode, target.toString());
        process.environment().put("XDG_CONFIG_HOME", target.getParent().resolve("profile-" + mode).toString());
        return process.redirectErrorStream(true).start();
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String readLine(BufferedReader reader) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(reader::readLine).get(TIMEOUT_SECONDS, SECONDS);
        }
    }

    public static final class SettingsWriter {
        public static void main(String[] args) throws Exception {
            Path target = Path.of(args[1]);
            boolean hold = args[0].startsWith("hold");
            boolean document = args[0].contains("document");
            SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(),
                hold ? Mode.PAUSE_FIRST_MOVE : Mode.NORMAL);
            var baseline = document ? null : AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
            EditorEngine editor;
            if (document) {
                TextModel model = new TextModel(new SimpleLineBuffer());
                editor = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
                editor.setGutterDiffMode(GutterDiffMode.DISABLED);
                editor.openExisting(JvmDocumentStorage.resource(fileSystem.wrap(target)));
                editor.pasteText("# document edits\n");
            } else { editor = null; }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result = executor.submit(() -> {
                    try {
                        if (editor != null) {
                            editor.save();
                            if (editor.state().isDirty()) throw new AssertionError("Successful document save stayed dirty");
                        } else {
                            AppSettingsStore.saveIfUnchanged(baseline,
                                settings(hold ? UiStyles.Theme.BLUE : UiStyles.Theme.MONOCHROME));
                        }
                        return "SAVED";
                    } catch (IOException expected) {
                        if (!(expected instanceof AppSettingsStore.SettingsConflictException)
                            && !expected.getMessage().contains("save lock")) throw expected;
                        if (editor != null && !editor.state().isDirty()) {
                            throw new AssertionError("Rejected document save lost dirty state");
                        }
                        return "CONFLICT";
                    }
                });
                try {
                    if (hold) {
                        if (!fileSystem.provider.firstMoveReady.await(TIMEOUT_SECONDS, SECONDS)) {
                            throw new IOException("Settings writer never reached publication");
                        }
                        System.out.println("READY");
                        System.out.flush();
                        if (System.in.read() < 0) throw new IOException("Parent closed the release pipe");
                        fileSystem.provider.releaseFirstMove.countDown();
                    }
                    System.out.println(result.get(TIMEOUT_SECONDS, SECONDS));
                } finally {
                    fileSystem.provider.releaseFirstMove.countDown();
                    executor.shutdownNow();
                }
            }
        }
    }

    @Test
    void explicitSettingsSavesCannotBypassAnActivePublicationGuard() throws Exception {
        Path target = directory.resolve("tuink.properties");
        AppSettings firstSettings = AppSettings.defaults();
        AppSettings secondSettings = new AppSettings(
            false, true, false, true, false, false, false, false, false,
            false, false, false, false, false, false, false, false,
            false,
            GutterDiffMode.STABLE, 2, TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.MONOCHROME, false, false
        );
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.PAUSE_FIRST_MOVE);
        Path wrappedTarget = fileSystem.wrap(target);

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> {
                    AppSettingsStore.save(wrappedTarget, firstSettings);
                    return null;
                });
                assertTrue(fileSystem.provider.firstMoveReady.await(TIMEOUT_SECONDS, SECONDS));

                assertThrows(IOException.class, () -> AppSettingsStore.save(wrappedTarget, secondSettings));
                assertFalse(Files.exists(target), "The first publication is still paused");

                fileSystem.provider.releaseFirstMove.countDown();
                first.get(TIMEOUT_SECONDS, SECONDS);
                assertEquals(firstSettings, AppSettingsStore.load(target));
                assertOnlyFiles("tuink.properties");
            } finally {
                fileSystem.provider.releaseFirstMove.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void preservesAnExistingTemporaryFileOwnedByAnotherWriter() throws IOException {
        Path target = directory.resolve("tuink.properties");
        Path foreignTemporary = directory.resolve("tuink.properties.tmp");
        Files.writeString(foreignTemporary, "another writer's pending snapshot\n");

        AppSettingsStore.save(target, AppSettings.defaults());

        assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
        assertEquals("another writer's pending snapshot\n", Files.readString(foreignTemporary));
        assertOnlyFiles("tuink.properties", "tuink.properties.tmp");
    }

    @Test
    void failedWritePreservesCommittedSettingsAndRemovesItsOwnTemporaryFile() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettings original = AppSettings.defaults();
        AppSettingsStore.save(target, original);
        String committedBytes = Files.readString(target);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.FAIL_WRITE);

        IOException failure = assertThrows(
            IOException.class,
            () -> AppSettingsStore.save(fileSystem.wrap(target), original)
        );

        assertEquals("Injected settings write failure", failure.getMessage());
        assertEquals(committedBytes, Files.readString(target));
        assertEquals(original, AppSettingsStore.load(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void failedWriteThroughSymlinkStagesBesidePhysicalTargetAndPreservesBothFiles() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path physicalDirectory = Files.createDirectory(directory.resolve("physical"));
        Path target = physicalDirectory.resolve("settings.properties");
        AppSettings original = AppSettings.defaults();
        AppSettingsStore.save(target, original);
        String committedBytes = Files.readString(target);
        Path linkContents = directory.relativize(target);
        Path alias = Files.createSymbolicLink(directory.resolve("alias.properties"), linkContents);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.FAIL_WRITE);

        IOException failure = assertThrows(
            IOException.class, () -> AppSettingsStore.save(fileSystem.wrap(alias), original)
        );

        assertEquals("Injected settings write failure", failure.getMessage());
        assertEquals(physicalDirectory.toRealPath(), fileSystem.provider.lastWrittenPath.getParent());
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(linkContents, Files.readSymbolicLink(alias));
        assertEquals(committedBytes, Files.readString(target));
        assertEquals(original, AppSettingsStore.load(alias));
        assertOnlyFiles("physical", "alias.properties");
        try (var files = Files.list(physicalDirectory)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void guardedSnapshotParsesAndFingerprintsTheSameSingleRead() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        String initial = Files.readString(target);
        byte[] manual = initial.replace("ui.tabWidth=4", "ui.tabWidth=2").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        fileSystem.provider.afterFirstRead = () -> Files.write(target, manual);

        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));

        assertEquals(1, fileSystem.provider.readCount.get());
        assertEquals(4, loaded.settings().tabWidth());
        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));
        assertArrayEquals(manual, Files.readAllBytes(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void unsupportedSnapshotRetainsTheFingerprintOfTheSameSingleReadForBothExplicitActions() throws IOException {
        for (boolean repair : List.of(false, true)) {
            Path target = directory.resolve("unsupported-" + repair + ".properties");
            Files.writeString(target, "format=99\n");
            byte[] external = "format=98\n# another writer\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
            fileSystem.provider.afterFirstRead = () -> Files.write(target, external);

            var unsupported = assertThrows(AppSettingsStore.UnsupportedSettingsException.class,
                () -> AppSettingsStore.loadSnapshot(fileSystem.wrap(target)));

            assertEquals(1, fileSystem.provider.readCount.get());
            assertEquals(AppSettings.defaults(), unsupported.repairedSettings());
            assertThrows(AppSettingsStore.SettingsConflictException.class, () -> {
                if (repair) AppSettingsStore.repairIfUnchanged(unsupported);
                else AppSettingsStore.deleteIfUnchanged(unsupported);
            });
            assertArrayEquals(external, Files.readAllBytes(target));
        }
    }

    @Test
    void explicitDeleteRechecksChangedBytesAfterItsFirstFingerprintWhileHoldingTheGuard() throws IOException {
        Path target = directory.resolve("tuink.properties");
        Files.writeString(target, "format=99\n");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var unsupported = assertThrows(AppSettingsStore.UnsupportedSettingsException.class,
            () -> AppSettingsStore.loadSnapshot(fileSystem.wrap(target)));
        byte[] external = "format=98\n# edit during delete admission\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        fileSystem.provider.readCount.set(0);
        fileSystem.provider.afterFirstRead = () -> Files.write(target, external);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.deleteIfUnchanged(unsupported));

        assertTrue(fileSystem.provider.readCount.get() >= 2, "Deletion must revalidate after acquiring the writer guard");
        assertArrayEquals(external, Files.readAllBytes(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void explicitRepairRechecksAfterStagingAndPreservesAnotherWritersBytes() throws IOException {
        Path target = directory.resolve("tuink.properties");
        Files.writeString(target, "format=99\n");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var unsupported = assertThrows(AppSettingsStore.UnsupportedSettingsException.class,
            () -> AppSettingsStore.loadSnapshot(fileSystem.wrap(target)));
        byte[] external = "format=98\n# edit during repair staging\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        fileSystem.provider.afterWrite = () -> Files.write(target, external);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.repairIfUnchanged(unsupported));

        assertArrayEquals(external, Files.readAllBytes(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void startupReadRemainsBoundedWhenTheFileGrowsAfterTheSizeCheck() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        String grown = "#" + "x".repeat(128 * 1024);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        fileSystem.provider.beforeRead = () -> Files.writeString(target, grown);

        IOException failure = assertThrows(IOException.class,
            () -> AppSettingsStore.loadSnapshot(fileSystem.wrap(target)));

        assertTrue(failure.getMessage().contains("Maximum size is 64 KiB"));
        assertEquals(64 * 1024 + 1, fileSystem.provider.bytesRead.get());
        assertEquals(grown, Files.readString(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void fingerprintReadRemainsBoundedWhenTheFileGrowsAfterTheSizeCheck() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        String grown = "#" + "x".repeat(128 * 1024);
        fileSystem.provider.beforeRead = () -> Files.writeString(target, grown);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertEquals(64 * 1024 + 1, fileSystem.provider.bytesRead.get());
        assertEquals(grown, Files.readString(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void guardedSaveRechecksAfterStagingAndCleansUpItsOwnTemporaryFile() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        byte[] manual = ("# manual edit while staging\n" + Files.readString(target))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.afterWrite = () -> Files.write(target, manual);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertArrayEquals(manual, Files.readAllBytes(target));
        assertEquals(0, fileSystem.provider.moveCount.get());
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void guardedSaveRechecksTheAliasAfterStagingBesideItsOriginalPhysicalTarget() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path first = directory.resolve("first.properties");
        AppSettingsStore.save(first, AppSettings.defaults());
        byte[] original = Files.readAllBytes(first);
        Path second = Files.write(directory.resolve("second.properties"), original);
        Path alias = Files.createSymbolicLink(directory.resolve("alias.properties"), first.getFileName());
        SavingFileSystem fileSystem = new SavingFileSystem(first.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(alias));
        fileSystem.provider.afterWrite = () -> {
            Files.delete(alias);
            Files.createSymbolicLink(alias, second.getFileName());
        };

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertArrayEquals(original, Files.readAllBytes(first));
        assertArrayEquals(original, Files.readAllBytes(second));
        assertEquals(second.getFileName(), Files.readSymbolicLink(alias));
        assertOnlyFiles("first.properties", "second.properties", "alias.properties");
    }

    @Test
    void guardedSaveRechecksBeforeTheNonAtomicMoveFallback() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        byte[] manual = ("# manual edit at atomic-move fallback\n" + Files.readString(target))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.beforeAtomicFailure = () -> Files.write(target, manual);

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertArrayEquals(manual, Files.readAllBytes(target));
        assertEquals(1, fileSystem.provider.moveCount.get());
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void unchangedGuardedSaveCanUseTheNonAtomicMoveFallback() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.atomicMoveUnsupported = true;

        AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults());

        assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
        assertEquals(2, fileSystem.provider.moveCount.get());
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void documentSaveRechecksBeforeTheNonAtomicMoveFallback() throws IOException {
        Path target = Files.writeString(directory.resolve("document.txt"), "original\n");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorEngine editor = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        editor.setGutterDiffMode(GutterDiffMode.DISABLED);
        editor.openExisting(JvmDocumentStorage.resource(fileSystem.wrap(target)));
        editor.pasteText("draft ");
        fileSystem.provider.beforeAtomicFailure = () -> Files.writeString(target, "external writer\n");

        IOException failure = assertThrows(IOException.class, editor::save);

        assertTrue(failure.getMessage().contains("changed outside TUInk"));
        assertEquals("external writer\n", Files.readString(target));
        assertTrue(editor.state().isDirty());
        assertEquals(1, fileSystem.provider.moveCount.get());
        assertOnlyFiles("document.txt");
    }

    @Test
    void expectedAbsentSettingsCannotOverwriteAFileAppearingAtPublication() throws IOException {
        Path target = directory.resolve("tuink.properties");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.beforeCreateLink = () -> Files.writeString(target, "another writer's file\n");

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertEquals("another writer's file\n", Files.readString(target));
        assertEquals(0, fileSystem.provider.moveCount.get());
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void expectedAbsentSettingsUseExclusiveCreationWhenHardLinksAreUnavailable() throws IOException {
        Path target = directory.resolve("tuink.properties");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        fileSystem.provider.noHardLinks = true;
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));

        AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults());

        assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
        assertEquals(1, fileSystem.provider.exclusiveCreateCount.get());
        assertEquals(0, fileSystem.provider.moveCount.get());
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void exclusiveCreationFallbackCannotReplaceAFileThatAppearsBeforeOpen() throws IOException {
        Path target = directory.resolve("tuink.properties");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        fileSystem.provider.noHardLinks = true;
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.beforeExclusiveCreate = () -> Files.writeString(target, "late writer\n");

        assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertEquals("late writer\n", Files.readString(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void failedExclusiveCreationWriteKeepsTheNewPathAndReportsPossiblePartialData() throws IOException {
        Path target = directory.resolve("tuink.properties");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        fileSystem.provider.noHardLinks = true;
        fileSystem.provider.failExclusiveWrite = true;
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));

        IOException failure = assertThrows(IOException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertFalse(failure instanceof AppSettingsStore.SettingsConflictException);
        assertTrue(failure.getMessage().contains("may contain incomplete data"));
        assertTrue(Files.exists(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void failedGuardedStagingPreservesTheOriginalAndRemovesTheTemporaryFile() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        byte[] original = Files.readAllBytes(target);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.FAIL_WRITE);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));

        IOException failure = assertThrows(IOException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertEquals("Injected settings write failure", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void postCommitTempCleanupFailureDoesNotReportTheCommittedSettingsAsFailed() throws IOException {
        Path target = directory.resolve("tuink.properties");
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.failTempDelete = true;

        AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults());

        assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
        assertNotNull(fileSystem.provider.lastWrittenPath);
        Files.deleteIfExists(fileSystem.provider.lastWrittenPath);
        assertOnlyFiles("tuink.properties");
    }

    @Test
    void cleanupFailureDoesNotHideThePrimaryGuardedConflict() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettingsStore.save(target, AppSettings.defaults());
        byte[] manual = ("# preserved even when temp cleanup fails\n" + Files.readString(target))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.NORMAL);
        var loaded = AppSettingsStore.loadSnapshot(fileSystem.wrap(target));
        fileSystem.provider.afterWrite = () -> Files.write(target, manual);
        fileSystem.provider.failTempDelete = true;

        var failure = assertThrows(AppSettingsStore.SettingsConflictException.class,
            () -> AppSettingsStore.saveIfUnchanged(loaded, AppSettings.defaults()));

        assertArrayEquals(manual, Files.readAllBytes(target));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("Injected temporary-file cleanup failure", failure.getSuppressed()[0].getMessage());
        Files.deleteIfExists(fileSystem.provider.lastWrittenPath);
        assertOnlyFiles("tuink.properties");
    }

    private void assertOnlyFiles(String... expected) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(Set.of(expected), files.map(path -> path.getFileName().toString())
                .collect(Collectors.toSet()));
        }
    }

    private enum Mode {
        NORMAL,
        PAUSE_FIRST_MOVE,
        FAIL_WRITE
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** A private forwarding filesystem that intercepts only the save's I/O boundaries. */
    private static final class SavingFileSystem extends FileSystem {
        private final FileSystem delegate;
        private final SavingProvider provider;

        SavingFileSystem(FileSystem delegate, Mode mode) {
            this.delegate = delegate;
            provider = new SavingProvider(this, mode);
        }

        Path wrap(Path path) {
            return path == null ? null : (Path) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Path.class}, new SavingPath(this, path)
            );
        }

        @Override public SavingProvider provider() { return provider; }
        @Override public void close() {}
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
        @Override public String getSeparator() { return delegate.getSeparator(); }
        @Override public Iterable<Path> getRootDirectories() {
            List<Path> roots = new ArrayList<>();
            delegate.getRootDirectories().forEach(path -> roots.add(wrap(path)));
            return roots;
        }
        @Override public Iterable<FileStore> getFileStores() { return delegate.getFileStores(); }
        @Override public Set<String> supportedFileAttributeViews() { return delegate.supportedFileAttributeViews(); }
        @Override public Path getPath(String first, String... more) { return wrap(delegate.getPath(first, more)); }
        @Override public PathMatcher getPathMatcher(String syntaxAndPattern) {
            PathMatcher matcher = delegate.getPathMatcher(syntaxAndPattern);
            return path -> matcher.matches(unwrap(path));
        }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() { return delegate.getUserPrincipalLookupService(); }
        @Override public WatchService newWatchService() throws IOException { return delegate.newWatchService(); }
    }

    private static final class SavingProvider extends FileSystemProvider {
        private final SavingFileSystem fileSystem;
        private final FileSystemProvider delegate;
        private final Mode mode;
        private final AtomicInteger moveCount = new AtomicInteger();
        private final CountDownLatch firstMoveReady = new CountDownLatch(1);
        private final CountDownLatch releaseFirstMove = new CountDownLatch(1);
        private volatile Path lastWrittenPath;
        private final AtomicInteger readCount = new AtomicInteger();
        private final AtomicInteger bytesRead = new AtomicInteger();
        private final AtomicInteger exclusiveCreateCount = new AtomicInteger();
        private IoAction beforeRead;
        private IoAction afterFirstRead;
        private IoAction afterWrite;
        private IoAction beforeAtomicFailure;
        private IoAction beforeCreateLink;
        private IoAction beforeExclusiveCreate;
        private boolean noHardLinks;
        private boolean atomicMoveUnsupported;
        private boolean failExclusiveWrite;
        private boolean failTempDelete;

        SavingProvider(SavingFileSystem fileSystem, Mode mode) {
            this.fileSystem = fileSystem;
            delegate = fileSystem.delegate.provider();
            this.mode = mode;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            int count = moveCount.incrementAndGet();
            if ((atomicMoveUnsupported || beforeAtomicFailure != null)
                && List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                if (beforeAtomicFailure != null) { beforeAtomicFailure.run(); }
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "Injected fallback");
            }
            if (mode == Mode.PAUSE_FIRST_MOVE && count == 1) {
                firstMoveReady.countDown();
                try {
                    if (!releaseFirstMove.await(TIMEOUT_SECONDS, SECONDS)) {
                        throw new IOException("Timed out waiting to commit the first settings snapshot");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted before committing settings", e);
                }
            }
            delegate.move(unwrap(source), unwrap(target), options);
        }

        @Override
        public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
            lastWrittenPath = unwrap(path);
            OutputStream output = delegate.newOutputStream(unwrap(path), options);
            if (mode != Mode.FAIL_WRITE && afterWrite == null) {
                return output;
            }
            return new FilterOutputStream(output) {
                @Override public void write(int value) throws IOException {
                    if (mode == Mode.FAIL_WRITE) { throw failure(); }
                    out.write(value);
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (mode == Mode.FAIL_WRITE) { throw failure(); }
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    super.close();
                    if (afterWrite != null) { afterWrite.run(); }
                }
                private IOException failure() { return new IOException("Injected settings write failure"); }
            };
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            if (beforeCreateLink != null) { beforeCreateLink.run(); }
            if (noHardLinks) { throw new UnsupportedOperationException("Injected unsupported hard links"); }
            delegate.createLink(unwrap(link), unwrap(existing));
        }

        @Override public String getScheme() { return delegate.getScheme(); }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { return fileSystem; }
        @Override public Path getPath(URI uri) { return fileSystem.wrap(delegate.getPath(uri)); }
        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            boolean exclusive = options.contains(StandardOpenOption.CREATE_NEW)
                && !path.getFileName().toString().startsWith(".tuink-settings.");
            if (exclusive) {
                exclusiveCreateCount.incrementAndGet();
                if (beforeExclusiveCreate != null) { beforeExclusiveCreate.run(); }
            }
            boolean observedRead = !options.contains(StandardOpenOption.WRITE) && beforeRead != null;
            if (observedRead) {
                beforeRead.run();
            }
            SeekableByteChannel channel = delegate.newByteChannel(unwrap(path), options, attrs);
            boolean firstRead = !options.contains(StandardOpenOption.WRITE) && readCount.incrementAndGet() == 1;
            if ((!firstRead || afterFirstRead == null) && (!exclusive || !failExclusiveWrite) && !observedRead) {
                return channel;
            }
            return new SeekableByteChannel() {
                @Override public int read(ByteBuffer bytes) throws IOException {
                    int count = channel.read(bytes);
                    if (observedRead && count > 0) { bytesRead.addAndGet(count); }
                    return count;
                }
                @Override public int write(ByteBuffer bytes) throws IOException {
                    if (exclusive && failExclusiveWrite) { throw new IOException("Injected exclusive write failure"); }
                    return channel.write(bytes);
                }
                @Override public long position() throws IOException { return channel.position(); }
                @Override public SeekableByteChannel position(long offset) throws IOException { channel.position(offset); return this; }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public SeekableByteChannel truncate(long size) throws IOException { channel.truncate(size); return this; }
                @Override public boolean isOpen() { return channel.isOpen(); }
                @Override public void close() throws IOException {
                    channel.close();
                    if (firstRead && afterFirstRead != null) { afterFirstRead.run(); }
                }
            };
        }
        @Override public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException { delegate.createDirectory(unwrap(path), attrs); }
        @Override public void delete(Path path) throws IOException {
            if (failTempDelete && path.getFileName().toString().startsWith(".tuink-settings.")) {
                throw new IOException("Injected temporary-file cleanup failure");
            }
            delegate.delete(unwrap(path));
        }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException { delegate.copy(unwrap(source), unwrap(target), options); }
        @Override public boolean isSameFile(Path first, Path second) throws IOException { return delegate.isSameFile(unwrap(first), unwrap(second)); }
        @Override public boolean isHidden(Path path) throws IOException { return delegate.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return delegate.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { delegate.checkAccess(unwrap(path), modes); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { return delegate.getFileAttributeView(unwrap(path), type, options); }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException { return delegate.readAttributes(unwrap(path), type, options); }
        @Override public Map<String, Object> readAttributes(Path path, String attrs, LinkOption... options) throws IOException { return delegate.readAttributes(unwrap(path), attrs, options); }
        @Override public void setAttribute(Path path, String attr, Object value, LinkOption... options) throws IOException { delegate.setAttribute(unwrap(path), attr, value, options); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            DirectoryStream<Path> stream = delegate.newDirectoryStream(unwrap(dir), path -> filter.accept(fileSystem.wrap(path)));
            return new DirectoryStream<>() {
                @Override public void close() throws IOException { stream.close(); }
                @Override public Iterator<Path> iterator() { return wrapIterator(fileSystem, stream.iterator()); }
            };
        }
    }

    private record SavingPath(SavingFileSystem fileSystem, Path delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getName().equals("getFileSystem")) {
                return fileSystem;
            }
            if (method.getName().equals("equals")) {
                return arguments[0] instanceof Path other
                    && Proxy.isProxyClass(other.getClass())
                    && Proxy.getInvocationHandler(other) instanceof SavingPath wrapped
                    && fileSystem == wrapped.fileSystem
                    && delegate.equals(wrapped.delegate);
            }
            if (method.getName().equals("hashCode")) {
                return 31 * System.identityHashCode(fileSystem) + delegate.hashCode();
            }
            if (method.getName().equals("iterator")) {
                return wrapIterator(fileSystem, delegate.iterator());
            }
            Object[] unwrapped = arguments == null ? null : arguments.clone();
            if (unwrapped != null) {
                for (int index = 0; index < unwrapped.length; index++) {
                    if (unwrapped[index] instanceof Path path) {
                        unwrapped[index] = unwrap(path);
                    }
                }
            }
            try {
                Object result = method.invoke(delegate, unwrapped);
                return result instanceof Path path ? fileSystem.wrap(path) : result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static Iterator<Path> wrapIterator(SavingFileSystem fileSystem, Iterator<Path> iterator) {
        return new Iterator<>() {
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public Path next() { return fileSystem.wrap(iterator.next()); }
        };
    }

    private static Path unwrap(Path path) {
        if (Proxy.isProxyClass(path.getClass()) && Proxy.getInvocationHandler(path) instanceof SavingPath wrapped) {
            return wrapped.delegate;
        }
        return path;
    }
}

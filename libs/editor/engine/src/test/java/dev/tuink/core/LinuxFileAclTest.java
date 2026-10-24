package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.document.ImmutableText;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LinuxFileAclTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesNamedEntriesAndRestrictedGroupPermissionsThroughASymlink() throws Exception {
        Path target = fileWithNamedAcl();
        byte[] before = LinuxAclFixtures.accessAcl(target);
        assertArrayEquals(LinuxAclFixtures.namedAcl(target, 4, 4), before);
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias.txt"), target.getFileName());

        saveEdited(alias);

        assertEquals("edited original", Files.readString(target));
        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void removesInheritedAclWhenTheOriginalOnlyHadBasePermissions() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("inherited"));
        LinuxAclFixtures.setDefaultAcl(directory, LinuxAclFixtures.namedAcl(directory, 4, 4));
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        assertNotNull(LinuxAclFixtures.accessAcl(target), "The directory must supply an inherited ACL");
        LinuxAclFixtures.removeAccessAcl(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
        assertNull(LinuxAclFixtures.accessAcl(target));

        saveEdited(target);

        assertNull(LinuxAclFixtures.accessAcl(target));
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(target));
        assertEquals("edited original", Files.readString(target));
    }

    @Test
    void preservesAnExplicitlyRestrictedMask() throws Exception {
        Path target = fileWithNamedAcl();
        LinuxAclFixtures.setAccessAcl(target, LinuxAclFixtures.namedAcl(target, 4, 0));
        byte[] before = LinuxAclFixtures.accessAcl(target);

        saveEdited(target);

        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
    }

    @Test
    void preservesSpecialModeBitsWithoutChangingNamedEntriesOrTheAclMask() throws Exception {
        Path target = fileWithNamedAcl();
        LinuxAclFixtures.setAccessAcl(target, LinuxAclFixtures.namedAcl(target, 4, 0));
        int mode = ((int) Files.getAttribute(target, "unix:mode") & 0777) | 07000;
        Files.setAttribute(target, "unix:mode", mode);
        assumeTrue(((int) Files.getAttribute(target, "unix:mode") & 07777) == mode);
        byte[] before = LinuxAclFixtures.accessAcl(target);

        saveEdited(target);

        assertEquals(mode, (int) Files.getAttribute(target, "unix:mode") & 07777);
        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
        assertEquals("edited original", Files.readString(target));
    }

    @Test
    void detectsChangedBasePermissionsEvenWhenNeitherFileHasAnExtendedAcl() throws Exception {
        Path target = Files.writeString(tempDir.resolve("document.txt"), "original");
        LinuxAclFixtures.removeAccessAcl(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
        LinuxFileAcl original = LinuxFileAcl.read(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));

        assertThrows(IOException.class, () -> original.verify(target));
    }

    @Test
    void savesWithNamedAclWhenPathContainsNoExternalPrograms() throws Exception {
        Path target = fileWithNamedAcl();
        byte[] before = LinuxAclFixtures.accessAcl(target);

        saveProbe(target, "--enable-native-access=ALL-UNNAMED", SaveProbe.class);

        assertEquals("edited original", Files.readString(target));
        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
    }

    @Test
    void deniedNativeAccessLeavesTheOriginalAndDocumentSaveStateIntact() throws Exception {
        Path target = fileWithNamedAcl();
        byte[] before = LinuxAclFixtures.accessAcl(target);

        saveProbe(target, "--illegal-native-access=deny", NativeAccessDeniedProbe.class);

        assertEquals("original", Files.readString(target));
        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
    }

    private void saveProbe(Path target, String nativeAccessOption, Class<?> probe) throws Exception {
        Path emptyPath = Files.createDirectory(tempDir.resolve("empty-path"));
        String classpath = String.join(File.pathSeparator,
            location(LinuxFileAclTest.class), location(TextModel.class), location(GraphemeText.class),
            location(ImmutableText.class), LinuxXattrTestRuntime.classpath());
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            nativeAccessOption, "-Xmx64m", "-cp", classpath,
            probe.getName(), target.toString()
        ).redirectErrorStream(true);
        builder.environment().put("PATH", emptyPath.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "ACL probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private Path fileWithNamedAcl() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path target = Files.writeString(tempDir.resolve("document.txt"), "original");
        LinuxAclFixtures.setAccessAcl(target, LinuxAclFixtures.namedAcl(target, 4, 4));
        return target;
    }

    private static void saveEdited(Path target) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(target);
        model.insertText(new Position(0, 0), "edited ");
        model.saveIfUnchanged(target, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class SaveProbe {
        public static void main(String[] args) throws IOException {
            Path target = Path.of(args[0]);
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(target);
            model.insertText(new Position(0, 0), "edited ");
            model.saveIfUnchanged(target, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);
            if (!Files.readString(target).equals("edited original")) {
                throw new AssertionError("Saved content differs from the edited document");
            }
            model.fileBaseline().requireUnchanged(target);
        }
    }

    public static final class NativeAccessDeniedProbe {
        public static void main(String[] args) throws IOException {
            Path target = Path.of(args[0]);
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(target);
            FileBaseline baseline = model.fileBaseline();
            DocumentFileMetadata metadata = model.fileMetadata();
            model.insertText(new Position(0, 0), "edited ");
            long revision = model.revision();
            try {
                model.saveIfUnchanged(target, metadata, SaveDisposition.REPLACE_EXISTING);
                throw new AssertionError("Save unexpectedly succeeded with native access denied");
            } catch (IOException expected) {
                boolean nativeAccessDenied = false;
                for (Throwable cause = expected; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IllegalCallerException) {
                        nativeAccessDenied = true;
                    }
                }
                if (!nativeAccessDenied) {
                    throw new AssertionError("Save did not fail because native access was denied", expected);
                }
            }
            if (!baseline.equals(model.fileBaseline()) || model.revision() != revision
                || !metadata.equals(model.fileMetadata())
                || !model.getLine(0).equals("edited original")
                || !Files.readString(target).equals("original")) {
                throw new AssertionError("Denied save changed the original or the document's saved state");
            }
        }
    }
}

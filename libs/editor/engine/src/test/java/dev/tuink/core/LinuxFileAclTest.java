package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        String before = acl(target);
        assertTrue(before.contains("group::---"));
        assertTrue(before.contains("mask::r--"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias.txt"), target.getFileName());

        saveEdited(alias);

        assertEquals("edited original", Files.readString(target));
        assertEquals(before, acl(target));
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void removesInheritedAclWhenTheOriginalOnlyHadBasePermissions() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("inherited"));
        int uid = (int) Files.getAttribute(directory, "unix:uid");
        command("setfacl", "-m", "d:u:" + uid + ":r--", directory.toString());
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        command("setfacl", "--remove-all", target.toString());
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
        String before = acl(target);
        assertEquals("user::rw-\ngroup::r--\nother::---", before);

        saveEdited(target);

        assertEquals(before, acl(target));
        assertEquals("edited original", Files.readString(target));
    }

    @Test
    void preservesAnExplicitlyRestrictedMask() throws Exception {
        Path target = fileWithNamedAcl();
        command("setfacl", "--no-mask", "-m", "m::---", target.toString());
        String before = acl(target);

        saveEdited(target);

        assertEquals(before, acl(target));
        assertTrue(acl(target).contains("mask::---"));
    }

    @Test
    void preservesSpecialModeBitsWithoutChangingNamedEntriesOrTheAclMask() throws Exception {
        Path target = fileWithNamedAcl();
        command("setfacl", "--no-mask", "-m", "m::---", target.toString());
        int mode = ((int) Files.getAttribute(target, "unix:mode") & 0777) | 07000;
        Files.setAttribute(target, "unix:mode", mode);
        assumeTrue(((int) Files.getAttribute(target, "unix:mode") & 07777) == mode);
        String before = acl(target);

        saveEdited(target);

        assertEquals(mode, (int) Files.getAttribute(target, "unix:mode") & 07777);
        assertEquals(before, acl(target));
        assertEquals("edited original", Files.readString(target));
    }

    @Test
    void keepsStagedBytesPrivateUntilTheInheritedAclIsReplaced() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("inherited"));
        int uid = (int) Files.getAttribute(directory, "unix:uid");
        command("setfacl", "-m", "d:u:" + uid + ":r--", directory.toString());
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        command("setfacl", "--remove-all", target.toString());
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
        String before = acl(target);
        Path tools = toolsWithGetfacl();
        Path stat = Path.of(command("/bin/sh", "-c", "command -v stat").strip());
        tool(tools, "setfacl", "#!/bin/sh\n"
            + "mode=\"$(\"" + stat + "\" -c %a -- \"$4\")\"\n"
            + "if [ \"$mode\" = 600 ]; then echo stage-private; else echo stage-exposed; fi\n"
            + "exit 1\n");

        failedSaveProbe(target, tools, "stage-private");

        assertEquals("original", Files.readString(target));
        assertEquals(before, acl(target));
    }

    @Test
    void missingToolsLeaveTheOriginalAndSaveBaselineIntact() throws Exception {
        Path target = fileWithNamedAcl();
        String before = acl(target);
        Path tools = Files.createDirectory(tempDir.resolve("no-tools"));

        failedSaveProbe(target, tools, "install the 'acl' package");

        assertEquals("original", Files.readString(target));
        assertEquals(before, acl(target));
    }

    @Test
    void unsuccessfulAclCopyLeavesTheOriginalIntact() throws Exception {
        Path target = fileWithNamedAcl();
        String before = acl(target);
        Path tools = toolsWithGetfacl();
        tool(tools, "setfacl", "#!/bin/sh\necho 'copy rejected'\nexit 1\n");

        failedSaveProbe(target, tools, "Cannot preserve file attributes");

        assertEquals("original", Files.readString(target));
        assertEquals(before, acl(target));
    }

    @Test
    void verifiesTheStagedAclBeforeReplacingTheOriginal() throws Exception {
        Path target = fileWithNamedAcl();
        String before = acl(target);
        Path tools = toolsWithGetfacl();
        tool(tools, "setfacl", "#!/bin/sh\nexit 0\n");

        failedSaveProbe(target, tools, "Cannot preserve file attributes");

        assertEquals("original", Files.readString(target));
        assertEquals(before, acl(target));
    }

    @Test
    void hungAclInspectionHasABoundedDeadline() throws Exception {
        Path target = fileWithNamedAcl();
        String before = acl(target);
        Path tools = Files.createDirectory(tempDir.resolve("slow-tools"));
        tool(tools, "getfacl", "#!/bin/sh\nexec /bin/sleep 30\n");

        failedSaveProbe(target, tools, "timed out");

        assertEquals("original", Files.readString(target));
        assertEquals(before, acl(target));
    }

    private Path fileWithNamedAcl() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path target = Files.writeString(tempDir.resolve("document.txt"), "original");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        int uid = (int) Files.getAttribute(target, "unix:uid");
        command("setfacl", "-m", "u:" + uid + ":r--", target.toString());
        return target;
    }

    private static void saveEdited(Path target) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(target);
        model.insertText(new Position(0, 0), "edited ");
        model.saveIfUnchanged(target, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);
    }

    private static String acl(Path target) throws Exception {
        return command("getfacl", "-cpnE", "--access", "--", target.toString()).strip();
    }

    private Path toolsWithGetfacl() throws Exception {
        Path tools = Files.createDirectory(tempDir.resolve("tools"));
        Path getfacl = Path.of(command("/bin/sh", "-c", "command -v getfacl").strip());
        Files.createSymbolicLink(tools.resolve("getfacl"), getfacl);
        return tools;
    }

    private static void tool(Path tools, String name, String script) throws IOException {
        Path path = Files.writeString(tools.resolve(name), script);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private static void failedSaveProbe(Path target, Path tools, String expected) throws Exception {
        String classpath = String.join(File.pathSeparator,
            location(LinuxFileAclTest.class), location(TextModel.class), location(GraphemeText.class));
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, FailedSaveProbe.class.getName(), target.toString(), expected
        ).redirectErrorStream(true);
        builder.environment().put("PATH", tools.toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "ACL probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "ACL test utility exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class FailedSaveProbe {
        public static void main(String[] args) throws IOException {
            Path target = Path.of(args[0]);
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(target);
            FileBaseline baseline = model.fileBaseline();
            model.insertText(new Position(0, 0), "edited ");
            long revision = model.revision();
            DocumentFileMetadata metadata = model.fileMetadata();
            try {
                model.saveIfUnchanged(target, metadata, SaveDisposition.REPLACE_EXISTING);
                throw new AssertionError("Save unexpectedly succeeded");
            } catch (IOException expected) {
                boolean found = false;
                for (Throwable cause = expected; cause != null; cause = cause.getCause()) {
                    if (cause.toString().contains(args[1])) {
                        found = true;
                    }
                }
                if (!found) {
                    throw expected;
                }
            }
            if (!baseline.equals(model.fileBaseline()) || model.revision() != revision
                || !metadata.equals(model.fileMetadata())
                || !model.getLine(0).equals("edited original")
                || !Files.readString(target).equals("original")) {
                throw new AssertionError("Failed save changed the original or the document's saved state");
            }
        }
    }
}

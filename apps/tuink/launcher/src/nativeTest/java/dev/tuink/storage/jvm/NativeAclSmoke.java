package dev.tuink.storage.jvm;

import dev.tuink.core.TextModel;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.Position;

import dev.tuink.editor.storage.SaveDisposition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

/** Standalone native-image verification entrypoint; excluded from application distributions. */
public final class NativeAclSmoke {
    private static final byte[] ACCESS = attribute("system.posix_acl_access");
    private static final byte[] DEFAULT = attribute("system.posix_acl_default");
    private static final int ENOENT = 2;
    private static final int EINVAL = 22;
    private static final int ERANGE = 34;
    private static final int ENODATA = 61;

    private NativeAclSmoke() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Usage: NativeAclSmoke <temporary-directory>");
        require("Linux".equals(System.getProperty("os.name")), "This smoke test requires Linux");
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "acl-smoke-");
        System.setProperty("user.home", Files.createDirectory(directory.resolve("home")).toAbsolutePath().toString());
        preservesNamedEntriesAndMask(directory);
        removesInheritedAcl(directory);
        preservesNonUtf8Identity(directory);
        reportsErrno(directory);
        System.out.println("Native ACL smoke passed: masked ACL, inherited ACL removal, mode, "
            + "content, raw Unix paths, and errno. Fixtures: " + directory);
    }

    private static void preservesNamedEntriesAndMask(Path directory) throws Exception {
        Path target = Files.writeString(directory.resolve("masked.txt"), "original");
        byte[] expectedAcl = namedAcl(target, 4, 0);
        set(target, ACCESS, expectedAcl);
        int expectedMode = mode(target);
        require(expectedMode == 0600, "A zero ACL mask must restrict group mode bits");
        require(Arrays.equals(expectedAcl, readAcl(target)), "The kernel changed the ACL fixture");

        saveEdited(target);

        require(Arrays.equals(expectedAcl, readAcl(target)), "Saving changed named entries or the ACL mask");
        require(mode(target) == expectedMode, "Saving changed file mode");
        require(Files.readString(target).equals("edited original"), "Saving changed file content");
    }

    private static void removesInheritedAcl(Path directory) throws Exception {
        Path parent = Files.createDirectory(directory.resolve("inheritance"));
        byte[] defaultAcl = namedAcl(parent, 4, 4);
        set(parent, DEFAULT, defaultAcl);
        Path target = Files.writeString(parent.resolve("plain.txt"), "original");
        require(readAcl(target) != null, "The default directory ACL was not inherited");
        require(LinuxXattr.remove(nativePath(target), ACCESS) == 0, "Cannot clear the original ACL");
        Files.setAttribute(target, "unix:mode", 0640);
        require(readAcl(target) == null, "The original must have no extended ACL");

        saveEdited(target);

        require(readAcl(target) == null, "Saving retained an ACL inherited by the temporary inode");
        require(mode(target) == 0640, "Saving changed the original base permissions");
        require(Files.readString(target).equals("edited original"), "Saving lost edited content");
        require(Arrays.equals(defaultAcl, read(parent, DEFAULT)), "Saving changed the directory default ACL");
        try (var children = Files.list(parent)) {
            require(children.count() == 1, "Saving left a temporary file behind");
        }
    }

    private static void preservesNonUtf8Identity(Path directory) throws Exception {
        Path parent = Files.createDirectory(directory.resolve("raw-paths"));
        Path target = Files.writeString(Path.of(URI.create(parent.toUri().toASCIIString() + "legacy-%FF.txt")),
            "original");
        Path neighbour = Files.writeString(parent.resolve("legacy-\uFFFD.txt"), "original");
        require(target.toString().equals(neighbour.toString()), "The fixture must contain a lossy name collision");
        require(!Arrays.equals(nativePath(target), nativePath(neighbour)), "The fixtures need different Unix bytes");
        require(Arrays.equals(nativePath(target), LinuxFileAcl.nativePath(target)), "Production path encoding is lossy");
        byte[] targetAcl = namedAcl(target, 4, 4);
        byte[] neighbourAcl = namedAcl(neighbour, 0, 4);
        set(target, ACCESS, targetAcl);
        set(neighbour, ACCESS, neighbourAcl);
        int expectedMode = mode(target);
        int neighbourMode = mode(neighbour);

        saveEdited(target);

        require(Files.readString(target).equals("edited original"), "Saving missed the raw-byte filename");
        require(Files.readString(neighbour).equals("original"), "Saving modified the replacement-character neighbour");
        require(Arrays.equals(targetAcl, readAcl(target)), "Saving changed the raw-path ACL");
        require(Arrays.equals(neighbourAcl, readAcl(neighbour)), "Saving modified the neighbour's ACL");
        require(mode(target) == expectedMode && mode(neighbour) == neighbourMode, "Saving changed raw-path modes");
    }

    private static void reportsErrno(Path directory) throws Exception {
        Path target = Files.writeString(directory.resolve("errno.txt"), "original");
        set(target, ACCESS, namedAcl(target, 4, 4));
        byte[] path = nativePath(target);
        int size = LinuxXattr.get(path, ACCESS, null);
        require(size > 0 && LinuxXattr.get(path, ACCESS, new byte[0]) == size, "ACL size query failed");
        require(LinuxXattr.get(path, ACCESS, new byte[1]) == -ERANGE, "A short buffer must report ERANGE");
        require(LinuxXattr.set(path, ACCESS, new byte[] {2}) == -EINVAL, "A malformed ACL must report EINVAL");
        require(LinuxXattr.remove(path, ACCESS) == 0, "ACL removal failed");
        require(LinuxXattr.get(path, ACCESS, null) == -ENODATA, "Missing ACL must report ENODATA");
        byte[] userAttribute = attribute("user.tuink-native-smoke");
        require(LinuxXattr.set(path, userAttribute, new byte[0]) == 0, "Empty attribute write failed");
        require(LinuxXattr.get(path, userAttribute, null) == 0, "Empty attribute size must be zero");
        require(LinuxXattr.remove(path, userAttribute) == 0, "Empty attribute removal failed");
        require(LinuxXattr.remove(path, userAttribute) == -ENODATA,
            "Removing an absent user attribute must report ENODATA");
        byte[] missing = nativePath(directory.resolve("does-not-exist"));
        require(LinuxXattr.get(missing, ACCESS, null) == -ENOENT, "Missing file read must report ENOENT");
        require(LinuxXattr.set(missing, ACCESS, namedAcl(target, 4, 4)) == -ENOENT,
            "Missing file write must report ENOENT");
        require(LinuxXattr.remove(missing, ACCESS) == -ENOENT, "Missing file removal must report ENOENT");
    }

    private static void saveEdited(Path target) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(JvmDocumentStorage.resource(target));
        model.insertText(new Position(0, 0), "edited ");
        model.saveIfUnchanged(JvmDocumentStorage.resource(target), model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);
        JvmDocumentStorage.baselineOf(model.fileBaseline()).requireUnchanged(target);
    }

    private static byte[] namedAcl(Path path, int namedPermissions, int maskPermissions) throws IOException {
        ByteBuffer acl = ByteBuffer.allocate(4 + 6 * 8).order(ByteOrder.LITTLE_ENDIAN);
        acl.putInt(2); // Linux POSIX ACL xattr version.
        entry(acl, 1, 6, -1); // ACL_USER_OBJ
        entry(acl, 2, namedPermissions, (int) Files.getAttribute(path, "unix:uid"));
        entry(acl, 4, 0, -1); // ACL_GROUP_OBJ
        entry(acl, 8, 2, (int) Files.getAttribute(path, "unix:gid"));
        entry(acl, 16, maskPermissions, -1); // ACL_MASK
        entry(acl, 32, 0, -1); // ACL_OTHER
        return acl.array();
    }

    private static void entry(ByteBuffer acl, int tag, int permissions, int id) {
        acl.putShort((short) tag).putShort((short) permissions).putInt(id);
    }

    private static int mode(Path path) throws IOException {
        return (int) Files.getAttribute(path, "unix:mode") & 07777;
    }

    private static byte[] readAcl(Path path) throws IOException {
        return read(path, ACCESS);
    }

    private static byte[] read(Path path, byte[] name) throws IOException {
        byte[] value = new byte[65_536];
        int size = LinuxXattr.get(nativePath(path), name, value);
        if (size == -ENODATA) return null;
        require(size > 0 && size <= value.length, "Cannot read ACL fixture; result " + size);
        return Arrays.copyOf(value, size);
    }

    private static void set(Path path, byte[] name, byte[] value) throws IOException {
        int result = LinuxXattr.set(nativePath(path), name, value);
        require(result == 0, "Cannot write ACL fixture; result " + result);
    }

    private static byte[] attribute(String name) {
        return (name + '\0').getBytes(StandardCharsets.US_ASCII);
    }

    // Fixture encoding is independent of the production URI decoder under test.
    private static byte[] nativePath(Path path) {
        String encoded = path.toAbsolutePath().toUri().getRawPath();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            if (encoded.charAt(i) == '%') {
                bytes.write(HexFormat.fromHexDigits(encoded, i + 1, i + 3));
                i += 2;
            } else {
                bytes.write(encoded.charAt(i));
            }
        }
        bytes.write(0);
        return bytes.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

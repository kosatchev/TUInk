package dev.tuink.storage.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/** Copies Linux POSIX access ACLs without invoking external programs. */
final class LinuxFileAcl {
    private static final int MAX_ACL_BYTES = 65_536;
    private static final int ENODATA = 61;
    private static final int EOPNOTSUPP = 95;
    private static final byte[] ATTRIBUTE = "system.posix_acl_access\0".getBytes(StandardCharsets.US_ASCII);
    private static final Access SYSTEM = new Access() {
        public int get(byte[] path, byte[] name, byte[] value) throws IOException {
            return LinuxXattrServices.get(path, name, value);
        }

        public int set(byte[] path, byte[] name, byte[] value) throws IOException {
            return LinuxXattrServices.set(path, name, value);
        }

        public int remove(byte[] path, byte[] name) throws IOException {
            return LinuxXattrServices.remove(path, name);
        }
    };

    private final byte[] accessAcl;
    private final Set<PosixFilePermission> permissions;
    private final Access access;

    private LinuxFileAcl(byte[] accessAcl, Set<PosixFilePermission> permissions, Access access) {
        this.accessAcl = accessAcl;
        this.permissions = permissions;
        this.access = access;
    }

    static LinuxFileAcl read(Path source) throws IOException {
        return read(source, SYSTEM);
    }

    static LinuxFileAcl read(Path source, Access access) throws IOException {
        if (!"Linux".equals(System.getProperty("os.name"))
            || source.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        byte[] value = new byte[MAX_ACL_BYTES];
        int count = access.get(nativePath(source), ATTRIBUTE, value);
        byte[] acl;
        if (absent(count)) {
            acl = null;
        } else {
            requireSuccess("read", count);
            if (count == 0 || count > value.length) {
                throw new IOException("Cannot preserve Linux file ACL: invalid access ACL size");
            }
            acl = Arrays.copyOf(value, count);
        }
        return new LinuxFileAcl(acl, Set.copyOf(Files.getPosixFilePermissions(source)), access);
    }

    void apply(Path staged) throws IOException {
        byte[] path = nativePath(staged);
        if (accessAcl != null) {
            // Set the entire ACL, including its exact mask, in one operation.
            requireSuccess("write", access.set(path, ATTRIBUTE, accessAcl));
        } else {
            // A temp inode can inherit named entries even when the original had
            // none. Remove them before chmod could activate their permissions.
            int result = access.remove(path, ATTRIBUTE);
            if (!absent(result)) requireSuccess("remove", result);
            LinuxFileAcl cleared = read(staged, access);
            if (cleared == null || cleared.accessAcl != null) {
                throw new IOException("Cannot preserve Linux file ACL: inherited ACL was not removed");
            }
            Files.setPosixFilePermissions(staged, permissions);
        }
        verify(staged);
    }

    void verify(Path staged) throws IOException {
        LinuxFileAcl copied = read(staged, access);
        if (copied == null || !Arrays.equals(accessAcl, copied.accessAcl)
            || !permissions.equals(copied.permissions)) {
            throw new IOException("Cannot preserve Linux file ACL: staged ACL or permissions differ from the original");
        }
    }

    /** Path.toUri percent-encodes the original Unix bytes, including invalid UTF-8. */
    static byte[] nativePath(Path path) throws IOException {
        String encoded = path.toAbsolutePath().toUri().getRawPath();
        byte[] bytes = new byte[encoded.length() + 1];
        int count = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char current = encoded.charAt(i);
            int value = current;
            if (current == '%') {
                if (i + 2 >= encoded.length()) throw new IOException("Invalid native file URI");
                int high = Character.digit(encoded.charAt(++i), 16);
                int low = Character.digit(encoded.charAt(++i), 16);
                if (high < 0 || low < 0) throw new IOException("Invalid native file URI");
                value = (high << 4) | low;
            }
            if (value == 0 || value > 127 && current != '%') {
                throw new IOException("Invalid native file URI");
            }
            bytes[count++] = (byte) value;
        }
        return Arrays.copyOf(bytes, count + 1);
    }

    private static boolean absent(int result) {
        return result == -ENODATA || result == -EOPNOTSUPP;
    }

    private static void requireSuccess(String operation, int result) throws IOException {
        if (result < 0) {
            throw new IOException("Cannot preserve Linux file ACL: " + operation + " failed (errno " + -result + ")");
        }
    }

    interface Access {
        int get(byte[] path, byte[] name, byte[] value) throws IOException;
        int set(byte[] path, byte[] name, byte[] value) throws IOException;
        int remove(byte[] path, byte[] name) throws IOException;
    }
}

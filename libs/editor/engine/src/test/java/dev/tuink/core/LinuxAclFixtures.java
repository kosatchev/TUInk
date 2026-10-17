package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Linux UAPI ACL fixtures independent of the optional acl command-line package. */
final class LinuxAclFixtures {
    private static final byte[] ACCESS = attributeName("system.posix_acl_access");
    private static final byte[] DEFAULT = attributeName("system.posix_acl_default");
    private static final int ENODATA = 61;
    private static final int EOPNOTSUPP = 95;

    private LinuxAclFixtures() { }

    static byte[] namedAcl(Path path, int namedPermissions, int maskPermissions) throws IOException {
        int uid = (int) Files.getAttribute(path, "unix:uid");
        int gid = (int) Files.getAttribute(path, "unix:gid");
        ByteBuffer acl = ByteBuffer.allocate(4 + 6 * 8).order(ByteOrder.LITTLE_ENDIAN);
        acl.putInt(2);
        entry(acl, 1, 6, -1); // ACL_USER_OBJ
        entry(acl, 2, namedPermissions, uid); // ACL_USER
        entry(acl, 4, 0, -1); // ACL_GROUP_OBJ
        entry(acl, 8, 2, gid); // ACL_GROUP, whose write permission is deliberately masked
        entry(acl, 16, maskPermissions, -1); // ACL_MASK
        entry(acl, 32, 0, -1); // ACL_OTHER
        return acl.array();
    }

    static byte[] accessAcl(Path path) throws IOException {
        byte[] value = new byte[65_536];
        int size = LinuxXattr.get(LinuxFileAcl.nativePath(path), ACCESS, value);
        if (size == -ENODATA || size == -EOPNOTSUPP) {
            return null;
        }
        assertTrue(size > 0 && size <= value.length, "Cannot read ACL fixture: errno " + -size);
        return Arrays.copyOf(value, size);
    }

    static void setAccessAcl(Path path, byte[] acl) throws IOException {
        set(path, ACCESS, acl);
    }

    static void setDefaultAcl(Path path, byte[] acl) throws IOException {
        set(path, DEFAULT, acl);
    }

    static void removeAccessAcl(Path path) throws IOException {
        int result = LinuxXattr.remove(LinuxFileAcl.nativePath(path), ACCESS);
        assumeTrue(result != -EOPNOTSUPP, "The test filesystem does not support POSIX ACLs");
        assertTrue(result == 0 || result == -ENODATA, "Cannot remove ACL fixture: errno " + -result);
    }

    private static void set(Path path, byte[] name, byte[] acl) throws IOException {
        int result = LinuxXattr.set(LinuxFileAcl.nativePath(path), name, acl);
        assumeTrue(result != -EOPNOTSUPP, "The test filesystem does not support POSIX ACLs");
        assertEquals(0, result, "Cannot set ACL fixture: errno " + -result);
    }

    private static void entry(ByteBuffer acl, int tag, int permissions, int id) {
        acl.putShort((short) tag).putShort((short) permissions).putInt(id);
    }

    private static byte[] attributeName(String name) {
        return (name + '\0').getBytes(StandardCharsets.US_ASCII);
    }
}

package dev.tuink.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Attributes copied to a staged inode before replacing an existing document. */
final class FileSaveAttributes {
    private static final int UNIX_PERMISSION_BITS = 07777;
    private final PosixFileAttributes posix;
    private final Integer unixMode;
    private final UserPrincipal aclOwner;
    private final List<AclEntry> acl;
    private final LinuxFileAcl linuxAcl;
    private final Map<String, byte[]> userAttributes;

    private FileSaveAttributes(
        PosixFileAttributes posix,
        Integer unixMode,
        UserPrincipal aclOwner,
        List<AclEntry> acl,
        LinuxFileAcl linuxAcl,
        Map<String, byte[]> userAttributes
    ) {
        this.posix = posix;
        this.unixMode = unixMode;
        this.aclOwner = aclOwner;
        this.acl = acl;
        this.linuxAcl = linuxAcl;
        this.userAttributes = userAttributes;
    }

    static FileSaveAttributes read(Path source) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        PosixFileAttributes posix = posixView == null ? null : posixView.readAttributes();
        Integer unixMode = source.getFileSystem().supportedFileAttributeViews().contains("unix")
            ? readUnixMode(source) : null;
        AclFileAttributeView aclView = Files.getFileAttributeView(source, AclFileAttributeView.class);
        UserPrincipal aclOwner = aclView == null ? null : aclView.getOwner();
        List<AclEntry> acl = aclView == null ? null : List.copyOf(aclView.getAcl());
        LinuxFileAcl linuxAcl = posix != null && aclView == null ? LinuxFileAcl.read(source) : null;
        Map<String, byte[]> userAttributes = new LinkedHashMap<>();
        UserDefinedFileAttributeView userView = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
        if (userView != null) {
            for (String name : userView.list()) {
                ByteBuffer value = ByteBuffer.allocate(userView.size(name));
                userView.read(name, value);
                value.flip();
                byte[] bytes = new byte[value.remaining()];
                value.get(bytes);
                userAttributes.put(name, bytes);
            }
        }
        return new FileSaveAttributes(posix, unixMode, aclOwner, acl, linuxAcl, userAttributes);
    }

    void apply(Path staged) throws IOException {
        try {
            if (posix != null) {
                PosixFileAttributeView view = requireView(staged, PosixFileAttributeView.class);
                PosixFileAttributes current = view.readAttributes();
                UserPrincipal owner = posix.owner();
                GroupPrincipal group = posix.group();
                if (!owner.equals(current.owner())) {
                    view.setOwner(owner);
                }
                if (!group.equals(current.group())) {
                    view.setGroup(group);
                }
            }
            if (acl != null) {
                AclFileAttributeView view = requireView(staged, AclFileAttributeView.class);
                if (!aclOwner.equals(view.getOwner())) {
                    view.setOwner(aclOwner);
                }
                view.setAcl(acl);
            }
            if (!userAttributes.isEmpty()) {
                UserDefinedFileAttributeView view = requireView(staged, UserDefinedFileAttributeView.class);
                for (var entry : userAttributes.entrySet()) {
                    ByteBuffer value = ByteBuffer.wrap(entry.getValue());
                    int written = view.write(entry.getKey(), value);
                    if (written != entry.getValue().length || value.hasRemaining()) {
                        throw new IOException("Incomplete extended attribute: " + entry.getKey());
                    }
                }
            }
            // Changing ownership can clear permission bits on some filesystems.
            // The Linux ACL supplies all rwx bits in one operation. A preceding
            // chmod could activate unrelated entries inherited by the temp file.
            if (posix != null && linuxAcl == null) {
                Set<PosixFilePermission> permissions = posix.permissions();
                requireView(staged, PosixFileAttributeView.class).setPermissions(permissions);
            }
            if (linuxAcl != null) {
                linuxAcl.apply(staged);
            }
            if (unixMode != null) {
                // Ownership changes and content writes can clear setuid/setgid.
                // Restore the complete mode last, using the original rwx bits
                // so an access ACL's mask remains unchanged.
                if (readUnixMode(staged) != unixMode.intValue()) {
                    Files.setAttribute(staged, "unix:mode", unixMode);
                    if (readUnixMode(staged) != unixMode.intValue()) {
                        throw new IOException("Staged Unix permission bits differ from the original");
                    }
                    if (linuxAcl != null) {
                        linuxAcl.verify(staged);
                    }
                }
            }
        } catch (IOException | UnsupportedOperationException failure) {
            throw new IOException("Cannot preserve file attributes; original file was left unchanged", failure);
        }
    }

    private static int readUnixMode(Path path) throws IOException {
        return (int) Files.getAttribute(path, "unix:mode") & UNIX_PERMISSION_BITS;
    }

    private static <T extends java.nio.file.attribute.FileAttributeView> T requireView(
        Path path, Class<T> type
    ) throws IOException {
        T view = Files.getFileAttributeView(path, type);
        if (view == null) {
            throw new IOException("File attribute view is unavailable: " + type.getSimpleName());
        }
        return view;
    }
}

package dev.tuink.storage.jvm;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coordinates document and settings publication by cooperating processes of one user on this host.
 * Lock files belong to a shared user namespace, independently of configuration profiles and target inodes.
 */
public final class FileSaveGuard implements AutoCloseable {
    // Closing a second descriptor can release this process's POSIX lock. Claim
    // the physical lock name before opening any descriptor, including aliases.
    private static final Set<String> LOCAL_LOCKS = new HashSet<>();
    private final String[] identities;
    private final FileChannel[] channels;
    private final FileLock[] locks;
    private boolean closed;

    private FileSaveGuard(String[] identities) {
        this.identities = identities;
        this.channels = new FileChannel[identities.length];
        this.locks = new FileLock[identities.length];
    }

    /** Fails promptly when another cooperating writer owns the same directory entry. */
    public static FileSaveGuard acquire(Path target) throws IOException {
        return acquire(target, defaultDirectory());
    }

    static FileSaveGuard acquire(Path target, Path namespace) throws IOException {
        return acquire(target, namespace, FileChannel::open);
    }

    @FunctionalInterface
    interface ChannelOpener {
        FileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException;
    }

    static FileSaveGuard acquire(Path target, Path namespace, ChannelOpener opener) throws IOException {
        List<Path> paths = lockPaths(target, namespace);
        String namespaceIdentity = unixIdentity(paths.get(0).getParent());
        String[] identities = new String[paths.size()];
        for (int index = 0; index < paths.size(); index++) {
            Path path = paths.get(index);
            identities[index] = namespaceIdentity == null ? path.toUri().toASCIIString()
                : namespaceIdentity + "/" + path.getFileName();
        }
        FileSaveGuard guard = new FileSaveGuard(identities);
        synchronized (LOCAL_LOCKS) {
            for (String identity : identities) {
                if (LOCAL_LOCKS.contains(identity)) throw unavailable(target, null);
            }
            try {
                for (String identity : identities) LOCAL_LOCKS.add(identity);
            } catch (RuntimeException | Error failure) {
                for (String identity : identities) LOCAL_LOCKS.remove(identity);
                throw failure;
            }
        }
        boolean acquired = false;
        Throwable primary = null;
        try {
            for (int index = 0; index < paths.size(); index++) {
                Path path = paths.get(index);
                requireRegularLock(path, true);
                Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                FileChannel channel;
                try {
                    channel = opener.open(path, options,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException unsupportedAttributes) {
                    // Keep NOFOLLOW_LINKS; providers unable to lock safely fail closed.
                    channel = opener.open(path, options);
                }
                guard.channels[index] = channel;
                requireRegularLock(path, false);
                FileLock lock = channel.tryLock();
                if (lock == null) throw unavailable(target, null);
                guard.locks[index] = lock;
            }
            acquired = true;
            return guard;
        } catch (IOException | UnsupportedOperationException | OverlappingFileLockException failure) {
            IOException reported = unavailable(target, failure);
            primary = reported;
            throw reported;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!acquired) {
                try {
                    guard.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    if (primary != null) suppress(primary, cleanupFailure);
                    else throw cleanupFailure;
                }
            }
        }
    }

    /** Resolves aliases without normalizing dot segments before following their parent links. */
    static Path canonicalTarget(Path requested) throws IOException {
        Path absolute = Objects.requireNonNull(requested, "target").toAbsolutePath();
        Path leaf = absolute.getFileName();
        if (leaf == null || leaf.toString().equals(".") || leaf.toString().equals("..")) {
            throw new IOException("Save target must be a file: " + requested);
        }
        Path entry = absolute.getParent().toRealPath().resolve(leaf);
        try {
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new IOException("Save target is not a regular file: " + requested);
            return entry.toRealPath();
        } catch (NoSuchFileException missing) {
            if (Files.isSymbolicLink(entry)) throw missing;
            return entry;
        }
    }

    static Path lockPath(Path target, Path namespace) throws IOException {
        Path physicalTarget = canonicalTarget(target);
        Path directory = privateDirectory(namespace);
        return legacyLockPath(physicalTarget, directory);
    }

    static List<Path> lockPaths(Path target, Path namespace) throws IOException {
        Path physicalTarget = canonicalTarget(target);
        Path directory = privateDirectory(namespace);
        ArrayList<Path> paths = new ArrayList<>(2);
        paths.add(legacyLockPath(physicalTarget, directory));
        String parentIdentity = unixIdentity(physicalTarget.getParent());
        if (parentIdentity != null) {
            // Device/inode identifies the directory through bind mounts; the
            // raw leaf keeps distinct hardlink directory entries independent.
            String rawPath = physicalTarget.toUri().getRawPath();
            String rawLeaf = rawPath.substring(rawPath.lastIndexOf('/') + 1);
            String entry = "entry-v1:" + parentIdentity + "/" + rawLeaf;
            paths.add(directory.resolve(HexFormat.of().formatHex(digest(entry)) + ".lock"));
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return List.copyOf(paths);
    }

    private static Path legacyLockPath(Path physicalTarget, Path directory) {
        // URI escaping retains Unix filename bytes which Path.toString() can lose.
        String identity = physicalTarget.toUri().toASCIIString();
        if (identity.endsWith("/")) identity = identity.substring(0, identity.length() - 1);
        return directory.resolve(HexFormat.of().formatHex(digest(identity)) + ".lock");
    }

    private static String unixIdentity(Path directory) throws IOException {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("unix")) return null;
        Map<String, Object> attributes = Files.readAttributes(directory, "unix:dev,ino");
        if (!(attributes.get("dev") instanceof Number device) || !(attributes.get("ino") instanceof Number inode)) {
            throw new IOException("Cannot identify the physical save lock directory: " + directory);
        }
        return "unix:" + Long.toUnsignedString(device.longValue()) + ":" + Long.toUnsignedString(inode.longValue());
    }

    private static byte[] digest(String identity) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("The Java runtime must provide SHA-256", impossible);
        }
    }

    private static Path defaultDirectory() throws IOException {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) throw new IOException("Cannot locate global save locks: user.home is empty");
        for (int index = 0; index < home.length(); index++) {
            char ch = home.charAt(index);
            if (ch == '\uFFFD' || Character.isLowSurrogate(ch)) {
                throw new IOException("Cannot locate global save locks: user.home is not an unambiguous native path");
            }
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 == home.length() || !Character.isLowSurrogate(home.charAt(index + 1))) {
                    throw new IOException("Cannot locate global save locks: user.home is not an unambiguous native path");
                }
                index++;
            }
        }
        try {
            Path path = Path.of(home);
            if (!path.isAbsolute()) throw new IOException("Cannot locate global save locks: user.home must be absolute");
            // A single home-based namespace coordinates different XDG_CONFIG_HOME
            // profiles. Do not fall back to a different namespace on failure.
            return path.resolve(".local/state/tuink/save-locks-v1");
        } catch (InvalidPathException invalid) {
            throw new IOException("Cannot locate global save locks: invalid user.home", invalid);
        }
    }

    private static Path privateDirectory(Path requested) throws IOException {
        if (Files.isSymbolicLink(requested)) throw new IOException("Save lock namespace must not be a symbolic link: " + requested);
        try {
            Files.createDirectories(requested,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException unsupportedAttributes) {
            Files.createDirectories(requested);
        }
        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Save lock namespace is not a directory: " + requested);
        }
        Path physical = requested.toRealPath();
        try {
            Files.setPosixFilePermissions(physical, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException unsupportedPermissions) {
            // Non-POSIX platforms inherit the user's home ACLs.
        }
        return physical;
    }

    private static void requireRegularLock(Path path, boolean mayBeMissing) throws IOException {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("Save lock must be a regular file: " + path);
            }
            if (path.getFileSystem().supportedFileAttributeViews().contains("unix")
                && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                throw new IOException("Save lock must not be a hard link: " + path);
            }
        } catch (NoSuchFileException missing) {
            if (!mayBeMissing) throw missing;
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            for (int index = channels.length - 1; index >= 0; index--) {
                try {
                    if (locks[index] != null) locks[index].release();
                } catch (IOException | RuntimeException | Error failed) {
                    if (failure == null) failure = failed;
                    else suppress(failure, failed);
                }
                try {
                    if (channels[index] != null) channels[index].close();
                } catch (IOException | RuntimeException | Error failed) {
                    if (failure == null) failure = failed;
                    else suppress(failure, failed);
                }
            }
        } finally {
            synchronized (LOCAL_LOCKS) {
                for (String identity : identities) LOCAL_LOCKS.remove(identity);
            }
        }
        // Never unlink: another process may already have opened this inode.
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary == secondary) return;
        try { primary.addSuppressed(secondary); }
        catch (RuntimeException | Error unavailable) { /* Retain the primary cleanup failure. */ }
    }

    private static IOException unavailable(Path target, Throwable cause) {
        return new IOException("Cannot acquire the save lock for " + target
            + ". Another TUInk writer may be saving it, or the shared user lock namespace is unavailable. Retry saving.", cause);
    }
}

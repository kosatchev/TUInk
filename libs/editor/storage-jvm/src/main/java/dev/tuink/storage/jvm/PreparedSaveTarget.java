package dev.tuink.storage.jvm;

import dev.tuink.editor.storage.StorageOperation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** The exact address and existing bytes inspected before a Save As is approved. */
final class PreparedSaveTarget {
    private final Path spelling;
    private final Path requested;
    private final Path target;
    private final DirectoryIdentity parentIdentity;
    private final Version version;

    private PreparedSaveTarget(Path spelling, Path requested, Path target,
                               DirectoryIdentity parentIdentity, Version version) {
        this.spelling = spelling;
        this.requested = requested;
        this.target = target;
        this.parentIdentity = parentIdentity;
        this.version = version;
    }

    @FunctionalInterface
    interface InputOpener {
        InputStream open(Path path) throws IOException;
    }

    static PreparedSaveTarget inspect(Path path, StorageOperation operation) throws IOException {
        return inspect(path, operation, Files::newInputStream);
    }

    static PreparedSaveTarget inspect(Path path, StorageOperation operation, InputOpener inputOpener)
        throws IOException {
        operation.checkCancelled();
        Path spelling = path.toAbsolutePath();
        Path requested = JvmDocumentStorage.resolveDocumentPath(spelling);
        Path target = FileSaveGuard.canonicalTarget(requested);
        DirectoryIdentity parentIdentity = directoryIdentity(target.getParent());
        Version version = version(target, inputOpener, operation, StorageOperation.Phase.INSPECTING, false, null);
        PreparedSaveTarget inspected = new PreparedSaveTarget(spelling, requested, target, parentIdentity, version);
        inspected.requireOriginalSpelling();
        inspected.requireAddress();
        operation.checkCancelled();
        return inspected;
    }

    Path requested() { return requested; }
    Path target() { return target; }
    boolean exists() { return version != null; }

    /** Freeze the inspected canonical parent before the writer starts staging bytes. */
    void beginWrite(StorageOperation operation) throws IOException {
        operation.checkCancelled();
        requireOriginalSpelling();
        requireUnchanged(operation);
    }

    void requireUnchanged(StorageOperation operation) throws IOException {
        requireUnchanged(operation, Files::newInputStream);
    }

    void requireUnchanged(StorageOperation operation, InputOpener inputOpener) throws IOException {
        operation.checkCancelled();
        requireAddress();
        Version current = version(target, inputOpener, operation, StorageOperation.Phase.VERIFYING, true, version);
        if (!Objects.equals(version, current)) throw conflict();
        requireAddress();
        operation.checkCancelled();
    }

    private void requireAddress() throws IOException {
        if (!target.equals(FileSaveGuard.canonicalTarget(requested))
            || !parentIdentity.equals(directoryIdentity(target.getParent()))) throw conflict();
    }

    private void requireOriginalSpelling() throws IOException {
        if (!requested.equals(JvmDocumentStorage.resolveDocumentPath(spelling))) throw conflict();
    }

    private static DirectoryIdentity directoryIdentity(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class);
        if (!attributes.isDirectory()) throw new IOException("Save parent is not a directory: " + directory);
        Object key = attributes.fileKey();
        // Directory mtime/size change when we create our own staging file.
        return new DirectoryIdentity(key, key == null ? attributes.creationTime() : null);
    }

    private IOException conflict() { return conflict(requested); }

    private static IOException conflict(Path path) {
        return new IOException("Save target changed outside TUInk since it was inspected: " + path
            + ". Choose the target again and retry Save As.");
    }

    private static Version version(Path path, InputOpener inputOpener, StorageOperation operation,
                                   StorageOperation.Phase phase, boolean rechecking, Version expected)
        throws IOException {
        operation.checkCancelled();
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            if (Files.isSymbolicLink(path)) throw missing;
            operation.progress(phase, 0L, 0L);
            return null;
        }
        if (!before.isRegularFile()) throw new IOException("Save target is not a regular file: " + path);
        Stamp stamp = Stamp.of(before);
        if (rechecking) {
            if (expected == null) {
                throw new FileAlreadyExistsException(path.toString(), null,
                    "Save target appeared since inspection; choose the target again");
            }
            // Reject size/identity changes before opening. An external writer
            // cannot turn verification of approved bytes into a larger scan.
            if (!expected.stamp().equals(stamp)) throw conflict(path);
        }
        MessageDigest digest = FileBaseline.digest();
        long size = before.size();
        operation.progress(phase, 0L, size);
        operation.checkCancelled();
        // Capture a fixed length, even for large targets. Reads cannot chase growth.
        try (InputStream input = inputOpener.open(path)) {
            byte[] buffer = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                operation.checkCancelled();
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new IOException("Save target shrank during inspection: " + path);
                digest.update(buffer, 0, read);
                remaining -= read;
                operation.progress(phase, size - remaining, size);
            }
            operation.checkCancelled();
            if (input.read() != -1) throw new IOException("Save target grew during inspection: " + path);
            operation.checkCancelled();
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
        if (!after.isRegularFile() || !stamp.equals(Stamp.of(after))) {
            throw new IOException("Save target changed during inspection: " + path);
        }
        return new Version(stamp, HexFormat.of().formatHex(digest.digest()));
    }

    private record Version(Stamp stamp, String sha256) {}
    private record DirectoryIdentity(Object fileKey, FileTime created) {}
    private record Stamp(Object fileKey, long size, FileTime modified, FileTime created) {
        static Stamp of(BasicFileAttributes attributes) {
            return new Stamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
        }
    }
}

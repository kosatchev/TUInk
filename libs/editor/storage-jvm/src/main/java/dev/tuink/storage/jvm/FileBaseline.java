package dev.tuink.storage.jvm;

import dev.tuink.editor.storage.ResourceId;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.StorageOperation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Identity of the file bytes last loaded or successfully saved by this document. */
public record FileBaseline(Path target, String sha256, long byteSize) implements ResourceVersion {
    /** Older recovery entries did not retain the original byte count. */
    public FileBaseline(Path target, String sha256) {
        this(target, sha256, -1L);
    }

    public FileBaseline {
        Objects.requireNonNull(target, "target");
        if (!target.isAbsolute() || !target.normalize().equals(target)) {
            throw new IllegalArgumentException("File baseline target must be absolute and normalized");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("File baseline digest must be lowercase SHA-256");
        }
        if (byteSize < -1L) {
            throw new IllegalArgumentException("File baseline byte size must be nonnegative or unknown (-1)");
        }
    }

    public static FileBaseline fromBytes(Path target, byte[] content) {
        return new FileBaseline(target, HexFormat.of().formatHex(digest().digest(content)), content.length);
    }

    @Override public ResourceId resourceId() {
        return JvmDocumentStorage.idOf(target);
    }

    void requireUnchanged(Path candidate) throws IOException {
        requireUnchanged(candidate, Files::newInputStream, StorageOperation.untracked());
    }

    void requireUnchanged(Path candidate, StorageOperation operation) throws IOException {
        requireUnchanged(candidate, Files::newInputStream, operation);
    }

    @FunctionalInterface
    interface InputOpener {
        InputStream open(Path path) throws IOException;
    }

    void requireUnchanged(Path candidate, InputOpener inputOpener) throws IOException {
        requireUnchanged(candidate, inputOpener, StorageOperation.untracked());
    }

    void requireUnchanged(Path candidate, InputOpener inputOpener, StorageOperation operation) throws IOException {
        operation.checkCancelled();
        try {
            Path physicalTarget = candidate.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(physicalTarget, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Not a regular file: " + candidate);
            }
            long expectedSize = byteSize < 0 ? attributes.size() : byteSize;
            if (attributes.size() != expectedSize) {
                throw new dev.tuink.editor.storage.ExternalFileChangedException(conflictMessage(candidate));
            }
            boolean sameTarget = target.equals(physicalTarget);
            if (!sameTarget && target.equals(target.toRealPath())) {
                // A hard-link alias has another canonical path but the same inode.
                // Do not follow a baseline path that itself has been retargeted.
                sameTarget = Files.isSameFile(target, physicalTarget);
            }
            if (sameTarget) {
                MessageDigest current = digest();
                operation.progress(StorageOperation.Phase.VERIFYING, 0L, expectedSize);
                operation.checkCancelled();
                try (InputStream input = inputOpener.open(physicalTarget)) {
                    byte[] buffer = new byte[8192];
                    long remaining = expectedSize;
                    while (remaining > 0) {
                        operation.checkCancelled();
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new dev.tuink.editor.storage.ExternalFileChangedException(conflictMessage(candidate));
                        current.update(buffer, 0, read);
                        remaining -= read;
                        operation.progress(StorageOperation.Phase.VERIFYING, expectedSize - remaining, expectedSize);
                    }
                    operation.checkCancelled();
                    if (input.read() != -1) throw new dev.tuink.editor.storage.ExternalFileChangedException(conflictMessage(candidate));
                    operation.checkCancelled();
                }
                if (sha256.equals(HexFormat.of().formatHex(current.digest()))) {
                    return;
                }
            }
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (dev.tuink.editor.storage.ExternalFileChangedException changed) {
            throw changed;
        } catch (IOException failure) {
            throw new IOException(conflictMessage(candidate), failure);
        }
        throw new dev.tuink.editor.storage.ExternalFileChangedException(conflictMessage(candidate));
    }

    static String conflictMessage(Path path) {
        return "File changed outside TUInk or is no longer accessible: " + path
            + ". Update from disk, or save again and confirm replacing external changes.";
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("The Java runtime must provide SHA-256", impossible);
        }
    }
}

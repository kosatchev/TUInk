package dev.tuink.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Identity of the file bytes last loaded or successfully saved by this document. */
public record FileBaseline(Path target, String sha256, long byteSize) {
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

    static FileBaseline fromBytes(Path target, byte[] content) {
        return new FileBaseline(target, HexFormat.of().formatHex(digest().digest(content)), content.length);
    }

    void requireUnchanged(Path candidate) throws IOException {
        requireUnchanged(candidate, Files::newInputStream);
    }

    @FunctionalInterface
    interface InputOpener {
        InputStream open(Path path) throws IOException;
    }

    void requireUnchanged(Path candidate, InputOpener inputOpener) throws IOException {
        try {
            Path physicalTarget = candidate.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(physicalTarget, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Not a regular file: " + candidate);
            }
            long expectedSize = byteSize < 0 ? attributes.size() : byteSize;
            if (attributes.size() != expectedSize || byteSize < 0 && expectedSize > TextModel.MAX_LOAD_BYTES) {
                throw new IOException("File size changed or exceeds the verification limit for a legacy baseline");
            }
            boolean sameTarget = target.equals(physicalTarget);
            if (!sameTarget && target.equals(target.toRealPath())) {
                // A hard-link alias has another canonical path but the same inode.
                // Do not follow a baseline path that itself has been retargeted.
                sameTarget = Files.isSameFile(target, physicalTarget);
            }
            if (sameTarget) {
                MessageDigest current = digest();
                try (InputStream input = inputOpener.open(physicalTarget)) {
                    byte[] buffer = new byte[8192];
                    long remaining = expectedSize;
                    while (remaining > 0) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new IOException("File shrank during verification");
                        current.update(buffer, 0, read);
                        remaining -= read;
                    }
                    if (input.read() != -1) throw new IOException("File grew during verification");
                }
                if (sha256.equals(HexFormat.of().formatHex(current.digest()))) {
                    return;
                }
            }
        } catch (IOException failure) {
            throw new IOException(conflictMessage(candidate), failure);
        }
        throw new IOException(conflictMessage(candidate));
    }

    static String conflictMessage(Path path) {
        return "File changed outside TUInk or is no longer accessible: " + path
            + ". Use Save As with a different name, or reopen the file.";
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("The Java runtime must provide SHA-256", impossible);
        }
    }
}

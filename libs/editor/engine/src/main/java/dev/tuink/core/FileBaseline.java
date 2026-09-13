package dev.tuink.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Identity of the file bytes last loaded or successfully saved by this document. */
public record FileBaseline(Path target, String sha256) {
    public FileBaseline {
        Objects.requireNonNull(target, "target");
        if (!target.isAbsolute() || !target.normalize().equals(target)) {
            throw new IllegalArgumentException("File baseline target must be absolute and normalized");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("File baseline digest must be lowercase SHA-256");
        }
    }

    static FileBaseline fromBytes(Path target, byte[] content) {
        return new FileBaseline(target, HexFormat.of().formatHex(digest().digest(content)));
    }

    void requireUnchanged(Path candidate) throws IOException {
        try {
            Path physicalTarget = candidate.toRealPath();
            boolean sameTarget = target.equals(physicalTarget);
            if (!sameTarget && target.equals(target.toRealPath())) {
                // A hard-link alias has another canonical path but the same inode.
                // Do not follow a baseline path that itself has been retargeted.
                sameTarget = Files.isSameFile(target, physicalTarget);
            }
            if (sameTarget) {
                MessageDigest current = digest();
                try (var input = new DigestInputStream(Files.newInputStream(physicalTarget), current)) {
                    input.transferTo(OutputStream.nullOutputStream());
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

    private static String conflictMessage(Path path) {
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

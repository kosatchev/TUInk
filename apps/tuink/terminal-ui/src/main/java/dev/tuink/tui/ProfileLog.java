package dev.tuink.tui;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Creates a profile log without replacing user files. */
final class ProfileLog {
    private ProfileLog() {
    }

    static PrintWriter open(Path file, Path document) throws IOException {
        Path target = file.toAbsolutePath();
        if (document != null) {
            Path documentTarget = document.toAbsolutePath();
            if (target.equals(documentTarget)
                || resolveParent(target).equals(resolveDocumentTarget(documentTarget))) {
                throw new IOException("Profile log must differ from the document: " + file);
            }
        }
        try {
            return new PrintWriter(
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                ),
                true
            );
        } catch (FileAlreadyExistsException e) {
            throw new FileAlreadyExistsException(target.toString(), null, "Profile log must be a new file");
        }
    }

    private static Path resolveDocumentTarget(Path document) throws IOException {
        Path current = document;
        for (int links = 0; links <= 40; links++) {
            // Resolve existing parents before interpreting either '..' or a relative
            // link target. Lexical normalization would change symlink/.. semantics.
            Path resolved = resolveParent(current);
            if (!Files.isSymbolicLink(resolved)) return resolved;
            if (links == 40) break;
            Path link = Files.readSymbolicLink(resolved);
            current = link.isAbsolute() ? link : resolved.getParent().resolve(link);
        }
        throw new FileSystemException(document.toString(), null, "Too many symbolic links in document path");
    }

    private static Path resolveParent(Path file) throws IOException {
        if (file.getParent() == null) {
            return file;
        }
        try {
            return file.getParent().toRealPath().resolve(file.getFileName());
        } catch (NoSuchFileException e) {
            // An unopened document may have a parent that does not exist yet.
            return file;
        }
    }
}

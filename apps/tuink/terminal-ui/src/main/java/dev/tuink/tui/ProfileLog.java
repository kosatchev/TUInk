package dev.tuink.tui;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Creates a profile log without replacing user files. */
final class ProfileLog {
    private ProfileLog() {
    }

    static PrintWriter open(Path file, Path document) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        if (document != null) {
            Path documentTarget = document.toAbsolutePath().normalize();
            if (target.equals(documentTarget)
                || resolveParent(target).equals(resolveParent(documentTarget))) {
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

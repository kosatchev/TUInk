package dev.tuink.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Linux POSIX ACLs are not exposed by the JDK's ACL or user-attribute views. */
final class LinuxFileAcl {
    private static final int MAX_OUTPUT_BYTES = 131_072;
    private static final long TIMEOUT_SECONDS = 5;
    private final byte[] accessAcl;

    private LinuxFileAcl(byte[] accessAcl) {
        this.accessAcl = accessAcl;
    }

    static LinuxFileAcl read(Path source) throws IOException {
        if (!"Linux".equals(System.getProperty("os.name"))
            || source.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        byte[] acl = run(
            List.of("getfacl", "--absolute-names", "--omit-header", "--numeric",
                "--no-effective", "--access", "--", source.toString()),
            new byte[0]
        );
        if (acl.length == 0) {
            throw new IOException("Cannot preserve Linux file ACL: getfacl returned an empty ACL");
        }
        return new LinuxFileAcl(acl);
    }

    void apply(Path staged) throws IOException {
        // Replace the complete ACL, including a source with only the three base
        // entries. A temporary inode can inherit a different default ACL from
        // its directory. The complete ACL also supplies the base permissions;
        // chmod before it could expose inherited entries. A later restoration
        // of special mode bits must keep the same rwx bits and verify the ACL.
        run(List.of("setfacl", "--no-mask", "--set-file=-", "--", staged.toString()), accessAcl);
        verify(staged);
    }

    void verify(Path staged) throws IOException {
        LinuxFileAcl copied = read(staged);
        if (copied == null || !Arrays.equals(accessAcl, copied.accessAcl)) {
            throw new IOException("Cannot preserve Linux file ACL: staged ACL differs from the original");
        }
    }

    private static byte[] run(List<String> command, byte[] input) throws IOException {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
        } catch (IOException failure) {
            throw new IOException(
                "Cannot preserve Linux file ACL: install the 'acl' package (getfacl and setfacl). "
                    + "The original file was left unchanged.", failure
            );
        }
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (var stream = process.getInputStream()) {
                return stream.readNBytes(MAX_OUTPUT_BYTES + 1);
            }
        });
        FutureTask<Void> writer = new FutureTask<>(() -> {
            try (var stream = process.getOutputStream()) {
                stream.write(input);
            }
            return null;
        });
        Thread.startVirtualThread(output);
        Thread.startVirtualThread(writer);
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Cannot preserve Linux file ACL: " + command.getFirst() + " timed out");
            }
            byte[] result = output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.length > MAX_OUTPUT_BYTES) {
                throw new IOException("Cannot preserve Linux file ACL: excessive tool output");
            }
            if (process.exitValue() != 0) {
                String diagnostic = new String(result, StandardCharsets.UTF_8).strip();
                throw new IOException("Cannot preserve Linux file ACL: " + diagnostic);
            }
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while preserving Linux file ACL", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Cannot preserve Linux file ACL", failure);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            writer.cancel(true);
            output.cancel(true);
        }
    }
}

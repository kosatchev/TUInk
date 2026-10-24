package dev.tuink.core.storage.spi;

import java.io.IOException;

/**
 * Optional Linux filesystem access used to preserve ACLs during replacement.
 * Paths and names contain exact native bytes followed by a zero terminator.
 * Results are nonnegative on success or a negated Linux errno on failure.
 */
public interface LinuxXattrAccess {
    /** A null or empty buffer queries the attribute size without copying bytes. */
    int get(byte[] path, byte[] name, byte[] value) throws IOException;

    int set(byte[] path, byte[] name, byte[] value) throws IOException;

    int remove(byte[] path, byte[] name) throws IOException;
}

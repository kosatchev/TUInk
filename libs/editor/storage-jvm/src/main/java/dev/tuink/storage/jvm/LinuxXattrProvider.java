package dev.tuink.storage.jvm;

import dev.tuink.core.storage.spi.LinuxXattrAccess;
import java.io.IOException;

/** JVM platform adapter. Constructing the service does not initialize its FFM bindings. */
public final class LinuxXattrProvider implements LinuxXattrAccess {
    public LinuxXattrProvider() {}

    @Override public int get(byte[] path, byte[] name, byte[] value) throws IOException {
        return LinuxXattr.get(path, name, value);
    }

    @Override public int set(byte[] path, byte[] name, byte[] value) throws IOException {
        return LinuxXattr.set(path, name, value);
    }

    @Override public int remove(byte[] path, byte[] name) throws IOException {
        return LinuxXattr.remove(path, name);
    }
}

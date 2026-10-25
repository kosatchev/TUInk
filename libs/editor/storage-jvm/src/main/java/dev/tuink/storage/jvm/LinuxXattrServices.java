package dev.tuink.storage.jvm;

import dev.tuink.storage.jvm.spi.LinuxXattrAccess;
import java.io.IOException;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Optional platform access; the editor runtime itself does not load native APIs. */
final class LinuxXattrServices {
    private LinuxXattrServices() {}

    static int get(byte[] path, byte[] name, byte[] value) throws IOException {
        return system().get(path, name, value);
    }

    static int set(byte[] path, byte[] name, byte[] value) throws IOException {
        return system().set(path, name, value);
    }

    static int remove(byte[] path, byte[] name) throws IOException {
        return system().remove(path, name);
    }

    private static LinuxXattrAccess system() throws IOException {
        Resolution resolution = SystemAccess.RESOLUTION;
        if (resolution.access() == null) {
            throw new IOException("Linux extended-attribute access is unavailable: "
                + "a working JVM storage provider is required to preserve file ACLs", resolution.failure());
        }
        return resolution.access();
    }

    private record Resolution(LinuxXattrAccess access, Throwable failure) {}

    private static final class SystemAccess {
        private static final Resolution RESOLUTION = resolve();

        private static Resolution resolve() {
            try {
                var providers = ServiceLoader.load(LinuxXattrAccess.class,
                    LinuxXattrAccess.class.getClassLoader()).iterator();
                if (!providers.hasNext()) return new Resolution(null, null);
                LinuxXattrAccess access = providers.next();
                if (providers.hasNext()) {
                    return new Resolution(null, new IOException("Multiple Linux extended-attribute providers"));
                }
                return new Resolution(access, null);
            } catch (ServiceConfigurationError | LinkageError | RuntimeException unavailable) {
                return new Resolution(null, unavailable);
            }
        }
    }
}

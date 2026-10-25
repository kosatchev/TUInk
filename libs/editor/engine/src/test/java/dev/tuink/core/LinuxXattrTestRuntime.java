package dev.tuink.core;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashSet;

/** Includes the service descriptor as well as classes in manually forked test JVMs. */
final class LinuxXattrTestRuntime {
    private static final String SERVICE = "META-INF/services/dev.tuink.storage.jvm.spi.LinuxXattrAccess";

    private LinuxXattrTestRuntime() {}

    static String classpath() throws Exception {
        ClassLoader loader = LinuxXattrTestRuntime.class.getClassLoader();
        Class<?> provider = Class.forName("dev.tuink.storage.jvm.LinuxXattrProvider", false, loader);
        var entries = new LinkedHashSet<String>();
        entries.add(Path.of(provider.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var resources = loader.getResources(SERVICE);
        boolean found = false;
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            found = true;
            if (resource.getProtocol().equals("jar")) {
                entries.add(Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI()).toString());
            } else if (resource.getProtocol().equals("file")) {
                Path root = Path.of(resource.toURI());
                for (int index = 0; index < 3; index++) root = root.getParent();
                entries.add(root.toString());
            } else {
                throw new IOException("Unsupported service resource URL: " + resource);
            }
        }
        if (!found) throw new IOException("The JVM storage provider's service descriptor is missing");
        return String.join(File.pathSeparator, entries);
    }
}

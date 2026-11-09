package dev.tuink.tui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Explicit runtime dependencies of real-window child probes, including optional project search. */
final class ProjectSearchProbeClasspath {
    private ProjectSearchProbeClasspath() {}
    static void addTo(Set<String> locations) throws Exception {
        for (Class<?> type : List.of(dev.tuink.core.SearchMatcher.class))
            locations.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
}
